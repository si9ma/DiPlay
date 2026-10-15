package com.shilapi.xcertplay.adb

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPair
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diagnostics for DiPlay's own link to the head unit's adbd (127.0.0.1:5555).
 *
 * [record] keeps a process-wide ring of what every [LocalAdb] did (connect, handshake packets,
 * failures), and [report] adds a live look at the environment: adbd settings and properties, which
 * sockets listen on the ADB port, this app's key fingerprint, and a handshake against every address
 * of the head unit. A PC reaching adbd over the car's network does not mean adbd answers on the
 * loopback address DiPlay uses, so each address is tried separately.
 */
object AdbDiagnostics {
    private const val TAG = "DiPlay-ADB"
    private const val MAX_EVENTS = 300
    private const val PROBE_TIMEOUT_SECONDS = 15L

    private class Event(val time: Long, val thread: String, val message: String, var repeats: Int = 0)

    private val events = ArrayDeque<Event>()

    /** Optional extra sink (the CarPlay session log). New messages only; repeats are folded. */
    @Volatile var sink: ((String) -> Unit)? = null

    fun record(message: String) {
        val thread = Thread.currentThread().name
        val fresh = synchronized(events) {
            val last = events.lastOrNull()
            if (last != null && last.message == message && last.thread == thread) {
                last.repeats++
                false
            } else {
                events.addLast(Event(System.currentTimeMillis(), thread, message))
                while (events.size > MAX_EVENTS) events.removeFirst()
                true
            }
        }
        if (fresh) {
            // Plain JVM unit tests have no android.util.Log; diagnostics must never break the link.
            runCatching { Log.i(TAG, "[$thread] $message") }
            runCatching { sink?.invoke("ADB [$thread] $message") }
        }
    }

    fun recentEvents(limit: Int = 120): List<String> {
        val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        return synchronized(events) {
            events.toList().takeLast(limit).map { event ->
                val repeated = if (event.repeats > 0) " (repeated ${event.repeats}x)" else ""
                "${clock.format(Date(event.time))} [${event.thread}] ${event.message}$repeated"
            }
        }
    }

    /** Blocking (up to ~20 s): run off the main thread. Never asks the driver for approval. */
    fun report(context: Context): String = buildString {
        val app = context.applicationContext
        val key = runCatching { AdbKeys.load(app) }.getOrNull()
        appendLine("ADB link diagnostics ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")

        appendLine("[settings]")
        val settings = listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled").associateWith { name ->
            runCatching { Settings.Global.getString(app.contentResolver, name) }.getOrNull()
        }
        settings.forEach { (name, value) -> appendLine("  $name=${value ?: "unset"}") }

        appendLine("[properties] (empty = unset or hidden from apps)")
        val props = PROPERTIES.associateWith(::property)
        props.forEach { (name, value) -> appendLine("  $name=${value.ifEmpty { "-" }}") }

        appendLine("[key]")
        if (key == null) appendLine("  could not load or create DiPlay's ADB key")
        else {
            appendLine("  fingerprint (MD5, as in the car's 'Allow USB debugging?' dialog): ${fingerprint(key)}")
            appendLine("  public key starts with: ${publicKeyPrefix(key)} (look for it in /data/misc/adb/adb_keys)")
            val file = File(File(app.noBackupFilesDir, "adb"), "adbkey.public")
            appendLine("  created: ${if (file.exists()) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.lastModified())) else "unknown"}")
        }

        val ports = (listOf(5555) + listOf("service.adb.tcp.port", "persist.adb.tcp.port")
            .mapNotNull { props[it]?.toIntOrNull() }.filter { it in 1..65535 }).distinct()

        appendLine("[listening sockets on ${ports.joinToString(",")}] (/proc/net/tcp*)")
        val listening = listeningSockets(ports)
        if (listening == null) appendLine("  /proc/net/tcp is not readable by apps on this firmware")
        else if (listening.isEmpty()) appendLine("  none: adbd is not listening on TCP (USB-only or ADB over network off)")
        else listening.forEach { appendLine("  $it") }

        appendLine("[addresses]")
        val addresses = localAddresses()
        addresses.forEach { (name, ip) -> appendLine("  $name $ip") }

        appendLine("[handshake probes] (mayAsk=false: no approval dialog)")
        val hosts = (listOf("127.0.0.1", "::1") + addresses.map { it.second }).distinct()
        val targets = hosts.flatMap { host -> ports.map { host to it } }.take(12)
        val results = if (key == null) emptyList() else probeAll(key, targets)
        results.forEach { appendLine("  $it") }

        appendLine("[verdict]")
        verdict(results, listening, props, settings).forEach { appendLine("  $it") }

        appendLine("[recent DiPlay ADB activity] (oldest first)")
        val recent = recentEvents()
        if (recent.isEmpty()) appendLine("  none since DiPlay started")
        else recent.forEach { appendLine("  $it") }
    }

    private class ProbeResult(val host: String, val port: Int, val access: LocalAdb.Access?, val millis: Long,
        val trace: List<String>, val shell: String?) {
        override fun toString(): String {
            val head = "$host:$port -> ${access ?: "error"} in ${millis}ms"
            val detail = trace.joinToString(" | ").take(600)
            return head + (shell?.let { " shell=\"${it.replace('\n', ' ').take(160)}\"" } ?: "") +
                if (detail.isNotEmpty()) "\n      $detail" else ""
        }
    }

    private fun probeAll(key: KeyPair, targets: List<Pair<String, Int>>): List<ProbeResult> {
        val pool = Executors.newFixedThreadPool(targets.size.coerceIn(1, 6)) { Thread(it, "diplay-adb-probe").apply { isDaemon = true } }
        return try {
            val futures = targets.map { (host, port) ->
                pool.submit(Callable {
                    val trace = mutableListOf<String>()
                    val started = System.nanoTime()
                    LocalAdb(key, host, port, trace = { synchronized(trace) { trace += it } }).use { adb ->
                        val access = runCatching { adb.connect(mayAsk = false) }.getOrNull()
                        val millis = (System.nanoTime() - started) / 1_000_000
                        val shell = if (access == LocalAdb.Access.READY) adb.shell("id -u; getprop service.adb.tcp.port") else null
                        ProbeResult(host, port, access, millis, synchronized(trace) { trace.toList() }, shell)
                    }
                })
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS)
            futures.mapIndexed { index, future ->
                val wait = (deadline - System.nanoTime()).coerceAtLeast(1)
                runCatching { future.get(wait, TimeUnit.NANOSECONDS) }.getOrElse {
                    future.cancel(true)
                    val (host, port) = targets[index]
                    ProbeResult(host, port, null, PROBE_TIMEOUT_SECONDS * 1000, listOf("probe timed out: ${it.javaClass.simpleName}"), null)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun verdict(results: List<ProbeResult>, listening: List<String>?, props: Map<String, String>,
        settings: Map<String, String?>): List<String> {
        val loopback = results.firstOrNull { it.host == "127.0.0.1" && it.port == 5555 }
        val ready = results.filter { it.access == LocalAdb.Access.READY }
        // Android shows "Allow debugging?" from AdbDebuggingManager, which only listens to adbd while
        // ADB is enabled in Settings. adbd started from a property alone keeps serving already
        // approved keys (a PC still works) but no new key can ever be approved.
        val adbSetting = settings["adb_enabled"]
        val dialogImpossible = adbSetting != null && adbSetting != "1" &&
            results.any { it.access == LocalAdb.Access.NOT_APPROVED }
        return buildList {
            if (dialogImpossible) add(
                "adbd is running (service.adb.tcp.port=${props["service.adb.tcp.port"].orEmpty().ifEmpty { "-" }}) " +
                    "but adb_enabled=$adbSetting, so Android never shows 'Allow debugging?' and DiPlay's key " +
                    "can never be approved. Already approved keys (your PC) keep working. Fix: turn on USB " +
                    "debugging in the car's developer options, or from a PC run " +
                    "`adb shell settings put global adb_enabled 1`, then use a DiPlay button that asks for ADB access.")
            when {
                loopback?.access == LocalAdb.Access.READY ->
                    add("OK: DiPlay reaches adbd on 127.0.0.1:5555 and its key is approved.")
                ready.isNotEmpty() -> add("adbd works on ${ready.joinToString { "${it.host}:${it.port}" }} " +
                    "but not on 127.0.0.1:5555, which DiPlay uses. adbd or a firewall (common on VM head units) " +
                    "is limited to that address.")
                dialogImpossible -> Unit
                results.any { it.access == LocalAdb.Access.NOT_APPROVED } ->
                    add("adbd answers but DiPlay's key is not approved: use a DiPlay button that asks for ADB " +
                        "access and tap 'Always allow' on the car screen (check the fingerprint above). " +
                        "Approving the PC does not approve DiPlay; each client has its own key.")
                results.any { it.access == LocalAdb.Access.UNSUPPORTED } ->
                    add("adbd only offers TLS (Android 11+ wireless debugging). DiPlay needs plain " +
                        "'ADB over network': from the PC run `adb tcpip 5555`.")
                listening?.isEmpty() == true || results.all { it.trace.any { line -> "refused" in line } } ->
                    add("Nothing accepts connections on the ADB port. From the PC run `adb tcpip 5555` " +
                        "(lost after a reboot unless the car keeps ADB over network on).")
                else -> add("adbd did not complete a handshake on any address; see the probe traces above.")
            }
            if (props["service.adb.tcp.port"].isNullOrEmpty() && props["persist.adb.tcp.port"].isNullOrEmpty())
                add("No adb TCP port property is visible to DiPlay (it may just be hidden by SELinux).")
        }
    }

    private val PROPERTIES = listOf(
        "init.svc.adbd", "service.adb.tcp.port", "persist.adb.tcp.port", "service.adb.tls.port",
        "persist.adb.tls_server.enable", "ro.adb.secure", "ro.secure", "ro.debuggable",
        "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
    )

    private fun property(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as? String
    }.getOrNull().orEmpty()

    /** MD5 of the decoded key blob, the way Android's USB debugging dialog shows it. */
    fun fingerprint(key: KeyPair): String {
        val blob = com.shilapi.xcertplay.compat.Base64Compat.decode(base64Key(key))
        return MessageDigest.getInstance("MD5").digest(blob).joinToString(":") { "%02X".format(it) }
    }

    private fun publicKeyPrefix(key: KeyPair) = base64Key(key).take(24) + "…"

    private fun base64Key(key: KeyPair): String =
        String(AdbKeys.publicKeyMessage(key.public), Charsets.UTF_8).substringBefore(' ').trim('\u0000')

    /** "proto local -> state" lines for sockets bound to [ports]; null when /proc/net is hidden. */
    private fun listeningSockets(ports: List<Int>): List<String>? {
        val files = listOf("/proc/net/tcp" to "tcp", "/proc/net/tcp6" to "tcp6")
        var readable = false
        val found = mutableListOf<String>()
        for ((path, proto) in files) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            readable = true
            for (line in lines.drop(1)) {
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 4) continue
                val local = cols[1]
                val port = local.substringAfter(':').toIntOrNull(16) ?: continue
                if (port !in ports) continue
                val state = when (cols[3]) { "0A" -> "LISTEN"; "01" -> "ESTABLISHED"; "06" -> "TIME_WAIT"; else -> "state=${cols[3]}" }
                found += "$proto ${hexAddress(local.substringBefore(':'))}:$port $state"
            }
        }
        return if (readable) found.distinct() else null
    }

    private fun hexAddress(hex: String): String = when (hex.length) {
        8 -> hex.chunked(2).reversed().joinToString(".") { it.toInt(16).toString() }
        32 -> when {
            hex.all { it == '0' } -> "[::]"
            hex == "00000000000000000000000001000000" -> "[::1]"
            // IPv4-mapped (::ffff:a.b.c.d): the last 32-bit word holds the IPv4 address.
            hex.startsWith("0000000000000000FFFF0000", ignoreCase = true) -> "[::ffff:${hexAddress(hex.takeLast(8))}]"
            else -> "[$hex]"
        }
        else -> hex
    }

    private fun localAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { iface ->
            iface.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }.map { iface.name to it.hostAddress.orEmpty() }
        }.filter { it.second.isNotEmpty() }
    }.getOrDefault(emptyList())
}

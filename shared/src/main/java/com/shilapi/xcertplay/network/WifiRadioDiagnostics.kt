package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort, read-only snapshot of what is using the Wi-Fi radio. An app cannot query the
 * vendor HAL interface combinations, so this infers conflicts from public capability flags,
 * kernel interface state and active networks. Every probe is optional and may report "unknown".
 */
internal object WifiRadioDiagnostics {
    data class Snapshot(val lines: List<String>, val softApInterfaces: List<String>, val softApReported: Boolean) {
        val softApActive: Boolean get() = softApInterfaces.isNotEmpty() || softApReported
    }

    /**
     * [capture] on a daemon thread, giving up after [timeoutMillis]. Some framework getters can
     * block (or wait for the main thread), and diagnostics must never hold up the connection.
     * Returns null when the probes did not finish in time.
     */
    /** Informational capture off the caller's thread; [report] runs on that background thread. */
    fun captureAsync(context: Context, phase: String, report: (List<String>) -> Unit) {
        val app = context.applicationContext
        Thread({ runCatching { report(capture(app, phase).lines) } }, "diplay-wifi-diagnostics")
            .apply { isDaemon = true }.start()
    }

    fun captureBounded(context: Context, phase: String, timeoutMillis: Long = 1_500L): Snapshot? {
        val app = context.applicationContext
        val task = java.util.concurrent.FutureTask { capture(app, phase) }
        Thread(task, "diplay-wifi-diagnostics").apply { isDaemon = true }.start()
        return try {
            task.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    private data class Iface(
        val name: String,
        val up: Boolean?,
        val operState: String?,
        val devType: String?,
        val wireless: Boolean,
        val ipv4: List<String>,
    )

    fun capture(context: Context, phase: String): Snapshot {
        val app = context.applicationContext
        val lines = mutableListOf<String>()
        val wifi = app.getSystemService(WifiManager::class.java)

        lines += "Wi-Fi radio [$phase] capabilities " + listOf(
            "p2pSupported" to probe { wifi?.isP2pSupported },
            "staApConcurrency" to probe(30) { wifi?.isStaApConcurrencySupported },
            "staLocalOnlyConcurrency" to probe(31) { wifi?.isStaConcurrencyForLocalOnlyConnectionsSupported },
            "makeBeforeBreak" to probe(31) { wifi?.isMakeBeforeBreakWifiSwitchingSupported },
            "bridgedApConcurrency" to probe(31) { wifi?.isBridgedApConcurrencySupported },
            "dualBandSimultaneous" to probe(31) { wifi?.isDualBandSimultaneousSupported },
            "staMultiInternet" to probe(33) { wifi?.isStaConcurrencyForMultiInternetSupported },
            "5GHz" to probe { wifi?.is5GHzBandSupported },
        ).joinToString(" ") { "${it.first}=${it.second}" }

        // Hidden getters; often blocked for apps, in which case they simply report unknown.
        val rawApState = reflect(wifi, "getWifiApState")
        val apState = rawApState?.let(::apStateLabel) ?: "unknown"
        val apEnabled = reflect(wifi, "isWifiApEnabled") ?: "unknown"
        val softApReported = rawApState == 12 || rawApState == 13 || apEnabled == true
        val tethered = (reflect(app.getSystemService(ConnectivityManager::class.java), "getTetheredIfaces")
            as? Array<*>)?.joinToString(",")
        lines += "Wi-Fi radio [$phase] softAp state=$apState enabled=$apEnabled tetheredIfaces=${tethered ?: "unknown"}"

        val interfaces = readInterfaces()
        val wireless = interfaces.filter { it.wireless }
        if (wireless.isEmpty()) lines += "Wi-Fi radio [$phase] wireless interfaces: none visible to the app"
        wireless.forEach { iface ->
            lines += "Wi-Fi radio [$phase] iface=${iface.name} role=${roleOf(iface.name)} up=${iface.up ?: "unknown"} " +
                "operstate=${iface.operState ?: "unknown"} devtype=${iface.devType ?: "unknown"} " +
                "ipv4=${iface.ipv4.ifEmpty { listOf("none") }.joinToString(",")}"
        }
        val others = interfaces.filterNot { it.wireless }.map { it.name + if (it.up == true) "(up)" else "" }
        if (others.isNotEmpty()) lines += "Wi-Fi radio [$phase] other interfaces: ${others.joinToString(" ")}"

        lines += activeNetworks(app, phase)

        val softAp = wireless.filter { roleOf(it.name) == "softap" && (it.up == true || it.ipv4.isNotEmpty() ||
            it.operState.equals("up", ignoreCase = true)) }.map { it.name }
        val p2pIfaces = wireless.filter { roleOf(it.name) == "p2p" }
        val stationUp = wireless.any { roleOf(it.name) == "station" && it.up == true }
        val verdict = when {
            softAp.isEmpty() && softApReported ->
                "the framework reports the soft AP as $apState (interfaces hidden from the app); many head-unit " +
                    "chips cannot add Wi-Fi Direct while a hotspot runs. Likely owner: the car's built-in hotspot"
            softAp.isNotEmpty() && stationUp ->
                "station + soft AP (${softAp.joinToString(",")}) are both active; many head-unit chips allow only two " +
                    "concurrent interfaces (e.g. STA+AP or STA+P2P), so Wi-Fi Direct cannot be added. " +
                    "Likely owner: the car's built-in hotspot / tethering"
            softAp.isNotEmpty() ->
                "soft AP (${softAp.joinToString(",")}) is active and may hold the radio needed for Wi-Fi Direct. " +
                    "Likely owner: the car's built-in hotspot / tethering"
            p2pIfaces.any { it.up == true } ->
                "a P2P interface (${p2pIfaces.filter { it.up == true }.joinToString(",") { it.name }}) is already up; " +
                    "another app or the system projection service may own Wi-Fi Direct"
            wireless.isEmpty() ->
                "no soft AP reported (state=$apState); wireless interfaces are hidden from apps on this firmware, " +
                    "so a P2P/system projection owner cannot be ruled out from here"
            else -> "no competing soft AP or P2P interface visible to the app"
        }
        lines += "Wi-Fi radio [$phase] conflict analysis: $verdict"
        return Snapshot(lines, softAp, softApReported)
    }

    private fun readInterfaces(): List<Iface> {
        val javaIfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList()).associateBy { it.name }
        // /sys/class/net also lists interfaces without addresses, which getifaddrs() hides from apps.
        val sysNames = runCatching { File("/sys/class/net").list()?.toList().orEmpty() }.getOrDefault(emptyList())
        return (sysNames + javaIfaces.keys).distinct().sorted().map { name ->
            val sys = File("/sys/class/net", name)
            val uevent = runCatching { File(sys, "uevent").readLines() }.getOrDefault(emptyList())
            val devType = uevent.firstOrNull { it.startsWith("DEVTYPE=") }?.substringAfter('=')
            val java = javaIfaces[name]
            val wireless = devType == "wlan" || runCatching { File(sys, "wireless").exists() || File(sys, "phy80211").exists() }
                .getOrDefault(false) || roleOf(name) != "other"
            Iface(
                name = name,
                up = runCatching { java?.isUp }.getOrNull(),
                operState = runCatching { File(sys, "operstate").readText().trim() }.getOrNull(),
                devType = devType,
                wireless = wireless,
                ipv4 = java?.inetAddresses?.toList().orEmpty().filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress },
            )
        }
    }

    private fun activeNetworks(context: Context, phase: String): List<String> = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_WIFI_AWARE to "wifiAware",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_VPN to "vpn",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
            ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
            val iface = connectivity.getLinkProperties(network)?.interfaceName ?: "unknown"
            "Wi-Fi radio [$phase] network iface=$iface transport=$transports " +
                "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        }
    }.getOrElse { listOf("Wi-Fi radio [$phase] networks unavailable: ${it.javaClass.simpleName}") }

    private fun roleOf(name: String): String = when {
        name.startsWith("p2p") -> "p2p"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> "softap"
        name.startsWith("wlan") -> "station"
        name.startsWith("aware") || name.startsWith("nan") -> "nan"
        else -> "other"
    }

    private fun apStateLabel(value: Any): String = when (value) {
        10 -> "disabling"; 11 -> "disabled"; 12 -> "enabling"; 13 -> "enabled"; 14 -> "failed"
        else -> value.toString()
    }

    private fun probe(minSdk: Int = 0, read: () -> Boolean?): String =
        if (Build.VERSION.SDK_INT < minSdk) "n/a" else runCatching { read() }.getOrNull()?.toString() ?: "unknown"

    private fun reflect(target: Any?, method: String): Any? = runCatching {
        target?.javaClass?.getMethod(method)?.invoke(target)
    }.getOrNull()
}

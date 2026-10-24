package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydSignalBatch.isErrorCode
import com.shilapi.xcertplay.input.ShellKeyReplay
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The line protocol between [BydShellAgent] and [BydShellAgentMain]. Requests (app to agent):
 * `R <id> <dev>,<fid>,<tx> …` reads getters (tx 5 or 7 only), `K <id> <token> <code> <scan> <down> <expires> <display>`
 * injects one key pair, `S <id> <dev>,<fid> …` replaces the push subscription, `P <id>` pings. Every agent
 * line starts with [prefix] so the BYD SDK's own stdout noise is ignored: `ready <protocol> binder=<0|1>`,
 * `r <id> <value|x> …`, `k <id> <outcome>`, `s <id> <ok|err:…> …`, `p <id>`, `e <dev>,<fid> <value>`, `bye <reason>`.
 */
internal object BydShellAgentProtocol {
    const val VERSION = 1
    const val MAX_READS = 64
    const val MAX_SUBSCRIPTIONS = 32
    private val tokenPattern = Regex("[0-9a-f]{32}")

    fun validToken(token: String) = tokenPattern.matches(token)
    fun prefix(token: String) = "dpa${token.take(8)}"

    fun command(apk: String, token: String, appPid: Int): String {
        require(validToken(token) && apk.isNotBlank() && apk.none { it == '\n' || it == '\r' || it == '\u0000' })
        val quoted = "'" + apk.replace("'", "'\\''") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${BydShellAgentMain::class.java.name} $token $appPid"
    }

    fun readBody(signals: List<BydSignal>): String {
        require(signals.size in 1..MAX_READS && signals.all { it.transaction == BydSignal.GET_INT || it.transaction == BydSignal.GET_FLOAT })
        return signals.joinToString(" ") { "${it.device},${it.fid},${it.transaction}" }
    }

    /** One read target, or null for anything but a getter. */
    fun parseRead(word: String): BydSignal? {
        val parts = word.split(',')
        if (parts.size != 3) return null
        val device = parts[0].toIntOrNull() ?: return null
        val fid = parts[1].toIntOrNull() ?: return null
        val transaction = parts[2].toIntOrNull()?.takeIf { it == BydSignal.GET_INT || it == BydSignal.GET_FLOAT } ?: return null
        return BydSignal(device, fid, transaction)
    }

    fun parseTarget(word: String): Pair<Int, Int>? {
        val parts = word.split(',')
        if (parts.size != 2) return null
        return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
    }

    /** The values of an `r` reply, error codes and failures as null; null when the reply does not match. */
    fun parseValues(words: List<String>, count: Int): List<Int?>? {
        if (words.size != count) return null
        return words.map { word -> if (word == "x") null else (word.toIntOrNull() ?: return null).takeUnless { it.isErrorCode() } }
    }
}

/**
 * A resident `app_process` under the head unit's adb shell, started over a dedicated adb link while
 * an owner needs it: vehicle reads go straight to autoservice's Binder in tens of milliseconds instead
 * of one `service call` process per signal, original-key replays skip a fresh adb handshake and JVM
 * start, and the agent can register BYD's own change listeners to push cluster changes. Every caller
 * keeps its old path for when the agent is not ready. Only getters, key pairs that pass
 * [ShellKeyReplay.Request.valid] and listener registrations ever cross; the agent writes no vehicle value.
 */
object BydShellAgent {
    internal fun interface PushListener { fun onPush(device: Int, fid: Int, value: Int) }

    private const val TAG = "DiPlay-BYD-Agent"
    private const val READY_MILLIS = 12_000L
    private const val RETRY_MILLIS = 30_000L
    private const val MAX_TIMEOUTS = 3
    /** An agent that dies this soon after a subscription is taken to have been killed by it. */
    private const val PUSH_FATAL_MILLIS = 15_000L

    private val lock = Any()
    private val owners = HashSet<String>()
    private var app: Context? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-shell-agent").apply { isDaemon = true } }
    @Volatile private var link: Link? = null
    private var retryAtMillis = 0L // worker thread
    private val push = CopyOnWriteArraySet<PushListener>()
    private val history = ArrayDeque<String>()

    /** Increments with every agent that became ready; a new one needs its subscription again. */
    @Volatile var generation = 0
        private set
    @Volatile private var lastSubscribeMillis = 0L
    @Volatile var pushDisabled: String? = null
        private set
    @Volatile private var subscription = "none"
    private val pushCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val stats = Stats()

    val ready: Boolean get() = link?.ready == true

    /** Keeps the agent running while any owner holds it; starting it may take a few seconds. */
    fun acquire(context: Context, owner: String) {
        synchronized(lock) {
            app = context.applicationContext
            if (!owners.add(owner)) return
        }
        note("acquired by $owner")
        worker.execute(::ensure)
    }

    fun release(owner: String) {
        val idle = synchronized(lock) {
            if (!owners.remove(owner)) return
            owners.isEmpty()
        }
        note("released by $owner")
        if (idle) worker.execute { if (synchronized(lock) { owners.isEmpty() }) link?.close("idle") }
    }

    internal fun addPushListener(listener: PushListener) { push.add(listener) }
    internal fun removePushListener(listener: PushListener) { push.remove(listener) }

    /** Values for [signals] with BYD error codes as null, or null when the agent is not ready (the caller falls back). */
    internal fun read(signals: List<BydSignal>, timeoutMillis: Long = 1_500): Map<BydSignal, Int?>? {
        val current = usable() ?: return null
        if (signals.isEmpty()) return emptyMap()
        val started = SystemClock.elapsedRealtime()
        val values = ArrayList<Int?>(signals.size)
        for (chunk in signals.chunked(BydShellAgentProtocol.MAX_READS)) {
            val words = current.call('R', BydShellAgentProtocol.readBody(chunk), timeoutMillis)?.await(timeoutMillis) ?: return null
            values += BydShellAgentProtocol.parseValues(words, chunk.size) ?: return null
        }
        stats.read(SystemClock.elapsedRealtime() - started)
        return signals.indices.associate { signals[it] to values[it] }
    }

    /**
     * One key pair. [prepare] gets the agent's token and returns the request, or null to cancel. Returns
     * null when nothing was sent (the caller may use its own shell), else the agent's outcome, "unknown"
     * when a sent request got no answer.
     */
    internal fun key(prepare: (String) -> ShellKeyReplay.Request?): String? {
        val current = usable() ?: return null
        val request = prepare(current.token) ?: return "cancelled"
        val started = SystemClock.elapsedRealtime()
        val pending = current.call('K', request.line(), 3_000) ?: return null
        val outcome = pending.await(3_000)?.singleOrNull()?.takeIf { it in ShellKeyReplay.outcomes } ?: "unknown"
        stats.key(SystemClock.elapsedRealtime() - started)
        note("key replay $outcome in ${SystemClock.elapsedRealtime() - started} ms")
        return outcome
    }

    /**
     * Replaces the push subscription with [targets] (device to fid); per target "ok" or the reason it
     * failed, or null when the agent is not ready or push was disabled after it took an agent down.
     */
    internal fun subscribe(targets: List<Pair<Int, Int>>): List<String>? {
        pushDisabled?.let { return null }
        val current = usable() ?: return null
        val wanted = targets.distinct().take(BydShellAgentProtocol.MAX_SUBSCRIPTIONS)
        lastSubscribeMillis = SystemClock.elapsedRealtime()
        val words = current.call('S', wanted.joinToString(" ") { "${it.first},${it.second}" }, 5_000)?.await(5_000) ?: return null
        if (words.size != wanted.size) return null
        subscription = if (wanted.isEmpty()) "none" else wanted.indices.joinToString(" ") { index ->
            "%d:0x%08X=%s".format(Locale.US, wanted[index].first, wanted[index].second, words[index])
        }
        note("push subscription $subscription")
        return words
    }

    fun report(): List<String> {
        val current = link
        return listOf("Shell agent: ready=${current?.ready == true} owners=${synchronized(lock) { owners.sorted() }} " +
            "generation=$generation ${stats.summary()} push=${pushDisabled?.let { "disabled ($it)" } ?: subscription} " +
            "pushEvents=${pushCounts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifEmpty { "0" }}") +
            synchronized(history) { history.toList() }
    }

    /** The ready link, or null after asking the worker to start one. */
    private fun usable(): Link? {
        link?.takeIf { it.ready }?.let { return it }
        if (synchronized(lock) { owners.isNotEmpty() }) worker.execute(::ensure)
        return null
    }

    // Worker thread.
    private fun ensure() {
        val context = synchronized(lock) { if (owners.isEmpty()) null else app } ?: return
        if (link?.alive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now < retryAtMillis) return
        retryAtMillis = now + RETRY_MILLIS
        val started = start(context) ?: return
        if (synchronized(lock) { owners.isEmpty() }) { started.close("idle"); return }
        generation++
        link = started
        retryAtMillis = 0
        note("ready in ${SystemClock.elapsedRealtime() - now} ms (${started.readyLine})")
    }

    private fun start(context: Context): Link? {
        val adb = LocalAdb(AdbKeys.load(context), trace = {})
        val access = adb.connect(mayAsk = false)
        if (access != LocalAdb.Access.READY) {
            adb.close()
            note("not started: adb $access")
            return null
        }
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val created = Link(token, adb)
        val command = BydShellAgentProtocol.command(context.applicationInfo.sourceDir, token, android.os.Process.myPid())
        val shell = adb.openDuplex(command, created::accept, created::ended)
        if (shell == null) {
            adb.close()
            note("not started: shell refused")
            return null
        }
        created.shell = shell
        val ready = runCatching { created.ready(READY_MILLIS) }.getOrNull()
        if (ready == null) {
            created.close("not ready")
            note("not started: no ready line in $READY_MILLIS ms")
            return null
        }
        return created
    }

    private fun onLinkEnded(ended: Link, reason: String) {
        if (link === ended) link = null
        val sinceSubscribe = SystemClock.elapsedRealtime() - lastSubscribeMillis
        if (ended.wasReady && subscription != "none" && lastSubscribeMillis > 0 && sinceSubscribe < PUSH_FATAL_MILLIS && pushDisabled == null) {
            pushDisabled = "agent ended $sinceSubscribe ms after subscribing: $reason"
        }
        if (ended.wasReady) subscription = "none"
        note("ended: $reason")
    }

    private fun deliver(device: Int, fid: Int, value: Int) {
        pushCounts.getOrPut("%d:0x%08X".format(Locale.US, device, fid)) { AtomicInteger() }.incrementAndGet()
        push.forEach { runCatching { it.onPush(device, fid, value) } }
    }

    private fun note(message: String) {
        synchronized(history) {
            if (history.size >= 12) history.removeFirst()
            history.addLast(message)
        }
        Log.i(TAG, message)
    }

    private class Pending(private val future: CompletableFuture<List<String>>, private val cleanup: () -> Unit) {
        fun await(timeoutMillis: Long): List<String>? = try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cleanup(); null
        } catch (_: Exception) {
            null
        }
    }

    private class Link(val token: String, private val adb: LocalAdb) {
        @Volatile var shell: LocalAdb.DuplexShell? = null
        private val prefix = BydShellAgentProtocol.prefix(token) + " "
        private val buffer = ByteArrayOutputStream()
        private val readyFuture = CompletableFuture<String>()
        private val pending = ConcurrentHashMap<Int, CompletableFuture<List<String>>>()
        private val ids = AtomicInteger()
        private val timeouts = AtomicInteger()
        @Volatile private var closedReason: String? = null
        @Volatile var wasReady = false
            private set
        @Volatile var readyLine = ""
            private set

        val alive: Boolean get() = closedReason == null
        val ready: Boolean get() = wasReady && closedReason == null && shell?.isOpen == true

        fun ready(timeoutMillis: Long): String = readyFuture.get(timeoutMillis, TimeUnit.MILLISECONDS)

        /** Writes one request; null when it could not be written. */
        fun call(op: Char, body: String, timeoutMillis: Long): Pending? {
            val stream = shell ?: return null
            if (!alive) return null
            val id = ids.incrementAndGet()
            val future = CompletableFuture<List<String>>()
            pending[id] = future
            if (!stream.write("$op $id $body\n", timeoutMillis)) {
                pending.remove(id)
                return null
            }
            future.whenComplete { _, error -> if (error == null) timeouts.set(0) }
            return Pending(future) {
                pending.remove(id)
                if (timeouts.incrementAndGet() >= MAX_TIMEOUTS) close("$MAX_TIMEOUTS timeouts")
            }
        }

        /** Reader thread: splits the agent's output into lines. */
        fun accept(bytes: ByteArray) {
            val lines = ArrayList<String>()
            synchronized(buffer) {
                for (byte in bytes) {
                    if (byte == '\n'.code.toByte()) {
                        lines += buffer.toString("UTF-8").trimEnd('\r')
                        buffer.reset()
                    } else if (buffer.size() < MAX_LINE_BYTES) buffer.write(byte.toInt())
                }
            }
            lines.forEach(::line)
        }

        private fun line(text: String) {
            if (!text.startsWith(prefix)) return
            val words = text.substring(prefix.length).trim().split(' ').filter { it.isNotEmpty() }
            when (words.firstOrNull()) {
                "ready" -> {
                    readyLine = words.drop(1).joinToString(" ")
                    if (words.getOrNull(1)?.toIntOrNull() == BydShellAgentProtocol.VERSION) {
                        wasReady = true
                        readyFuture.complete(readyLine)
                    } else close("protocol ${words.getOrNull(1)}")
                }
                "r", "k", "s", "p" -> words.getOrNull(1)?.toIntOrNull()?.let { pending.remove(it) }?.complete(words.drop(2))
                "e" -> {
                    val target = words.getOrNull(1)?.let(BydShellAgentProtocol::parseTarget) ?: return
                    val value = words.getOrNull(2)?.toIntOrNull() ?: return
                    deliver(target.first, target.second, value)
                }
                "bye" -> closedReason = closedReason ?: "agent: ${words.drop(1).joinToString(" ")}"
            }
        }

        /** The stream ended (reader thread or [close]). */
        fun ended() {
            val reason = closedReason ?: "stream ended".also { closedReason = it }
            readyFuture.completeExceptionally(IllegalStateException(reason))
            pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
            pending.clear()
            runCatching { adb.close() }
            onLinkEnded(this, reason)
        }

        fun close(reason: String) {
            if (closedReason == null) closedReason = reason
            val stream = shell
            if (stream != null) stream.close() else ended()
        }

        private companion object {
            const val MAX_LINE_BYTES = 8_192
        }
    }

    private class Stats {
        private var reads = 0L
        private var readTotal = 0L
        private var readMax = 0L
        private var keys = 0L
        private var keyMax = 0L
        @Synchronized fun read(millis: Long) { reads++; readTotal += millis; readMax = maxOf(readMax, millis) }
        @Synchronized fun key(millis: Long) { keys++; keyMax = maxOf(keyMax, millis) }
        @Synchronized fun summary() = "reads=$reads avg=${if (reads == 0L) 0 else readTotal / reads}ms max=${readMax}ms keys=$keys keyMax=${keyMax}ms"
    }
}

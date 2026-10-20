package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Window lifecycle evidence is independent of the much busier music metadata ring. */
internal object ClusterWindowDiagnostics {
    private const val FILE_NAME = "cluster-window-diagnostics.json"
    private val journal = ClusterWindowJournal(Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-window-diagnostics").apply { isDaemon = true }
    })

    fun attachContext(context: Context) {
        runCatching { journal.attach(File(context.applicationContext.filesDir, FILE_NAME)) }
            .onFailure { journal.storageUnavailable(it) }
    }

    fun note(message: String) = journal.note(message)
    fun currentSnapshot(): ClusterWindowJournal.CurrentSnapshot = journal.currentSnapshot()

    /** No initialization, window inspection, permission request or ADB command is performed here. */
    fun report(context: Context): String = try {
        journal.report(File(context.applicationContext.filesDir, FILE_NAME))
    } catch (error: Exception) {
        "window diagnostics read failed=${error.javaClass.simpleName}; current=${currentSnapshot()}"
    }
}

internal class ClusterWindowJournal(private val executor: Executor) {
    data class CurrentSnapshot(
        val status: String = "unknown",
        val observedAtMillis: Long = 0,
        val backend: String? = null,
        val requestedType: Int? = null,
        val contextType: Int? = null,
        val actualType: Int? = null,
        val displayId: Int? = null,
    )

    private data class Event(val atMillis: Long, val elapsedMillis: Long, val message: String) {
        val kind: String get() = classify(message)!!
        val fields: Map<String, String> get() = fields(message)
        fun report() = "atMillis=$atMillis elapsedMillis=$elapsedMillis $message"
        fun json() = JSONObject().put("atMillis", atMillis).put("elapsedMillis", elapsedMillis).put("message", message)
    }

    private data class History(
        val events: List<Event> = emptyList(),
        val shown: Event? = null,
        val failed: Event? = null,
        val fallback: Event? = null,
        val stopped: Event? = null,
    ) {
        fun append(event: Event) = copy(
            events = (events + event).takeLast(MAX_EVENTS),
            shown = if (event.kind == "shown" && confirmed(event)) event else shown,
            failed = if (event.kind in setOf("failed", "dismiss_failed", "unavailable")) event else failed,
            fallback = if (event.kind == "fallback") event else fallback,
            stopped = if (event.kind in setOf("stopped", "dismiss")) event else stopped,
        )

        fun withRecent(recent: History) = History(
            (events.filterNot { it in recent.events } + recent.events).takeLast(MAX_EVENTS),
            recent.shown ?: shown, recent.failed ?: failed, recent.fallback ?: fallback, recent.stopped ?: stopped,
        )

        fun json(): JSONObject = JSONObject().put("version", 1)
            .put("events", JSONArray().apply { events.forEach { put(it.json()) } })
            .put("shown", shown?.json()).put("failed", failed?.json())
            .put("fallback", fallback?.json()).put("stopped", stopped?.json())
    }

    private val lock = Any()
    private val ioLock = Any()
    private var live = History()
    private var current = CurrentSnapshot()
    private var file: File? = null
    private var scheduled = false
    private var dirty = false
    @Volatile private var readFailure = "none"
    @Volatile private var writeFailure = "none"

    fun attach(target: File) = synchronized(lock) {
        if (file != null) return@synchronized
        file = target
        scheduleLocked()
    }

    fun storageUnavailable(error: Throwable) { writeFailure = error.javaClass.simpleName }

    fun note(message: String, atMillis: Long = System.currentTimeMillis(), elapsedMillis: Long = SystemClock.elapsedRealtime()) {
        val safe = sanitize(message) ?: return
        val event = Event(atMillis, elapsedMillis, safe)
        synchronized(lock) {
            live = live.append(event)
            current = when {
                event.kind == "shown" && confirmed(event) -> event.fields.let {
                    CurrentSnapshot("active", atMillis, it["backend"], it["requested"]?.toIntOrNull(),
                        it["contextType"]?.toIntOrNull(), it["actual"]?.toIntOrNull(), it["display"]?.toIntOrNull())
                }
                event.kind in setOf("failed", "fallback", "stopped", "dismiss", "unavailable") -> CurrentSnapshot("inactive", atMillis)
                else -> CurrentSnapshot("unknown", atMillis)
            }
            scheduleLocked()
        }
    }

    fun currentSnapshot(): CurrentSnapshot = synchronized(lock) { current }

    private fun scheduleLocked() {
        dirty = true
        if (file == null || scheduled) return
        scheduled = true
        try {
            executor.execute(::drain)
        } catch (error: Exception) {
            scheduled = false
            storageUnavailable(error)
        }
    }

    /** At most one queued/running task; bursts replace a bounded snapshot rather than queueing writes. */
    private fun drain() {
        while (true) {
            val (target, snapshot) = synchronized(lock) {
                if (!dirty) { scheduled = false; return }
                dirty = false
                file!! to live
            }
            synchronized(ioLock) {
                val previous = read(target)
                if (snapshot.events.isNotEmpty()) {
                    var stream: FileOutputStream? = null
                    val atomic = AtomicFile(target)
                    try {
                        val output = atomic.startWrite()
                        stream = output
                        output.write(previous.withRecent(snapshot).json().toString().toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(output)
                    } catch (error: Exception) {
                        writeFailure = error.javaClass.simpleName
                        runCatching { atomic.failWrite(stream) }
                    }
                }
            }
        }
    }

    /** Reads a committed snapshot without AtomicFile.openRead(), which can rename/delete recovery files. */
    private fun read(target: File): History = try {
        val backup = File(target.path + ".bak")
        val source = if (backup.exists()) backup else target
        if (!source.exists()) History() else {
            require(source.length() <= MAX_BYTES) { "oversized journal" }
            val bytes = source.inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val size = input.read(buffer, count, buffer.size - count)
                    if (size < 0) break
                    count += size
                }
                require(count <= MAX_BYTES) { "oversized journal" }
                buffer.copyOf(count)
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            require(root.getInt("version") == 1) { "unknown journal version" }
            val events = root.getJSONArray("events")
            require(events.length() <= MAX_EVENTS) { "oversized event list" }
            History(List(events.length()) { decode(events.getJSONObject(it)) },
                root.optJSONObject("shown")?.let(::decode)?.takeIf { it.kind == "shown" && confirmed(it) },
                root.optJSONObject("failed")?.let(::decode)?.takeIf { it.kind in setOf("failed", "dismiss_failed", "unavailable") },
                root.optJSONObject("fallback")?.let(::decode)?.takeIf { it.kind == "fallback" },
                root.optJSONObject("stopped")?.let(::decode)?.takeIf { it.kind in setOf("stopped", "dismiss") })
        }
    } catch (error: Exception) {
        readFailure = error.javaClass.simpleName
        History()
    }

    private fun decode(json: JSONObject): Event = Event(json.getLong("atMillis"), json.getLong("elapsedMillis"),
        requireNotNull(sanitize(json.getString("message"))))

    fun report(target: File): String {
        val (runtime, now, attached, pending) = synchronized(lock) { ReportState(live, current, file != null, dirty || scheduled) }
        val persisted = synchronized(ioLock) { read(target) }
        val history = persisted.withRecent(runtime)
        return buildString {
            appendLine("currentProcess status=${now.status} observedAtMillis=${now.observedAtMillis} " +
                "backend=${now.backend ?: "none"} requested=${now.requestedType ?: "none"} contextType=${now.contextType ?: "none"} " +
                "actual=${now.actualType ?: "none"} display=${now.displayId ?: "none"}")
            appendLine("Current state is lifecycle-observed in this process; saved history is not proof of an active window after restart.")
            appendLine("persistence attached=$attached pending=$pending lastReadFailure=$readFailure lastWriteFailure=$writeFailure")
            appendLine("lastSuccessfulShown (historical): ${history.shown?.report() ?: "none"}")
            appendLine("lastFailure (historical): ${history.failed?.report() ?: "none"}")
            appendLine("lastFallback (historical): ${history.fallback?.report() ?: "none"}")
            appendLine("lastStopped (historical): ${history.stopped?.report() ?: "none"}")
            appendLine("Window lifecycle history (${history.events.size}/$MAX_EVENTS; no music metadata):")
            history.events.forEach { appendLine(it.report()) }
        }
    }

    private data class ReportState(val live: History, val current: CurrentSnapshot, val attached: Boolean, val pending: Boolean)

    companion object {
        private const val MAX_EVENTS = 96
        private const val MAX_BYTES = 128 * 1024
        private val prefixes = linkedMapOf(
            "custom window dismiss failed" to "dismiss_failed",
            "custom windows shown" to "shown",
            "custom window failed" to "failed",
            "custom window fallback" to "fallback",
            "custom windows stopped" to "stopped",
            "custom window dismiss" to "dismiss",
            "custom windows unavailable" to "unavailable",
        )
        private val fieldPattern = Regex("(?:^|\\s)([A-Za-z]+)=([^\\s]+)")
        private val exceptionTypePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val stackFramePattern = Regex("(?:(?:android|java|javax|kotlin)\\.|com\\.shilapi\\.)[A-Za-z0-9_.$]+\\.(?:[A-Za-z0-9_$]+|<init>|<clinit>):-?[0-9]{1,6}")
        private val reasons = setOf("window_type_mismatch", "permission_denied", "invalid_display", "bad_token",
            "window_operation_failed", "overlay_unavailable", "overlay_failed", "no_display", "system_dismissed",
            "owner_dismissed", "display_removed", "show_failed", "shutdown", "session_disconnected", "display_unavailable",
            "configuration_or_session_changed", "owner_cleanup", "settings_disabled", "host_destroyed", "activity_destroyed", "test_cleanup")

        private fun classify(message: String): String? = prefixes.entries.firstOrNull {
            message == it.key || message.startsWith(it.key + " ")
        }?.value

        private fun fields(message: String): Map<String, String> = fieldPattern.findAll(message).associate { it.groupValues[1] to it.groupValues[2] }

        private fun confirmed(event: Event): Boolean = event.fields.let {
            it["backend"] in setOf("overlay_dialog", "presentation") &&
                listOf("requested", "contextType", "actual", "display").all { key -> it[key]?.toIntOrNull() != null }
        }

        private fun sanitize(message: String): String? {
            if (message.length > 8192) return null
            val kind = classify(message) ?: return null
            val prefix = prefixes.entries.first { it.value == kind }.key
            val allowed = fields(message).mapNotNull { (key, value) ->
                if (key == "stack" || key == "error") {
                    val type = value.substringBefore(':').takeIf { it.length <= 96 && exceptionTypePattern.matches(it) &&
                        (it.endsWith("Exception") || it.endsWith("Error") || it == "Throwable") }
                    val trace = if (type != null) value.substringAfter(':', "") else value
                    val frames = if (key == "stack") trace.split('|').take(4)
                        .filter { it.length <= 160 && stackFramePattern.matches(it) } else emptyList()
                    return@mapNotNull listOfNotNull(type?.let { "error=$it" },
                        frames.takeIf { it.isNotEmpty() }?.joinToString("|")?.let { "stack=$it" })
                        .takeIf { it.isNotEmpty() }?.joinToString(" ")
                }
                val safe = when (key) {
                    "backend" -> value.takeIf { it in setOf("overlay_dialog", "presentation") }
                    "requested", "contextType", "actual", "display" -> value.takeIf { it.toIntOrNull() != null || it in setOf("uncreated", "null") }
                    "phase" -> value.takeIf { it in setOf("construct", "show", "dismiss") }
                    "reason" -> value.takeIf { it in reasons } ?: "other"
                    "music", "map" -> value.takeIf { it == "true" || it == "false" }
                    else -> null
                }
                safe?.let { "$key=$it" }
            }
            return (listOf(prefix) + allowed).joinToString(" ")
        }
    }
}

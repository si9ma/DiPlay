package com.shilapi.xcertplay

/**
 * Folds a line that repeats without changing into one entry per window.
 *
 * The session log is a fixed budget, and a chatty line spends it on nothing: a Di150VCP session spent
 * 47% of its log on `iAP tunnel iap2 rx=0x5201`, logged about once a second with the same value for
 * 23 minutes, which pushed 23035 lines of everything else out of the exported report. A line that
 * carries a changing value is a different line each time and is never folded; only an exact repeat is,
 * and its count is kept so the report still shows it happened and how often.
 *
 * Not thread-safe; [SessionLogFile] calls it under its own lock.
 */
internal class SessionLogRateLimit(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val maxTracked: Int = MAX_TRACKED,
) {
    private class Window(var startedAt: Long, var suppressed: Int)

    private val windows = LinkedHashMap<String, Window>()

    /**
     * What to write for [message] seen at [nowMillis]: the line itself, a line carrying the repeats
     * that were held back, or null while the window is still open.
     */
    fun accept(message: String, nowMillis: Long): String? {
        val key = key(message)
        val window = windows.remove(key)
        if (window == null) {
            while (windows.size >= maxTracked) windows.remove(windows.keys.first())
            windows[key] = Window(nowMillis, 0)
            return message
        }
        windows[key] = window
        // A clock that jumped backwards would otherwise hold the line back indefinitely.
        val elapsed = nowMillis - window.startedAt
        if (elapsed in 0 until windowMillis) {
            window.suppressed++
            return null
        }
        val suppressed = window.suppressed
        window.startedAt = nowMillis
        window.suppressed = 0
        return if (suppressed == 0) message else "$message  (repeated x$suppressed in the last ${elapsed / 1000}s)"
    }

    /**
     * The repeats still held back, for the end of a session: a line that stopped repeating would
     * otherwise take its last count with it.
     */
    fun drain(): List<String> {
        val pending = windows.entries.filter { it.value.suppressed > 0 }
            .map { (key, window) -> "$key  (repeated x${window.suppressed}, not logged again)" }
        windows.clear()
        return pending
    }

    /** Timestamps differ on every line, so the repeat is judged on what follows them. */
    private fun key(message: String): String {
        val separator = message.indexOf("  ")
        return if (separator > 0 && TIMESTAMP.matches(message.substring(0, separator))) {
            message.substring(separator + 2)
        } else {
            message
        }
    }

    companion object {
        /** Long enough to fold a once-a-second line, short enough to keep a minute's resolution. */
        const val WINDOW_MILLIS = 60_000L

        /** Bounds the memory a session can spend on distinct lines; the oldest is forgotten first. */
        const val MAX_TRACKED = 256

        private val TIMESTAMP = Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")
    }
}

package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The navigation troubleshooting buffer, shown by [NavigationDebugOverlay] and exported verbatim in
 * its own section of the diagnostic report.
 *
 * Deliberately separate from the session log, which cannot carry this at all: [DiagnosticRedactor]
 * drops any line matching `name[=:]`, and a route line reads `mToPoi=POI{id='…', name='…'}`, so the
 * one line worth having is exactly the one that disappears — silently, which is worse. Multi-line
 * output is dropped outright and anything past 700 characters is cut.
 *
 * So this buffer holds the car's own text unchanged: real places, real coordinates, real ids. It is
 * collected only while the driver has asked for it, and the report names the section plainly so it is
 * never attached to a public issue by accident. The rest of the report keeps its redaction, which is
 * what protects credentials; this is about the destination, which the driver already knows.
 */
internal object NavigationDebugLog {
    /**
     * Bounded by lines. Larger than the window needs, because the report is usually exported after
     * the drive that is being investigated, not during it.
     */
    const val MAX_LINES = 1_500

    /** One ADB read can be long; a head is kept and the rest is counted. */
    const val MAX_RAW_CHARS = 4_000

    enum class Kind {
        /** A decision DiPlay made: the same text the session log receives. */
        EVENT,

        /** Raw output of a read from the head unit, with its real content. */
        RAW,
    }

    data class Line(val atMillis: Long, val kind: Kind, val text: String)

    /** Set while the driver has asked for this, so nothing is collected otherwise. */
    @Volatile var collecting = false
        private set

    /** Called when a line arrives, so the overlay can append without polling. */
    @Volatile var onLine: ((Line) -> Unit)? = null

    private val lines = ArrayDeque<Line>()

    @Volatile private var dropped = 0

    fun start() = synchronized(this) {
        collecting = true
    }

    /** Stops collecting and forgets everything held: the raw text is not kept past its use. */
    fun stop() = synchronized(this) {
        collecting = false
        lines.clear()
        dropped = 0
    }

    fun event(message: String) = add(Kind.EVENT, message)

    /** [label] names the read; [output] is the car's own text and may be empty. */
    fun raw(label: String, output: String?) {
        if (!collecting) return
        val body = output?.trim().orEmpty()
        add(Kind.RAW, if (body.isEmpty()) "$label: (no output)" else "$label:\n${clip(body)}")
    }

    private fun add(kind: Kind, message: String) {
        val line = synchronized(this) {
            if (!collecting) return
            val entry = Line(System.currentTimeMillis(), kind, message)
            lines.addLast(entry)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                dropped++
            }
            entry
        }
        onLine?.invoke(line)
    }

    fun snapshot(): List<Line> = synchronized(this) { lines.toList() }

    fun droppedCount(): Int = synchronized(this) { dropped }

    /** The whole buffer as text, for the overlay's copy button. */
    fun asText(): String = synchronized(this) {
        buildString {
            if (dropped > 0) appendLine("($dropped older lines dropped)")
            lines.forEach { appendLine(format(it)) }
        }.trimEnd()
    }

    fun format(line: Line): String =
        "${TIME.format(Date(line.atMillis))} ${if (line.kind == Kind.RAW) "<" else "*"} ${line.text}"

    private fun clip(body: String): String =
        if (body.length <= MAX_RAW_CHARS) body
        else body.take(MAX_RAW_CHARS) + "\n… ${body.length - MAX_RAW_CHARS} more characters"

    private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

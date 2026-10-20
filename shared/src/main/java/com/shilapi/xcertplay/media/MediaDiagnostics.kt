package com.shilapi.xcertplay.media

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import java.util.concurrent.atomic.AtomicInteger

/** In-memory only. Contents are exported explicitly, never sent to logcat or a network service. */
internal class DiagnosticRing(private val capacity: Int) {
    private val entries = ArrayDeque<String>()
    private var dropped = 0
    private var sequence = 0L
    @Synchronized fun add(text: String) {
        if (entries.size == capacity) { entries.removeFirst(); dropped++ }
        entries.addLast("seq=${++sequence} monoMs=${SystemClock.elapsedRealtime()} ${text.take(1600)}")
    }
    @Synchronized fun clear() { entries.clear(); dropped = 0; sequence = 0 }
    @Synchronized fun report() = listOf("entries=${entries.size} dropped=$dropped") + entries.toList()
}

object PlaybackDiagnostics {
    private val ring = DiagnosticRing(500)
    private val sessions = AtomicInteger()
    fun newSession(): Int = sessions.incrementAndGet()
    fun note(message: String) = ring.add(message)
    fun report(): List<String> = ring.report()
    fun clear() = ring.clear()
}

/** Consent is process-local; collection stops after ten minutes, and disabling erases all samples. */
object LyricsDiagnostics {
    private val ring = DiagnosticRing(300)
    private var deadline = 0L
    private var owner: java.lang.ref.WeakReference<Any>? = null
    private var previous = CarPlayNowPlaying()
    private var frames = 0L
    private var changes = 0L
    @Synchronized fun collecting(): Boolean = deadline > SystemClock.elapsedRealtime()
    @Synchronized fun start() {
        stop()
        deadline = SystemClock.elapsedRealtime() + 600_000L
        ring.add("capture=start durationMs=600000 textLimit=160 fieldsLimit=48 unknownValues=not_captured")
    }
    @Synchronized fun stop() {
        deadline = 0; owner = null; previous = CarPlayNowPlaying(); frames = 0; changes = 0; ring.clear()
    }
    @Synchronized fun report(): List<String> = listOf(
        "collecting=${collecting()} frames=$frames titleChanges=$changes textMayContainLyrics=true",
        "Only observed NowPlaying fields are inspected. Dynamic title is not proof of lyrics; no lyric field is requested or identified.",
    ) + ring.report()

    @Synchronized fun observe(source: Any, frame: Iap2Frame, next: CarPlayNowPlaying) {
        if (!collecting() || frame.messageId != CarPlayPlaybackStatus.NOW_PLAYING_UPDATE) return
        if (owner?.get() !== source) { owner = java.lang.ref.WeakReference(source); previous = CarPlayNowPlaying(); ring.add("source=changed") }
        frames++
        val changed = previous.title != next.title
        if (changed) changes++
        val stable = previous.artist == next.artist && previous.album == next.album &&
            previous.durationMillis == next.durationMillis && previous.artworkTransferId == next.artworkTransferId &&
            previous.sourceApp == next.sourceApp
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull()
        val fields = mutableListOf<String>()
        body?.list()?.take(16)?.forEach { group ->
            val bytes = group.payload
            fields += "${group.id}(bytes=${bytes.size})"
            if (group.id in setOf(0, 1, 4) && bytes.size <= 32_768) {
                val children = runCatching { Iap2ParameterList.parse(bytes).asList() }.getOrNull()
                if (children == null) fields += "${group.id}/parse=failed"
                else children.take(48 - fields.size.coerceAtMost(48)).forEach { field ->
                    val data = field.payload
                    val knownText = (group.id == 0 && field.id in setOf(1, 6, 12)) || (group.id == 1 && field.id == 7)
                    val scalar = (group.id == 1 && field.id in setOf(0, 1)) || (group.id == 0 && field.id in setOf(4, 26))
                    val detail = when {
                        knownText -> runCatching { "text=\"${sample(Iap2WireCodec.readString(data))}\"" }.getOrDefault("text=malformed")
                        scalar && data.size in 1..4 -> "value=${data.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }}"
                        else -> "unknownValue=not_captured"
                    }
                    fields += "${group.id}/${field.id}(bytes=${data.size},$detail)"
                }
            }
        }
        ring.add("frame=$frames parse=${body != null} titleChanged=$changed otherTrackFieldsStable=$stable " +
            "candidateDynamicTitle=${changed && stable && previous.title != null} playing=${next.playing} " +
            "elapsed=${next.elapsedMillis} ref=${next.artworkTransferId} fields=${fields.joinToString(";")}")
        previous = next
    }

    private fun sample(value: String): String = value.take(160).map { c ->
        when (c) { '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '"' -> "\\\""; '\\' -> "\\\\"; else -> if (c.isISOControl()) "?" else c.toString() }
    }.joinToString("") + if (value.length > 160) "[truncated]" else ""
}

/** Bounded correlation for diagnostic commands only, not video, touch, or iAP payloads. */
internal class EventCommandTrace(private val session: Int) {
    private data class Pending(val kind: String, val at: Long)
    private val pending = LinkedHashMap<Int, Pending>()
    @Synchronized fun sent(cseq: Int, kind: String) {
        if (pending.size == 32) {
            val first = pending.keys.first()
            PlaybackDiagnostics.note("session=$session cseq=$first reply=evicted_unanswered")
            pending.remove(first)
        }
        pending[cseq] = Pending(kind, SystemClock.elapsedRealtime())
        PlaybackDiagnostics.note("session=$session cseq=$cseq phase=write_begin kind=$kind")
    }
    @Synchronized fun written(cseq: Int, ok: Boolean) {
        val request = pending[cseq]
        PlaybackDiagnostics.note("session=$session cseq=$cseq phase=write_end ok=$ok elapsedMs=${request?.let { SystemClock.elapsedRealtime() - it.at }}")
        if (!ok) pending.remove(cseq)
    }
    @Synchronized fun reply(response: RtspMessage.Request) {
        val cseq = response.headers["cseq"]?.toIntOrNull()
        val request = pending.remove(cseq)
        val body = if (request != null) runCatching { BplistCodec.decode(response.body) as? Map<*, *> }.getOrNull() else null
        PlaybackDiagnostics.note("session=$session cseq=$cseq phase=reply matched=${request != null} " +
            "kind=${request?.kind ?: "untracked"} rtsp=${response.path.toIntOrNull()} bytes=${response.body.size} " +
            "plistParsed=${body != null} status=${body?.get("status") as? Number} " +
            "rttMs=${request?.let { SystemClock.elapsedRealtime() - it.at }}")
    }
    @Synchronized fun close() {
        PlaybackDiagnostics.note("session=$session event=closed unanswered=${pending.keys.joinToString(",")}")
        pending.clear()
    }
}

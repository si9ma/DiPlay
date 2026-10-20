package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Main-thread snapshots reuse the bounded media artwork cache; they never own/recycle its bitmap. */
internal object ClusterMusicState {
    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val changedAt: Long = 0L, val revision: Long = 0L)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    @Volatile private var state = Snapshot()
    private val events = ArrayDeque<String>()

    /** Bounded, in-memory routing diagnostics. Callers supply flags and IDs, never song text. */
    fun note(message: String) {
        if (message.startsWith("custom window")) ClusterWindowDiagnostics.note(message)
        val line = "${SystemClock.elapsedRealtime()}ms $message"
        synchronized(events) {
            if (events.size == 80) events.removeFirst()
            events.addLast(line)
        }
    }

    fun diagnosticEvents(): List<String> = synchronized(events) { events.map { "Cluster music: $it" } }

    /** For the report: whether a song and its cover reached the dashboard card. No song text. */
    fun report(): String = state.let {
        "hasTitle=${!it.info.title.isNullOrBlank()} hasArtist=${!it.info.artist.isNullOrBlank()} " +
            "artworkRef=${it.info.artworkTransferId ?: "none"} " +
            "artwork=${it.artwork?.takeUnless { bitmap -> bitmap.isRecycled }?.let { bitmap -> "${bitmap.width}x${bitmap.height}" } ?: "none"} " +
            "listeners=${listeners.size}"
    }

    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?) {
        main.post {
            val changed = trackChanged(state.info, info)
            val imageChanged = state.artwork !== artwork || state.info.artworkTransferId != info.artworkTransferId
            state = Snapshot(info, artwork, if (changed) SystemClock.elapsedRealtime() else state.changedAt, state.revision + if (changed) 1 else 0)
            if (changed || imageChanged) note("publish ${report()}")
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        old.title != next.title || old.artist != next.artist || old.album != next.album ||
            old.sourceApp != next.sourceApp ||
            (old.durationMillis != null && next.durationMillis != null && old.durationMillis != next.durationMillis)
}

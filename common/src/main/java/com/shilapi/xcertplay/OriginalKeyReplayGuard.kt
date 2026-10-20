package com.shilapi.xcertplay

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.input.ShellKeyReplay

/** Retains exact synthetic press identities across every DiPlay input path, without releasing real presses. */
internal object OriginalKeyReplayGuard {
    private data class Entry(val request: ShellKeyReplay.Request, val until: Long)
    private val entries = ArrayDeque<Entry>()
    private var lastDown = 0L
    @Synchronized fun prepare(code: Int, scan: Int, display: Int): ShellKeyReplay.Request {
        val now = SystemClock.uptimeMillis()
        entries.removeAll { now > it.until }
        val down = maxOf(now, lastDown + 1)
        lastDown = down
        val request = ShellKeyReplay.Request(java.util.UUID.randomUUID().toString().replace("-", ""),
            code, scan, down, down + 1500, display)
        require(request.valid())
        if (entries.size >= 8) entries.removeFirst()
        entries.addLast(Entry(request, down + 6000))
        return request
    }
    @Synchronized fun matches(event: KeyEvent, media: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
        entries.removeAll { now > it.until }
        if (event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD ||
            event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        return entries.any { entry ->
            val request = entry.request
            event.downTime == request.downTime && event.eventTime >= request.downTime &&
                event.eventTime <= entry.until && (event.keyCode == request.code && (media || event.scanCode == request.scan) ||
                media && CarPlayMediaButton.forWheelKey(request.code, request.scan)?.let { original ->
                    CarPlayMediaButton.forKeyCode(event.keyCode, true) == original
                } == true)
        }
    }
}

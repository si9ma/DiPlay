package com.shilapi.xcertplay.orchestration

import java.io.Closeable
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

data class AutoPlaybackOptions(val enabled: Boolean = false, val appUrl: String = "") {
    companion object {
        /** Empty means resume the current player; a custom scheme is not proof of iOS support. */
        fun validatedUrl(input: String): String? {
            val value = input.trim()
            if (value.isEmpty()) return ""
            if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
            val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(value)?.value ?: return null
            if (scheme.dropLast(1).lowercase() in setOf("file", "content", "javascript", "data", "intent")) return null
            if (value == scheme || value == "$scheme//") return value
            return value.takeIf { runCatching { URI(it).isAbsolute }.getOrDefault(false) }
        }
    }
}

/** One bounded attempt per phone session, independent of Activity and audio-stream lifetimes. */
internal class ConnectionAutoPlayback<T : Any>(
    private val options: () -> AutoPlaybackOptions,
    private val state: (T) -> State?,
    private val schedule: (Long, () -> Unit) -> Closeable,
    private val execute: (() -> Unit) -> Unit,
    private val now: () -> Long,
    private val openApp: (T, String) -> Boolean,
    private val play: (T) -> Boolean?,
    private val log: (String) -> Unit = {},
) {
    data class State(val stream: Any?, val ready: Boolean, val playing: Boolean, val inCall: Boolean)

    private class Attempt<T>(val owner: T, val options: AutoPlaybackOptions, val manual: Boolean,
        val deadline: Long, var due: Long, var stream: Any?) {
        var opened = false
        val timer = AtomicReference<Closeable?>()
    }

    private val lastSession = AtomicReference<T?>()
    private val pending = AtomicReference<Attempt<T>?>()

    @Synchronized
    fun onSessionActive(owner: T) {
        if (lastSession.getAndSet(owner) !== owner) {
            cancel()
            begin(owner, manual = false)
        }
    }

    fun tryOnce(owner: T): Boolean = begin(owner, manual = true)

    @Synchronized
    fun cancel() {
        pending.getAndSet(null)?.timer?.getAndSet(null)?.close()
    }

    @Synchronized
    private fun begin(owner: T, manual: Boolean): Boolean {
        val selected = options()
        if (!manual && !selected.enabled) return false
        val url = AutoPlaybackOptions.validatedUrl(selected.appUrl) ?: return false
        val current = state(owner) ?: return false
        if (current.playing || current.inCall || (manual && (!current.ready || current.stream == null))) return false
        val start = now()
        val attempt = Attempt(owner, selected.copy(appUrl = url), manual,
            start + 20_000, start + 3_000, current.stream)
        pending.getAndSet(attempt)?.timer?.getAndSet(null)?.close()
        enqueue(attempt)
        return true
    }

    private fun enqueue(attempt: Attempt<T>) {
        if (pending.get() !== attempt) return
        try {
            val timer = schedule(250) {
                if (pending.get() === attempt) {
                    try {
                        execute {
                            try { step(attempt) } catch (_: Exception) { finish(attempt, "failed") }
                        }
                    } catch (_: Exception) { finish(attempt, "cancelled") }
                }
            }
            attempt.timer.getAndSet(timer)?.close()
            if (pending.get() !== attempt) attempt.timer.getAndSet(null)?.close()
        } catch (_: Exception) { finish(attempt, "cancelled") }
    }

    private fun step(attempt: Attempt<T>) {
        if (pending.get() !== attempt) return
        val current = state(attempt.owner)
        val selected = options()
        if (current == null || (!attempt.manual && !selected.enabled) ||
            AutoPlaybackOptions.validatedUrl(selected.appUrl) != attempt.options.appUrl ||
            current.playing || current.inCall || now() >= attempt.deadline) {
            finish(attempt, "skipped")
            return
        }
        if (attempt.stream != null && attempt.stream !== current.stream) {
            finish(attempt, "stream changed")
            return
        }
        if (current.stream != null) attempt.stream = current.stream
        if (!current.ready || current.stream == null || now() < attempt.due) {
            enqueue(attempt)
            return
        }
        if (pending.get() !== attempt) return
        if (!attempt.opened && attempt.options.appUrl.isNotEmpty()) {
            attempt.opened = true
            if (!openApp(attempt.owner, attempt.options.appUrl)) {
                finish(attempt, "app request failed")
                return
            }
            attempt.due = now() + 1_500
            enqueue(attempt)
        } else if (pending.compareAndSet(attempt, null)) {
            attempt.timer.getAndSet(null)?.close()
            log(when (play(attempt.owner)) {
                true -> "PLAY sent"
                false -> "PLAY failed"
                null -> "PLAY awaiting main audio"
            })
        }
    }

    private fun finish(attempt: Attempt<T>, result: String) {
        if (pending.compareAndSet(attempt, null)) {
            attempt.timer.getAndSet(null)?.close()
            log(result)
        }
    }
}

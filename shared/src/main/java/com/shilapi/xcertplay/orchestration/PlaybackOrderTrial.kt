package com.shilapi.xcertplay.orchestration

import java.util.concurrent.atomic.AtomicReference

/** Explicit one-shot experiment; never used by normal media keys or connection autoplay. */
internal class PlaybackOrderTrial<T : Any>(
    private val state: (T) -> State?,
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val play: (T) -> Boolean,
    private val release: (T) -> Boolean,
    private val log: (String) -> Unit,
) {
    data class State(val screen: Any?, val ready: Boolean, val inCall: Boolean, val playing: Boolean,
        val audioOwner: Boolean?, val detail: String = "")
    private class Attempt<T>(val owner: T, val screen: Any, val playFirst: Boolean, val started: Long) {
        var stage = 0
        var lastState = ""
    }
    private val pending = AtomicReference<Attempt<T>?>()
    fun start(owner: T, playFirst: Boolean): Boolean {
        val current = state(owner)
        if (current == null || current.screen == null || !current.ready || current.inCall || current.playing || current.audioOwner != false) {
            log("rejected=baseline_not_ready state=$current")
            return false
        }
        val attempt = Attempt(owner, current.screen, playFirst, now())
        if (!pending.compareAndSet(null, attempt)) { log("rejected=busy"); return false }
        log("start variant=${if (playFirst) "B_PLAY_THEN_RELEASE" else "A_RELEASE_THEN_PLAY"} waitMs=1000 observationMs=8000 gapBMs=750")
        enqueue(attempt, 0)
        return true
    }
    fun cancel(reason: String) { pending.getAndSet(null)?.let { log("end=cancelled reason=$reason dtMs=${now() - it.started}") } }
    private fun finish(a: Attempt<T>, reason: String) {
        if (pending.compareAndSet(a, null)) log("end=$reason dtMs=${now() - a.started}")
    }
    private fun enqueue(a: Attempt<T>, delay: Long) {
        try { schedule(delay) { step(a) } } catch (_: Exception) { finish(a, "scheduler_unavailable") }
    }
    private fun step(a: Attempt<T>) {
        if (pending.get() !== a) return
        val s = state(a.owner)
        if (s == null || s.screen !== a.screen || !s.ready || s.inCall) { finish(a, "session_or_call_changed"); return }
        val elapsed = now() - a.started
        val description = "owner=${s.audioOwner} playing=${s.playing} ${s.detail}"
        if (description != a.lastState) { a.lastState = description; log("observe dtMs=$elapsed $description") }
        if (s.playing) { finish(a, "playing_observed_not_speaker_verified"); return }
        if (elapsed >= 8_000) { finish(a, "no_playback_observed"); return }
        if (pending.get() !== a) return
        try {
            when (a.stage) {
                0 -> {
                    if (s.audioOwner != false) { finish(a, "baseline_changed_before_start"); return }
                    val ok = if (a.playFirst) play(a.owner) else release(a.owner)
                    log("step=${if (a.playFirst) "PLAY" else "RELEASE"} written=$ok dtMs=${now() - a.started}")
                    if (!ok) { finish(a, "write_failed"); return }
                    a.stage = 1
                }
                1 -> {
                    if (!a.playFirst && s.audioOwner != true && elapsed >= 1_000) { finish(a, "ownership_timeout_no_PLAY"); return }
                    if ((!a.playFirst && s.audioOwner == true) || (a.playFirst && elapsed >= 750)) {
                        if (a.playFirst && s.audioOwner == true) log("step=RELEASE skipped=already_phone_owned")
                        else {
                            val ok = if (a.playFirst) release(a.owner) else play(a.owner)
                            log("step=${if (a.playFirst) "RELEASE" else "PLAY"} written=$ok dtMs=${now() - a.started}")
                            if (!ok) { finish(a, "write_failed"); return }
                        }
                        a.stage = 2
                    }
                }
            }
        } catch (error: Exception) { finish(a, "exception_${error.javaClass.simpleName}"); return }
        enqueue(a, 100)
    }
}

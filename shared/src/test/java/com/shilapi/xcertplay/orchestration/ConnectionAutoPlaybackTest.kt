package com.shilapi.xcertplay.orchestration

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class ConnectionAutoPlaybackTest {
    private class Fixture {
        var time = 0L
        var owner = Any()
        var options = AutoPlaybackOptions(enabled = true)
        var state = ConnectionAutoPlayback.State(Any(), ready = true, playing = false, inCall = false)
        var connected = true
        var holdWorker = false
        var openResult = true
        var playResult = true
        var afterOpen: () -> Unit = {}
        val commands = mutableListOf<String>()
        val worker = mutableListOf<() -> Unit>()
        data class Timer(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val timers = mutableListOf<Timer>()
        val policy = ConnectionAutoPlayback<Any>(
            options = { options },
            state = { if (connected && it === owner) state else null },
            schedule = { delay, action ->
                val timer = Timer(time + delay, action).also(timers::add)
                Closeable { timer.cancelled = true }
            },
            execute = { if (holdWorker) worker.add(it) else it() },
            now = { time },
            openApp = { _, url -> commands.add(url); afterOpen(); openResult },
            play = { commands.add("PLAY"); playResult },
        )
        fun advance(millis: Long) {
            val target = time + millis
            while (true) {
                val next = timers.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                timers.remove(next)
                time = next.due
                next.action()
            }
            time = target
        }
    }

    @Test fun disabledDoesNotArmUntilNextSession() = with(Fixture()) {
        options = AutoPlaybackOptions()
        policy.onSessionActive(owner)
        options = options.copy(enabled = true)
        policy.onSessionActive(owner)
        advance(30_000)
        assertTrue(commands.isEmpty())
        owner = Any()
        policy.onSessionActive(owner)
        advance(3_000)
        assertEquals(listOf("PLAY"), commands)
    }

    @Test fun sendsExplicitPlayOnlyOnceDespiteRepeatedRecordEvents() = with(Fixture()) {
        policy.onSessionActive(owner)
        advance(2_999)
        assertTrue(commands.isEmpty())
        advance(1)
        policy.onSessionActive(owner)
        advance(30_000)
        assertEquals(listOf("PLAY"), commands)
    }

    @Test fun waitsForScreenAndEncryptedEventChannelButTimesOut() {
        with(Fixture()) {
            state = state.copy(stream = null, ready = false)
            policy.onSessionActive(owner)
            advance(4_000)
            assertTrue(commands.isEmpty())
            state = state.copy(stream = Any(), ready = true)
            advance(250)
            assertEquals(listOf("PLAY"), commands)
        }
        with(Fixture()) {
            state = state.copy(ready = false)
            policy.onSessionActive(owner)
            advance(20_000)
            state = state.copy(ready = true)
            advance(5_000)
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun customAppRequestPrecedesPlayAndDoesNotRetryFailure() {
        with(Fixture()) {
            options = options.copy(appUrl = "testmusic://")
            policy.onSessionActive(owner)
            advance(3_000)
            assertEquals(listOf("testmusic://"), commands)
            advance(1_500)
            assertEquals(listOf("testmusic://", "PLAY"), commands)
        }
        with(Fixture()) {
            options = options.copy(appUrl = "testmusic://")
            openResult = false
            policy.onSessionActive(owner)
            advance(30_000)
            assertEquals(listOf("testmusic://"), commands)
        }
        with(Fixture()) {
            playResult = false
            policy.onSessionActive(owner)
            advance(30_000)
            assertEquals(listOf("PLAY"), commands)
        }
    }

    @Test fun callOrPlaybackCancelsRatherThanResumesLater() {
        for (afterUrl in listOf(false, true)) for (call in listOf(false, true)) with(Fixture()) {
            options = options.copy(appUrl = "testmusic://")
            policy.onSessionActive(owner)
            advance(if (afterUrl) 3_000 else 1_000)
            state = state.copy(inCall = call, playing = !call)
            advance(250)
            state = state.copy(inCall = false, playing = false)
            advance(10_000)
            assertEquals(if (afterUrl) listOf("testmusic://") else emptyList<String>(), commands)
        }
    }

    @Test fun disabledChangedUrlDisconnectedOrReplacedStreamCancels() {
        for (change in 0..4) with(Fixture()) {
            policy.onSessionActive(owner)
            advance(1_000)
            when (change) {
                0 -> options = options.copy(enabled = false)
                1 -> options = options.copy(appUrl = "other://")
                2 -> connected = false
                3 -> state = state.copy(stream = Any())
                4 -> state = state.copy(stream = null)
            }
            advance(10_000)
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun queuedWorkerCannotSendAfterCancellationOrSessionReplacement() {
        for (replace in listOf(false, true)) with(Fixture()) {
            policy.onSessionActive(owner)
            holdWorker = true
            advance(3_000)
            if (replace) {
                owner = Any()
                policy.onSessionActive(owner)
            } else policy.cancel()
            worker.toList().forEach { it() }
            worker.clear()
            assertTrue(commands.isEmpty())
            holdWorker = false
            advance(5_000)
            assertEquals(if (replace) listOf("PLAY") else emptyList<String>(), commands)
        }
    }

    @Test fun cancellationDuringAppRequestDoesNotSchedulePlay() = with(Fixture()) {
        options = options.copy(appUrl = "testmusic://")
        afterOpen = { policy.cancel() }
        policy.onSessionActive(owner)
        advance(10_000)
        assertEquals(listOf("testmusic://"), commands)
    }

    @Test fun manualTestDoesNotEnableAutomationAndRequiresSafeReadySession() = with(Fixture()) {
        options = AutoPlaybackOptions()
        policy.onSessionActive(owner)
        assertTrue(policy.tryOnce(owner))
        advance(5_000)
        assertEquals(listOf("PLAY"), commands)
        assertFalse(options.enabled)
        state = state.copy(inCall = true)
        assertFalse(policy.tryOnce(owner))
        state = state.copy(inCall = false, playing = true)
        assertFalse(policy.tryOnce(owner))
        state = state.copy(playing = false, ready = false)
        assertFalse(policy.tryOnce(owner))
    }

    @Test fun rejectedManualTestPreservesAutomaticReadinessWait() = with(Fixture()) {
        state = state.copy(ready = false)
        policy.onSessionActive(owner)
        assertFalse(policy.tryOnce(owner))
        state = state.copy(ready = true)
        advance(5_000)
        assertEquals(listOf("PLAY"), commands)
    }

    @Test fun validatesImportedUrlsWithoutGuessingOrRewritingSchemes() {
        listOf("", "testmusic:", "testmusic://", "testmusic://play?id=1", "https://example.com/play").forEach {
            assertEquals(it, AutoPlaybackOptions.validatedUrl("  $it  "))
        }
        listOf("not a url", "testmusic://a\nb", "testmusic://a b", "testmusic://bad%zz", "file:///private", "javascript:run()", "intent://play", "x:" + "a".repeat(2048)).forEach {
            assertNull(it, AutoPlaybackOptions.validatedUrl(it))
        }
        with(Fixture()) {
            options = options.copy(appUrl = "invalid")
            policy.onSessionActive(owner)
            advance(30_000)
            assertTrue(commands.isEmpty())
        }
    }
}

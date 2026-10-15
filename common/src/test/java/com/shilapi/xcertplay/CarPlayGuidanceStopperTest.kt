package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The stored sequence is JSON, so the test needs Android's org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayGuidanceStopperTest {
    private val taps = listOf(
        CarPlayGuidanceStopper.Tap(0.5, 0.92, 300),
        CarPlayGuidanceStopper.Tap(0.81, 0.47, 1_400),
    )

    /** A chosen spacing replaces the rhythm the finger happened to have during calibration. */
    @Test fun aChosenGapReplacesTheRecordedOne() {
        assertEquals(1_000L, CarPlayGuidanceStopper.gapBefore(taps, 1, 1_000L))
    }

    @Test fun zeroKeepsTheRecordedRhythm() {
        assertEquals(1_400L, CarPlayGuidanceStopper.gapBefore(taps, 1, 0L))
    }

    /**
     * What was recorded before the first tap is the driver reaching for the screen after pressing
     * Start, not anything the replay has to wait out, so neither it nor a chosen spacing applies.
     */
    @Test fun theFirstTapNeverWaitsOutTheRecordedReach() {
        val slowStart = listOf(CarPlayGuidanceStopper.Tap(0.27, 0.93, 4_000), taps[1])
        for (override in listOf(0L, 1_000L, 4_000L)) {
            assertEquals(0L, CarPlayGuidanceStopper.gapBefore(slowStart, 0, override))
        }
    }

    /** The wait after the map comes forward is the driver's choice now, with the old fixed value as default. */
    @Test fun theForegroundWaitIsASettingThatDefaultsToTheOldFixedValue() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(1_200L, AirPlayPersistence.loadCarPlayStopForegroundWaitMillis(app))
        AirPlayPersistence.saveCarPlayStopForegroundWaitMillis(app, 0L)
        assertEquals(0L, AirPlayPersistence.loadCarPlayStopForegroundWaitMillis(app))
        AirPlayPersistence.saveCarPlayStopForegroundWaitMillis(app, 99_000L)
        assertEquals(AirPlayPersistence.CARPLAY_STOP_FOREGROUND_WAIT_CHOICES.last(),
            AirPlayPersistence.loadCarPlayStopForegroundWaitMillis(app))
    }

    /** A stored value from an older build, or a hand-edited one, must not stall a replay. */
    @Test fun aChosenGapIsClampedToTheSameRangeAsARecordedOne() {
        assertEquals(CarPlayGuidanceStopper.MIN_DELAY_MILLIS, CarPlayGuidanceStopper.gapBefore(taps, 1, 1L))
        assertEquals(CarPlayGuidanceStopper.MAX_DELAY_MILLIS, CarPlayGuidanceStopper.gapBefore(taps, 1, 99_000L))
    }

    @Test fun aRecordedSequenceSurvivesStorage() {
        assertEquals(taps, CarPlayGuidanceStopper.decode(CarPlayGuidanceStopper.encode(taps)))
    }

    /**
     * A blind replay cannot be judged by its outcome, so every step has to reach the debug window.
     * Without a session the sequence stops early, which is exactly the case worth recording.
     */
    @Test fun aReplayReportsWhyItStoppedToTheDebugWindow() {
        NavigationDebugLog.start()
        try {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            AirPlayPersistence.saveCarPlayStopTaps(app, CarPlayGuidanceStopper.encode(taps))
            var result: CarPlayGuidanceStopper.Result? = null
            CarPlayGuidanceStopper.replay(app, CarPlayGuidanceStopper.Action.STOP) { result = it }
            assertEquals(CarPlayGuidanceStopper.Result.NO_SESSION, result)
            val lines = NavigationDebugLog.snapshot().map { it.text }
            assertTrue(lines.toString(), lines.any { it.contains("STOP requested") })
            // The recorded points are named, so a wrong calibration is visible without guessing.
            assertTrue(lines.toString(), lines.any { it.contains("50%, 92%") })
            assertTrue(lines.toString(), lines.any { it.contains("no CarPlay session") })
        } finally {
            NavigationDebugLog.stop()
        }
    }

    /** Nothing is collected while the driver has not asked for it, this channel included. */
    @Test fun replayStepsAreNotCollectedWhileTheWindowIsOff() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveCarPlayStopTaps(app, CarPlayGuidanceStopper.encode(taps))
        CarPlayGuidanceStopper.replay(app, CarPlayGuidanceStopper.Action.STOP) {}
        assertTrue(NavigationDebugLog.snapshot().isEmpty())
    }

    @Test fun noSequenceIsStoredUntilItIsRecorded() {
        listOf(null, "", "   ", "not json", "{}", "[]").forEach {
            assertTrue(it.orEmpty(), CarPlayGuidanceStopper.decode(it).isEmpty())
        }
    }

    /** A point outside the video would tap a letterbox bar or be clamped onto an edge. */
    @Test fun aPointOffTheScreenIsDropped() {
        val stored = """[{"x":1.4,"y":0.5,"d":300},{"x":0.5,"y":-0.1,"d":300},{"x":0.5,"y":0.5,"d":300}]"""
        assertEquals(listOf(CarPlayGuidanceStopper.Tap(0.5, 0.5, 300)), CarPlayGuidanceStopper.decode(stored))
    }

    /** One slow or impatient calibration must not leave a replay stalled or racing the animations. */
    @Test fun delaysAreClampedToASaneRange() {
        val stored = """[{"x":0.5,"y":0.5,"d":10},{"x":0.6,"y":0.6,"d":90000}]"""
        assertEquals(listOf(250L, 4_000L), CarPlayGuidanceStopper.decode(stored).map { it.delayBeforeMillis })
    }

    @Test fun storedTapsBeyondTheLimitAreIgnored() {
        val many = (0..9).map { CarPlayGuidanceStopper.Tap(0.1 * it, 0.5, 300) }
        assertEquals(CarPlayGuidanceStopper.MAX_TAPS,
            CarPlayGuidanceStopper.decode(CarPlayGuidanceStopper.encode(many)).size)
    }

    @Test fun theLabelNamesThePointAndThePause() {
        assertEquals("50%, 92% (+300ms)", taps.first().label())
    }
}

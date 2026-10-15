package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationDebugLogTest {
    @Before fun reset() = NavigationDebugLog.stop()

    @After fun tearDown() = NavigationDebugLog.stop()

    /** The buffer is the one place real places are allowed, so it must hold nothing while it is off. */
    @Test fun nothingIsCollectedUntilItIsStarted() {
        assertFalse(NavigationDebugLog.collecting)
        NavigationDebugLog.event("route planned")
        NavigationDebugLog.raw("adb logcat", "mToPoi=POI{name='示例地点甲'}")
        assertTrue(NavigationDebugLog.snapshot().isEmpty())
    }

    @Test fun startingCollectsBothKinds() {
        NavigationDebugLog.start()
        NavigationDebugLog.event("route planned")
        NavigationDebugLog.raw("adb logcat", "mToPoi=POI{name='示例地点甲'}")
        val kinds = NavigationDebugLog.snapshot().map { it.kind }
        assertEquals(listOf(NavigationDebugLog.Kind.EVENT, NavigationDebugLog.Kind.RAW), kinds)
    }

    /** Raw is raw: shaping it here would defeat the point of having a second channel at all. */
    @Test fun rawKeepsTheCarsOwnText() {
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", "mToPoi=POI{name='示例地点甲', point=GeoPoint{lon=110.98, lat=30.12}}")
        val line = NavigationDebugLog.snapshot().single().text
        assertTrue(line, line.contains("示例地点甲"))
        assertTrue(line, line.contains("110.98"))
    }

    /**
     * The report's own section takes this verbatim. The session log cannot: [DiagnosticRedactor]
     * drops any line matching name[=:], which is exactly the route line worth having.
     */
    @Test fun theExportedTextKeepsWhatTheSessionLogWouldHaveDropped() {
        val route = "E/BaseNaviPresenter(1): mToPoi=POI{id='B0TESTPOI1', name='示例地点甲', " +
            "point=GeoPoint{lon=110.98765432, lat=30.12345678}}"
        assertEquals(null, DiagnosticRedactor.redact(route))
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", route)
        val exported = NavigationDebugLog.asText()
        assertTrue(exported, exported.contains("示例地点甲"))
        assertTrue(exported, exported.contains("110.98765432"))
    }

    @Test fun theExportedTextCountsWhatItDropped() {
        NavigationDebugLog.start()
        repeat(NavigationDebugLog.MAX_LINES + 3) { NavigationDebugLog.event("line $it") }
        assertTrue(NavigationDebugLog.asText().startsWith("(3 older lines dropped)"))
    }

    /** Turning collection off drops what was held; the text is not kept past its use. */
    @Test fun stoppingForgetsEverything() {
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", "mToPoi=POI{name='示例地点甲'}")
        NavigationDebugLog.stop()
        assertTrue(NavigationDebugLog.snapshot().isEmpty())
        assertEquals(0, NavigationDebugLog.droppedCount())
        assertFalse(NavigationDebugLog.collecting)
    }

    @Test fun theBufferIsBoundedAndCountsWhatItDropped() {
        NavigationDebugLog.start()
        repeat(NavigationDebugLog.MAX_LINES + 20) { NavigationDebugLog.event("line $it") }
        assertEquals(NavigationDebugLog.MAX_LINES, NavigationDebugLog.snapshot().size)
        assertEquals(20, NavigationDebugLog.droppedCount())
        // The newest survive, since those are the ones being looked at.
        assertTrue(NavigationDebugLog.snapshot().last().text.endsWith("${NavigationDebugLog.MAX_LINES + 19}"))
    }

    /** One read can be very long; the window shows a head and says how much was left. */
    @Test fun aLongReadIsClippedAndSaysSo() {
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", "x".repeat(NavigationDebugLog.MAX_RAW_CHARS + 500))
        val line = NavigationDebugLog.snapshot().single().text
        assertTrue(line, line.contains("500 more characters"))
        assertTrue(line.length.toString(), line.length < NavigationDebugLog.MAX_RAW_CHARS + 200)
    }

    @Test fun anEmptyReadIsStillRecordedSoASilentPollIsVisible() {
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", "")
        assertTrue(NavigationDebugLog.snapshot().single().text.contains("(no output)"))
    }

    @Test fun eachLineIsListenedForAsItArrives() {
        NavigationDebugLog.start()
        val seen = mutableListOf<String>()
        NavigationDebugLog.onLine = { seen.add(it.text) }
        NavigationDebugLog.event("route planned")
        NavigationDebugLog.onLine = null
        assertEquals(listOf("route planned"), seen)
    }

    @Test fun formattingMarksRawApartFromEvents() {
        NavigationDebugLog.start()
        NavigationDebugLog.event("route planned")
        NavigationDebugLog.raw("adb logcat", "toPoi=x")
        val (event, raw) = NavigationDebugLog.snapshot()
        assertTrue(NavigationDebugLog.format(event).contains(" * "))
        assertTrue(NavigationDebugLog.format(raw).contains(" < "))
    }
}

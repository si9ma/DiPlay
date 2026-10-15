package com.shilapi.xcertplay

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The head unit's map log, as the car wrote it, reaches a report only with its switch on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RawCarLogReportTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val place = "示例地点甲"

    @After fun tearDown() {
        NavigationDebugLog.stop()
        AirPlayPersistence.saveReportRawCarLog(app, false)
    }

    private fun report(): String {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        return activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, app) as String
    }

    private fun recordALine() {
        NavigationDebugLog.start()
        NavigationDebugLog.raw("adb logcat", "mToPoi=POI{name='$place', point=GeoPoint{lon=110.98, lat=30.12}}")
    }

    @Test fun itIsOffByDefaultAndTheReportSaysWhatWasLeftOut() {
        recordALine()
        val text = report()
        assertFalse(text, text.contains(place))
        assertFalse(text, text.contains("110.98"))
        assertFalse(text, text.contains("NOT redacted"))
        assertTrue(text, text.contains("left out: 1 line(s) recorded"))
    }

    @Test fun withTheSwitchOnTheLinesAreIncludedVerbatim() {
        AirPlayPersistence.saveReportRawCarLog(app, true)
        recordALine()
        val text = report()
        assertTrue(text, text.contains("NOT redacted"))
        assertTrue(text, text.contains(place))
    }

    @Test fun nothingRecordedMeansNoSectionEitherWay() {
        NavigationDebugLog.stop()
        for (on in listOf(false, true)) {
            AirPlayPersistence.saveReportRawCarLog(app, on)
            assertFalse(report().contains("Head-unit map, as the car wrote it"))
        }
    }
}

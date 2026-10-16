package com.shilapi.xcertplay

import android.content.Context
import android.view.KeyEvent
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyRoutingTest {
    @Before fun setup() = WheelKeyLog.reset()
    @After fun cleanup() = WheelKeyLog.reset()

    @Test fun routesKeepDownAndUpButOmitRepeatsAndAllTextInput() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            WheelKeyLog.input("test", KeyEvent(action, 340)) { "matched=false" }
            WheelKeyLog.input("test", KeyEvent(action, KeyEvent.KEYCODE_A)) { error("text detail evaluated") }
            WheelKeyLog.input("test", KeyEvent(action, KeyEvent.KEYCODE_4)) { error("text detail evaluated") }
        }
        WheelKeyLog.input("test", KeyEvent(0, 0, KeyEvent.ACTION_DOWN, 340, 12)) { error("repeat detail evaluated") }
        val lines = WheelKeyLog.routingReport()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("code=340") && lines[0].contains("action=down"))
        assertTrue(lines[1], lines[1].contains("action=up"))
    }

    @Test fun routeDiagnosticsAreBoundedAndRepeatedMessagesAreCoalesced() {
        repeat(300) { WheelKeyLog.route("state=$it") }
        assertEquals(120, WheelKeyLog.routingReport().size)
        repeat(10) { WheelKeyLog.route("state=299") }
        assertTrue(WheelKeyLog.routingReport().last().contains("x11"))
        WheelKeyLog.reset()
        assertEquals(listOf("Wheel routing: no events recorded"), WheelKeyLog.routingReport())
    }

    @Test fun callSettingOffAndMissingSessionHaveDistinctReasons() {
        val app = RuntimeEnvironment.getApplication()
        val previous = BydOutputSettings.carPlayCallControls(app)
        try {
            BydOutputSettings.setCarPlayCallControls(app, false)
            assertFalse(CarPlayCallKeys.onKey(app, 313, false, null))
            assertTrue(WheelKeyLog.routingReport().last().contains("reason=setting_off"))
            BydOutputSettings.setCarPlayCallControls(app, true)
            assertFalse(CarPlayCallKeys.onKey(app, 313, false, null))
            assertTrue(WheelKeyLog.routingReport().last().contains("reason=no_session"))
        } finally {
            BydOutputSettings.setCarPlayCallControls(app, previous)
        }
    }

    @Test fun exportContainsRoutingStateWithoutInputDeviceName() {
        val app = RuntimeEnvironment.getApplication()
        WheelKeyLog.input("test", KeyEvent(KeyEvent.ACTION_DOWN, 340)) { "matched=false" }
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val report = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, app) as String
        assertTrue(report.contains("Wheel key routing"))
        assertTrue(report.contains("siriEnabled="))
        assertTrue(report.contains("filterCapable="))
        assertTrue(report.contains("Media keys: attached="))
        assertTrue(report.contains("input source=test code=340"))
    }
}

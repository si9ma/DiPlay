package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.hud.BydBatteryDetails
import com.shilapi.xcertplay.hud.BydBatteryField
import com.shilapi.xcertplay.hud.BydVehicleEvent
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleShortcutsAndBatteryCardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun rule(event: BydVehicleEvent? = BydVehicleEvent.RIGHT_MAP_CARD_SHOWN, id: String = "vehicle-rule") = ShortcutRule(
        id = id, name = "Map card", enabled = true, event = ShortcutEvent.VEHICLE, vehicleEvent = event,
        steps = listOf(ShortcutStep(ShortcutAction.HIDE_CLUSTER_MAP), ShortcutStep(ShortcutAction.SHOW_BATTERY_CARD)),
    )

    @Test fun vehicleRulesRoundTripAndKeepTheEncodingVersion() {
        val rules = listOf(rule(), rule(BydVehicleEvent.GEAR_R, "gear-rule"),
            ShortcutRule(id = "key-rule", name = "Key", event = ShortcutEvent.KEY, keyCode = 87, scanCode = 163,
                steps = listOf(ShortcutStep(ShortcutAction.NEXT))))
        val json = ShortcutCodec.encode(rules)!!
        assertTrue(json.contains("\"version\":3"))
        assertTrue(json.contains("\"vehicle\":\"RIGHT_MAP_CARD_SHOWN\""))
        assertEquals(rules, ShortcutCodec.decode(json))
    }

    @Test fun vehicleEventsAreValidatedAndConflictPerEvent() {
        assertTrue(ShortcutCodec.valid(rule()))
        assertFalse(ShortcutCodec.valid(rule(event = null)))
        assertFalse(ShortcutCodec.valid(rule().copy(clickCount = 2)))
        assertFalse(ShortcutCodec.valid(rule().copy(keyCode = 87)))
        assertFalse(ShortcutCodec.valid(rule().copy(steps = listOf(ShortcutStep(ShortcutAction.ORIGINAL_CLICK)))))
        assertFalse(ShortcutCodec.valid(rule().copy(event = ShortcutEvent.KEY)))
        assertFalse(ShortcutCodec.valid(rule().copy(event = ShortcutEvent.CONNECTED)))
        assertTrue(ShortcutCodec.conflicts(listOf(rule(), rule(id = "other"))))
        assertFalse(ShortcutCodec.conflicts(listOf(rule(), rule(BydVehicleEvent.RIGHT_CARD_SHOWN, "other"))))
        assertTrue(ShortcutCodec.executable(rule()))
    }

    @Test fun anUnknownVehicleEventDropsOnlyThatRule() {
        val json = ShortcutCodec.encode(listOf(rule(), rule(BydVehicleEvent.GEAR_P, "keep")))!!
            .replace("RIGHT_MAP_CARD_SHOWN", "FUTURE_EVENT")
        assertEquals(listOf("keep"), ShortcutCodec.decode(json).map { it.id })
    }

    @Test fun batteryCardSettingsRoundTripAndClamp() {
        BatteryCardSettings.prefs(app).edit().clear().commit()
        assertEquals(BatteryCardSettings.DEFAULT_FIELDS, BatteryCardSettings.load(app).fields)
        val config = BatteryCardConfig(visible = true, target = BatteryCardTarget.CLUSTER, style = BatteryCardStyle.RICH,
            fields = listOf(BydBatteryField.POWER, BydBatteryField.SOC), scale = 140, opacity = 60, clusterX = 80, clusterY = 10)
        BatteryCardSettings.save(app, config)
        // Fields keep their catalogue order, whatever order they were picked in.
        assertEquals(config.copy(fields = listOf(BydBatteryField.SOC, BydBatteryField.POWER)), BatteryCardSettings.load(app))
        BatteryCardSettings.prefs(app).edit().putInt("scale", 999).putString("fields", "SOC,NOT_A_FIELD").putString("style", "X").commit()
        val loaded = BatteryCardSettings.load(app)
        assertEquals(160, loaded.scale)
        assertEquals(listOf(BydBatteryField.SOC), loaded.fields)
        assertEquals(BatteryCardStyle.STANDARD, loaded.style)
        BatteryCardSettings.prefs(app).edit().clear().commit()
    }

    @Test fun valuesAreFormattedForTheCard() {
        assertEquals("⚡45.0 kW", BatteryCardFormat.value(BydBatteryField.POWER, -45.0))
        assertEquals("12.3 kW", BatteryCardFormat.value(BydBatteryField.POWER, 12.34))
        assertEquals("9.9 MΩ", BatteryCardFormat.value(BydBatteryField.INSULATION, 9935.0))
        assertEquals("1:05", BatteryCardFormat.value(BydBatteryField.CHARGE_TIME_LEFT, 65.0))
        assertEquals("32°C", BatteryCardFormat.value(BydBatteryField.TEMP_MAX, 32.0))
        assertEquals("3.332 V", BatteryCardFormat.value(BydBatteryField.CELL_VOLTAGE_MAX, 3.332))
        assertEquals(BatteryCardFormat.MISSING, BatteryCardFormat.value(BydBatteryField.SOH, null))
    }

    private fun card(config: BatteryCardConfig) = BatteryCardView(app, BatteryCardText.of(app)).also { it.config = config }

    private fun draw(view: BatteryCardView): Pair<Int, Int> {
        val (width, height) = view.contentSize()
        view.measure(0, 0)
        view.layout(0, 0, width, height)
        view.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
        return view.measuredWidth to view.measuredHeight
    }

    @Test fun everyStyleKeepsItsSizeWhileTheReadingChanges() {
        val reading = BydVehicleMonitor.BatteryUpdate(BydBatteryDetails(mapOf(
            BydBatteryField.SOC to 96.0, BydBatteryField.RANGE to 654.0, BydBatteryField.POWER to -120.5,
            BydBatteryField.SOH to 98.0, BydBatteryField.TEMP_MAX to 32.0, BydBatteryField.CHARGE_LIMIT to 90.0,
        ), charging = true), BydVehicleMonitor.Link.READY)
        val updates = listOf(null, BydVehicleMonitor.BatteryUpdate(null, BydVehicleMonitor.Link.NO_ADB),
            BydVehicleMonitor.BatteryUpdate(null, BydVehicleMonitor.Link.UNSUPPORTED), reading)
        for (style in BatteryCardStyle.entries) {
            val view = card(BatteryCardConfig(style = style, fields = BydBatteryField.entries))
            val sizes = updates.map { update -> view.update = update; draw(view) }.toSet()
            assertEquals(style.name, 1, sizes.size)
            val (width, height) = sizes.single()
            assertTrue(style.name, width > 0 && height > 0)
        }
    }

    @Test fun sizeFollowsFieldsAndScale() {
        val few = card(BatteryCardConfig(style = BatteryCardStyle.STANDARD, fields = listOf(BydBatteryField.SOC, BydBatteryField.RANGE)))
        val many = card(BatteryCardConfig(style = BatteryCardStyle.STANDARD, fields = BydBatteryField.entries))
        assertTrue(many.contentSize().second > few.contentSize().second)
        assertEquals(few.contentSize().first, many.contentSize().first)
        val normal = card(BatteryCardConfig(style = BatteryCardStyle.MINI))
        val large = card(BatteryCardConfig(style = BatteryCardStyle.MINI, scale = 150))
        assertTrue(large.contentSize().first > normal.contentSize().first)
        assertTrue(large.contentSize().second > normal.contentSize().second)
    }
}

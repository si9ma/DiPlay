package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.hud.BydBatteryDetails
import com.shilapi.xcertplay.hud.BydBatteryField
import com.shilapi.xcertplay.hud.BydVehicleEvent
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import com.shilapi.xcertplay.hud.BydVehicleSignalGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private fun rule(event: BydVehicleEvent? = BydVehicleEvent.RIGHT_MAP_CARD_SHOWN, id: String = "vehicle-rule",
        conditions: List<ShortcutCondition> = emptyList()) = ShortcutRule(
        id = id, name = "Map card", enabled = true, event = ShortcutEvent.VEHICLE, vehicleEvent = event,
        steps = listOf(ShortcutStep(ShortcutAction.HIDE_CLUSTER_MAP), ShortcutStep(ShortcutAction.SHOW_CLUSTER_BATTERY_CARD)),
        conditions = conditions,
    )

    @Test fun rulesRoundTripWithConditionsInVersion4() {
        val gear = ShortcutCondition(ShortcutConditionKey.GEAR, setOf("D", "R"))
        val right = ShortcutCondition(ShortcutConditionKey.RIGHT_CARD, setOf("MAP_TURN", "MAP_MINI"))
        val rules = listOf(rule(conditions = listOf(gear, right)), rule(BydVehicleEvent.CHARGING_ENDED, "charge-rule"),
            ShortcutRule(id = "key-rule", name = "Key", event = ShortcutEvent.KEY, keyCode = 87, scanCode = 163,
                steps = listOf(ShortcutStep(ShortcutAction.NEXT))))
        val json = ShortcutCodec.encode(rules)!!
        assertTrue(json.contains("\"version\":4"))
        assertTrue(json.contains("\"vehicle\":\"RIGHT_MAP_CARD_SHOWN\""))
        // Values keep their catalogue order.
        assertTrue(json.contains("\"values\":[\"R\",\"D\"]"))
        assertEquals(rules, ShortcutCodec.decode(json))
    }

    @Test fun manyRulesAndRulesSharingATriggerAreKept() {
        val rules = (1..40).map { rule(id = "rule-$it") }
        val json = ShortcutCodec.encode(rules)!!
        assertEquals(40, ShortcutCodec.decode(json).size)
    }

    @Test fun vehicleRulesAndConditionsAreValidated() {
        assertTrue(ShortcutCodec.valid(rule()))
        assertFalse(ShortcutCodec.valid(rule(event = null)))
        assertFalse(ShortcutCodec.valid(rule().copy(clickCount = 2)))
        assertFalse(ShortcutCodec.valid(rule().copy(keyCode = 87)))
        assertFalse(ShortcutCodec.valid(rule().copy(steps = listOf(ShortcutStep(ShortcutAction.ORIGINAL_CLICK)))))
        assertFalse(ShortcutCodec.valid(rule().copy(event = ShortcutEvent.KEY)))
        assertFalse(ShortcutCodec.valid(rule().copy(event = ShortcutEvent.CONNECTED)))
        assertFalse(ShortcutCodec.valid(rule(conditions = listOf(ShortcutCondition(ShortcutConditionKey.GEAR, emptySet())))))
        assertFalse(ShortcutCodec.valid(rule(conditions = listOf(ShortcutCondition(ShortcutConditionKey.GEAR, setOf("X"))))))
        assertFalse(ShortcutCodec.valid(rule(conditions = List(2) { ShortcutCondition(ShortcutConditionKey.GEAR, setOf("P")) })))
        assertTrue(ShortcutCodec.executable(rule()))
    }

    @Test fun anUnknownVehicleEventOrConditionDropsOnlyThatRule() {
        val json = ShortcutCodec.encode(listOf(rule(), rule(BydVehicleEvent.GEAR_P, "keep"),
            rule(BydVehicleEvent.GEAR_R, "future-condition", listOf(ShortcutCondition(ShortcutConditionKey.NIGHT, setOf("DAY"))))))!!
            .replace("RIGHT_MAP_CARD_SHOWN", "FUTURE_EVENT").replace("\"NIGHT\"", "\"FUTURE_KEY\"")
        assertEquals(listOf("keep"), ShortcutCodec.decode(json).map { it.id })
    }

    @Test fun earlierRulesKeepTheirMeaning() {
        val step = { action: String -> JSONObject().put("action", action).put("waitMs", 0).put("url", "")
            .put("keyCode", -1).put("scanCode", -1) }
        val legacy = JSONObject().put("version", 3).put("rules", JSONArray().put(JSONObject().put("id", "old").put("name", "Old")
            .put("enabled", true).put("event", "VEHICLE").put("keyCode", 0).put("scanCode", 0).put("clickCount", 1)
            .put("vehicle", "OEM_NAVIGATION_STARTED")
            .put("steps", JSONArray().put(step("SHOW_BATTERY_CARD")).put(step("HIDE_BATTERY_CARD"))))).toString()
        val center = ShortcutCodec.decode(legacy).single()
        assertEquals(BydVehicleEvent.CARPLAY_NAVIGATION_STARTED, center.vehicleEvent)
        assertEquals(listOf(ShortcutAction.SHOW_CENTER_BATTERY_CARD, ShortcutAction.HIDE_CENTER_BATTERY_CARD), center.steps.map { it.action })
        assertEquals(listOf(ShortcutAction.SHOW_CLUSTER_BATTERY_CARD, ShortcutAction.HIDE_CLUSTER_BATTERY_CARD),
            ShortcutCodec.decode(legacy, BatteryCardTarget.CLUSTER).single().steps.map { it.action })
    }

    @Test fun conditionsHoldOnlyWhenEveryOneHolds() {
        val gear = ShortcutCondition(ShortcutConditionKey.GEAR, setOf("D"))
        val map = ShortcutCondition(ShortcutConditionKey.RIGHT_CARD, setOf("MAP_TURN", "MAP_MINI"))
        fun read(vararg values: Pair<ShortcutConditionKey, String?>) = values.toMap().let { known -> { key: ShortcutConditionKey -> known[key] } }
        assertEquals(true, ShortcutConditions.matches(emptyList(), read()))
        assertEquals(true, ShortcutConditions.matches(listOf(gear, map),
            read(ShortcutConditionKey.GEAR to "D", ShortcutConditionKey.RIGHT_CARD to "MAP_MINI")))
        assertEquals(false, ShortcutConditions.matches(listOf(gear, map),
            read(ShortcutConditionKey.GEAR to "R", ShortcutConditionKey.RIGHT_CARD to null)))
        assertNull(ShortcutConditions.matches(listOf(gear, map), read(ShortcutConditionKey.GEAR to "D")))
        assertEquals(setOf(BydVehicleSignalGroup.GEAR, BydVehicleSignalGroup.RIGHT_CARD),
            ShortcutConditions.groups(listOf(rule(conditions = listOf(gear, map,
                ShortcutCondition(ShortcutConditionKey.NIGHT, setOf("DAY")))))))
    }

    @Test fun theSingleBatteryCardBecomesTheCardOfItsDisplay() {
        val legacy = app.getSharedPreferences("diplay_battery_card", android.content.Context.MODE_PRIVATE)
        BatteryCardTarget.entries.forEach { app.getSharedPreferences(BatteryCardSettings.prefsName(it), 0).edit().clear().commit() }
        legacy.edit().clear().putString("target", "CLUSTER").putBoolean("visible", true).putString("style", "RICH")
            .putInt("scale", 140).commit()
        val cluster = BatteryCardSettings.load(app, BatteryCardTarget.CLUSTER)
        assertTrue(cluster.visible)
        assertEquals(BatteryCardStyle.RICH, cluster.style)
        assertEquals(140, cluster.scale)
        assertEquals(BatteryCardConfig(), BatteryCardSettings.load(app, BatteryCardTarget.CENTER))
        assertEquals(BatteryCardTarget.CLUSTER, BatteryCardSettings.legacyTarget(app))
        legacy.edit().clear().commit()
        BatteryCardTarget.entries.forEach { app.getSharedPreferences(BatteryCardSettings.prefsName(it), 0).edit().clear().commit() }
    }

    @Test fun batteryCardSettingsRoundTripAndClampPerCard() {
        BatteryCardTarget.entries.forEach { BatteryCardSettings.prefs(app, it).edit().clear().commit() }
        assertEquals(BatteryCardSettings.DEFAULT_FIELDS, BatteryCardSettings.load(app, BatteryCardTarget.CENTER).fields)
        val config = BatteryCardConfig(visible = true, style = BatteryCardStyle.RICH,
            fields = listOf(BydBatteryField.POWER, BydBatteryField.SOC), scale = 140, opacity = 60, clusterX = 80, clusterY = 10,
            theme = BatteryCardTheme.NIGHT)
        BatteryCardSettings.save(app, BatteryCardTarget.CLUSTER, config)
        // Fields keep their catalogue order, whatever order they were picked in.
        assertEquals(config.copy(fields = listOf(BydBatteryField.SOC, BydBatteryField.POWER)),
            BatteryCardSettings.load(app, BatteryCardTarget.CLUSTER))
        // The other card is untouched.
        assertEquals(BatteryCardConfig(), BatteryCardSettings.load(app, BatteryCardTarget.CENTER))
        BatteryCardSettings.prefs(app, BatteryCardTarget.CLUSTER).edit().putInt("scale", 999)
            .putString("fields", "SOC,NOT_A_FIELD").putString("style", "X").commit()
        val loaded = BatteryCardSettings.load(app, BatteryCardTarget.CLUSTER)
        assertEquals(160, loaded.scale)
        assertEquals(listOf(BydBatteryField.SOC), loaded.fields)
        assertEquals(BatteryCardStyle.STANDARD, loaded.style)
        BatteryCardTarget.entries.forEach { BatteryCardSettings.prefs(app, it).edit().clear().commit() }
    }

    @Test fun valuesAreFormattedForTheCard() {
        assertEquals("⚡45.0 kW", BatteryCardFormat.value(BydBatteryField.POWER, -45.0))
        assertEquals("12.3 kW", BatteryCardFormat.value(BydBatteryField.POWER, 12.34))
        assertEquals("9.9 MΩ", BatteryCardFormat.value(BydBatteryField.INSULATION, 9935.0))
        assertEquals("1:05", BatteryCardFormat.value(BydBatteryField.CHARGE_TIME_LEFT, 65.0))
        assertEquals("32°C", BatteryCardFormat.value(BydBatteryField.TEMP_MAX, 32.0))
        assertEquals("3.332 V", BatteryCardFormat.value(BydBatteryField.CELL_VOLTAGE_MAX, 3.332))
        assertEquals("12.5 kWh", BatteryCardFormat.value(BydBatteryField.CHARGED_ENERGY, 12.5))
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

    @Test fun everyStyleKeepsItsSizeWhileTheReadingAndThemeChange() {
        val reading = BydVehicleMonitor.BatteryUpdate(BydBatteryDetails(mapOf(
            BydBatteryField.SOC to 96.0, BydBatteryField.RANGE to 654.0, BydBatteryField.POWER to -120.5,
            BydBatteryField.SOH to 98.0, BydBatteryField.TEMP_MAX to 32.0, BydBatteryField.CHARGE_LIMIT to 90.0,
        ), charging = true), BydVehicleMonitor.Link.READY)
        val updates = listOf(null, BydVehicleMonitor.BatteryUpdate(null, BydVehicleMonitor.Link.NO_ADB),
            BydVehicleMonitor.BatteryUpdate(null, BydVehicleMonitor.Link.UNSUPPORTED), reading)
        for (style in BatteryCardStyle.entries) {
            val view = card(BatteryCardConfig(style = style, fields = BydBatteryField.entries))
            val sizes = updates.flatMap { update ->
                view.update = update
                listOf(true, false).map { night -> view.night = night; draw(view) }
            }.toSet()
            assertEquals(style.name, 1, sizes.size)
            val (width, height) = sizes.single()
            assertTrue(style.name, width > 0 && height > 0)
        }
    }

    @Test fun aMiniCardPrintsTheChargeOnlyWhenChosenAndCanBeFullyTransparent() {
        val fields = listOf(BydBatteryField.RANGE, BydBatteryField.POWER)
        val without = card(BatteryCardConfig(style = BatteryCardStyle.MINI, fields = fields))
        val with = card(BatteryCardConfig(style = BatteryCardStyle.MINI, fields = listOf(BydBatteryField.SOC) + fields))
        assertTrue(with.contentSize().first > without.contentSize().first)
        // 0 % opacity draws no background and no border: corners outside the text stay empty.
        val clear = card(BatteryCardConfig(style = BatteryCardStyle.STANDARD, opacity = 0, fields = fields))
        val (width, height) = clear.contentSize()
        clear.measure(0, 0); clear.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        clear.draw(Canvas(bitmap))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(width / 2, height - 2)))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(width - 2, height / 2)))
        BatteryCardSettings.prefs(app, BatteryCardTarget.CENTER).edit().putInt("opacity", 0).commit()
        assertEquals(0, BatteryCardSettings.load(app, BatteryCardTarget.CENTER).opacity)
        BatteryCardSettings.prefs(app, BatteryCardTarget.CENTER).edit().clear().commit()
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

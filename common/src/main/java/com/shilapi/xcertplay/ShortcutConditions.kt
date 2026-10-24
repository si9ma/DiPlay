package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import com.shilapi.xcertplay.hud.BydVehicleSignalGroup
import com.shilapi.xcertplay.orchestration.CarPlayController

private val SHOWN_HIDDEN = listOf("SHOWN", "HIDDEN")
private val YES_NO = listOf("YES", "NO")

/**
 * What a shortcut condition looks at, and the values it can take. Vehicle keys name the
 * [BydVehicleSignalGroup] whose reads they need; the others are DiPlay's own state. New keys go last.
 */
internal enum class ShortcutConditionKey(val values: List<String>, val group: BydVehicleSignalGroup? = null) {
    GEAR(listOf("P", "R", "N", "D"), BydVehicleSignalGroup.GEAR),
    CHARGING(listOf("CHARGING", "PAUSED", "IDLE"), BydVehicleSignalGroup.CHARGING),
    LEFT_CARD(SHOWN_HIDDEN, BydVehicleSignalGroup.LEFT_CARD),
    RIGHT_CARD(listOf("HIDDEN", "CARD", "MAP_TURN", "MAP_MINI"), BydVehicleSignalGroup.RIGHT_CARD),
    CARPLAY_NAVIGATING(YES_NO),
    CLUSTER_MUSIC_WINDOW(SHOWN_HIDDEN),
    CLUSTER_MAP_WINDOW(SHOWN_HIDDEN),
    CENTER_BATTERY_CARD(SHOWN_HIDDEN),
    CLUSTER_BATTERY_CARD(SHOWN_HIDDEN),
    NIGHT(listOf("DAY", "NIGHT")),
    MEDIA_PLAYING(YES_NO),
}

/** Holds when the current value of [key] is one of [values]. */
internal data class ShortcutCondition(val key: ShortcutConditionKey, val values: Set<String>) {
    val valid: Boolean get() = values.isNotEmpty() && key.values.containsAll(values)
}

/**
 * Rule-level conditions: a rule runs only when all of its conditions hold. Values come from what
 * DiPlay already follows (the vehicle monitor's latest reading, its own windows), never from a
 * fresh autoservice call, so checking them costs nothing.
 */
internal object ShortcutConditions {
    const val MAX_PER_RULE = 16
    /** How long a trigger waits for a vehicle value that has not been read yet, as right after connecting. */
    const val UNKNOWN_WAIT_MILLIS = 1_500L

    /** True when all hold, false when one does not, null when none failed but one could not be read. */
    fun matches(conditions: List<ShortcutCondition>, current: (ShortcutConditionKey) -> String?): Boolean? {
        var unknown = false
        for (condition in conditions) {
            val value = current(condition.key)
            if (value == null) unknown = true else if (value !in condition.values) return false
        }
        return if (unknown) null else true
    }

    /** The vehicle reads [rules]' conditions need. */
    fun groups(rules: List<ShortcutRule>): Set<BydVehicleSignalGroup> =
        rules.flatMap { it.conditions }.mapNotNullTo(HashSet()) { it.key.group }

    /** Main thread. Each key is looked up only when a condition asks for it. */
    fun reader(context: Context, controller: CarPlayController?): (ShortcutConditionKey) -> String? {
        val vehicle by lazy { BydVehicleMonitor.state() }
        fun flag(value: Boolean?, yes: String, no: String) = value?.let { if (it) yes else no }
        return { key ->
            when (key) {
                ShortcutConditionKey.GEAR -> vehicle.gear?.let { "PRND"[it - 1].toString() }
                ShortcutConditionKey.CHARGING -> vehicle.charging?.name
                ShortcutConditionKey.LEFT_CARD -> flag(vehicle.leftShown, "SHOWN", "HIDDEN")
                ShortcutConditionKey.RIGHT_CARD -> vehicle.right?.name
                ShortcutConditionKey.CARPLAY_NAVIGATING -> flag(BydNavigationOutputs.carPlayGuidanceActive(), "YES", "NO")
                ShortcutConditionKey.CLUSTER_MUSIC_WINDOW -> flag(controller != null &&
                    ClusterWindowVisibility.shown(context, controller, ClusterWindowPanel.MUSIC), "SHOWN", "HIDDEN")
                ShortcutConditionKey.CLUSTER_MAP_WINDOW -> flag(controller != null &&
                    ClusterWindowVisibility.shown(context, controller, ClusterWindowPanel.MAP), "SHOWN", "HIDDEN")
                ShortcutConditionKey.CENTER_BATTERY_CARD -> flag(BatteryCard.shown(BatteryCardTarget.CENTER), "SHOWN", "HIDDEN")
                ShortcutConditionKey.CLUSTER_BATTERY_CARD -> flag(BatteryCard.shown(BatteryCardTarget.CLUSTER), "SHOWN", "HIDDEN")
                ShortcutConditionKey.NIGHT -> HeadUnitNightMode.read(context)?.let { if (it) "NIGHT" else "DAY" }
                ShortcutConditionKey.MEDIA_PLAYING -> flag(controller?.nowPlayingSnapshot()?.playing == true, "YES", "NO")
            }
        }
    }
}

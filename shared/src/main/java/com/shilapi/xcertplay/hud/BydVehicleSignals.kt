package com.shilapi.xcertplay.hud

/** One read through `service call autoservice <transaction> i32 <device> i32 <fid>`; 5 = getInt, 7 = getFloat. */
internal data class BydSignal(val device: Int, val fid: Int, val transaction: Int = GET_INT) {
    companion object {
        const val GET_INT = 5
        const val GET_FLOAT = 7
    }
}

/** Many getter calls in one adb shell round trip. Only getter transactions are ever issued. */
internal object BydSignalBatch {
    private val LINE = Regex("@(\\d+):(-?\\d+):(\\d+)\\s*(.*)")

    fun command(signals: Collection<BydSignal>): String {
        require(signals.all { it.transaction == BydSignal.GET_INT || it.transaction == BydSignal.GET_FLOAT })
        // Quoted, so the parcel's printable bytes are never glob-expanded by the shell.
        return signals.joinToString("; ") {
            "echo \"@${it.device}:${it.fid}:${it.transaction} \$(service call autoservice ${it.transaction} i32 ${it.device} i32 ${it.fid} 2>&1)\""
        }
    }

    /** The raw 32 bits per signal; a missing reply, a Binder exception or a BYD error code reads as null. */
    fun parse(output: String?, signals: Collection<BydSignal>): Map<BydSignal, Int?> {
        val values = HashMap<BydSignal, Int?>()
        output?.lineSequence()?.forEach { line ->
            val match = LINE.find(line) ?: return@forEach
            val (device, fid, transaction, reply) = match.destructured
            val signal = BydSignal(device.toInt(), fid.toInt(), transaction.toInt())
            values[signal] = BydParcel.value(reply)?.takeUnless { it.isErrorCode() }
        }
        return signals.associateWith { values[it] }
    }

    /** autoservice answers an absent or unavailable property with -10000..-10099 (seen: -10011, -10013). */
    fun Int.isErrorCode() = this in -10099..-10000
}

/**
 * Cluster, gear, navigation and charging state. Only getters; the meanings were read on a
 * Fangchengbao Tai 7 EV (Di150VCP, Android 14) and match BYDProjectionService's own constants.
 */
internal object BydVehicleSignals {
    const val INSTRUMENT = 1007
    const val GEARBOX = 1011
    const val CHARGING = 1009

    /** INSTRUMENT_LEFT_SIDE_COVER_PANEL: 1 shown, 2 hidden; follows the cluster's own cards too. */
    val LEFT_CARD = BydSignal(INSTRUMENT, 0x40C0B028)
    /** INSTRUMENT_RIGHT_SIDE_COVER_PANEL: 1 shown, 2 hidden. */
    val RIGHT_CARD = BydSignal(INSTRUMENT, 0x40C0B02A)
    /** INSTRUMENT_MAP_NAVIGATION_PROMPTS: 1 turn card, 2 hidden, 3 mini map. */
    val MAP_PROMPTS = BydSignal(INSTRUMENT, 0x40C0B01C)
    /** INSTRUMENT_RIGHT_PROJECTION_SET: the Android card on the right, 2 turn card, 3 mini map. */
    val RIGHT_PROJECTION = BydSignal(INSTRUMENT, 0x40C0C016)
    /** INSTRUMENT_CLUSTER_SCREEN_MIRRORING_MAP_STATUS: 3 while the stock map card is up. */
    val MAP_MIRRORING = BydSignal(INSTRUMENT, 0x28C02033)
    /** INSTRUMENT_SEND_NAVI_STATUS_SET: 1 open without route, 2 route set, 3 opening, 4 closed. */
    val NAVI_STATUS = BydSignal(INSTRUMENT, 0x43E0003A)
    /** GEARBOX_AUTO_MODE_TYPE: 1 P, 2 R, 3 N, 4 D. */
    val GEAR = BydSignal(GEARBOX, 555745336)

    fun charging(protocol: BydBatteryProtocol) = BydSignal(CHARGING, protocol.chargingStateId)

    fun events(protocol: BydBatteryProtocol?): List<BydSignal> =
        listOfNotNull(LEFT_CARD, RIGHT_CARD, MAP_PROMPTS, RIGHT_PROJECTION, MAP_MIRRORING, NAVI_STATUS, GEAR,
            protocol?.let(::charging))

    fun state(values: Map<BydSignal, Int?>, protocol: BydBatteryProtocol?): BydVehicleState {
        fun card(value: Int?) = when (value) { 1 -> true; 2 -> false; else -> null }
        val prompts = values[MAP_PROMPTS]
        val projection = values[RIGHT_PROJECTION]
        val map = prompts == 1 || prompts == 3 || projection == 2 || projection == 3 || values[MAP_MIRRORING] == 3
        val right = card(values[RIGHT_CARD])?.let { shown ->
            if (!shown) BydSideCard.HIDDEN else if (map) BydSideCard.MAP else BydSideCard.SHOWN
        }
        val navi = values[NAVI_STATUS]?.takeIf { it in 0..4 }?.let { it == 2 || it == 3 }
        val charging = protocol?.let { values[charging(it)] }?.let { it == 1 }
        return BydVehicleState(
            left = card(values[LEFT_CARD])?.let { if (it) BydSideCard.SHOWN else BydSideCard.HIDDEN },
            right = right,
            navigating = navi,
            gear = values[GEAR]?.takeIf { it in 1..4 },
            charging = charging,
        )
    }
}

internal enum class BydSideCard { HIDDEN, SHOWN, MAP }

/** A null field could not be read. */
internal data class BydVehicleState(
    val left: BydSideCard?,
    val right: BydSideCard?,
    val navigating: Boolean?,
    val gear: Int?,
    val charging: Boolean?,
)

/** Cluster, gear, navigation and charging changes that a shortcut can follow. */
enum class BydVehicleEvent {
    LEFT_CARD_SHOWN, LEFT_CARD_HIDDEN,
    RIGHT_CARD_SHOWN, RIGHT_MAP_CARD_SHOWN, RIGHT_CARD_HIDDEN,
    OEM_NAVIGATION_STARTED,
    GEAR_P, GEAR_R, GEAR_D,
    CHARGING_STARTED,
}

/**
 * Turns samples into events. Most changes count at the first sample that shows them. Only a right
 * card that shows without a map signal waits [rightSettle] samples, because the cluster can raise
 * the map card's signals up to half a second after its cover; a map signal ends that wait at once.
 * The first value after a start or a lost link only sets the baseline, so connecting in D never
 * reports "D engaged".
 */
internal class BydVehicleEventDetector(private val rightSettle: Int = 3) {
    private class Tracker<T : Any>(private val needed: (T) -> Int = { 1 }) {
        var committed: T? = null
        private var candidate: T? = null
        private var seen = 0

        /** (previous, new) once a change is confirmed; previous is null for the first value. */
        fun accept(value: T?): Pair<T?, T>? {
            if (value == null || value == committed) {
                candidate = null; seen = 0
                return null
            }
            if (value == candidate) seen++ else { candidate = value; seen = 1 }
            // The baseline needs no confirmation: it never becomes an event.
            if (committed != null && seen < needed(value)) return null
            val previous = committed
            committed = value; candidate = null; seen = 0
            return previous to value
        }
    }

    private fun rightTracker() = Tracker<BydSideCard> { if (it == BydSideCard.SHOWN) rightSettle else 1 }
    private var left = Tracker<BydSideCard>()
    private var right = rightTracker()
    private var navigating = Tracker<Boolean>()
    private var gear = Tracker<Int>()
    private var charging = Tracker<Boolean>()

    fun reset() {
        left = Tracker(); right = rightTracker(); navigating = Tracker(); gear = Tracker(); charging = Tracker()
    }

    /** [carPlayGuidance]: DiPlay itself feeds the stock adapter, so the navigation status is not the stock map's. */
    fun accept(state: BydVehicleState, carPlayGuidance: Boolean): List<BydVehicleEvent> = buildList {
        left.accept(state.left)?.let { (previous, now) ->
            if (previous != null) add(if (now == BydSideCard.HIDDEN) BydVehicleEvent.LEFT_CARD_HIDDEN else BydVehicleEvent.LEFT_CARD_SHOWN)
        }
        right.accept(state.right)?.let { (previous, now) ->
            if (previous != null) add(when (now) {
                BydSideCard.HIDDEN -> BydVehicleEvent.RIGHT_CARD_HIDDEN
                BydSideCard.MAP -> BydVehicleEvent.RIGHT_MAP_CARD_SHOWN
                BydSideCard.SHOWN -> BydVehicleEvent.RIGHT_CARD_SHOWN
            })
        }
        navigating.accept(state.navigating)?.let { (previous, now) ->
            if (previous == false && now && !carPlayGuidance) add(BydVehicleEvent.OEM_NAVIGATION_STARTED)
        }
        gear.accept(state.gear)?.let { (previous, now) ->
            if (previous != null) when (now) {
                1 -> add(BydVehicleEvent.GEAR_P)
                2 -> add(BydVehicleEvent.GEAR_R)
                4 -> add(BydVehicleEvent.GEAR_D)
            }
        }
        charging.accept(state.charging)?.let { (previous, now) ->
            if (previous == false && now) add(BydVehicleEvent.CHARGING_STARTED)
        }
    }
}

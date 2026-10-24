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
    /** INSTRUMENT_MAP_NAVIGATION_PROMPTS, sent by the cluster: 1 turn card, 2 hidden, 3 mini map. */
    val MAP_PROMPTS = BydSignal(INSTRUMENT, 0x40C0B01C)
    /** INSTRUMENT_RIGHT_PROJECTION_SET, written back by the projection service: 2 turn card, 3 mini map, 0 none. */
    val RIGHT_PROJECTION = BydSignal(INSTRUMENT, 0x40C0C016)
    /**
     * INSTRUMENT_CLUSTER_SCREEN_MIRRORING_MAP_STATUS. Not used for the map card: on the Tai 7 it stays 3
     * while a door card covers the map, so it says that the stock map is projected, not that it is seen.
     */
    val MAP_MIRRORING = BydSignal(INSTRUMENT, 0x28C02033)
    /** INSTRUMENT_SEND_NAVI_STATUS_SET: 1 open without route, 2 route set, 3 opening, 4 closed. */
    val NAVI_STATUS = BydSignal(INSTRUMENT, 0x43E0003A)
    /** GEARBOX_AUTO_MODE_TYPE: 1 P, 2 R, 3 N, 4 D. */
    val GEAR = BydSignal(GEARBOX, 555745336)

    fun charging(protocol: BydBatteryProtocol) = BydSignal(CHARGING, protocol.chargingStateId)

    /** Whether reading [wanted] needs the car's CAN protocol, which only the charging state does. */
    fun needsProtocol(wanted: Set<BydVehicleEvent>) = BydVehicleEvent.CHARGING_STARTED in wanted

    /** The reads behind [wanted] only; a rule set without, say, gear events never reads the gear. */
    fun events(protocol: BydBatteryProtocol?, wanted: Set<BydVehicleEvent> = BydVehicleEvent.entries.toSet()): List<BydSignal> =
        buildList {
            if (wanted.any { it.left }) add(LEFT_CARD)
            if (wanted.any { it.right }) { add(RIGHT_CARD); add(MAP_PROMPTS); add(RIGHT_PROJECTION) }
            if (BydVehicleEvent.OEM_NAVIGATION_STARTED in wanted) add(NAVI_STATUS)
            if (wanted.any { it.gear }) add(GEAR)
            if (BydVehicleEvent.CHARGING_STARTED in wanted && protocol != null) add(charging(protocol))
        }

    /** The right card shows the stock map while the cluster prompts it or the projection service placed it. */
    fun mapShown(prompts: Int?, projection: Int?) = prompts == 1 || prompts == 3 || projection == 2 || projection == 3

    fun state(values: Map<BydSignal, Int?>, protocol: BydBatteryProtocol?): BydVehicleState {
        fun card(value: Int?) = when (value) { 1 -> true; 2 -> false; else -> null }
        val map = mapShown(values[MAP_PROMPTS], values[RIGHT_PROJECTION])
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
    CHARGING_STARTED;

    internal val left get() = this == LEFT_CARD_SHOWN || this == LEFT_CARD_HIDDEN
    internal val right get() = this == RIGHT_CARD_SHOWN || this == RIGHT_MAP_CARD_SHOWN || this == RIGHT_CARD_HIDDEN
    internal val gear get() = this == GEAR_P || this == GEAR_R || this == GEAR_D
}

/**
 * Turns samples into events. Most changes count at the first sample that shows them. Only a right
 * card without the map waits [rightSettleMillis], because the cluster can raise the map card's
 * signals up to half a second after its cover; a map signal ends that wait at once. The same wait
 * covers a map card that turns into another card (a door warning over the map), so a map signal
 * that flickers does not fire. Time-based, so pushed values and polls can be mixed.
 * The first value after a start or a lost link only sets the baseline, so connecting in D never
 * reports "D engaged".
 */
internal class BydVehicleEventDetector(private val rightSettleMillis: Long = 700) {
    private class Tracker<T : Any>(private val neededMillis: (T) -> Long = { 0L }) {
        var committed: T? = null
        private var candidate: T? = null
        private var since = 0L

        /** (previous, new) once a change is confirmed; previous is null for the first value. */
        fun accept(value: T?, now: Long): Pair<T?, T>? {
            if (value == null) return null
            if (value == committed) { candidate = null; return null }
            if (value != candidate) { candidate = value; since = now }
            // The baseline needs no confirmation: it never becomes an event.
            if (committed != null && now - since < neededMillis(value)) return null
            val previous = committed
            committed = value; candidate = null
            return previous to value
        }
    }

    private fun rightTracker() = Tracker<BydSideCard> { if (it == BydSideCard.SHOWN) rightSettleMillis else 0L }
    private var left = Tracker<BydSideCard>()
    private var right = rightTracker()
    private var navigating = Tracker<Boolean>()
    private var gear = Tracker<Int>()
    private var charging = Tracker<Boolean>()

    fun reset() {
        left = Tracker(); right = rightTracker(); navigating = Tracker(); gear = Tracker(); charging = Tracker()
    }

    /** [carPlayGuidance]: DiPlay itself feeds the stock adapter, so the navigation status is not the stock map's. */
    fun accept(state: BydVehicleState, carPlayGuidance: Boolean, now: Long): List<BydVehicleEvent> = buildList {
        left.accept(state.left, now)?.let { (previous, current) ->
            if (previous != null) add(if (current == BydSideCard.HIDDEN) BydVehicleEvent.LEFT_CARD_HIDDEN else BydVehicleEvent.LEFT_CARD_SHOWN)
        }
        right.accept(state.right, now)?.let { (previous, current) ->
            if (previous != null) add(when (current) {
                BydSideCard.HIDDEN -> BydVehicleEvent.RIGHT_CARD_HIDDEN
                BydSideCard.MAP -> BydVehicleEvent.RIGHT_MAP_CARD_SHOWN
                BydSideCard.SHOWN -> BydVehicleEvent.RIGHT_CARD_SHOWN
            })
        }
        navigating.accept(state.navigating, now)?.let { (previous, current) ->
            if (previous == false && current && !carPlayGuidance) add(BydVehicleEvent.OEM_NAVIGATION_STARTED)
        }
        gear.accept(state.gear, now)?.let { (previous, current) ->
            if (previous != null) when (current) {
                1 -> add(BydVehicleEvent.GEAR_P)
                2 -> add(BydVehicleEvent.GEAR_R)
                4 -> add(BydVehicleEvent.GEAR_D)
            }
        }
        charging.accept(state.charging, now)?.let { (previous, current) ->
            if (previous == false && current) add(BydVehicleEvent.CHARGING_STARTED)
        }
    }
}

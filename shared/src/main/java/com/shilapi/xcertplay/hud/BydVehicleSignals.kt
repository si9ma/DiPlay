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

/** The car signals an event or a shortcut condition can ask for; each is read only while something needs it. */
enum class BydVehicleSignalGroup { LEFT_CARD, RIGHT_CARD, GEAR, CHARGING }

/**
 * Cluster, gear and charging state. Only getters; the meanings were read on a Fangchengbao Tai 7 EV
 * (Di150VCP, Android 14) and match BYDProjectionService's own constants.
 */
internal object BydVehicleSignals {
    const val INSTRUMENT = 1007
    const val GEARBOX = 1011
    const val CHARGING = 1009

    /** INSTRUMENT_LEFT_SIDE_COVER_PANEL: 1 shown, 2 hidden; follows the cluster's own cards too. */
    val LEFT_CARD = BydSignal(INSTRUMENT, 0x40C0B028)
    /** INSTRUMENT_RIGHT_SIDE_COVER_PANEL: 1 shown, 2 hidden. Stays 1 while the map turns into a door card. */
    val RIGHT_CARD = BydSignal(INSTRUMENT, 0x40C0B02A)
    /**
     * INSTRUMENT_MAP_NAVIGATION_PROMPTS, sent by the cluster: 1 turn card, 2 hidden, 3 mini map. The only
     * map signal used: the projection service's RIGHT_PROJECTION_SET follows it up to two seconds late, and
     * MAP_MIRRORING_STATUS stays 3 while a door card covers the map.
     */
    val MAP_PROMPTS = BydSignal(INSTRUMENT, 0x40C0B01C)
    /** GEARBOX_AUTO_MODE_TYPE: 1 P, 2 R, 3 N, 4 D. */
    val GEAR = BydSignal(GEARBOX, 555745336)

    /** BMS charging state: 1 charging, 13 paused; anything else is not charging. */
    fun charging(protocol: BydBatteryProtocol) = BydSignal(CHARGING, protocol.chargingStateId)

    /** Only the charging state needs the car's CAN protocol. */
    fun needsProtocol(groups: Set<BydVehicleSignalGroup>) = BydVehicleSignalGroup.CHARGING in groups

    /** The reads behind [groups] only; nothing that asks for the gear makes the gear read. */
    fun reads(protocol: BydBatteryProtocol?, groups: Set<BydVehicleSignalGroup>): List<BydSignal> = buildList {
        if (BydVehicleSignalGroup.LEFT_CARD in groups) add(LEFT_CARD)
        if (BydVehicleSignalGroup.RIGHT_CARD in groups) { add(RIGHT_CARD); add(MAP_PROMPTS) }
        if (BydVehicleSignalGroup.GEAR in groups) add(GEAR)
        if (BydVehicleSignalGroup.CHARGING in groups && protocol != null) add(charging(protocol))
    }

    /** What the right side shows; an unreadable map prompt counts as no map. */
    fun right(cover: Int?, prompts: Int?): BydRightCard? = when (cover) {
        2 -> BydRightCard.HIDDEN
        1 -> when (prompts) {
            1 -> BydRightCard.MAP_TURN
            3 -> BydRightCard.MAP_MINI
            else -> BydRightCard.CARD
        }
        else -> null
    }

    fun state(values: Map<BydSignal, Int?>, protocol: BydBatteryProtocol?, carPlayNavigating: Boolean? = null): BydVehicleState {
        val charging = protocol?.let { values[charging(it)] }?.let {
            when (it) { 1 -> BydChargingState.CHARGING; 13 -> BydChargingState.PAUSED; else -> BydChargingState.IDLE }
        }
        return BydVehicleState(
            leftShown = when (values[LEFT_CARD]) { 1 -> true; 2 -> false; else -> null },
            right = right(values[RIGHT_CARD], values[MAP_PROMPTS]),
            gear = values[GEAR]?.takeIf { it in 1..4 },
            charging = charging,
            carPlayNavigating = carPlayNavigating,
        )
    }
}

enum class BydRightCard {
    HIDDEN, CARD, MAP_TURN, MAP_MINI;
    val map: Boolean get() = this == MAP_TURN || this == MAP_MINI
}

enum class BydChargingState { IDLE, CHARGING, PAUSED }

/** The latest reading; a null field could not be read or is not being read. [gear]: 1 P, 2 R, 3 N, 4 D. */
data class BydVehicleState(
    val leftShown: Boolean? = null,
    val right: BydRightCard? = null,
    val gear: Int? = null,
    val charging: BydChargingState? = null,
    val carPlayNavigating: Boolean? = null,
)

/** Cluster, gear, navigation and charging changes that a shortcut can follow. */
enum class BydVehicleEvent(val group: BydVehicleSignalGroup?) {
    LEFT_CARD_SHOWN(BydVehicleSignalGroup.LEFT_CARD), LEFT_CARD_HIDDEN(BydVehicleSignalGroup.LEFT_CARD),
    /** The right side shows a card that is not the map: it appeared, or replaced the map. */
    RIGHT_CARD_SHOWN(BydVehicleSignalGroup.RIGHT_CARD),
    RIGHT_MAP_CARD_SHOWN(BydVehicleSignalGroup.RIGHT_CARD),
    RIGHT_CARD_HIDDEN(BydVehicleSignalGroup.RIGHT_CARD),
    /** From DiPlay's own CarPlay guidance; needs no adb. */
    CARPLAY_NAVIGATION_STARTED(null), CARPLAY_NAVIGATION_ENDED(null),
    GEAR_P(BydVehicleSignalGroup.GEAR), GEAR_R(BydVehicleSignalGroup.GEAR), GEAR_D(BydVehicleSignalGroup.GEAR),
    CHARGING_STARTED(BydVehicleSignalGroup.CHARGING), CHARGING_ENDED(BydVehicleSignalGroup.CHARGING);

    val carPlay: Boolean get() = group == null

    companion object {
        /** Names stored by earlier versions; the stock-navigation start became CarPlay's. */
        fun fromStored(name: String): BydVehicleEvent =
            if (name == "OEM_NAVIGATION_STARTED") CARPLAY_NAVIGATION_STARTED else valueOf(name)
    }
}

/**
 * Turns samples into events. A change counts at the first sample that shows it, with one exception:
 * a right card that appears without a map waits [rightSettleMillis], because the cluster can raise
 * the map prompt up to half a second after the cover; the prompt arriving ends the wait at once.
 * A map that turns into another card (a door warning) is reported at once. Time-based, so pushed
 * values and polls can be mixed. The first value after a start or a lost link only sets the
 * baseline, so connecting in D never reports "D engaged".
 */
internal class BydVehicleEventDetector(private val rightSettleMillis: Long = 700) {
    private class Tracker<T : Any>(private val neededMillis: (previous: T, value: T) -> Long = { _, _ -> 0L }) {
        var committed: T? = null
        private var candidate: T? = null
        private var since = 0L

        /** (previous, new) once a change is confirmed; previous is null for the first value. */
        fun accept(value: T?, now: Long): Pair<T?, T>? {
            if (value == null) return null
            if (value == committed) { candidate = null; return null }
            if (value != candidate) { candidate = value; since = now }
            val previous = committed
            // The baseline needs no confirmation: it never becomes an event.
            if (previous != null && now - since < neededMillis(previous, value)) return null
            committed = value; candidate = null
            return previous to value
        }
    }

    private fun rightTracker() = Tracker<BydRightCard> { previous, value ->
        if (previous == BydRightCard.HIDDEN && value == BydRightCard.CARD) rightSettleMillis else 0L
    }
    private var left = Tracker<Boolean>()
    private var right = rightTracker()
    private var gear = Tracker<Int>()
    private var charging = Tracker<BydChargingState>()
    private var carPlay = Tracker<Boolean>()

    /** A lost link: the car values start a new baseline. [carPlayToo] also for CarPlay's guidance. */
    fun reset(carPlayToo: Boolean = true) {
        left = Tracker(); right = rightTracker(); gear = Tracker(); charging = Tracker()
        if (carPlayToo) carPlay = Tracker()
    }

    fun accept(state: BydVehicleState, now: Long): List<BydVehicleEvent> = buildList {
        left.accept(state.leftShown, now)?.let { (previous, current) ->
            if (previous != null) add(if (current) BydVehicleEvent.LEFT_CARD_SHOWN else BydVehicleEvent.LEFT_CARD_HIDDEN)
        }
        right.accept(state.right, now)?.let { (previous, current) ->
            if (previous != null) when {
                current == BydRightCard.HIDDEN -> add(BydVehicleEvent.RIGHT_CARD_HIDDEN)
                // A turn card becoming the mini map is still the map.
                current.map -> if (!previous.map) add(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN)
                else -> add(BydVehicleEvent.RIGHT_CARD_SHOWN)
            }
        }
        gear.accept(state.gear, now)?.let { (previous, current) ->
            if (previous != null) when (current) {
                1 -> add(BydVehicleEvent.GEAR_P)
                2 -> add(BydVehicleEvent.GEAR_R)
                4 -> add(BydVehicleEvent.GEAR_D)
            }
        }
        charging.accept(state.charging, now)?.let { (previous, current) ->
            // A pause neither ends nor, when it resumes, starts a charge.
            if (previous == BydChargingState.IDLE && current == BydChargingState.CHARGING) add(BydVehicleEvent.CHARGING_STARTED)
            if (previous != null && previous != BydChargingState.IDLE && current == BydChargingState.IDLE) add(BydVehicleEvent.CHARGING_ENDED)
        }
        carPlay.accept(state.carPlayNavigating, now)?.let { (previous, current) ->
            if (previous != null) add(if (current) BydVehicleEvent.CARPLAY_NAVIGATION_STARTED else BydVehicleEvent.CARPLAY_NAVIGATION_ENDED)
        }
    }
}

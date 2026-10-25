package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydVehicleSignalsTest {
    private fun reply(value: Long) = "Result: Parcel(00000000 %08x   '........')".format(value and 0xFFFFFFFFL)

    @Test fun batchUsesOnlyGettersAndQuotesTheParcelText() {
        val command = BydSignalBatch.command(listOf(BydVehicleSignals.LEFT_CARD, BydSignal(1014, 1246777400, BydSignal.GET_FLOAT)))
        assertEquals(
            "echo \"@1007:1086369832:5 \$(service call autoservice 5 i32 1007 i32 1086369832 2>&1)\"; " +
                "echo \"@1014:1246777400:7 \$(service call autoservice 7 i32 1014 i32 1246777400 2>&1)\"",
            command,
        )
        assertTrue(runCatching { BydSignalBatch.command(listOf(BydSignal(1007, 1, transaction = 6))) }.isFailure)
    }

    @Test fun errorCodesAndExceptionsReadAsMissing() {
        val ok = BydSignal(1007, 1)
        val unsupported = BydSignal(1007, 2)
        val unavailable = BydSignal(1007, 3)
        val exception = BydSignal(1007, 4)
        val negative = BydSignal(1001, -1903448641)
        val absent = BydSignal(1007, 5)
        val output = listOf(
            "@1007:1:5 ${reply(1)}",
            "@1007:2:5 ${reply(-10011)}",
            "@1007:3:5 ${reply(-10013)}",
            "@1007:4:5 Result: Parcel(ffffffec 00000000 '........')",
            "@1001:-1903448641:5 ${reply(-40)}",
        ).joinToString("\n")
        val values = BydSignalBatch.parse(output, listOf(ok, unsupported, unavailable, exception, negative, absent))
        assertEquals(1, values[ok])
        assertNull(values[unsupported])
        assertNull(values[unavailable])
        assertNull(values[exception])
        assertEquals(-40, values[negative])
        assertNull(values[absent])
    }

    private fun state(left: Int? = 2, right: Int? = 2, prompts: Int? = 2, gear: Int? = 1, bms: Int? = 15,
                      carPlay: Boolean? = null) = BydVehicleSignals.state(mapOf(
        BydVehicleSignals.LEFT_CARD to left, BydVehicleSignals.RIGHT_CARD to right, BydVehicleSignals.MAP_PROMPTS to prompts,
        BydVehicleSignals.GEAR to gear, BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to bms,
    ), BydBatteryProtocol.CANFD, carPlay)

    @Test fun rightCardIsTheMapOnlyWhileTheClusterPromptsIt() {
        assertEquals(BydRightCard.HIDDEN, state(right = 2, prompts = 1).right)
        assertEquals(BydRightCard.CARD, state(right = 1).right)
        assertEquals(BydRightCard.MAP_TURN, state(right = 1, prompts = 1).right)
        assertEquals(BydRightCard.MAP_MINI, state(right = 1, prompts = 3).right)
        // Tai 7, door open over the map: the prompt falls to 2 while the cover stays 1.
        assertEquals(BydRightCard.CARD, state(right = 1, prompts = 2).right)
        // An unreadable prompt is no map.
        assertEquals(BydRightCard.CARD, state(right = 1, prompts = null).right)
        assertNull(state(right = null).right)
        assertNull(state(right = 0).right)
        assertNull(state(gear = 9).gear)
        assertEquals(BydChargingState.IDLE, state(bms = 15).charging)
        assertEquals(BydChargingState.CHARGING, state(bms = 1).charging)
        assertEquals(BydChargingState.PAUSED, state(bms = 13).charging)
        assertEquals(true, state(left = 1).leftShown)
        assertNull(state(left = 0).leftShown)
    }

    @Test fun onlyTheReadsBehindTheWantedGroupsAreMade() {
        assertEquals(listOf(BydVehicleSignals.LEFT_CARD),
            BydVehicleSignals.reads(BydBatteryProtocol.CANFD, setOf(BydVehicleSignalGroup.LEFT_CARD)))
        assertEquals(listOf(BydVehicleSignals.RIGHT_CARD, BydVehicleSignals.MAP_PROMPTS),
            BydVehicleSignals.reads(BydBatteryProtocol.CANFD, setOf(BydVehicleSignalGroup.RIGHT_CARD)))
        assertEquals(listOf(BydVehicleSignals.GEAR), BydVehicleSignals.reads(null, setOf(BydVehicleSignalGroup.GEAR)))
        assertEquals(emptyList<BydSignal>(), BydVehicleSignals.reads(null, setOf(BydVehicleSignalGroup.CHARGING)))
        assertTrue(BydVehicleSignals.needsProtocol(setOf(BydVehicleSignalGroup.CHARGING)))
        assertFalse(BydVehicleSignals.needsProtocol(setOf(BydVehicleSignalGroup.LEFT_CARD)))
        assertEquals(emptyList<BydSignal>(), BydVehicleSignals.reads(BydBatteryProtocol.CANFD, emptySet()))
        assertNull(BydVehicleEvent.CARPLAY_NAVIGATION_ENDED.group)
        assertEquals(BydVehicleEvent.CARPLAY_NAVIGATION_STARTED, BydVehicleEvent.fromStored("OEM_NAVIGATION_STARTED"))
    }

    /** A fake clock: every fed sample is [step] ms after the previous one. */
    private var clock = 0L
    private fun BydVehicleEventDetector.feed(vararg states: BydVehicleState, step: Long = 250) =
        states.flatMap { clock += step; accept(it, clock) }

    @Test fun firstValueIsABaselineAndOtherChangesCountAtOnce() {
        val detector = BydVehicleEventDetector()
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(left = 1, gear = 4)))
        assertEquals(listOf(BydVehicleEvent.LEFT_CARD_HIDDEN), detector.feed(state(left = 2, gear = 4)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(left = 2, gear = 4)))
        assertEquals(listOf(BydVehicleEvent.GEAR_R), detector.feed(state(left = 2, gear = 2)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(left = 2, gear = 3)))
        assertEquals(listOf(BydVehicleEvent.GEAR_P), detector.feed(state(left = 2, gear = 1)))
        assertEquals(setOf(BydVehicleEvent.GEAR_D, BydVehicleEvent.LEFT_CARD_SHOWN), detector.feed(state(left = 1, gear = 4)).toSet())
        // Unreadable samples change nothing.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(left = null, gear = null)))
    }

    @Test fun aCardThatAppearsWaitsForALateMapPrompt() {
        val detector = BydVehicleEventDetector()
        detector.feed(state())
        // A card from hidden waits 700 ms for a late map prompt.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(right = 1), state(right = 1)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.feed(state(right = 1), state(right = 1)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_HIDDEN), detector.feed(state(right = 2)))
        // The map from hidden counts at once.
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN), detector.feed(state(right = 1, prompts = 1)))
        // Turn card to mini map is still the map.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(right = 1, prompts = 3)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_HIDDEN), detector.feed(state(right = 2)))
    }

    @Test fun aDoorCardOverTheMapAndBackFiresAtOnceBothWays() {
        // The Tai 7 readings: the cover stays 1 throughout; only the prompt changes.
        val detector = BydVehicleEventDetector()
        detector.feed(state(right = 1, prompts = 1))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.feed(state(right = 1, prompts = 2), step = 10))
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN), detector.feed(state(right = 1, prompts = 1), step = 10))
    }

    @Test fun theRightCardWaitIsConfigurableDownToNone() {
        var wait = 0L
        val detector = BydVehicleEventDetector { wait }
        detector.feed(state())
        // No wait: a card counts at once, and a late map prompt then reports the map as well.
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.feed(state(right = 1), step = 50))
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN), detector.feed(state(right = 1, prompts = 1), step = 300))
        detector.feed(state())
        wait = 1_000L
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(right = 1), state(right = 1), state(right = 1), state(right = 1)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.feed(state(right = 1)))
    }

    @Test fun aWaitingCardSaysWhenItCanBeConfirmed() {
        val detector = BydVehicleEventDetector { 50L }
        detector.accept(state(), 0)
        assertNull(detector.pendingMillis(0))
        assertEquals(emptyList<BydVehicleEvent>(), detector.accept(state(right = 1), 10))
        assertEquals(40L, detector.pendingMillis(20))
        // Confirmed by the same state once the wait is over, without a new sample in between.
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.accept(state(right = 1), 60))
        assertNull(detector.pendingMillis(60))
    }

    @Test fun aMapPromptArrivingAfterTheCoverReportsOnlyTheMap() {
        val detector = BydVehicleEventDetector()
        detector.feed(state())
        // Pushed changes: the cover first, the prompt 400 ms later.
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN),
            detector.feed(state(right = 1), step = 50) + detector.feed(state(right = 1, prompts = 1), step = 400))
    }

    @Test fun carPlayNavigationStartsAndEnds() {
        val detector = BydVehicleEventDetector()
        detector.feed(state(carPlay = false))
        assertEquals(listOf(BydVehicleEvent.CARPLAY_NAVIGATION_STARTED), detector.feed(state(carPlay = true)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(carPlay = true)))
        // A lost adb link keeps CarPlay's baseline.
        detector.reset(carPlayToo = false)
        assertEquals(listOf(BydVehicleEvent.CARPLAY_NAVIGATION_ENDED), detector.feed(state(carPlay = false)))
        // Not followed: no event.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(carPlay = null)))
    }

    @Test fun chargingStartsAndEndsOnceAndAPauseIsNeither() {
        val detector = BydVehicleEventDetector()
        detector.feed(state(bms = 15))
        assertEquals(listOf(BydVehicleEvent.CHARGING_STARTED), detector.feed(state(bms = 1), state(bms = 1)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(bms = 13), state(bms = 1)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(bms = null), state(bms = 1)))
        assertEquals(listOf(BydVehicleEvent.CHARGING_ENDED), detector.feed(state(bms = 2)))
        detector.feed(state(bms = 1))
        assertEquals(listOf(BydVehicleEvent.CHARGING_ENDED), detector.feed(state(bms = 13), state(bms = 15)))
        detector.reset()
        // After a reset (a lost link), charging that began meanwhile is the new baseline, not an event.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(bms = 1), state(bms = 1)))
    }

    private fun bits(value: Float) = java.lang.Float.floatToRawIntBits(value)

    @Test fun batteryDecodesTheParkedTai7Sample() {
        val values = mapOf(
            BydBatterySignals.SOC to 95, BydBatterySignals.SOH to 98, BydBatterySignals.RANGE to 654,
            BydBatterySignals.REMAINING to bits(87.3f), BydBatterySignals.VOLTAGE to 719,
            BydBatterySignals.CHARGE_CURRENT to bits(0.4f), BydBatterySignals.CURRENT to 30007, BydBatterySignals.ENGINE_POWER to 0,
            BydBatterySignals.CHARGED to -10011,
            BydBatterySignals.CELL_MAX to 3332, BydBatterySignals.CELL_MIN to 3326, BydBatterySignals.TEMP_MAX to 72,
            BydBatterySignals.TEMP_MIN to 67, BydBatterySignals.TEMP_AVG to 69, BydBatterySignals.INSULATION to 9935,
            BydBatterySignals.MAX_CHARGE to 1740, BydBatterySignals.MAX_DISCHARGE to 9485, BydBatterySignals.CHARGE_LIMIT to 100,
            BydBatterySignals.TIME_HOURS to 31, BydBatterySignals.TIME_MINUTES to 63,
            BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to 15,
        )
        val details = BydBatterySignals.decode(values, BydBatteryProtocol.CANFD)
        assertEquals(95.0, details[BydBatteryField.SOC]!!, 0.0)
        assertEquals(98.0, details[BydBatteryField.SOH]!!, 0.0)
        assertEquals(654.0, details[BydBatteryField.RANGE]!!, 0.0)
        assertEquals(87.3, details[BydBatteryField.REMAINING_ENERGY]!!, 1e-4)
        assertEquals("1005:0x34A00010=87.30", details.sources[BydBatteryField.REMAINING_ENERGY])
        // The float charging current wins over the GB current (0.7 A).
        assertEquals(0.4, details[BydBatteryField.CURRENT]!!, 1e-6)
        assertEquals(0.2876, details[BydBatteryField.POWER]!!, 1e-6)
        assertNull(details[BydBatteryField.CHARGED_ENERGY])
        assertEquals(3.332, details[BydBatteryField.CELL_VOLTAGE_MAX]!!, 1e-9)
        assertEquals(6.0, details[BydBatteryField.CELL_VOLTAGE_DIFF]!!, 0.0)
        assertEquals(32.0, details[BydBatteryField.TEMP_MAX]!!, 0.0)
        assertEquals(27.0, details[BydBatteryField.TEMP_MIN]!!, 0.0)
        assertEquals(174.0, details[BydBatteryField.MAX_CHARGE_POWER]!!, 1e-9)
        // 31 h / 63 min is the "no estimate" pattern.
        assertNull(details[BydBatteryField.CHARGE_TIME_LEFT])
        assertEquals(false, details.charging)
    }

    @Test fun remainingEnergyMustFitThePackAndTheOldStatisticIsGone() {
        fun remaining(raw: Int?, soc: Int = 95) = BydBatterySignals.decode(mapOf(BydBatterySignals.SOC to soc,
            BydBatterySignals.REMAINING to raw), null)[BydBatteryField.REMAINING_ENERGY]
        assertNull(remaining(-10013))
        assertNull(remaining(bits(-1.0e9f)))
        assertNull(remaining(bits(400f)))
        // 5 kWh at 95 % would be a 5 kWh pack.
        assertNull(remaining(bits(5f)))
        assertEquals(5.0, remaining(bits(5f), soc = 6)!!, 1e-6)
        // The integer read DiPlay used before is not a reliable energy and is no longer read.
        assertFalse(BydSignal(1014, 0x44700028) in BydBatterySignals.all(BydBatteryProtocol.CANFD))
    }

    @Test fun chargingPowerUsesTheChargingCurrentThenTheEngine() {
        val charging = BydBatterySignals.decode(mapOf(BydBatterySignals.VOLTAGE to 700,
            BydBatterySignals.CHARGE_CURRENT to bits(-150f), BydBatterySignals.CURRENT to 30007,
            BydBatterySignals.CHARGED to bits(12.5f)), null)
        assertEquals(-105.0, charging[BydBatteryField.POWER]!!, 1e-6)
        assertEquals("V×I (charging current)", charging.sources[BydBatteryField.POWER])
        assertEquals(12.5, charging[BydBatteryField.CHARGED_ENERGY]!!, 1e-6)
        val engine = BydBatterySignals.decode(mapOf(BydBatterySignals.CHARGE_CURRENT to -10013,
            BydBatterySignals.ENGINE_POWER to -42), null)
        assertEquals(-42.0, engine[BydBatteryField.POWER]!!, 0.0)
        assertEquals("1012:0x14400020=-42", engine.sources[BydBatteryField.POWER])
    }

    @Test fun batteryFallsBackToTheVerifiedSocAndDropsImplausibleValues() {
        val soc = bits(57.5f)
        val values = mapOf(
            BydBatterySignals.SOC to null, BydSignal(1014, BydBatteryProtocol.CANFD.percentId, BydSignal.GET_FLOAT) to soc,
            BydBatterySignals.VOLTAGE to 719, BydBatterySignals.CURRENT to 29000, BydBatterySignals.TEMP_MAX to 255,
            BydBatterySignals.CELL_MAX to 65535, BydBatterySignals.TIME_HOURS to 1, BydBatterySignals.TIME_MINUTES to 5,
            BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to 1,
        )
        val details = BydBatterySignals.decode(values, BydBatteryProtocol.CANFD)
        assertEquals(57.5, details[BydBatteryField.SOC]!!, 1e-6)
        // No float charging current: the GB current is the fallback.
        assertEquals(-100.0, details[BydBatteryField.CURRENT]!!, 1e-6)
        assertEquals(-71.9, details[BydBatteryField.POWER]!!, 1e-6)
        assertEquals("V×I (GB current)", details.sources[BydBatteryField.POWER])
        assertNull(details[BydBatteryField.TEMP_MAX])
        assertNull(details[BydBatteryField.CELL_VOLTAGE_MAX])
        assertNull(details[BydBatteryField.CELL_VOLTAGE_DIFF])
        assertEquals(65.0, details[BydBatteryField.CHARGE_TIME_LEFT]!!, 0.0)
        assertEquals(true, details.charging)
        assertFalse(BydBatterySignals.decode(emptyMap(), null).values.isNotEmpty())
        assertNull(BydBatterySignals.decode(emptyMap(), null).charging)
    }

    @Test fun batteryReadsAreDistinctAndIncludeTheChargingState() {
        val reads = BydBatterySignals.all(BydBatteryProtocol.CANFD)
        assertEquals(reads.size, reads.toSet().size)
        assertTrue(BydVehicleSignals.charging(BydBatteryProtocol.CANFD) in reads)
        val all = BydVehicleSignalGroup.entries.toSet()
        assertTrue(BydVehicleSignals.charging(BydBatteryProtocol.CAN) in BydVehicleSignals.reads(BydBatteryProtocol.CAN, all))
        assertFalse(BydVehicleSignals.reads(null, all).any { it.device == BydVehicleSignals.CHARGING })
    }
}

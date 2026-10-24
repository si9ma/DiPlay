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

    private fun state(left: Int? = 2, right: Int? = 2, prompts: Int? = 2, projection: Int? = 0, mirroring: Int? = 0,
                      navi: Int? = 4, gear: Int? = 1, bms: Int? = 15) = BydVehicleSignals.state(mapOf(
        BydVehicleSignals.LEFT_CARD to left, BydVehicleSignals.RIGHT_CARD to right, BydVehicleSignals.MAP_PROMPTS to prompts,
        BydVehicleSignals.RIGHT_PROJECTION to projection, BydVehicleSignals.MAP_MIRRORING to mirroring,
        BydVehicleSignals.NAVI_STATUS to navi, BydVehicleSignals.GEAR to gear,
        BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to bms,
    ), BydBatteryProtocol.CANFD)

    @Test fun rightCardIsTheMapWhenAnyOfTheStockMapSignalsSaysSo() {
        assertEquals(BydSideCard.HIDDEN, state(right = 2, prompts = 1).right)
        assertEquals(BydSideCard.SHOWN, state(right = 1).right)
        assertEquals(BydSideCard.MAP, state(right = 1, prompts = 1).right)
        assertEquals(BydSideCard.MAP, state(right = 1, prompts = 2, projection = 2).right)
        assertEquals(BydSideCard.MAP, state(right = 1, mirroring = 3).right)
        assertNull(state(right = null).right)
        assertNull(state(right = 0).right)
        assertEquals(false, state(navi = 4).navigating)
        assertEquals(true, state(navi = 2).navigating)
        assertNull(state(navi = 7).navigating)
        assertNull(state(gear = 9).gear)
        assertEquals(false, state(bms = 15).charging)
        assertEquals(true, state(bms = 1).charging)
    }

    private fun BydVehicleEventDetector.feed(vararg states: BydVehicleState, guidance: Boolean = false) =
        states.flatMap { accept(it, guidance) }

    @Test fun firstValueIsABaselineAndOtherChangesCountAtOnce() {
        val detector = BydVehicleEventDetector()
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(left = 1, gear = 4)))
        assertEquals(listOf(BydVehicleEvent.LEFT_CARD_HIDDEN), detector.accept(state(left = 2, gear = 4), false))
        assertEquals(emptyList<BydVehicleEvent>(), detector.accept(state(left = 2, gear = 4), false))
        assertEquals(listOf(BydVehicleEvent.GEAR_R), detector.accept(state(left = 2, gear = 2), false))
        assertEquals(emptyList<BydVehicleEvent>(), detector.accept(state(left = 2, gear = 3), false))
        assertEquals(listOf(BydVehicleEvent.GEAR_P), detector.accept(state(left = 2, gear = 1), false))
        assertEquals(setOf(BydVehicleEvent.GEAR_D, BydVehicleEvent.LEFT_CARD_SHOWN),
            detector.accept(state(left = 1, gear = 4), false).toSet())
        // Unreadable samples change nothing.
        assertEquals(emptyList<BydVehicleEvent>(), detector.accept(state(left = null, gear = null), false))
    }

    @Test fun recordedRightCardSequenceFromTheTai7() {
        // watch-084022: the cover, the map prompt and the projection rose in one sample; watch-084422: the
        // prompt fell first and the cover 15 s later.
        val detector = BydVehicleEventDetector()
        detector.feed(state())
        // A plain card waits three samples for a late map signal.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(right = 1), state(right = 1)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN), detector.accept(state(right = 1), false))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_HIDDEN), detector.accept(state(right = 2), false))
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN), detector.accept(state(right = 1, prompts = 1), false))
        assertEquals(emptyList<BydVehicleEvent>(), detector.accept(state(right = 1, prompts = 1, projection = 2), false))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_SHOWN),
            detector.feed(state(right = 1, prompts = 2, projection = 2), state(right = 1, prompts = 2), state(right = 1, prompts = 2),
                state(right = 1, prompts = 2)))
        assertEquals(listOf(BydVehicleEvent.RIGHT_CARD_HIDDEN), detector.accept(state(right = 2), false))
    }

    @Test fun mapPromptArrivingAfterTheCoverStillReportsTheMapCard() {
        val detector = BydVehicleEventDetector()
        detector.feed(state())
        assertEquals(listOf(BydVehicleEvent.RIGHT_MAP_CARD_SHOWN),
            detector.feed(state(right = 1), state(right = 1), state(right = 1, prompts = 1), state(right = 1, prompts = 1)))
    }

    @Test fun stockNavigationStartIsNotReportedWhileCarPlayFeedsTheAdapter() {
        val detector = BydVehicleEventDetector()
        detector.feed(state(navi = 4))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(navi = 2), state(navi = 2), guidance = true))
        detector.feed(state(navi = 4))
        assertEquals(listOf(BydVehicleEvent.OEM_NAVIGATION_STARTED), detector.feed(state(navi = 2), state(navi = 2)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(navi = 3), state(navi = 3)))
    }

    @Test fun chargingStartsOnceAndAResetStartsANewBaseline() {
        val detector = BydVehicleEventDetector()
        detector.feed(state(bms = 15))
        assertEquals(listOf(BydVehicleEvent.CHARGING_STARTED), detector.feed(state(bms = 1), state(bms = 1)))
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(bms = null), state(bms = 1)))
        detector.reset()
        // After a reset (a lost link), charging that began meanwhile is the new baseline, not an event.
        assertEquals(emptyList<BydVehicleEvent>(), detector.feed(state(bms = 1), state(bms = 1)))
        detector.feed(state(bms = 15))
        assertEquals(listOf(BydVehicleEvent.CHARGING_STARTED), detector.feed(state(bms = 1)))
    }

    @Test fun batteryDecodesTheParkedTai7Sample() {
        val values = mapOf(
            BydBatterySignals.SOC to 96, BydBatterySignals.SOH to 98, BydBatterySignals.RANGE to 654,
            BydBatterySignals.REMAINING to 961, BydBatterySignals.VOLTAGE to 719, BydBatterySignals.CURRENT to 30004,
            BydBatterySignals.CELL_MAX to 3332, BydBatterySignals.CELL_MIN to 3326, BydBatterySignals.TEMP_MAX to 72,
            BydBatterySignals.TEMP_MIN to 67, BydBatterySignals.TEMP_AVG to 69, BydBatterySignals.INSULATION to 9935,
            BydBatterySignals.MAX_CHARGE to 1740, BydBatterySignals.MAX_DISCHARGE to 9485, BydBatterySignals.CHARGE_LIMIT to 100,
            BydBatterySignals.TIME_HOURS to 31, BydBatterySignals.TIME_MINUTES to 63,
            BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to 15,
        )
        val details = BydBatterySignals.decode(values, BydBatteryProtocol.CANFD)
        assertEquals(96.0, details[BydBatteryField.SOC]!!, 0.0)
        assertEquals(98.0, details[BydBatteryField.SOH]!!, 0.0)
        assertEquals(654.0, details[BydBatteryField.RANGE]!!, 0.0)
        assertEquals(96.1, details[BydBatteryField.REMAINING_ENERGY]!!, 1e-9)
        assertEquals(0.4, details[BydBatteryField.CURRENT]!!, 1e-6)
        assertEquals(0.2876, details[BydBatteryField.POWER]!!, 1e-6)
        assertEquals(3.332, details[BydBatteryField.CELL_VOLTAGE_MAX]!!, 1e-9)
        assertEquals(6.0, details[BydBatteryField.CELL_VOLTAGE_DIFF]!!, 0.0)
        assertEquals(32.0, details[BydBatteryField.TEMP_MAX]!!, 0.0)
        assertEquals(27.0, details[BydBatteryField.TEMP_MIN]!!, 0.0)
        assertEquals(174.0, details[BydBatteryField.MAX_CHARGE_POWER]!!, 1e-9)
        // 31 h / 63 min is the "no estimate" pattern.
        assertNull(details[BydBatteryField.CHARGE_TIME_LEFT])
        assertEquals(false, details.charging)
    }

    @Test fun batteryFallsBackToTheVerifiedSocAndDropsImplausibleValues() {
        val soc = java.lang.Float.floatToRawIntBits(57.5f)
        val values = mapOf(
            BydBatterySignals.SOC to null, BydSignal(1014, BydBatteryProtocol.CANFD.percentId, BydSignal.GET_FLOAT) to soc,
            BydBatterySignals.VOLTAGE to 719, BydBatterySignals.CURRENT to 29000, BydBatterySignals.TEMP_MAX to 255,
            BydBatterySignals.CELL_MAX to 65535, BydBatterySignals.TIME_HOURS to 1, BydBatterySignals.TIME_MINUTES to 5,
            BydVehicleSignals.charging(BydBatteryProtocol.CANFD) to 1,
        )
        val details = BydBatterySignals.decode(values, BydBatteryProtocol.CANFD)
        assertEquals(57.5, details[BydBatteryField.SOC]!!, 1e-6)
        assertEquals(-100.0, details[BydBatteryField.CURRENT]!!, 1e-6)
        assertEquals(-71.9, details[BydBatteryField.POWER]!!, 1e-6)
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
        assertTrue(BydVehicleSignals.charging(BydBatteryProtocol.CAN) in BydVehicleSignals.events(BydBatteryProtocol.CAN))
        assertFalse(BydVehicleSignals.events(null).any { it.device == BydVehicleSignals.CHARGING })
    }
}

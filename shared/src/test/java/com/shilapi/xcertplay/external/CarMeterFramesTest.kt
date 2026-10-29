package com.shilapi.xcertplay.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarMeterFramesTest {
    @Test fun timeFrameMatchesTheFirmwareProtocol() {
        assertEquals("{\"unix\":1761400000123}", CarMeterFrames.time(1_761_400_000_123L))
    }

    @Test fun navFrameUsesArrivalAndRemainingMeters() {
        val frame = CarMeterFrames.nav(
            active = true,
            arrivalEpochSeconds = 1_761_400_800L,
            remainingSeconds = null,
            remainingMeters = 8_400L,
            nowMs = 1_761_400_000_000L,
        )
        assertEquals("{\"nav\":1,\"eta\":1761400800500,\"rem_m\":8400}", frame)
    }

    @Test fun navFrameFallsBackToRemainingSeconds() {
        val frame = CarMeterFrames.nav(
            active = true,
            arrivalEpochSeconds = null,
            remainingSeconds = 1_200L,
            remainingMeters = 14_400L,
            nowMs = 1_761_400_000_000L,
        )
        assertEquals("{\"nav\":1,\"eta\":1761401200000,\"rem_m\":14400}", frame)
    }

    @Test fun navEndFrameClearsTheDashboard() {
        assertEquals("{\"nav\":0,\"eta\":0,\"rem_m\":0}",
            CarMeterFrames.nav(false, null, null, null, 0L))
    }

    @Test fun activeNavWithoutRouteFieldsIsSkipped() {
        assertNull(CarMeterFrames.nav(true, null, null, null, 1_000L))
        assertNull(CarMeterFrames.nav(true, 10L, null, null, 1_000L))
        assertNull(CarMeterFrames.nav(true, null, 10L, -5L, 1_000L))
    }

    @Test fun testFramesAreValidProtocolFrames() {
        val now = 1_761_400_000_000L
        assertEquals("{\"nav\":1,\"eta\":1761400720000,\"rem_m\":8400}", CarMeterFrames.testDistance(now))
        assertEquals("{\"nav\":1,\"eta\":1761402520000,\"rem_m\":30240}", CarMeterFrames.testDuration(now))
        assertEquals("{\"nav\":1,\"eta\":1761400860000,\"rem_m\":6000}", CarMeterFrames.testArrival(now, now + 860_000L))
    }
}

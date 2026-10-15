package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogRateLimitTest {
    private val limit = SessionLogRateLimit(windowMillis = 60_000L)

    private fun line(at: String, message: String) = "$at  $message"

    @Test fun theFirstOfAKindIsAlwaysWritten() {
        assertEquals(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"),
            limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 0))
    }

    @Test fun anUnchangedRepeatIsHeldBackWithinTheWindow() {
        limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 0)
        assertNull(limit.accept(line("23:09:02.413", "iAP tunnel iap2 rx=0x5201"), 1_000))
        assertNull(limit.accept(line("23:09:03.410", "iAP tunnel iap2 rx=0x5201"), 2_000))
    }

    /** The count is what makes folding safe: the report still shows the line happened, and how often. */
    @Test fun theWindowEndingWritesTheLineWithItsCount() {
        limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 0)
        repeat(60) { limit.accept(line("23:09:02.413", "iAP tunnel iap2 rx=0x5201"), it * 1_000L) }
        val written = limit.accept(line("23:10:02.000", "iAP tunnel iap2 rx=0x5201"), 61_000)
        assertNotNull(written)
        assertTrue(written!!, written.contains("(repeated x"))
        assertTrue(written, written.startsWith(line("23:10:02.000", "iAP tunnel iap2 rx=0x5201")))
    }

    /** A line carrying a changing value is a different line, so nothing with content is ever folded. */
    @Test fun aChangingValueIsNeverFolded() {
        val first = limit.accept(line("23:09:01.418", "Video: video stats rx=38.0fps"), 0)
        val second = limit.accept(line("23:09:06.418", "Video: video stats rx=37.6fps"), 5_000)
        assertNotNull(first)
        assertNotNull(second)
    }

    @Test fun differentLinesDoNotShareAWindow() {
        limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 0)
        assertNotNull(limit.accept(line("23:09:02.084", "iAP tunnel iap2 rx=0x4158"), 1_000))
        assertNull(limit.accept(line("23:09:02.413", "iAP tunnel iap2 rx=0x5201"), 2_000))
    }

    @Test fun drainReportsWhatWasStillHeldBack() {
        limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 0)
        repeat(5) { limit.accept(line("23:09:02.413", "iAP tunnel iap2 rx=0x5201"), 1_000) }
        val pending = limit.drain()
        assertEquals(1, pending.size)
        assertTrue(pending.first(), pending.first().contains("repeated x5"))
        // Drained, so the next occurrence starts over rather than reporting the same count twice.
        assertTrue(limit.drain().isEmpty())
    }

    @Test fun aLineWithoutATimestampIsFoldedOnItsWholeText() {
        assertNotNull(limit.accept("Activity destroyed", 0))
        assertNull(limit.accept("Activity destroyed", 1_000))
    }

    /** A clock moved backwards must not hold a line back for as long as the jump. */
    @Test fun aBackwardClockDoesNotSuppressIndefinitely() {
        limit.accept(line("23:09:01.418", "iAP tunnel iap2 rx=0x5201"), 500_000)
        assertNotNull(limit.accept(line("23:09:02.413", "iAP tunnel iap2 rx=0x5201"), 1_000))
    }

    @Test fun theNumberOfTrackedLinesIsBounded() {
        val bounded = SessionLogRateLimit(windowMillis = 60_000L, maxTracked = 4)
        repeat(10) { bounded.accept(line("23:09:01.418", "message $it"), 0) }
        // The oldest were forgotten, so the first message is written as if it were new.
        assertNotNull(bounded.accept(line("23:09:02.000", "message 0"), 1_000))
        assertNull(bounded.accept(line("23:09:03.000", "message 9"), 2_000))
    }
}

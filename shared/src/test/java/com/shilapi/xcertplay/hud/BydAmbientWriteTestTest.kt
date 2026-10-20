package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.media.AmbientDiagnostics
import org.junit.Assert.*
import org.junit.Test

class BydAmbientWriteTestTest {
    private class Worker(val replies: Map<String, String?>) {
        val sent = mutableListOf<String>()
        var slept = 0L
        fun exchange(command: String, timeout: Int): String? {
            sent += command
            return if (command in replies) replies[command] else if (command.startsWith("test-apply "))
                "test-applied=57:57,57,6,6,${command.substringAfter(' ')}" else "ready"
        }
    }
    private val log = mutableListOf<String>()

    private fun run(worker: Worker) = BydAmbientWriteTest.execute(worker::exchange, { worker.slept += it }, log::add)

    @Test fun passingSequenceHoldsWithHeartbeatsThenStopsAndConfirmsTheOriginal() {
        val w = Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3",
            "test-apply 1" to "test-applied=57:57,127,6,6,1", "stop" to "restored=127,127,6,6,3:${"a".repeat(32)}"))
        val out = run(w)
        assertEquals(BydAmbientWriteTest.Result.PASSED, out.result)
        assertEquals(57, out.color)
        assertEquals("57,57,6,6,3", out.applied)
        assertEquals(listOf("ping", "test-begin", "test-apply 1", "ping", "ping", "test-apply 2", "ping", "ping", "test-apply 3", "ping", "ping", "stop"), w.sent)
        assertEquals(BydAmbientWriteTest.HOLD_MILLIS * 3, w.slept)
        assertEquals("a".repeat(32), out.confirmation)
        assertFalse(out.needsManualRestore)
        assertTrue(w.sent.none { it.startsWith("apply") || it.startsWith("begin") })
        log.forEach { assertEquals(it, AmbientDiagnostics.sanitize(it)) }
    }

    @Test fun restoreMismatchOrBrokenLinkAsksForManualRestore() {
        val mismatch = run(Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3",
            "test-apply 1" to "test-applied=57:57,127,6,6,1", "stop" to "restored=57,127,6,6,3")))
        assertEquals(BydAmbientWriteTest.Result.RESTORE_FAILED, mismatch.result)
        assertTrue(mismatch.needsManualRestore)
        val lost = run(Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3", "test-apply 1" to null)))
        assertEquals(BydAmbientWriteTest.Result.RESTORE_FAILED, lost.result)
        assertEquals("127,127,6,6,3", lost.original)
    }

    @Test fun rejectedOrUnreadWritesAreDistinguishedAndAlreadyRestoredByTheWorker() {
        val rejected = run(Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3",
            "test-apply 1" to "error=IllegalStateException:set-result-1")))
        assertEquals(BydAmbientWriteTest.Result.WRITE_REJECTED, rejected.result)
        assertFalse(rejected.needsManualRestore)
        val clipped = run(Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3",
            "test-apply 1" to "error=IllegalStateException:readback-timeout:31,127,6,6,1")))
        assertEquals(BydAmbientWriteTest.Result.WRITE_NOT_READ_BACK, clipped.result)
        assertEquals("31,127,6,6,1", clipped.lastRead)
        val unrestored = run(Worker(mapOf("test-begin" to "test-begun=127,127,6,6,3",
            "test-apply 1" to "error=IllegalStateException:set-result-1 restore-failed")))
        assertEquals(BydAmbientWriteTest.Result.RESTORE_FAILED, unrestored.result)
    }

    @Test fun nothingIsWrittenWhenBeginIsRefusedOrTheWorkerIsBusy() {
        val wrong = Worker(mapOf("test-begin" to "error=IllegalStateException:profile-not-127"))
        assertEquals(BydAmbientWriteTest.Result.NOT_127_PROFILE, run(wrong).result)
        assertFalse("test-apply 1" in wrong.sent)
        val state = Worker(mapOf("test-begin" to "error=IllegalStateException:area-unverified"))
        assertEquals(BydAmbientWriteTest.Result.UNSUPPORTED_STATE, run(state).result)
        val busy = Worker(mapOf("ping" to "error worker-busy"))
        assertEquals(BydAmbientWriteTest.Result.BUSY, run(busy).result)
        assertEquals(listOf("ping"), busy.sent)
    }
}

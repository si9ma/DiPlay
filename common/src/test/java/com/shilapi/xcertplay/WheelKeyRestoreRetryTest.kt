package com.shilapi.xcertplay

import com.shilapi.xcertplay.WheelKeyService.Companion.EnableResult
import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.assertEquals
import org.junit.Test

/** At boot the car's adb can come up after DiPlay; one early restore left the keys unbound (#606, #619). */
class WheelKeyRestoreRetryTest {
    private fun result(access: LocalAdb.Access) = EnableResult(access, access == LocalAdb.Access.READY)

    @Test fun unreachableAdbAtBootIsTriedAgainUntilTheServiceBinds() {
        val sleeps = mutableListOf<Long>()
        val results = mutableListOf<Pair<Int, LocalAdb.Access>>()
        var bound = false
        var restores = 0
        WheelKeyService.restoreWithRetries(
            needsRestore = { !bound },
            restore = { result(if (++restores < 3) LocalAdb.Access.UNREACHABLE else LocalAdb.Access.READY).also { if (restores == 3) bound = true } },
            sleep = { sleeps += it },
        ) { attempt, r -> results += attempt to r.access }

        assertEquals(listOf(1 to LocalAdb.Access.UNREACHABLE, 2 to LocalAdb.Access.UNREACHABLE, 3 to LocalAdb.Access.READY), results)
        // The fourth wake finds the service bound and stops.
        assertEquals(listOf(4_000L, 10_000L, 10_000L, 10_000L), sleeps)
    }

    @Test fun anUnapprovedKeyIsNotRetriedAndAttemptsAreBounded() {
        val results = mutableListOf<LocalAdb.Access>()
        WheelKeyService.restoreWithRetries({ true }, { result(LocalAdb.Access.NOT_APPROVED) }, {}) { _, r -> results += r.access }
        assertEquals(listOf(LocalAdb.Access.NOT_APPROVED), results)

        results.clear()
        WheelKeyService.restoreWithRetries({ true }, { result(LocalAdb.Access.UNREACHABLE) }, {}, attempts = 6) { _, r -> results += r.access }
        assertEquals(6, results.size)
    }

    @Test fun aServiceThatBoundDuringTheGraceIsLeftAlone() {
        var restores = 0
        WheelKeyService.restoreWithRetries({ false }, { restores++; result(LocalAdb.Access.READY) }, {}) { _, _ -> }
        assertEquals(0, restores)
    }
}

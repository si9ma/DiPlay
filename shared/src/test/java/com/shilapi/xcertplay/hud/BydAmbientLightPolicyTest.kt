package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydAmbientLightPolicyTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun protocolAcceptsOnlyWhitelistedCommandsAndBounds() {
        assertEquals("minimum-stop", BydAmbientLightWorkerProtocol.parse("$token minimum-stop", token)?.op)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token minimum-stop 1", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token off", token))
        assertEquals("begin", BydAmbientLightWorkerProtocol.parse("$token begin", token)?.op)
        assertEquals(listOf(1, 16, 6), BydAmbientLightWorkerProtocol.parse("$token apply 1 16 6", token)?.args)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token shell id", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 0 16 6", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 1 128 6", token))
        assertEquals(listOf(1, 57, 6), BydAmbientLightWorkerProtocol.parse("$token apply 1 57 6", token)?.args)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 1 16 0", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("${token.dropLast(1)}x read", token))
        assertNull(ByDProtocolLong(token))
    }

    @Test fun theWorkersReplyIsItsLastLineAfterTheFirmwaresHotfixNote() {
        // As a 2024 Tang (DiLink 5) prints it before the worker's first reply.
        val hotfix = "HotFixRoot: /data/app/com.android.shell.aG90Zml -> patchMetaFile:/data/app/com.android.shell.aG90Zml/current.meta"
        assertEquals("ready", BydAmbientLightWorkerProtocol.reply("$hotfix\nready"))
        assertEquals("state=20,20,3,3,3", BydAmbientLightWorkerProtocol.reply("state=20,20,3,3,3"))
        assertEquals("ready", BydAmbientLightWorkerProtocol.reply("ready\r\n"))
        assertNull(BydAmbientLightWorkerProtocol.reply(null))
        assertNull(BydAmbientLightWorkerProtocol.reply(""))
    }

    @Test fun diagnosticsPreserveStagesAndRawValuesWithoutChangingTheReply() {
        val lines = listOf(
            BydAmbientLightWorkerProtocol.diagnostic("feature-SET_IAL_BACK_COLOR", "ok", value = 42),
            BydAmbientLightWorkerProtocol.diagnostic("read-SET_IAL_BACK_COLOR", "ok", value = 0),
            BydAmbientLightWorkerProtocol.diagnostic("lamp-state", "failed", error = IllegalStateException("invalid-lamp-state")),
        )
        val output = "HotFixRoot: /data/app/private/current.meta\n${lines.joinToString("\r\n")}\nerror=IllegalStateException:invalid-lamp-state"
        assertEquals(lines.map { it.removePrefix("ambient-diag ") }, BydAmbientLightWorkerProtocol.diagnostics(output))
        assertEquals("error=IllegalStateException:invalid-lamp-state", BydAmbientLightWorkerProtocol.reply(output))
        assertEquals("worker-error", BydAmbientLightWorkerProtocol.replySummary(output))
        assertEquals("ready", BydAmbientLightWorkerProtocol.replySummary("${lines.first()}\nready"))
        assertEquals("state=1,2,3,4,3", BydAmbientLightWorkerProtocol.replySummary("state=1,2,3,4,3"))
    }

    @Test fun diagnosticErrorsKeepCauseTypesButNeverExceptionMessagesOrArbitraryOutput() {
        val failure = java.lang.reflect.InvocationTargetException(SecurityException("private path /data/app/secret token=$token"))
        val line = BydAmbientLightWorkerProtocol.diagnostic("manager-instance", "failed", error = failure)
        assertEquals("InvocationTargetException>SecurityException", BydAmbientLightWorkerProtocol.errorTypes(failure))
        assertFalse(line.contains(token))
        assertFalse(line.contains("/data/"))
        assertEquals(1, BydAmbientLightWorkerProtocol.diagnostics(line).size)
        assertTrue(BydAmbientLightWorkerProtocol.diagnostics("ambient-diag stage=unknown result=ok\nambient-diag stage=init result=failed token=$token").isEmpty())
        assertEquals("unexpected-reply", BydAmbientLightWorkerProtocol.replySummary("token=$token"))
        assertEquals("no-reply", BydAmbientLightWorkerProtocol.replySummary(null))
        val cycle = IllegalStateException("secret")
        val cause = IllegalArgumentException("secret", cycle)
        cycle.initCause(cause)
        assertEquals(5, BydAmbientLightWorkerProtocol.errorTypes(cycle).split('>').size)
    }

    @Test fun startupDiagnosticsLeaveRoomForFirmwareOutputWithinTheResponseLimit() {
        val stages = listOf("worker-lock", "system-context", "shell-context", "manager-class", "manager-instance",
            "method-getInt", "method-setIntArray", "method-setInt")
        val lines = stages.map { BydAmbientLightWorkerProtocol.diagnostic(it, "ok") } +
            (BydAmbientLightPolicy.getNames + BydAmbientLightPolicy.setNames).map {
                BydAmbientLightWorkerProtocol.diagnostic("feature-$it", "ok", value = Int.MAX_VALUE)
            } + BydAmbientLightPolicy.configNames.map {
                BydAmbientLightWorkerProtocol.diagnostic("read-$it", "ok", value = Int.MAX_VALUE, unknownSemantics = true)
            }
        val output = lines.joinToString("\n") + "\nready\ndone\n"
        assertEquals(lines.size, BydAmbientLightWorkerProtocol.diagnostics(output).size)
        assertTrue(output.toByteArray(Charsets.UTF_8).size < 2500)
    }

    @Test fun onlyLightingFeaturesAreWhitelisted() {
        assertEquals(9, BydAmbientLightPolicy.getNames.size)
        assertEquals(3, BydAmbientLightPolicy.setNames.size)
        assertFalse(BydAmbientLightPolicy.setNames.any { "SOURCE" in it })
        assertEquals(15_000L, BydAmbientLightPolicy.LEASE_MILLIS)
        assertEquals(50L, BydAmbientLightPolicy.READBACK_POLL_MILLIS)
    }

    @Test fun validatesSnapshotAndRequestedRawValues() {
        assertTrue(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(0, 16, 6, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 0, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 4)))
        assertTrue(BydAmbientLightPolicy.validApply(2, 31, 1))
        assertFalse(BydAmbientLightPolicy.validApply(3, 32, 6))
    }

    @Test fun equalZoneSnapshotCanUseKnownAllBatchAndThenAreaOnly() {
        val s = BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 2)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 6, 3)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 2),
        ), BydAmbientLightPolicy.restoreSteps(s))
    }

    @Test fun differentZoneSnapshotRestoresFrontThenBackThenAreaOnly() {
        val s = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 3)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(7, 2, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(23, 5, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 3),
        ), BydAmbientLightPolicy.restoreSteps(s))
    }

    @Test fun minimumKeepsCurrentColorsAndAreaInsteadOfRestoringSavedBrightness() {
        val current = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2)
        val target = BydAmbientLightPolicy.Snapshot(7, 23, 1, 1, 2)
        assertEquals(target, BydAmbientLightPolicy.minimumTarget(current))
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(7, 1, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(23, 1, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 2),
        ), BydAmbientLightPolicy.minimumSteps(current))
        assertEquals(BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2), current)
    }

    @Test fun equalCurrentColorsStillUseTwoMinimumZoneWritesAndReturnToTheArea() {
        val current = BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 3)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 1, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 1, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 3),
        ), BydAmbientLightPolicy.minimumSteps(current))
        val target = BydAmbientLightPolicy.minimumTarget(current)
        assertEquals(target, BydAmbientLightPolicy.minimumTarget(target))
        assertNotEquals(BydAmbientLightPolicy.restoreSteps(current), BydAmbientLightPolicy.minimumSteps(current))
    }

    @Test(expected = IllegalArgumentException::class)
    fun minimumCannotBypassSnapshotValidationWithUnsupportedZeroColor() {
        BydAmbientLightPolicy.minimumSteps(BydAmbientLightPolicy.Snapshot(0, 16, 6, 6, 3))
    }

    @Test fun seededBeginDoesNotRecaptureCurrentAppColorsAndOldBeginRemainsCompatible() {
        val seed = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2)
        val request = requireNotNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5 2", token))
        assertEquals(seed, BydAmbientLightWorkerProtocol.snapshotForBegin(request) { error("Must not read app-controlled current colors") })
        val old = requireNotNull(BydAmbientLightWorkerProtocol.parse("$token begin", token))
        assertEquals(seed, BydAmbientLightWorkerProtocol.snapshotForBegin(old) { seed })
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 0 5 2", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5 4", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("${token.dropLast(1)}x begin 7 23 2 5 2", token))
    }

    @Test fun any127CandidateStillNeedsIndependentVerification() {
        val fingerprint = "different-compatible-firmware"
        assertEquals("oem127", BydAmbientLightPolicy.observedColorProfile(fingerprint, 6))
        assertEquals("oem127", BydAmbientLightPolicy.observedColorProfile(fingerprint + ".other", 6))
        assertNull(BydAmbientLightPolicy.observedColorProfile(fingerprint, null))
        assertNull(BydAmbientLightPolicy.observedColorProfile(fingerprint, 5))
        assertEquals(1..31, BydAmbientLightPolicy.sessionColors(fingerprint, 6))
        assertEquals(1..127, BydAmbientLightPolicy.sessionColors(fingerprint, 6, verified = true))
        assertEquals(1..31, BydAmbientLightPolicy.sessionColors(fingerprint, 5))
        assertEquals(1..31, BydAmbientLightPolicy.sessionColors(fingerprint, null))
        assertEquals(1..31, BydAmbientLightPolicy.sessionColors("other", 6))
        assertFalse(BydAmbientLightPolicy.validApply(3, 127, 6))
        assertTrue(BydAmbientLightPolicy.validApply(3, 127, 6, 1..127))
        assertFalse(BydAmbientLightPolicy.canRestore(BydAmbientLightPolicy.parseSnapshot("127,127,6,6,3")))
        val wide = BydAmbientLightPolicy.parseSnapshot("57,32,6,6,3")
        assertEquals(BydAmbientLightPolicy.Snapshot(57, 32, 1, 1, 3), BydAmbientLightPolicy.minimumTarget(wide, 1..127))
        assertEquals(3, BydAmbientLightPolicy.minimumSteps(wide, 1..127).size)
        assertTrue(runCatching { BydAmbientLightPolicy.minimumSteps(wide) }.isFailure)
    }

    @Test fun rawSnapshotKeepsUnknownColorsButALegacySessionCannotBeginApplyOrRestoreThem() {
        val raw = BydAmbientLightPolicy.parseSnapshot("57,57,6,1,2")
        assertEquals(BydAmbientLightPolicy.Snapshot(57, 57, 6, 1, 2), raw)
        assertFalse(BydAmbientLightPolicy.canRestore(raw))
        assertEquals("color-unverified", BydAmbientLightPolicy.restorationFailure(raw))
        // The protocol carries 1..127; a 31-color session still refuses the seed.
        val seeded = requireNotNull(BydAmbientLightWorkerProtocol.parse("$token begin 57 57 6 1 2", token))
        assertTrue(runCatching { BydAmbientLightWorkerProtocol.snapshotForBegin(seeded) { raw } }.isFailure)
        assertEquals(raw, BydAmbientLightWorkerProtocol.snapshotForBegin(seeded, 1..127) { error("seeded") })
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 128 57 6 1 2", token))
        assertTrue(runCatching { BydAmbientLightWorkerProtocol.snapshotForBegin(BydAmbientLightWorkerProtocol.Request("begin")) { raw } }.isFailure)
        assertTrue(runCatching { BydAmbientLightPolicy.restoreSteps(raw) }.isFailure)
        assertTrue(runCatching { BydAmbientLightPolicy.minimumSteps(raw) }.isFailure)
        val known = BydAmbientLightPolicy.parseSnapshot("31,31,6,1,2")
        assertTrue(BydAmbientLightPolicy.canRestore(known))
        assertTrue(BydAmbientLightPolicy.restoreSteps(known).isNotEmpty())
    }

    @Test fun capabilityReadsKeepRawValuesAndUnknownSemanticsWithoutInferringAColorCount() {
        val reads = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val raw = mapOf("SET_IAL_COLOR_CONFIG" to 64, "SET_IAL_BRIGHTNESS_CONFIG" to -1, "SET_IAL_AREA_CONFIG" to Int.MAX_VALUE)
        assertEquals(raw, BydAmbientLightWorkerProtocol.readCapabilities({ reads += it; raw.getValue(it) }, logs::add))
        assertEquals(BydAmbientLightPolicy.configNames.toList(), reads)
        assertTrue(logs.all { it.contains("semantics=unknown") })
        assertEquals(3, BydAmbientLightWorkerProtocol.diagnostics(logs.joinToString("\n")).size)
        assertFalse(BydAmbientLightPolicy.canRestore(BydAmbientLightPolicy.parseSnapshot("57,57,6,1,2")))
    }

    @Test fun oneFailedConfigReadDoesNotHideOtherConfigsOrACompleteRawSnapshot() {
        val logs = mutableListOf<String>()
        val raw = BydAmbientLightWorkerProtocol.readCapabilities({
            if (it == "SET_IAL_BRIGHTNESS_CONFIG") throw SecurityException("private-data")
            57
        }, logs::add)
        assertEquals(2, raw.size)
        assertEquals(3, logs.size)
        assertTrue(logs.any { it.contains("read-SET_IAL_BRIGHTNESS_CONFIG result=failed error=SecurityException semantics=unknown") })
        assertFalse(logs.any { it.contains("private-data") })
        val state = "state=57,57,6,1,2"
        assertEquals(state, BydAmbientLightWorkerProtocol.reply(logs.joinToString("\n") + "\n$state"))
        assertEquals(57, BydAmbientLightPolicy.parseSnapshot(state.removePrefix("state=")).frontColor)
    }

    @Test fun writeTestRangeIsExplicitAndLeavesTheDefaultRangeUntouched() {
        val s = BydAmbientLightPolicy.parseSnapshot("127,127,6,6,3")
        assertFalse(BydAmbientLightPolicy.canRestore(s))
        assertTrue(BydAmbientLightPolicy.canRestore(s, BydAmbientLightPolicy.OEM127_COLORS))
        assertEquals(listOf(BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(127, 6, 3))),
            BydAmbientLightPolicy.restoreSteps(s, BydAmbientLightPolicy.OEM127_COLORS))
        assertTrue(runCatching { BydAmbientLightPolicy.restoreSteps(s) }.isFailure)
        assertEquals("color-unverified", BydAmbientLightPolicy.restorationFailure(
            BydAmbientLightPolicy.Snapshot(128, 1, 6, 6, 3), BydAmbientLightPolicy.OEM127_COLORS))
        assertEquals("area-unverified", BydAmbientLightPolicy.restorationFailure(
            BydAmbientLightPolicy.Snapshot(57, 57, 6, 6, 16), BydAmbientLightPolicy.OEM127_COLORS))
        assertEquals(57, BydAmbientLightPolicy.writeTestColor(s))
        assertEquals(104, BydAmbientLightPolicy.writeTestColor(s.copy(frontColor = 57)))
        assertEquals(90, BydAmbientLightPolicy.writeTestColor(s.copy(frontColor = 57, backColor = 104)))
        assertFalse(BydAmbientLightPolicy.validApply(1, 57, 6))
        assertFalse(BydAmbientLightPolicy.validSnapshot(s))
    }

    @Test fun writeTestOperationsAreBoundedAndConfirmationNeedsAChallenge() {
        assertEquals("test-begin", BydAmbientLightWorkerProtocol.parse("$token test-begin", token)?.op)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token test-apply", token))
        for (area in 1..3) assertEquals(listOf(area), BydAmbientLightWorkerProtocol.parse("$token test-apply $area", token)?.args)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token test-apply 4", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token verify-confirm yes", token))
        assertEquals(token, BydAmbientLightWorkerProtocol.parse("$token verify-confirm $token", token)?.confirmation)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token test-apply 1 57 6", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token test-begin 127 127 6 6 3", token))
    }

    private fun ByDProtocolLong(token: String) = BydAmbientLightWorkerProtocol.parse("$token ${"x".repeat(600)}", token)
}

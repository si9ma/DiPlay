package com.shilapi.xcertplay.hud

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AmbientVerificationTest {
    private val owner = "a".repeat(32)
    private fun environment(fingerprint: String = "any-fw", config: List<Int> = listOf(3, 2, 6), contract: String = "interface-v1-$owner") =
        AmbientVerificationStore.environment(fingerprint, config, contract)
    private fun directory() = File(RuntimeEnvironment.getApplication().cacheDir, "verification-${UUID.randomUUID()}")
    private val original = "127,32,6,4,3"

    @Test fun readOnlyIdentificationAndOldPreferencesNeverAuthorise() {
        val store = AmbientVerificationStore(directory(), owner, secure = false)
        assertFalse(store.status(environment()))
        assertEquals(1..31, BydAmbientLightPolicy.sessionColors("any-fw", 6))
        RuntimeEnvironment.getApplication().getSharedPreferences("ambient_profile", 0).edit()
            .putString("fingerprint", android.os.Build.FINGERPRINT).putString("palette", "OEM127").commit()
        assertFalse(store.status(environment()))
    }
    @Test fun dirtyOrOnlyRestoredStateCannotAuthoriseUntilMatchingHumanConfirmation() {
        val dir = directory()
        val store = AmbientVerificationStore(dir, owner, secure = false)
        val env = environment()
        store.begin(env, original, false)
        assertFalse(store.status(env))
        assertFalse(store.confirm(env, owner, original))
        val challenge = requireNotNull(store.settled(env, original, false, true))
        assertFalse(store.status(env))
        assertFalse(store.confirm(env, "b".repeat(32), original))
        assertFalse(store.confirm(env, challenge, "57,32,6,4,3"))
        val reopened = AmbientVerificationStore(dir, owner, secure = false)
        assertTrue(reopened.confirm(env, challenge, original))
        assertTrue(reopened.status(env))
        assertFalse(reopened.confirm(env, challenge, original))
    }
    @Test fun expiryAndEachEnvironmentChangeRequireNewVerification() {
        val store = AmbientVerificationStore(directory(), owner, secure = false)
        val env = environment()
        var challenge = requireNotNull(store.settled(env, original, false, true))
        assertFalse(store.confirm(env, challenge, original, store.read()!!.expires + 1))
        challenge = requireNotNull(store.settled(env, original, false, true))
        assertTrue(store.confirm(env, challenge, original))
        assertFalse(store.status(environment(fingerprint = "updated-fw")))
        assertFalse(store.status(env))
        for (different in listOf(environment(config = listOf(8, 2, 6)), environment(config = listOf(3, 4, 6)),
            environment(config = listOf(3, 2, 5)), environment(contract = "interface-v2"), environment(contract = "interface-v1-new-install"))) {
            challenge = requireNotNull(store.settled(env, original, false, true))
            assertTrue(store.confirm(env, challenge, original))
            assertFalse(store.status(different))
        }
    }
    @Test fun verifiedAuthorisationSurvivesAnInterruptedSession() {
        val dir = directory()
        val store = AmbientVerificationStore(dir, owner, secure = false)
        val env = environment()
        val challenge = requireNotNull(store.settled(env, original, false, true))
        assertTrue(store.confirm(env, challenge, original))
        store.begin(env, original, true)
        val other = AmbientVerificationStore(dir, "b".repeat(32), secure = false)
        assertFalse(other.status(environment(contract = "new-install")))
        assertEquals(original, other.read()?.original)
        assertEquals(true, other.read()?.dirty)
        // A verified session stays authorised even while dirty: the next begin re-reads the
        // car's current state as its baseline instead of requiring a manual restore.
        assertTrue(other.status(env))
        store.settled(env, original, false, false)
        assertFalse(store.status(env))
    }
    @Test fun damagedAndOversizedEvidenceFailClosed() {
        for (value in listOf("not json", "x".repeat(4097), "{\"version\":999}")) {
            val dir = directory().apply { mkdirs() }
            File(dir, "state.json").writeText(value)
            val store = AmbientVerificationStore(dir, owner, secure = false)
            assertTrue(runCatching { store.status(environment()) }.isFailure)
        }
    }
    @Test fun installationIdentityIsPersistentOutsideSettingsBackup() {
        val app = RuntimeEnvironment.getApplication()
        val first = AmbientInstallation.id(app)
        assertTrue(BydAmbientLightWorkerProtocol.validToken(first))
        app.getSharedPreferences("ambient_profile", 0).edit().clear().commit()
        assertEquals(first, AmbientInstallation.id(app))
        assertTrue(File(app.noBackupFilesDir, "ambient-installation-id").isFile)
    }
}

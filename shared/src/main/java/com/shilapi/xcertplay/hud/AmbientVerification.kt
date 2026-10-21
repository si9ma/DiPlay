package com.shilapi.xcertplay.hud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Installation identity is deliberately outside Android and application-settings backups. */
object AmbientInstallation {
    @Synchronized fun id(context: Context): String {
        val file = File(context.applicationContext.noBackupFilesDir, "ambient-installation-id")
        val atomic = AtomicFile(file)
        if (file.exists()) return atomic.openRead().use { it.bufferedReader().readText() }.also {
            require(BydAmbientLightWorkerProtocol.validToken(it)) { "installation-id-invalid" }
        }
        val value = UUID.randomUUID().toString().replace("-", "")
        val stream = atomic.startWrite()
        try { stream.write(value.toByteArray()); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        return value
    }
}

/** Worker-owned evidence; a UI preference or imported backup never authorises writes. */
internal class AmbientVerificationStore(private val directory: File, owner: String,
    private val secure: Boolean = true) {
    companion object {
        const val VERSION = 1
        const val CONFIRM_MILLIS = 300_000L
        fun environment(fingerprint: String, configs: List<Int>, contract: String): String {
            require(fingerprint.isNotBlank() && configs.size == 3 && contract.isNotBlank())
            return MessageDigest.getInstance("SHA-256").digest(
                "$VERSION|$fingerprint|${configs.joinToString(",")}|$contract".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
    data class Evidence(val environment: String, val verified: Boolean = false,
        val original: String? = null, val dirty: Boolean = false,
        val challenge: String? = null, val expires: Long = 0) {
        // Verification is a one-time gate: once verified, an unrestored write (dirty) must not
        // re-block control. The next session re-reads the car's current state as its new baseline.
        fun authorised(environment: String) = this.environment == environment && verified && challenge == null
    }
    private val file: File
    init {
        require(BydAmbientLightWorkerProtocol.validToken(owner))
        file = File(directory, "state.json")
    }
    private fun access() {
        if (!directory.exists()) {
            check(directory.mkdirs()) { "verification-storage" }
            if (secure) Os.chmod(directory.path, 448)
        }
        if (secure) {
            val stat = Os.lstat(directory.path)
            check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == android.os.Process.myUid() &&
                stat.st_mode and 63 == 0) { "verification-storage-permission" }
            for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
                if (candidate.exists() || runCatching { Os.lstat(candidate.path) }.isSuccess) {
                    val item = Os.lstat(candidate.path)
                    check(OsConstants.S_ISREG(item.st_mode) && item.st_uid == android.os.Process.myUid() &&
                        item.st_mode and 63 == 0) { "verification-file-permission" }
                }
            }
        }
    }
    fun read(): Evidence? {
        access()
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { input ->
            val bytes = ByteArray(4097)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            check(size <= 4096) { "verification-data" }
            val json = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
            check(json.getInt("version") == VERSION) { "verification-version" }
            val env = json.getString("environment")
            check(env.matches(Regex("[0-9a-f]{64}"))) { "verification-environment" }
            val original = if (json.isNull("original")) null else json.getString("original")
            original?.let { check(BydAmbientLightPolicy.canRestore(BydAmbientLightPolicy.parseSnapshot(it), 1..127)) }
            val challenge = if (json.isNull("challenge")) null else json.getString("challenge")
            check(challenge == null || BydAmbientLightWorkerProtocol.validToken(challenge))
            Evidence(env, json.getBoolean("verified"), original, json.getBoolean("dirty"), challenge, json.getLong("expires"))
                .also { check(!it.dirty || it.original != null) }
        }
    }
    fun write(evidence: Evidence) {
        access()
        val json = JSONObject().put("version", VERSION).put("environment", evidence.environment)
            .put("verified", evidence.verified).put("original", evidence.original ?: JSONObject.NULL)
            .put("dirty", evidence.dirty).put("challenge", evidence.challenge ?: JSONObject.NULL).put("expires", evidence.expires)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            if (secure) Os.fchmod(stream.fd, 384)
            stream.write(json.toString().toByteArray())
            atomic.finishWrite(stream)
            check(read() == evidence) { "verification-persist" }
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    fun status(environment: String): Boolean {
        val evidence = read() ?: return false
        if (evidence.environment != environment) {
            if (!evidence.dirty) revoke(environment)
            return false
        }
        return evidence.authorised(environment)
    }
    fun begin(environment: String, original: String, verified: Boolean) {
        write(Evidence(environment, verified, original, dirty = true))
    }
    fun settled(environment: String, original: String, verified: Boolean, awaitConfirmation: Boolean): String? {
        val challenge = if (awaitConfirmation) UUID.randomUUID().toString().replace("-", "") else null
        write(Evidence(environment, verified && !awaitConfirmation, original, false, challenge,
            if (awaitConfirmation) System.currentTimeMillis() + CONFIRM_MILLIS else 0))
        return challenge
    }
    fun confirm(environment: String, challenge: String, originalNow: String, now: Long = System.currentTimeMillis()): Boolean {
        val old = read() ?: return false
        if (old.environment != environment) {
            if (!old.dirty) revoke(environment)
            return false
        }
        if (old.dirty || old.challenge != challenge ||
            now !in (old.expires - CONFIRM_MILLIS)..old.expires || old.original != originalNow) return false
        write(Evidence(environment, verified = true))
        return true
    }
    fun revoke(environment: String) { write(Evidence(environment)) }
}

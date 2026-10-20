package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Parked compatibility verification: three bounded zone writes, complete restoration and a separate human
 * confirmation committed by the worker. Palette discovery never enables lamp control.
 */
object BydAmbientWriteTest {
    enum class Result {
        PASSED, BUSY, NOT_127_PROFILE, UNSUPPORTED_STATE, WRITE_REJECTED, WRITE_NOT_READ_BACK,
        RESTORE_FAILED, ADB_NOT_APPROVED, ADB_UNREACHABLE, ADB_UNSUPPORTED, WORKER_FAILED, RECOVERY_REQUIRED,
    }
    data class Outcome(val result: Result, val color: Int? = null, val original: String? = null,
        val applied: String? = null, val lastRead: String? = null, val restored: String? = null,
        val confirmation: String? = null) {
        /** A write may have happened and was not confirmed undone: the driver must restore it by hand. */
        val needsManualRestore: Boolean get() = result == Result.RESTORE_FAILED || result == Result.RECOVERY_REQUIRED
    }

    const val HOLD_MILLIS = 8_000L
    private const val KEEPALIVE_MILLIS = 3_000L
    private val state = Regex("-?[0-9]{1,11}(?:,-?[0-9]{1,11}){4}")
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ambient-write-test").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "ambient-write-test-deadline").apply { isDaemon = true } }

    fun run(context: Context, log: (String) -> Unit): CompletableFuture<Outcome> {
        val app = context.applicationContext
        val result = CompletableFuture<Outcome>()
        if (com.shilapi.xcertplay.media.AmbientMusicSettings.load(app).enabled || !running.compareAndSet(false, true))
            return result.apply { complete(Outcome(Result.BUSY)) }
        executor.execute {
            val outcome = runCatching { overAdb(app, log) }.getOrElse { Outcome(Result.RESTORE_FAILED) }
            // Software palette discovery is not permission to control the lamps.
            if (outcome.result == Result.PASSED && com.shilapi.xcertplay.media.AmbientColorProfile.record(app, android.os.Build.FINGERPRINT, 6)) {
                com.shilapi.xcertplay.media.AmbientMusicController.settingsChanged(app)
            }
            running.set(false)
            result.complete(outcome)
        }
        return result
    }

    private fun overAdb(context: Context, log: (String) -> Unit): Outcome {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            // Bounds a stalled link; the worker's own lease restores the lamp if this side disappears.
            val deadline = deadlines.schedule({ adb.cancelPendingOperations() }, 60, TimeUnit.SECONDS)
            try {
                when (adb.connect(mayAsk = false)) {
                    LocalAdb.Access.READY -> Unit
                    LocalAdb.Access.NOT_APPROVED -> return Outcome(Result.ADB_NOT_APPROVED)
                    LocalAdb.Access.UNSUPPORTED -> return Outcome(Result.ADB_UNSUPPORTED)
                    LocalAdb.Access.UNREACHABLE -> return Outcome(Result.ADB_UNREACHABLE)
                }
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
                val stream = adb.openShell("CLASSPATH=$apk app_process /system/bin ${BydAmbientLightTool::class.java.name} --stdin $token ${AmbientInstallation.id(context)}")
                    ?: return Outcome(Result.WORKER_FAILED)
                return stream.use { shell ->
                    execute({ command, timeout ->
                        val output = shell.exchangeBounded("$token $command", timeout) { response ->
                            BydAmbientLightWorkerProtocol.diagnostics(response).forEach { log(it) }
                        }
                        BydAmbientLightWorkerProtocol.reply(output)
                    }, { Thread.sleep(it) }, log)
                }
            } finally { deadline.cancel(false) }
        }
    }

    /** The test sequence over [exchange], which returns the worker's reply line or null on a broken link. */
    internal fun execute(exchange: (String, Int) -> String?, sleep: (Long) -> Unit, log: (String) -> Unit): Outcome {
        log("stage=write-test result=start step=ping")
        when (val ready = exchange("ping", 10_000)) {
            "ready" -> Unit
            "error worker-busy" -> return Outcome(Result.BUSY).also { log("stage=write-test result=busy step=ping") }
            else -> return Outcome(Result.WORKER_FAILED).also {
                log("stage=write-test result=failed step=ping reason=${replyKind(ready)}")
            }
        }
        val begun = exchange("test-begin", 5_000)
        val original = begun?.removePrefix("test-begun=")?.takeIf { begun.startsWith("test-begun=") && state.matches(it) }
        if (original == null) {
            val result = when {
                begun?.contains("verification-recovery-required:") == true -> Result.RECOVERY_REQUIRED
                begun?.contains("profile-not-127") == true -> Result.NOT_127_PROFILE
                begun?.contains("-unverified") == true -> Result.UNSUPPORTED_STATE
                else -> Result.WORKER_FAILED
            }
            val recovery = begun?.let { Regex("verification-recovery-required:(${state.pattern})").find(it)?.groupValues?.get(1) }
            log("stage=write-test result=failed step=begin outcome=${result.name}" + (recovery?.let { " original=$it" } ?: ""))
            return Outcome(result, original = recovery)
        }
        log("stage=write-test result=ok step=begin original=$original")
        val snapshot = runCatching { BydAmbientLightPolicy.parseSnapshot(original) }.getOrNull()
        if (snapshot == null || !BydAmbientLightPolicy.canRestore(snapshot, 1..127)) return Outcome(Result.UNSUPPORTED_STATE)
        val expectedColor = BydAmbientLightPolicy.writeTestColor(snapshot)
        var appliedState: String? = null
        for (area in 1..3) {
            val applied = exchange("test-apply $area", 5_000)
            val match = applied?.let { Regex("test-applied=([0-9]{1,3}):(${state.pattern})").matchEntire(it) }
            if (match == null) {
                val reply = applied.orEmpty()
                val lastRead = Regex("readback-timeout:(${state.pattern})").find(reply)?.groupValues?.get(1)
                val result = when {
                    applied == null || reply.endsWith(" restore-failed") -> Result.RESTORE_FAILED
                    reply.contains("set-result-") -> Result.WRITE_REJECTED
                    lastRead != null -> Result.WRITE_NOT_READ_BACK
                    else -> Result.WORKER_FAILED
                }
                log("stage=write-test result=failed step=apply outcome=${result.name} original=$original" +
                    (lastRead?.let { " lastRead=$it" } ?: ""))
                return Outcome(result, original = original, lastRead = lastRead)
            }
            appliedState = match.groupValues[2]
            if (match.groupValues[1].toInt() != expectedColor) {
                exchange("stop", 10_000)
                return Outcome(Result.RESTORE_FAILED, original = original)
            }
            log("stage=write-test result=ok step=apply area=$area testColor=$expectedColor applied=$appliedState")
            var held = 0L
            while (held < HOLD_MILLIS) {
                val wait = minOf(KEEPALIVE_MILLIS, HOLD_MILLIS - held)
                sleep(wait); held += wait
                if (held < HOLD_MILLIS && exchange("ping", 5_000) != "ready") {
                    exchange("stop", 10_000)
                    return Outcome(Result.RESTORE_FAILED, original = original)
                }
            }
        }
        val stopped = exchange("stop", 10_000)
        val match = stopped?.let { Regex("restored=(${state.pattern}):([0-9a-f]{32})").matchEntire(it) }
        val restored = match?.groupValues?.get(1)
        if (restored != original) {
            log("stage=write-test result=failed step=restore outcome=${Result.RESTORE_FAILED.name} original=$original" +
                (restored?.let { " restored=$it" } ?: ""))
            return Outcome(Result.RESTORE_FAILED, expectedColor, original, appliedState, restored = restored)
        }
        log("stage=write-test result=ok step=restore outcome=${Result.PASSED.name} original=$original restored=$restored")
        return Outcome(Result.PASSED, expectedColor, original, appliedState, restored = restored, confirmation = requireNotNull(match).groupValues[2])
    }

    /** Called only after a passed test and the driver's explicit observation confirmation. */
    fun confirm(context: Context, outcome: Outcome): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val challenge = outcome.confirmation
        if (outcome.result != Result.PASSED || challenge == null || !BydAmbientLightWorkerProtocol.validToken(challenge) ||
            !running.compareAndSet(false, true)) return result.apply { complete(false) }
        val app = context.applicationContext
        executor.execute {
            val accepted = runCatching {
                LocalAdb(AdbKeys.load(app)).use { adb ->
                    val deadline = deadlines.schedule({ adb.cancelPendingOperations() }, 15, TimeUnit.SECONDS)
                    try {
                        if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) false
                        else {
                            val token = UUID.randomUUID().toString().replace("-", "")
                            val apk = "'" + app.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
                            val shell = adb.openShell("CLASSPATH=$apk app_process /system/bin ${BydAmbientLightTool::class.java.name} --stdin $token ${AmbientInstallation.id(app)}")
                            shell?.use {
                                BydAmbientLightWorkerProtocol.reply(it.exchangeBounded("$token verify-confirm $challenge", 5000)) == "verified"
                            } == true
                        }
                    } finally { deadline.cancel(false) }
                }
            }.getOrDefault(false)
            running.set(false)
            result.complete(accepted)
        }
        return result
    }

    private fun replyKind(reply: String?) = when {
        reply == null -> "no-reply"
        reply == "error init" -> "worker-init"
        else -> "unexpected-reply"
    }
}

package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One parked, user-confirmed write test on the observed OEM 127-color firmware: save the state, write one known
 * color to the front row, hold it briefly, restore and read back. It never enables lamp control.
 */
object BydAmbientWriteTest {
    enum class Result {
        PASSED, BUSY, NOT_127_PROFILE, UNSUPPORTED_STATE, WRITE_REJECTED, WRITE_NOT_READ_BACK,
        RESTORE_FAILED, ADB_NOT_APPROVED, ADB_UNREACHABLE, ADB_UNSUPPORTED, WORKER_FAILED,
    }
    data class Outcome(val result: Result, val color: Int? = null, val original: String? = null,
        val applied: String? = null, val lastRead: String? = null, val restored: String? = null) {
        /** A write may have happened and was not confirmed undone: the driver must restore it by hand. */
        val needsManualRestore: Boolean get() = result == Result.RESTORE_FAILED
    }

    const val HOLD_MILLIS = 8_000L
    private const val KEEPALIVE_MILLIS = 3_000L
    private val state = Regex("-?[0-9]{1,11}(,-?[0-9]{1,11}){4}")
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ambient-write-test").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "ambient-write-test-deadline").apply { isDaemon = true } }

    fun run(context: Context, log: (String) -> Unit): CompletableFuture<Outcome> {
        val app = context.applicationContext
        val result = CompletableFuture<Outcome>()
        if (!running.compareAndSet(false, true)) return result.apply { complete(Outcome(Result.BUSY)) }
        executor.execute {
            val outcome = runCatching { overAdb(app, log) }.getOrElse { Outcome(Result.WORKER_FAILED) }
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
                val stream = adb.openShell("CLASSPATH=$apk app_process /system/bin ${BydAmbientLightTool::class.java.name} --stdin $token")
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
                begun?.contains("profile-not-127") == true -> Result.NOT_127_PROFILE
                begun?.contains("-unverified") == true -> Result.UNSUPPORTED_STATE
                else -> Result.WORKER_FAILED
            }
            log("stage=write-test result=failed step=begin outcome=${result.name}")
            return Outcome(result)
        }
        log("stage=write-test result=ok step=begin original=$original")
        val applied = exchange("test-apply", 5_000)
        val match = applied?.let { Regex("test-applied=([0-9]{1,3}):(${state.pattern})").matchEntire(it) }
        if (match == null) {
            // The worker restores before replying with an error; an unconfirmed restore is reported as such.
            val reply = applied.orEmpty()
            val restoreFailed = applied == null || reply.endsWith(" restore-failed")
            val lastRead = Regex("readback-timeout:(${state.pattern})").find(reply)?.groupValues?.get(1)
            val result = when {
                restoreFailed -> Result.RESTORE_FAILED
                reply.contains("set-result-") -> Result.WRITE_REJECTED
                lastRead != null -> Result.WRITE_NOT_READ_BACK
                else -> Result.WORKER_FAILED
            }
            log("stage=write-test result=failed step=apply outcome=${result.name} original=$original" +
                (lastRead?.let { " lastRead=$it" } ?: ""))
            return Outcome(result, original = original, lastRead = lastRead)
        }
        val color = match.groupValues[1].toInt()
        val appliedState = match.groupValues[2]
        log("stage=write-test result=ok step=apply testColor=$color applied=$appliedState")
        var held = 0L
        while (held < HOLD_MILLIS) {
            val wait = minOf(KEEPALIVE_MILLIS, HOLD_MILLIS - held)
            sleep(wait); held += wait
            if (held < HOLD_MILLIS && exchange("ping", 5_000) != "ready") break
        }
        val stopped = exchange("stop", 10_000)
        val restored = stopped?.removePrefix("restored=")?.takeIf { stopped.startsWith("restored=") && state.matches(it) }
        if (restored != original) {
            log("stage=write-test result=failed step=restore outcome=${Result.RESTORE_FAILED.name} original=$original" +
                (restored?.let { " restored=$it" } ?: ""))
            return Outcome(Result.RESTORE_FAILED, color, original, appliedState, restored = restored)
        }
        log("stage=write-test result=ok step=restore outcome=${Result.PASSED.name} original=$original restored=$restored")
        return Outcome(Result.PASSED, color, original, appliedState, restored = restored)
    }

    private fun replyKind(reply: String?) = when {
        reply == null -> "no-reply"
        reply == "error init" -> "worker-init"
        else -> "unexpected-reply"
    }
}
package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.media.Oem127Palette
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** User-initiated inspection. A successful read is never a lamp-control support decision. */
object BydAmbientSdkProbe {
    data class Outcome(val details: String, val complete: Boolean, val busy: Boolean)
    private const val PREFS = "ambient_sdk_probe"
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ambient-sdk-probe").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "ambient-sdk-deadline").apply { isDaemon = true } }
    val isRunning: Boolean get() = running.get()

    fun check(context: Context): CompletableFuture<Outcome> = start(context) { read(context.applicationContext) }

    internal fun start(context: Context, execute: () -> String?): CompletableFuture<Outcome> {
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(
            outcome(listOf(BydAmbientSdkProtocol.Entry("lock", "busy"))))
        val app = context.applicationContext
        val result = CompletableFuture<Outcome>()
        executor.execute {
            try {
                val entries = try { BydAmbientSdkProtocol.parse(execute()) }
                    catch (error: Throwable) { listOf(BydAmbientSdkProtocol.Entry("transport", BydAmbientSdkProtocol.failure(error))) }
                val safe = entries.ifEmpty { listOf(BydAmbientSdkProtocol.Entry("transport", "incomplete")) }
                val value = outcome(safe)
                save(app, value.details)
                running.set(false)
                result.complete(value)
            } catch (error: Throwable) {
                running.set(false)
                result.completeExceptionally(error)
            }
        }
        return result
    }

    private fun outcome(entries: List<BydAmbientSdkProtocol.Entry>) = Outcome(
        entries.joinToString("\n") { it.line() }, BydAmbientSdkProtocol.complete(entries), entries.any { it.status == "busy" })

    private fun read(context: Context): String? {
        LocalAdb(AdbKeys.load(context), trace = {}).use { adb ->
            val expired = AtomicBoolean(false)
            val deadline = deadlines.schedule({ expired.set(true); adb.cancelPendingOperations() }, 18, TimeUnit.SECONDS)
            try {
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) return BydAmbientSdkProtocol.Entry("transport", when (access) {
                    LocalAdb.Access.NOT_APPROVED -> "not_approved"
                    LocalAdb.Access.UNSUPPORTED -> "unsupported"
                    else -> if (expired.get()) "timeout" else "unreachable"
                }).line()
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
                val command = "CLASSPATH=$apk app_process /system/bin ${BydAmbientSdkTool::class.java.name} --stdin $token"
                val stream = adb.openShell(command) ?: return BydAmbientSdkProtocol.Entry("transport", "failed").line()
                return stream.use {
                    var partial = ""
                    val output = it.exchangeBounded("$token read-sdk", 10_000) { text -> partial = text }
                    output ?: partial + "\n" + BydAmbientSdkProtocol.Entry("transport", if (expired.get()) "timeout" else "incomplete").line()
                }
            } finally { deadline.cancel(false) }
        }
    }

    @Synchronized private fun save(context: Context, details: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (i in 4 downTo 1) {
            edit.putString("run$i", prefs.getString("run${i - 1}", ""))
            edit.putLong("time$i", prefs.getLong("time${i - 1}", 0))
        }
        check(edit.putString("run0", details).putLong("time0", System.currentTimeMillis()).commit()) { "probe-save-failed" }
    }

    /** Cached reports only. Does not connect ADB, load SDK code, or change the existing support result. */
    @Synchronized fun report(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return buildString {
            append("OEM ambient SDK (read-only; control authorization unchanged)\n")
            append("palette=${Oem127Palette.SOURCE} entries=${Oem127Palette.SIZE} referenceOnly=true\n")
            var found = false
            for (i in 0..4) {
                val entries = BydAmbientSdkProtocol.parse(prefs.getString("run$i", null))
                if (entries.isEmpty()) continue
                found = true
                append("time=${prefs.getLong("time$i", 0)}\n")
                entries.forEach { append(it.line()).append('\n') }
            }
            if (!found) append("not_run\n")
        }.trimEnd()
    }
}

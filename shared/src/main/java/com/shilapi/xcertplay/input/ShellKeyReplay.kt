package com.shilapi.xcertplay.input

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One authorised, bounded Android key pair; never an arbitrary command or a physical-device impersonation. */
object ShellKeyReplay {
    data class Request(val token: String, val code: Int, val scan: Int, val downTime: Long, val expires: Long,
        val display: Int = 0) {
        fun valid(): Boolean = token.matches(Regex("[a-f0-9]{32}")) && safeCode(code) && scan in 0..65535 &&
            downTime > 0 && expires > downTime && expires - downTime in 1..1500 && display == 0
        fun line() = "$token $code $scan $downTime $expires $display"
    }
    val outcomes = setOf("injected", "rejected", "partial", "permission_denied", "unsupported", "expired",
        "cancelled", "timeout", "not_approved", "unreachable", "unknown", "busy")
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "diplay-key-replay").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-key-deadline").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    class Operation internal constructor() {
        val result = CompletableFuture<String>()
        internal val cancelled = AtomicBoolean(false)
        @Volatile internal var adb: LocalAdb? = null
        fun cancel() { cancelled.set(true); adb?.cancelPendingOperations() }
    }
    fun safeCode(code: Int): Boolean = code in 1..65535 && code !in setOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH) &&
        !KeyEvent(KeyEvent.ACTION_DOWN, code).isPrintingKey

    fun parseRequest(line: String?, token: String): Request? = runCatching {
        require(line != null && line.length <= 200)
        val p = line.split(' ')
        require(p.size == 6 && p[0] == token)
        Request(p[0], p[1].toInt(), p[2].toInt(), p[3].toLong(), p[4].toLong(), p[5].toInt()).takeIf { it.valid() }
    }.getOrNull()
    fun parseResult(text: String?, token: String): String {
        if (text == null || text.length > 2048) return "unknown"
        val lines = text.lineSequence().filter { it.startsWith("key-replay ") }.toList()
        if (lines.size != 1) return "unknown"
        val p = lines.single().split(' ')
        return if (p.size == 3 && p[1] == token && p[2] in outcomes) p[2] else "unknown"
    }
    fun command(apk: String, token: String): String {
        require(token.matches(Regex("[a-f0-9]{32}")) && apk.isNotBlank() && apk.none { it == '\n' || it == '\r' || it == '\u0000' })
        val quoted = "'" + apk.replace("'", "'\\''") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${ShellKeyReplayMain::class.java.name} --stdin $token"
    }
    fun submit(context: Context, code: Int, scan: Int, display: Int, current: () -> Boolean,
        prepare: () -> Request): Operation {
        val op = Operation()
        if (!safeCode(code) || scan !in 0..65535 || display != 0) { op.result.complete("unsupported"); return op }
        if (!busy.compareAndSet(false, true)) { op.result.complete("busy"); return op }
        val app = context.applicationContext
        executor.execute {
            var outcome = "unknown"
            val expired = AtomicBoolean(false)
            val timer = deadlines.schedule({ expired.set(true); op.cancel(); op.result.complete("timeout") }, 6000, TimeUnit.MILLISECONDS)
            try {
                if (op.cancelled.get() || !current()) outcome = "cancelled"
                else LocalAdb(AdbKeys.load(app), trace = {}).use { adb ->
                    op.adb = adb
                    if (op.cancelled.get()) { outcome = "cancelled"; return@use }
                    val access = adb.connect(mayAsk = false)
                    if (access != LocalAdb.Access.READY) {
                        outcome = when (access) {
                            LocalAdb.Access.NOT_APPROVED -> "not_approved"
                            LocalAdb.Access.UNSUPPORTED -> "unsupported"
                            else -> "unreachable"
                        }
                        return@use
                    }
                    val token = java.util.UUID.randomUUID().toString().replace("-", "")
                    val shell = adb.openShell(command(app.applicationInfo.sourceDir, token)) ?: return@use
                    shell.use exchange@ {
                        if (op.cancelled.get() || !current()) { outcome = "cancelled"; return@exchange }
                        val ready = prepare()
                        val request = ready.copy(token = token)
                        if (!request.valid() || request.code != code || request.scan != scan || request.display != display ||
                            op.cancelled.get() || !current()) { outcome = "cancelled"; return@exchange }
                        outcome = parseResult(it.exchangeBounded(request.line(), 3000), token)
                    }
                }
            } catch (_: Exception) {
                outcome = if (op.cancelled.get()) "cancelled" else "unknown"
            } finally {
                timer.cancel(false)
                op.adb = null
                busy.set(false)
                op.result.complete(if (expired.get()) "timeout" else outcome)
            }
        }
        return op
    }
}

/** Runs as the existing ADB shell UID. Framework permission checks remain authoritative. */
object ShellKeyReplayMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin" || !args[1].matches(Regex("[a-f0-9]{32}"))) return
        val token = args[1]
        val output = System.out
        var outcome = "unsupported"
        val release = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.schedule({
            val pending = release.getAndSet(null)
            if (pending != null) {
                val emergency = Thread({ pending() }, "key-release").apply { isDaemon = true; start() }
                runCatching { emergency.join(100) }
            }
            output.println("key-replay $token timeout"); output.println("done"); output.flush()
            Runtime.getRuntime().halt(0)
        }, 5, TimeUnit.SECONDS)
        try {
            if (android.os.Process.myUid() != 2000) return
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
            val request = ShellKeyReplay.parseRequest(readLine(), token) ?: return
            val am = Class.forName("android.app.ActivityManager")
            val user = am.getDeclaredMethod("getCurrentUser").apply { isAccessible = true }.invoke(null) as Int
            if (user < 0) return
            val type = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
                .getOrElse { Class.forName("android.hardware.input.InputManager") }
            val manager = type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val inject = type.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            val now = SystemClock.uptimeMillis()
            if (now < request.downTime || now > request.expires) { outcome = "expired"; return }
            val down = KeyEvent(request.downTime, now, KeyEvent.ACTION_DOWN, request.code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, request.scan, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(down, 0)
            }
            if (SystemClock.uptimeMillis() > request.expires ||
                am.getDeclaredMethod("getCurrentUser").apply { isAccessible = true }.invoke(null) != user) {
                outcome = "expired"; return
            }
            outcome = injectPair(down, { SystemClock.uptimeMillis() },
                { event, mode -> inject.invoke(manager, event, mode) == true }, release)
        } catch (error: Throwable) {
            val cause = generateSequence(error) { it.cause }.take(4).last()
            outcome = if (cause is SecurityException) "permission_denied" else "unsupported"
        } finally {
            watchdog.shutdownNow()
            output.println("key-replay $token $outcome"); output.println("done"); output.flush()
            Runtime.getRuntime().halt(0)
        }
    }
    internal fun injectPair(down: KeyEvent, now: () -> Long, inject: (KeyEvent, Int) -> Boolean,
        release: java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>): String {
        fun up(mode: Int): Boolean = inject(
            KeyEvent.changeAction(KeyEvent.changeTimeRepeat(down, now(), 0), KeyEvent.ACTION_UP), mode)
        release.set { runCatching { up(0) }; Unit }
        var accepted = false
        var released = false
        try {
            accepted = inject(down, 1)
        } finally {
            if (release.getAndSet(null) != null) released = runCatching { up(1) }.getOrDefault(false)
        }
        return if (accepted && released) "injected" else if (accepted || !released) "partial" else "rejected"
    }
    private fun readLine(): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= 200) {
            val value = System.`in`.read()
            if (value < 0) return null
            if (value == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            bytes.write(value)
        }
        return null
    }
}

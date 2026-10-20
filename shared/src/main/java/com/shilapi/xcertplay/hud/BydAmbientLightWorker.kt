package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import java.io.InputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Token-bound app_process bridge. It accepts only the explicit interior-lamp operations below. */
internal object BydAmbientLightWorkerProtocol {
    private const val MAX_LINE_BYTES = 512
    private val tokenPattern = Regex("[0-9a-f]{32}")
    data class Request(val op: String, val args: List<Int> = emptyList())
    fun validToken(token: String) = tokenPattern.matches(token)

    /**
     * The worker's one-line reply in [output]. On BYD firmware with its hotfix framework (DiLink 5 on a 2024 Tang),
     * app_process prints "HotFixRoot: … -> patchMetaFile:…" on stdout as the worker sets up BYD's SDK, before
     * the first reply; only the last line is the worker's.
     */
    fun reply(output: String?): String? = output?.lines()?.lastOrNull { it.isNotBlank() }?.trim()

    private const val DIAGNOSTIC_PREFIX = "ambient-diag "
    private val diagnosticStages = setOf("worker-lock", "system-context", "shell-context", "manager-class",
        "manager-instance", "method-getInt", "method-setIntArray", "method-setInt", "init", "lamp-support", "lamp-state") +
        (BydAmbientLightPolicy.getNames + BydAmbientLightPolicy.setNames).map { "feature-$it" } +
        BydAmbientLightPolicy.getNames.map { "read-$it" }
    private val diagnosticPattern = Regex("stage=([A-Za-z0-9_-]+) result=(ok|failed|busy|invalid)( value=-?[0-9]{1,11})?( error=[A-Za-z0-9_$>]{1,240})?( semantics=unknown)?( reason=(color|brightness|area)-unverified)?")

    // Exception messages and arbitrary SDK stdout can contain paths or identifiers; keep only types.
    fun errorTypes(error: Throwable): String {
        val types = mutableListOf<String>()
        var current: Throwable? = error
        repeat(5) {
            val next = current ?: return@repeat
            types += next.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_$]"), "_").take(40).ifEmpty { "Throwable" }
            current = next.cause?.takeUnless { it === next }
        }
        return types.joinToString(">")
    }

    fun diagnostic(stage: String, result: String, value: Int? = null, error: Throwable? = null,
        unknownSemantics: Boolean = false, reason: String? = null): String =
        "$DIAGNOSTIC_PREFIX" + "stage=$stage result=$result" +
            (value?.let { " value=$it" } ?: "") + (error?.let { " error=${errorTypes(it)}" } ?: "") +
            (if (unknownSemantics) " semantics=unknown" else "") + (reason?.let { " reason=$it" } ?: "")

    /** Optional CONFIG reads never hide the independently readable current lamp state. */
    fun readCapabilities(read: (String) -> Int, emit: (String) -> Unit): Map<String, Int> = buildMap {
        for (name in BydAmbientLightPolicy.configNames) {
            val value = runCatching { read(name) }
            value.getOrNull()?.let { put(name, it) }
            emit(diagnostic("read-$name", if (value.isSuccess) "ok" else "failed",
                value = value.getOrNull(), error = value.exceptionOrNull(), unknownSemantics = true))
        }
    }

    fun diagnostics(output: String?): List<String> = output.orEmpty().lineSequence().map { it.trim() }
        .filter { it.startsWith(DIAGNOSTIC_PREFIX) }.map { it.removePrefix(DIAGNOSTIC_PREFIX) }
        .filter { line -> diagnosticPattern.matchEntire(line)?.groupValues?.get(1) in diagnosticStages }
        .take(32).toList()

    fun replySummary(output: String?): String {
        val reply = reply(output) ?: return "no-reply"
        return when {
            reply == "ready" || reply == "error init" || reply == "error worker-busy" -> reply
            reply.matches(Regex("state=-?[0-9]{1,11}(,-?[0-9]{1,11}){4}")) -> reply
            reply.startsWith("error=") -> "worker-error"
            else -> "unexpected-reply"
        }
    }

    fun parse(line: String, token: String): Request? {
        if (!validToken(token) || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val f = line.split(' ')
        if (f.firstOrNull() != token) return null
        if (f.size == 2 && f[1] in setOf("ping", "read", "begin", "stop", "minimum-stop", "close", "test-begin", "test-apply")) return Request(f[1])
        // Syntactic bounds only; each session then enforces its own head unit's range.
        if (f.size == 7 && f[1] == "begin") {
            val a = f.drop(2).map { it.toIntOrNull() ?: return null }
            val seed = BydAmbientLightPolicy.Snapshot(a[0], a[1], a[2], a[3], a[4])
            return if (BydAmbientLightPolicy.canRestore(seed, BydAmbientLightPolicy.OEM127_COLORS)) Request("begin", a) else null
        }
        if (f.size == 5 && f[1] == "apply") {
            val a = f.drop(2).map { it.toIntOrNull() ?: return null }
            return if (BydAmbientLightPolicy.validApply(a[0], a[1], a[2], BydAmbientLightPolicy.OEM127_COLORS)) Request("apply", a) else null
        }
        return null
    }

    fun snapshotForBegin(request: Request, colors: IntRange = BydAmbientLightPolicy.LEGACY_COLORS,
        readCurrent: () -> BydAmbientLightPolicy.Snapshot): BydAmbientLightPolicy.Snapshot {
        require(request.op == "begin")
        val a = request.args
        val snapshot = if (a.isEmpty()) readCurrent() else {
            require(a.size == 5)
            BydAmbientLightPolicy.Snapshot(a[0], a[1], a[2], a[3], a[4])
        }
        require(BydAmbientLightPolicy.canRestore(snapshot, colors))
        return snapshot
    }

    fun readLine(input: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        repeat(MAX_LINE_BYTES + 1) {
            val c = input.read()
            if (c < 0) return null
            if (c == 10) return b.toString("UTF-8")
            b.write(c)
        }
        return null
    }
}

/** Called by app_process under the authorized shell identity, not from the app's restricted UID. */
object BydAmbientLightTool {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin") return
        val token = args[1].takeIf(BydAmbientLightWorkerProtocol::validToken) ?: return
        // A blocked old Binder/restore must exclude new writes even beyond its 15-second lease.
        val lockResult = runCatching { AmbientWorkerOwnerLock.tryAcquire(File("/data/local/tmp/diplay-ambient-worker.lock")) }
        val ownership = lockResult.getOrNull()
        println(BydAmbientLightWorkerProtocol.diagnostic("worker-lock",
            if (ownership != null) "ok" else if (lockResult.isFailure) "failed" else "busy", error = lockResult.exceptionOrNull()))
        if (ownership == null) {
            println("error worker-busy"); println("done"); System.out.flush()
            return // Do not initialize the SDK before acquiring ownership.
        }
        try { runOwned(token) } finally { ownership.close() }
    }

    private fun runOwned(token: String) {
        val lease = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val snapshotRef = AtomicReference<BydAmbientLightPolicy.Snapshot?>(null)
        // Set once per session from this head unit's fingerprint and COLOR_CONFIG; every restore path honours it.
        val sessionColors = AtomicReference(BydAmbientLightPolicy.LEGACY_COLORS)
        var testSession = false
        var testApplied = false
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val device = runCatching { AmbientDevice() }.onFailure {
            println(BydAmbientLightWorkerProtocol.diagnostic("init", "failed", error = it))
        }.getOrNull()
        val restoreLock = Any()
        watchdog.scheduleAtFixedRate({
            if (System.nanoTime() - lease.get() > TimeUnit.MILLISECONDS.toNanos(BydAmbientLightPolicy.LEASE_MILLIS)) {
                synchronized(restoreLock) {
                    if (System.nanoTime() - lease.get() > TimeUnit.MILLISECONDS.toNanos(BydAmbientLightPolicy.LEASE_MILLIS)) {
                        val saved = snapshotRef.get()
                        if (saved == null) System.exit(0)
                        if (saved != null && device != null) {
                            val ok = runCatching { restore(device, saved, sessionColors.get()); true }.getOrDefault(false)
                            if (ok) {
                                snapshotRef.compareAndSet(saved, null)
                                System.exit(0)
                            } else {
                                System.err.println("ambient lease restore failed; retaining snapshot and retrying")
                            }
                        }
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
        try {
            if (device == null) { println("error init"); println("done"); System.out.flush(); return }
            var lastSetNanos = 0L
            var stop = false
            while (!stop) {
                val line = BydAmbientLightWorkerProtocol.readLine(System.`in`) ?: break
                val req = BydAmbientLightWorkerProtocol.parse(line, token) ?: break
                lease.set(System.nanoTime())
                val out = synchronized(restoreLock) { try {
                    when (req.op) {
                        "ping" -> { device.readCapabilities(); "ready" }
                        "read" -> "state=${device.read(diagnostics = true).encode()}"
                        "begin" -> {
                            if (snapshotRef.get() != null) "error already-begun"
                            else {
                                val colors = device.sessionColors()
                                val s = BydAmbientLightWorkerProtocol.snapshotForBegin(req, colors, device::read)
                                sessionColors.set(colors)
                                snapshotRef.compareAndSet(null, s); "begun=${s.encode()}"
                            }
                        }
                        "apply" -> {
                            val s = snapshotRef.get() ?: throw IllegalStateException("begin-required")
                            check(!testSession) { "test-session" }
                            val colors = sessionColors.get()
                            val (area, color, bright) = req.args
                            check(BydAmbientLightPolicy.validApply(area, color, bright, colors)) { "color-out-of-range" }
                            if (!BydAmbientLightPolicy.canRestore(s, colors)) throw IllegalStateException("unsupported-state")
                            val wait = TimeUnit.NANOSECONDS.toMillis(200_000_000L - (System.nanoTime() - lastSetNanos)).coerceAtLeast(0)
                            if (wait > 0) Thread.sleep(wait)
                            val result = device.set(color, bright, area, colors)
                            lastSetNanos = System.nanoTime()
                            if (result != 0) throw IllegalStateException("set-result-$result")
                            awaitReadback(device) { r -> when (area) {
                                1 -> r.frontColor == color && r.frontBrightness == bright && r.area == area
                                2 -> r.backColor == color && r.backBrightness == bright && r.area == area
                                else -> r.frontColor == color && r.backColor == color && r.frontBrightness == bright && r.backBrightness == bright && r.area == area
                            } }
                            "applied=${device.read().encode()}"
                        }
                        "test-begin" -> {
                            check(snapshotRef.get() == null) { "already-begun" }
                            device.requireWriteTestProfile()
                            val s = device.read()
                            BydAmbientLightPolicy.restorationFailure(s, BydAmbientLightPolicy.OEM127_COLORS)?.let { throw IllegalStateException(it) }
                            sessionColors.set(BydAmbientLightPolicy.OEM127_COLORS)
                            testSession = true
                            snapshotRef.set(s)
                            "test-begun=${s.encode()}"
                        }
                        "test-apply" -> {
                            val s = snapshotRef.get() ?: throw IllegalStateException("begin-required")
                            check(testSession) { "test-session-required" }
                            check(!testApplied) { "test-already-applied" }
                            testApplied = true
                            // One write of one color to the front row; brightness and area stay as saved.
                            val color = BydAmbientLightPolicy.writeTestColor(s)
                            val result = device.set(color, s.frontBrightness, 1, BydAmbientLightPolicy.OEM127_COLORS)
                            lastSetNanos = System.nanoTime()
                            if (result != 0) throw IllegalStateException("set-result-$result")
                            val read = awaitReadback(device) { r -> r.frontColor == color && r.frontBrightness == s.frontBrightness }
                            "test-applied=$color:${read.encode()}"
                        }
                        "minimum-stop" -> {
                            check(snapshotRef.get() != null) { "begin-required" }
                            check(!testSession) { "test-session" }
                            val minimum = holdAtMinimum(device, sessionColors.get())
                            // Commit only after both zones and the original current area read back.
                            snapshotRef.set(null)
                            stop = true
                            "minimum=${minimum.encode()}"
                        }
                        "stop" -> {
                            val s = snapshotRef.get()
                            val restored = if (s == null) "no-snapshot" else { restore(device, s, sessionColors.get()); "restored=${device.read().encode()}" }
                            snapshotRef.set(null); stop = true; restored
                        }
                        "close" -> { snapshotRef.get()?.let { restore(device, it, sessionColors.get()) }; snapshotRef.set(null); stop = true; "closed-restored" }
                        else -> throw IllegalArgumentException("operation")
                    }
                } catch (t: Throwable) {
                    val s = snapshotRef.get()
                    val restored = s == null || runCatching { restore(device, s, sessionColors.get()); true }.getOrDefault(false)
                    if (restored) snapshotRef.set(null)
                    stop = true
                    "error=${safeError(t)}" + if (restored) "" else " restore-failed"
                } }
                println(out); println("done"); System.out.flush()
            }
        } catch (_: Throwable) {
            // The finally path attempts the one saved restoration on stream failure/EOF.
        } finally {
            synchronized(restoreLock) { snapshotRef.get()?.let { s ->
                val ok = runCatching { device?.let { restore(it, s, sessionColors.get()) }; true }.getOrDefault(false)
                if (ok) snapshotRef.compareAndSet(s, null)
                else System.err.println("ambient restore failed; retry did not confirm readback")
            } }
            watchdog.shutdownNow()
            System.exit(0)
        }
    }

    private fun holdAtMinimum(device: AmbientDevice, colors: IntRange): BydAmbientLightPolicy.Snapshot {
        val current = device.read()
        val target = BydAmbientLightPolicy.minimumTarget(current, colors)
        for (step in BydAmbientLightPolicy.minimumSteps(current, colors)) {
            val values = step.values
            if (values != null) {
                check(device.set(values.color, values.rawBrightness, values.area, colors) == 0) { "minimum-set-failed" }
                awaitReadback(device) { read -> when (values.area) {
                    1 -> read.frontColor == target.frontColor && read.frontBrightness == 1 &&
                        read.backColor == current.backColor && read.area == 1
                    2 -> read.frontColor == target.frontColor && read.frontBrightness == 1 &&
                        read.backColor == target.backColor && read.backBrightness == 1 && read.area == 2
                    else -> false
                } }
            } else {
                check(device.setArea(requireNotNull(step.areaOnly)) == 0) { "minimum-area-failed" }
                awaitReadback(device) { it == target }
            }
        }
        return awaitReadback(device) { it == target }
    }

    private fun restore(device: AmbientDevice, snapshot: BydAmbientLightPolicy.Snapshot,
        colors: IntRange = BydAmbientLightPolicy.LEGACY_COLORS) {
        val steps = BydAmbientLightPolicy.restoreSteps(snapshot, colors)
        for (step in steps) {
            val v = step.values
            if (v != null) check(device.set(v.color, v.rawBrightness, v.area, colors) == 0) { "restore-set-failed" }
            else check(device.setArea(step.areaOnly!!) == 0) { "restore-area-failed" }
            awaitReadback(device) { r -> when {
                v?.area == 1 -> r.frontColor == v.color && r.frontBrightness == v.rawBrightness && r.area == 1
                v?.area == 2 -> r.backColor == v.color && r.backBrightness == v.rawBrightness && r.area == 2
                v?.area == 3 -> r.frontColor == snapshot.frontColor && r.backColor == snapshot.backColor &&
                    r.frontBrightness == snapshot.frontBrightness && r.backBrightness == snapshot.backBrightness && r.area == 3
                step.areaOnly != null -> r.area == step.areaOnly
                else -> false
            }
            }
        }
        awaitReadback(device) { it == snapshot }
    }

    private fun awaitReadback(device: AmbientDevice, predicate: (BydAmbientLightPolicy.Snapshot) -> Boolean): BydAmbientLightPolicy.Snapshot {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        var last: BydAmbientLightPolicy.Snapshot? = null
        while (System.nanoTime() < until) {
            last = device.read()
            if (predicate(last)) return last
            Thread.sleep(BydAmbientLightPolicy.READBACK_POLL_MILLIS)
        }
        error("readback-timeout:${last?.encode()}")
    }

    private fun safeError(t: Throwable): String = ((t.cause ?: t).javaClass.simpleName + ":" + (t.cause ?: t).message.orEmpty())
        .replace(Regex("[\\r\\n ]+"), " ").take(120)
}

private fun BydAmbientLightPolicy.Snapshot.encode() = "${frontColor},${backColor},${frontBrightness},${backBrightness},${area}"

private fun <T> ambientDiagnosticStep(stage: String, enabled: Boolean = true, block: () -> T): T {
    if (!enabled) return block()
    return try {
        block().also {
            println(BydAmbientLightWorkerProtocol.diagnostic(stage, "ok", value = it as? Int))
            System.out.flush()
        }
    } catch (error: Throwable) {
        println(BydAmbientLightWorkerProtocol.diagnostic(stage, "failed", error = error))
        System.out.flush()
        throw error
    }
}

@SuppressLint("PrivateApi")
private class AmbientDevice {
    private val manager: Any
    private val getInt: java.lang.reflect.Method
    private val setIntArray: java.lang.reflect.Method
    private val setInt: java.lang.reflect.Method
    private val ids: Map<String, Int>

    init {
        runCatching { android.os.Looper.prepareMainLooper() }
        val systemContext = ambientDiagnosticStep("system-context") {
            val thread = Class.forName("android.app.ActivityThread")
            val main = thread.getMethod("systemMain").invoke(null)
            thread.getMethod("getSystemContext").invoke(main) as Context
        }
        val context = ambientDiagnosticStep("shell-context") { systemContext.createPackageContext("com.android.shell", 0) }
        val managerClass = ambientDiagnosticStep("manager-class") { Class.forName("android.hardware.bydauto.BYDAutoDeviceManager") }
        manager = ambientDiagnosticStep("manager-instance") {
            requireNotNull(managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)) { "manager-null" }
        }
        getInt = ambientDiagnosticStep("method-getInt") {
            managerClass.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        setIntArray = ambientDiagnosticStep("method-setIntArray") {
            managerClass.methods.firstOrNull { it.name == "setIntArray" && it.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType, IntArray::class.java, IntArray::class.java)) }
                ?: throw NoSuchMethodException("BYDAutoDeviceManager.setIntArray(int,int[],int[])")
        }
        setInt = ambientDiagnosticStep("method-setInt") {
            managerClass.getMethod("setInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        ids = (BydAmbientLightPolicy.getNames - BydAmbientLightPolicy.configNames).associateWith(::featureId) +
            BydAmbientLightPolicy.setNames.associateWith(::featureId) +
            BydAmbientLightPolicy.configNames.mapNotNull { name -> runCatching { name to featureId(name) }.getOrNull() }.toMap()
    }

    private fun featureId(name: String): Int = ambientDiagnosticStep("feature-$name") {
        val cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$Setting")
        cls.getField(name).getInt(null)
    }
    private fun get(name: String): Int = (getInt.invoke(manager, BydAmbientLightPolicy.TYPE, ids.getValue(name)) as Number).toInt()
    fun read(): BydAmbientLightPolicy.Snapshot = read(diagnostics = false)

    /** This head unit's verified range; an unreadable COLOR_CONFIG keeps the 31-color range. */
    fun sessionColors(): IntRange = BydAmbientLightPolicy.sessionColors(android.os.Build.FINGERPRINT,
        runCatching { get("SET_IAL_COLOR_CONFIG") }.getOrNull())

    fun requireWriteTestProfile() {
        val config = runCatching { get("SET_IAL_COLOR_CONFIG") }.getOrNull()
        check(BydAmbientLightPolicy.observedColorProfile(android.os.Build.FINGERPRINT, config) != null) { "profile-not-127" }
    }

    fun readCapabilities() {
        BydAmbientLightWorkerProtocol.readCapabilities(::get) { println(it) }
        System.out.flush()
    }

    fun read(diagnostics: Boolean): BydAmbientLightPolicy.Snapshot {
        if (diagnostics) readCapabilities()
        fun readValue(name: String) = ambientDiagnosticStep("read-$name", diagnostics) { get(name) }
        ambientDiagnosticStep("lamp-support", diagnostics) {
            check(readValue("SET_HAS_INTERIOR_ATMOSPHERE_LAMP") == 1) { "lamp-not-supported" }
        }
        val s = BydAmbientLightPolicy.Snapshot(readValue("SET_IAL_FRONT_COLOR"), readValue("SET_IAL_BACK_COLOR"),
            readValue("SET_IAL_FRONT_BRIGHTNESS"), readValue("SET_IAL_BACK_BRIGHTNESS"), readValue("SET_INTERIOR_ATMOSPHERE_LAMP_AREA"))
        if (diagnostics) {
            val failure = BydAmbientLightPolicy.restorationFailure(s, sessionColors())
            println(BydAmbientLightWorkerProtocol.diagnostic("lamp-state", if (failure == null) "ok" else "invalid", reason = failure))
            System.out.flush()
        }
        return s
    }
    fun set(color: Int, brightness: Int, area: Int, colors: IntRange = BydAmbientLightPolicy.LEGACY_COLORS): Int {
        require(area in 1..3 && color in colors && brightness in 1..6)
        val method = setIntArray
        return (method.invoke(manager, BydAmbientLightPolicy.TYPE,
            intArrayOf(ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET"), ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET"), ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET")),
            intArrayOf(color, brightness, area)) as Number).toInt()
    }
    fun setArea(area: Int): Int {
        require(area in 1..3)
        return (setInt.invoke(manager, BydAmbientLightPolicy.TYPE, ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET"), area) as Number).toInt()
    }
}

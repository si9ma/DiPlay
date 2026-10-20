package com.shilapi.xcertplay.media

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.shilapi.xcertplay.hud.BydAmbientLightClient
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import com.shilapi.xcertplay.hud.BydAmbientLightWorkerProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The sole scheduler owns lamp I/O; audio writes only append small envelope metadata. */
object AmbientMusicController {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-ambient-music").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val activeSink = AtomicLong()
    private val activeRenderer = AtomicLong()
    private val phonePlayback = AmbientPhonePlaybackState()
    @Volatile private var artworkOwner: Any? = null
    private var albumColor: Int? = null
    @Volatile private var settings = AmbientMusicSettings.Values()
    private var palette = AmbientPalette.LEGACY31
    private var context: Context? = null
    private var envelope: AmbientMusicEnvelope? = null
    private var playback: (() -> Pair<Int, Boolean>)? = null
    private var allowed = false
    private var colors = AmbientMusicColorDetector()
    private var brightnessEnvelope = AmbientMusicBrightness(settings.speed)
    private var appliedCount = 0L
    private var lastLogMillis = 0L
    private var idleSinceMillis: Long? = null

    private val lampSession = AmbientLampSession(
        factory = {
            val real = BydAmbientLightClient(requireNotNull(context))
            object : AmbientLampSession.Client {
                override fun unhealthy(): Boolean {
                    val phase = real.status().phase
                    return phase == BydAmbientLightClient.Phase.FAILED || phase == BydAmbientLightClient.Phase.CLOSED
                }
                override fun begin(seed: BydAmbientLightPolicy.Snapshot?) = real.begin(seed?.let {
                    BydAmbientLightClient.State(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area)
                }).thenApply { BydAmbientLightPolicy.Snapshot(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area) }
                override fun apply(desired: Triple<Int, Int, Int>) = real.apply(desired.first, desired.second, desired.third).thenApply { Unit }
                override fun stop() = real.stop().thenApply { Unit }
                // stop's future completes only after closeTransport, including its failure path.
                override fun close() = real.close()
            }
        },
        dispatch = { executor.execute(it) },
        now = { android.os.SystemClock.uptimeMillis() },
        log = { Log.i("DiPlay-Ambient", it) },
        applied = { desired ->
            appliedCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogMillis >= 5_000) {
                lastLogMillis = now
                Log.i("DiPlay-Ambient", "applied=$appliedCount area=${desired.first} color=${desired.second} brightness=${desired.third} colorMode=${settings.colorMode} estimatedBpm=${colors.estimatedBpm?.toInt() ?: 0}")
            }
        },
    )

    // The 20 Hz lamp update, scheduled only while the feature is on; turning it off restores the lamps by itself.
    private var ticking: ScheduledFuture<*>? = null

    /** On the executor, after [settings] changed. */
    private fun updateTicking() {
        if (!settings.enabled) {
            ticking?.cancel(false)
            ticking = null
        } else if (ticking == null) {
            ticking = executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                Log.w("DiPlay-Ambient", "lamp update failed", it)
                lampSession.recover()
            } }, 50, 50, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun openSink(context: Context?): Long {
        if (context == null) return 0L
        val token = sequence.incrementAndGet()
        activeSink.set(token)
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != token) return@execute
            this.context = context.applicationContext
            palette = AmbientColorProfile.current(context)
            settings = AmbientMusicSettings.load(context, palette)
            resetColors()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled; envelope = null; playback = null
            idleSinceMillis = longIdle()
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
        return token
    }

    @Synchronized internal fun openRenderer(sink: Long, envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>): Long {
        if (sink == 0L || sink != activeSink.get()) return 0L
        val token = sequence.incrementAndGet()
        activeRenderer.set(token)
        executor.execute {
            if (activeSink.get() != sink || activeRenderer.get() != token) return@execute
            allowed = true
            this.envelope = envelope
            this.playback = playback
        }
        return token
    }

    internal fun wantsPcm(token: Long): Boolean = token != 0L && token == activeRenderer.get() && settings.enabled && settings.music

    /** Binds phone playback callbacks to the current CarPlay media owner. */
    @Synchronized fun claimPlaybackOwner(owner: Any) {
        phonePlayback.claim(owner)
        if (artworkOwner === owner) return
        artworkOwner = owner
        albumColorChanged(owner, null)
    }

    /** Already decoded artwork is sampled on the existing artwork worker, never on PCM writes. */
    fun albumColorChanged(owner: Any, color: Int?) {
        executor.execute {
            if (artworkOwner !== owner) return@execute
            val valid = color?.takeIf { it in palette.colors }
            if (albumColor == valid) return@execute
            albumColor = valid
            if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
        }
    }

    /** Stale callbacks from a replaced media owner are ignored. */
    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)

    @Synchronized fun releasePlaybackOwner(owner: Any): Boolean {
        if (artworkOwner === owner) {
            artworkOwner = null
            executor.execute {
                if (artworkOwner != null) return@execute
                albumColor = null
                if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
            }
        }
        return phonePlayback.release(owner)
    }

    private fun resetColors(preserveTiming: Boolean = false) {
        val colorsInUse = ambientColorPalette(settings.colorSource, settings.selectedColors, albumColor, palette)
        val initial = if (settings.colorSource == AmbientColorSource.ALBUM) albumColor ?: colorsInUse.first()
            else settings.color
        val random = if (settings.colorSource == AmbientColorSource.ALBUM && albumColor == null)
            ({ size: Int -> kotlin.random.Random.nextInt(size) }) else null
        if (preserveTiming) colors.updatePalette(initial, colorsInUse, random, palette.colors)
        else colors = AmbientMusicColorDetector(initial, colorsInUse, AmbientColorSpeed.STANDARD, palette.colors, random)
    }

    /** Checks existing authorization and lamp readback only; never asks for ADB or writes a lamp. */
    fun checkSupport(context: Context): CompletableFuture<Boolean> {
        val checkId = AmbientDiagnostics.beginCheck(context)
        val result = AmbientSupportResult(checkId)
        runAmbientSupportCheck(
            result = result,
            dispatch = { executor.execute(it) },
            ready = { lampSession.isReady() },
            factory = { log ->
                val client = BydAmbientLightClient(context.applicationContext, log)
                object : AmbientSupportClient {
                    override fun read() = client.read().thenApply {
                        val snapshot = BydAmbientLightPolicy.Snapshot(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area)
                        // The head unit's own range is judged from its COLOR_CONFIG in the check's details.
                        check(BydAmbientLightPolicy.canRestore(snapshot, BydAmbientLightPolicy.OEM127_COLORS)) { "unverified-restore-state" }
                        Unit
                    }
                    override fun stop() = client.stop().thenApply { Unit }
                    override fun close() = client.close()
                }
            },
            diagnostic = { AmbientDiagnostics.record(context, it, checkId) },
            fingerprint = android.os.Build.FINGERPRINT,
            observedConfig = { config ->
                if (AmbientColorProfile.record(context.applicationContext, android.os.Build.FINGERPRINT, config)) settingsChanged(context)
            },
        )
        return result
    }

    fun supportCheckId(result: CompletableFuture<Boolean>): String? = (result as? AmbientSupportResult)?.checkId

    fun supportCheckDetails(result: CompletableFuture<Boolean>): AmbientSupportDetails? = (result as? AmbientSupportResult)?.details

    fun settingsChanged(context: Context) {
        executor.execute {
            this.context = context.applicationContext
            palette = AmbientColorProfile.current(context)
            settings = AmbientMusicSettings.load(context, palette)
            resetColors()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
    }

    /** Silence that began before this session: the car's own lighting returns without a first lamp session. */
    private fun longIdle() = android.os.SystemClock.uptimeMillis() - AmbientMusicActivityPolicy.RESTORE_AFTER_MILLIS

    @Synchronized internal fun closeRenderer(token: Long) {
        if (token == 0L || !activeRenderer.compareAndSet(token, 0L)) return
        executor.execute {
            if (activeRenderer.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    @Synchronized fun closeSink(token: Long) {
        if (token == 0L || !activeSink.compareAndSet(token, 0L)) return
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    private fun tick() {
        val values = settings
        if (!allowed) return
        if (!values.enabled) { restoreOriginal(); return }
        if (context == null) return
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive) playback?.invoke() else null
        val playing = playbackState?.second == true && phonePlayback.current() != false
        if (playing) idleSinceMillis = null
        else if (idleSinceMillis == null) idleSinceMillis = nowMillis
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            brightness = values.brightness,
            rendererActive = rendererActive,
            playing = playing,
            idle = values.idle,
            idleMillis = idleSinceMillis?.let { nowMillis - it } ?: 0,
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreOriginal(); return }
        lampSession.setEnabled(true)
        val energy = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK) {
            val (head, headPlaying) = playbackState ?: return
            envelope?.playedWindow(head, headPlaying) ?: AmbientMusicEnvelope.Energy(0.0, 0.0)
        } else AmbientMusicEnvelope.Energy(0.0, 0.0)
        val rms = energy.rms
        val brightness = when (target) {
            AmbientMusicTarget.LOWEST -> 1
            AmbientMusicTarget.FOLLOW_PLAYBACK -> brightnessEnvelope.level(rms, nowMillis, values.brightness)
            else -> values.brightness
        }
        val area = if (target == AmbientMusicTarget.LOWEST) 3 else values.area
        val color = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK && values.colorCycle)
            colors.color(values.colorMode, nowMillis, rms, energy.bassRms) else if (values.colorSource == AmbientColorSource.ALBUM) albumColor ?: 1
            else values.selectedColors.firstOrNull() ?: values.color
        lampSession.update(Triple(area, color, brightness))
    }

    private fun restoreOriginal() { lampSession.setEnabled(false) }
}

/** Compatibility wrapper: callers may keep using the Boolean future and optionally inspect details. */
class AmbientSupportResult(val checkId: String, details: AmbientSupportDetails? = null) : CompletableFuture<Boolean>() {
    @Volatile var details: AmbientSupportDetails? = details
        internal set
}

data class AmbientSupportDetails(
    val reason: String,
    val stage: String,
    val rawState: String? = null,
    val rawColorConfig: Int? = null,
    val rawBrightnessConfig: Int? = null,
    val rawAreaConfig: Int? = null,
    val error: String? = null,
    val observedProfile: String? = null,
) {
    val frontColor: Int? get() = rawState?.split(',')?.getOrNull(0)?.toIntOrNull()
    val backColor: Int? get() = rawState?.split(',')?.getOrNull(1)?.toIntOrNull()

    fun diagnosticSummary(): String = AmbientDiagnostics.sanitize(buildString {
        val colors = if (observedProfile != null) BydAmbientLightPolicy.OEM127_COLORS else BydAmbientLightPolicy.LEGACY_COLORS
        append("failureStage=$stage reason=$reason semantics=unknown expectedColor=${colors.first}..${colors.last} expectedBrightness=1..6 expectedArea=1..3")
        rawState?.let { append(" rawState=$it") }
        rawColorConfig?.let { append(" rawColorConfig=$it") }
        observedProfile?.let { append(" observedProfile=$it") }
        rawBrightnessConfig?.let { append(" rawBrightnessConfig=$it") }
        rawAreaConfig?.let { append(" rawAreaConfig=$it") }
        error?.let { append(" error=$it") }
    })
}

private class AmbientSupportObservations {
    private val values = mutableMapOf<String, Int>()
    private var firstFailure: Map<String, String>? = null

    fun record(message: String) {
        val fields = AmbientDiagnostics.sanitize(message).split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val stage = fields["stage"] ?: return
        if (stage.startsWith("read-") || stage == "verification") {
            fields["value"]?.toIntOrNull()?.let { values[stage.removePrefix("read-")] = it }
        }
        val optional = BydAmbientLightPolicy.configNames.any { stage == "read-$it" || stage == "feature-$it" }
        if (!optional && firstFailure == null && fields["result"] in setOf("failed", "invalid", "busy", "NOT_APPROVED", "UNREACHABLE", "UNSUPPORTED")) {
            firstFailure = fields
        }
    }

    fun details(ok: Boolean, error: Throwable?, fingerprint: String?): AmbientSupportDetails {
        val names = listOf("SET_IAL_FRONT_COLOR", "SET_IAL_BACK_COLOR", "SET_IAL_FRONT_BRIGHTNESS",
            "SET_IAL_BACK_BRIGHTNESS", "SET_INTERIOR_ATMOSPHERE_LAMP_AREA")
        val raw = if (names.all { it in values }) names.joinToString(",") { values.getValue(it).toString() } else null
        val profile = BydAmbientLightPolicy.observedColorProfile(fingerprint, values["SET_IAL_COLOR_CONFIG"])
        val colors = if (profile != null) BydAmbientLightPolicy.OEM127_COLORS else BydAmbientLightPolicy.LEGACY_COLORS
        val invalid = raw?.let { BydAmbientLightPolicy.restorationFailure(BydAmbientLightPolicy.parseSnapshot(it), colors) }
        val failure = firstFailure
        val reason = invalid ?: if (profile != null && values["verification"] != 1) "profile-write-unverified" else if (ok) "none" else when {
            failure?.get("stage") == "lamp-support" -> "lamp-not-supported"
            failure?.get("stage") == "worker-lock" -> "worker-busy"
            failure?.get("result") == "NOT_APPROVED" -> "adb-not-approved"
            failure?.get("result") == "UNREACHABLE" -> "adb-unreachable"
            failure?.get("result") == "UNSUPPORTED" -> "adb-unsupported"
            else -> "check-failed"
        }
        return AmbientSupportDetails(reason, if (invalid != null) "lamp-state" else failure?.get("stage") ?: "check",
            raw, values["SET_IAL_COLOR_CONFIG"], values["SET_IAL_BRIGHTNESS_CONFIG"], values["SET_IAL_AREA_CONFIG"],
            failure?.get("error") ?: error?.let(BydAmbientLightWorkerProtocol::errorTypes), profile)
    }
}

internal interface AmbientSupportClient {
    fun read(): CompletableFuture<Unit>
    fun stop(): CompletableFuture<Unit>
    fun close()
}

internal fun runAmbientSupportCheck(
    result: CompletableFuture<Boolean>,
    dispatch: (() -> Unit) -> Unit,
    ready: () -> Boolean,
    factory: ((String) -> Unit) -> AmbientSupportClient,
    diagnostic: (String) -> Unit,
    fingerprint: String? = null,
    observedConfig: (Int) -> Unit = {},
) {
    val started = System.nanoTime()
    val observations = AmbientSupportObservations()
    fun log(message: String) {
        observations.record(message)
        runCatching { diagnostic(message) }
    }
    fun errorField(error: Throwable?) = error?.let { " error=${BydAmbientLightWorkerProtocol.errorTypes(it)}" }.orEmpty()
    fun finish(ok: Boolean, error: Throwable? = null, source: String = "read-only") {
        val details = observations.details(ok, error, fingerprint)
        val supported = ok && details.reason == "none"
        (result as? AmbientSupportResult)?.details = details
        // Before completing, so a caller sees the palette this read identified.
        details.rawColorConfig?.let { config -> runCatching { observedConfig(config) } }
        log("stage=check result=${if (supported) "ok" else "failed"} source=$source elapsedMs=${(System.nanoTime() - started) / 1_000_000} ${details.diagnosticSummary()}")
        result.complete(supported)
    }
    fun cleanup(client: AmbientSupportClient, supported: Boolean, readError: Throwable?) {
        fun close(stopError: Throwable?) {
            log("stage=cleanup result=${if (stopError == null) "ok" else "failed"}${errorField(stopError)}")
            val closeError = runCatching { client.close() }.exceptionOrNull()
            if (closeError != null) log("stage=close result=failed${errorField(closeError)}")
            finish(supported && stopError == null && closeError == null, readError ?: stopError ?: closeError)
        }
        log("stage=cleanup result=start")
        try { client.stop().whenComplete { _, error -> close(error) } }
        catch (error: Throwable) { close(error) }
    }
    try {
        dispatch {
            try {
                if (ready()) finish(true, source = "existing-session")
                else {
                    val client = factory(::log)
                    try {
                        client.read().whenComplete { _, error ->
                            log("stage=read result=${if (error == null) "ok" else "failed"}${errorField(error)}")
                            cleanup(client, error == null, error)
                        }
                    } catch (error: Throwable) {
                        log("stage=read result=failed${errorField(error)}")
                        cleanup(client, false, error)
                    }
                }
            } catch (error: Throwable) { finish(false, error) }
        }
    } catch (error: Throwable) { finish(false, error) }
}

/** Own-app, bounded diagnostics; unrelated ADB traffic and CarPlay lifecycle cannot evict them. */
object AmbientDiagnostics {
    private val stores = ConcurrentHashMap<Context, AmbientDiagnosticStore>()
    private val fields = Regex("([A-Za-z]+)=([^\\s]+)")
    private val stages = setOf("configuration-open", "enable-change", "save-request", "check-skipped", "save-result",
        "cancel", "dismiss", "check-request", "check-complete", "check-complete-dialog-closed", "check", "read", "cleanup",
        "close", "adb-key", "adb-connect", "worker-launch", "exchange", "worker-lock", "system-context", "shell-context",
        "manager-class", "manager-instance", "method-getInt", "method-setIntArray", "method-setInt", "init", "lamp-support", "lamp-state",
        "write-test", "write-test-confirm", "verification") +
        (BydAmbientLightPolicy.getNames + BydAmbientLightPolicy.setNames).map { "feature-$it" } +
        BydAmbientLightPolicy.getNames.map { "read-$it" }
    private val results = setOf("start", "ok", "failed", "busy", "invalid", "skipped", "changed", "closed", "READY", "NOT_APPROVED", "UNREACHABLE", "UNSUPPORTED")

    private fun store(context: Context): AmbientDiagnosticStore {
        val app = context.applicationContext
        return stores.getOrPut(app) { AmbientDiagnosticStore { File(app.noBackupFilesDir, "ambient-diagnostics/state.bin") } }
    }

    fun beginCheck(context: Context): String {
        val id = UUID.randomUUID().toString()
        record(context, "stage=check result=start mayAsk=false readOnly=true", id, startsCheck = true)
        return id
    }

    fun record(context: Context, message: String, checkId: String? = null, startsCheck: Boolean = false) {
        runCatching {
            val safe = sanitize(message)
            store(context).record(safe, checkId, startsCheck)
        }.onFailure { runCatching { Log.w("DiPlay-Ambient", "diagnostics unavailable error=${it.javaClass.simpleName}") } }
    }

    fun configuration(values: AmbientMusicSettings.Values): String =
        "enabled=${values.enabled} music=${values.music} colorMode=${values.colorMode} speed=${values.speed} " +
            "colorSource=${values.colorSource} colorCycle=${values.colorCycle} colors=${values.selectedColors.filter { it in 1..127 }.take(127).joinToString(",")} " +
            "color=${values.color.coerceIn(1, 127)} brightness=${values.brightness.coerceIn(0, 6)} area=${values.area.coerceIn(1, 3)} idle=${values.idle}"

    /** Ordered behind all previously submitted events. Call from the report's background worker. */
    fun report(context: Context): String {
        val saved = runCatching { configuration(AmbientMusicSettings.load(context)) }
            .getOrElse { "unavailable error=${BydAmbientLightWorkerProtocol.errorTypes(it)}" }
        val palette = runCatching { AmbientColorProfile.current(context).name }.getOrDefault("unavailable")
        val history = runCatching { store(context).reportAsync().get(5, TimeUnit.SECONDS) }
            .getOrElse {
                if (it is InterruptedException) Thread.currentThread().interrupt()
                "ambient diagnostics unavailable error=${BydAmbientLightWorkerProtocol.errorTypes(it)}"
            }
        return "saved $saved\npalette=$palette\n$history"
    }

    internal fun sanitize(message: String): String = fields.findAll(message.take(2048)).mapNotNull { match ->
        val (key, value) = match.destructured
        val valid = when (key) {
            "stage", "failureStage" -> value in stages
            "result" -> value in results
            "semantics" -> value == "unknown"
            "observedProfile" -> value == BydAmbientLightPolicy.OEM127_PROFILE || value == "oem127_read_only"
            "reason" -> value in setOf("none", "profile-write-unverified", "color-unverified", "brightness-unverified", "area-unverified",
                "lamp-not-supported", "worker-busy", "adb-not-approved", "adb-unreachable", "adb-unsupported", "check-failed")
            "rawState", "original", "applied", "restored", "lastRead" -> value.matches(Regex("-?[0-9]{1,11}(,-?[0-9]{1,11}){4}"))
            "step" -> value in setOf("ping", "begin", "apply", "restore")
            "outcome" -> com.shilapi.xcertplay.hud.BydAmbientWriteTest.Result.entries.any { it.name == value }
            "testColor" -> value.toIntOrNull()?.let { it in 1..127 } == true
            "seen" -> value in setOf("yes", "no", "unsure")
            "rawColorConfig", "rawBrightnessConfig", "rawAreaConfig" -> value.toIntOrNull() != null
            "enabled", "music", "colorCycle", "mayAsk", "readOnly" -> value == "true" || value == "false"
            "value", "color", "brightness", "area", "elapsedMs", "timeoutMs" -> value.matches(Regex("-?[0-9]{1,18}"))
            "colors" -> value.matches(Regex("[0-9]{1,3}(,[0-9]{1,3}){0,126}"))
            "colorMode" -> AmbientColorMode.entries.any { it.name == value }
            "speed" -> AmbientColorSpeed.entries.any { it.name == value }
            "colorSource" -> AmbientColorSource.entries.any { it.name == value }
            "idle" -> AmbientIdleBehavior.entries.any { it.name == value }
            "source" -> value in setOf("existing-session", "read-only", "disabled", "button", "back-or-outside")
            "op" -> value in setOf("ping", "read", "stop", "close")
            "reply" -> value in setOf("ready", "no-reply", "worker-error", "unexpected-reply", "error") ||
                value.matches(Regex("state=-?[0-9]{1,11}(,-?[0-9]{1,11}){4}"))
            "expectedSupport" -> value == "1"
            "expectedColor" -> value == "1..31" || value == "1..127"
            "expectedBrightness" -> value == "1..6"
            "expectedArea" -> value == "1..3"
            "error" -> value.matches(Regex("[A-Za-z0-9_$>]{1,240}"))
            else -> false
        }
        if (valid) "$key=$value" else null
    }.joinToString(" ").take(1536)
}

/** Shared queue orders writes and report barriers; a file lock also serializes separate app processes. */
internal class AmbientDiagnosticStore(private val file: () -> File) {
    private data class Event(val time: Long, val checkId: String, val text: String) {
        fun report() = "time=$time checkId=$checkId $text"
    }
    private data class State(val events: List<Event> = emptyList(), val lastCheck: Event? = null,
        val lastFailure: Event? = null)
    private var state = State()
    private var pending = emptyList<Pair<Event, Boolean>>()
    @Volatile private var storageFailure: String? = null

    fun record(message: String, checkId: String? = null, startsCheck: Boolean = false) {
        val event = Event(System.currentTimeMillis(), checkId?.takeIf { ID.matches(it) } ?: "none", AmbientDiagnostics.sanitize(message))
        submit {
            pending = (pending + (event to startsCheck)).takeLast(MAX_EVENTS)
            try {
                locked(write = true) { target ->
                    state = runCatching { read(target) }.getOrElse { failed("read", it); state }
                    state = mergePending(state)
                    write(target, state)
                    pending = emptyList()
                }
            } catch (error: Throwable) {
                state = mergePending(state)
                failed("write", error)
            }
        }
    }

    private fun mergePending(saved: State): State = pending.fold(saved) { current, (event, start) ->
        if (event in current.events) current else append(current, event, start)
    }

    fun reportAsync(): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        submit {
            try { locked(write = false) { state = mergePending(read(it)) } }
            catch (error: Throwable) { failed("read", error) }
            result.complete(buildString {
                appendLine("storage=${if (storageFailure == null) "ok" else "degraded"} lastStorageFailure=${storageFailure ?: "none"}")
                appendLine("lastCheck ${state.lastCheck?.report() ?: "none"}")
                appendLine("lastFailure ${state.lastFailure?.report() ?: "none"}")
                appendLine("recentEvents=${state.events.size} limit=$MAX_EVENTS oldestFirst=true")
                state.events.forEach { appendLine("AMBIENT_SUPPORT ${it.report()}") }
            })
        }.onFailure { result.complete("storage=degraded error=${it.javaClass.simpleName}") }
        return result
    }

    private fun append(old: State, event: Event, startsCheck: Boolean): State {
        val last = old.lastCheck
        val terminal = last?.text?.let { it.startsWith("stage=check result=ok") || it.startsWith("stage=check result=failed") } == true
        val latest = when {
            startsCheck && event.checkId != "none" -> event
            event.checkId != "none" && last?.checkId == event.checkId && !terminal -> event
            else -> last
        }
        val failed = Regex("(?:^| )result=(failed|invalid|busy|NOT_APPROVED|UNREACHABLE|UNSUPPORTED)(?: |$)").containsMatchIn(event.text)
        val failure = when {
            startsCheck && event.checkId != "none" -> null
            event.checkId != "none" && last?.checkId == event.checkId && !terminal &&
                event.text.startsWith("stage=check result=failed") && event.text.contains(" reason=") -> event
            event.checkId != "none" && last?.checkId == event.checkId && !terminal && failed && old.lastFailure == null -> event
            else -> old.lastFailure
        }
        return State((old.events + event).takeLast(MAX_EVENTS), latest, failure)
    }

    private fun submit(block: () -> Unit): Result<Unit> = runCatching { queue.execute(block) }
        .onFailure { failed("queue", it) }

    private fun failed(operation: String, error: Throwable) {
        val failure = "$operation:${BydAmbientLightWorkerProtocol.errorTypes(error)}"
        if (storageFailure == null || operation == "write" || operation == "queue") storageFailure = failure
        runCatching { Log.w("DiPlay-Ambient", "diagnostic-storage=$failure") }
    }

    private fun <T> locked(write: Boolean, block: (File) -> T): T {
        val target = file()
        val lock = File(target.parentFile, "state.lock")
        if (!write && !lock.exists()) return block(target)
        if (write) check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        return RandomAccessFile(lock, "rw").use { handle -> handle.channel.lock().use { block(target) } }
    }

    private fun read(target: File): State {
        val committed = File(target.path + ".bak").takeIf { it.exists() } ?: target
        val input = try { committed.inputStream() } catch (error: FileNotFoundException) {
            if (!committed.exists()) return State()
            throw error
        }
        return DataInputStream(input).use { data ->
            check(input.channel.size() <= MAX_BYTES)
            val version = data.readInt().also { check(it in 1..2) }
            fun event(): Event {
                val time = data.readLong()
                val id = data.readUTF().also { check(it == "none" || ID.matches(it)) }
                val text = data.readUTF().also { check(it.length <= 1536 && AmbientDiagnostics.sanitize(it) == it) }
                return Event(time, id, text)
            }
            val last = if (data.readBoolean()) event() else null
            val failure = if (version >= 2 && data.readBoolean()) event() else null
            val size = data.readInt().also { check(it in 0..MAX_EVENTS) }
            State(List(size) { event() }, last, failure)
        }
    }

    private fun write(target: File, state: State) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeInt(2)
            fun event(event: Event) { data.writeLong(event.time); data.writeUTF(event.checkId); data.writeUTF(event.text) }
            data.writeBoolean(state.lastCheck != null)
            state.lastCheck?.let { event(it) }
            data.writeBoolean(state.lastFailure != null)
            state.lastFailure?.let { event(it) }
            data.writeInt(state.events.size)
            state.events.forEach { event(it) }
            data.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            check(read(target) == state)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val MAX_EVENTS = 96
        private const val MAX_BYTES = 192 * 1024L
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val queue = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "ambient-diagnostics").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    }
}

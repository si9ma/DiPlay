package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydVehicleEvent
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import com.shilapi.xcertplay.hud.BydVehicleSignalGroup
import com.shilapi.xcertplay.media.NavigationPlayback
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URI
import java.util.UUID

internal enum class ShortcutAction {
    WAIT, URI, PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SIRI, SELECT, BACK, LEFT, RIGHT, HOME,
    SHOW_WINDOW, HIDE_WINDOW,
    SHOW_CLUSTER_MUSIC, HIDE_CLUSTER_MUSIC, SHOW_CLUSTER_MAP, HIDE_CLUSTER_MAP, KEY_PRESS, TAP, PINCH_IN, PINCH_OUT, ORIGINAL_CLICK,
    SHOW_CENTER_BATTERY_CARD, HIDE_CENTER_BATTERY_CARD, SHOW_CLUSTER_BATTERY_CARD, HIDE_CLUSTER_BATTERY_CARD,
    // 外接设备(CarMeter)：导航地图 → 导航简洁信息 → 不显示导航 循环。新动作只能追加在末尾(上面有序号区间)
    CYCLE_CARMETER_CONTENT;

    val legacyWindow get() = this == SHOW_WINDOW || this == HIDE_WINDOW
    val clusterWindow get() = this in SHOW_CLUSTER_MUSIC..HIDE_CLUSTER_MAP
    val batteryCard get() = this in SHOW_CENTER_BATTERY_CARD..HIDE_CLUSTER_BATTERY_CARD
    val pinch get() = this == PINCH_IN || this == PINCH_OUT
}
/** VEHICLE follows a read-only car signal (see [BydVehicleMonitor]); it needs the adb shell. */
internal enum class ShortcutEvent { CONNECTED, KEY, VEHICLE }
internal data class ShortcutStep(val action: ShortcutAction, val waitMs: Long = 0, val url: String = "",
    val keyCode: Int = -1, val scanCode: Int = -1, val tapX: Double = -1.0, val tapY: Double = -1.0,
    val zoomCenterX: Double = 0.5, val zoomCenterY: Double = 0.5,
    val pinchScale: Double = CarPlayController.DEFAULT_SHORTCUT_PINCH_SCALE,
    /**
     * TAP and pinch only. True: the point ([tapX]/[tapY], or the pinch's [zoomCenterX]/[zoomCenterY]) is a
     * fraction of DiPlay's CarPlay window as the driver sees it, mapped to the CarPlay canvas when replayed,
     * so the same spot stays the same spot whatever canvas or view area the session uses. False (steps made
     * by earlier versions): a fraction of the CarPlay canvas, sent as it is.
     */
    val inWindow: Boolean = false)
/** Runs when its trigger fires and every one of [conditions] holds. Several rules may share a trigger; all that hold run. */
internal data class ShortcutRule(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val enabled: Boolean = false,
    val event: ShortcutEvent = ShortcutEvent.CONNECTED, val keyCode: Int = 0, val scanCode: Int = 0,
    val steps: List<ShortcutStep> = emptyList(), val clickCount: Int = 1,
    val vehicleEvent: BydVehicleEvent? = null,
    val conditions: List<ShortcutCondition> = emptyList(),
)

/** Versioned, bounded local data. Invalid entries never become executable partial rules. */
internal object ShortcutCodec {
    const val VERSION = 4
    const val MAX_STEPS = 32
    /** The whole rule set; as large as a settings backup may be. */
    const val MAX_BYTES = 512 * 1024
    /**
     * A TAP's or pinch's "space": the window DiPlay shows CarPlay in. Absent means the CarPlay canvas. An app
     * that predates it ignores the key and replays the point on the canvas, as it did before.
     */
    private const val SPACE_WINDOW = "window"
    fun valid(rule: ShortcutRule): Boolean = rule.id.length in 1..64 && rule.name.length in 1..64 &&
        rule.name.none { it.isISOControl() } && rule.keyCode in 0..65535 && rule.scanCode in 0..65535 &&
        rule.clickCount in 1..4 && (rule.event == ShortcutEvent.KEY || rule.clickCount == 1) &&
        (rule.event == ShortcutEvent.VEHICLE) == (rule.vehicleEvent != null) &&
        (rule.event != ShortcutEvent.VEHICLE || rule.keyCode == 0 && rule.scanCode == 0) &&
        rule.conditions.size <= ShortcutConditions.MAX_PER_RULE && rule.conditions.all { it.valid } &&
        rule.conditions.map { it.key }.distinct().size == rule.conditions.size &&
        (!rule.enabled || rule.steps.none { it.action.legacyWindow || it.action == ShortcutAction.KEY_PRESS }) &&
        (rule.steps.none { it.action == ShortcutAction.ORIGINAL_CLICK } ||
            rule.event == ShortcutEvent.KEY && com.shilapi.xcertplay.input.ShellKeyReplay.safeCode(rule.keyCode)) &&
        rule.steps.size in 1..MAX_STEPS && rule.steps.sumOf { it.waitMs } in 0..120_000 &&
        rule.steps.withIndex().all { (index, step) ->
            ((step.keyCode == -1 && step.scanCode == -1) ||
                (step.keyCode in 0..65535 && step.scanCode in 0..65535)) &&
                (step.action == ShortcutAction.TAP || step.tapX == -1.0 && step.tapY == -1.0) &&
                (step.action == ShortcutAction.TAP || step.action.pinch || !step.inWindow) &&
                (step.action.pinch || step.zoomCenterX == 0.5 && step.zoomCenterY == 0.5 &&
                    step.pinchScale == CarPlayController.DEFAULT_SHORTCUT_PINCH_SCALE) && when (step.action) {
                ShortcutAction.PINCH_IN, ShortcutAction.PINCH_OUT -> step.waitMs == 0L && step.url.isEmpty() &&
                    step.zoomCenterX.isFinite() && step.zoomCenterY.isFinite() &&
                    step.zoomCenterX in 0.05..0.95 && step.zoomCenterY in 0.05..0.95 &&
                    step.pinchScale.isFinite() && step.pinchScale in CarPlayController.SHORTCUT_PINCH_SCALE_RANGE
                ShortcutAction.TAP -> step.waitMs == 0L && step.url.isEmpty() &&
                    step.keyCode == -1 && step.scanCode == -1 && step.tapX.isFinite() && step.tapY.isFinite() &&
                    step.tapX in 0.0..1.0 && step.tapY in 0.0..1.0
                ShortcutAction.WAIT -> step.waitMs in 0..30_000 && step.url.isEmpty()
                ShortcutAction.URI -> step.waitMs == 0L && safeUrl(step.url) == step.url
                ShortcutAction.KEY_PRESS -> step.waitMs == 0L && step.url.isEmpty() && step.keyCode >= 0
                else -> step.waitMs == 0L && step.url.isEmpty() &&
                    (step.action != ShortcutAction.SIRI || index == rule.steps.lastIndex)
            }
        }

    fun executable(rule: ShortcutRule): Boolean = valid(rule) &&
        rule.steps.none { it.action.legacyWindow || it.action == ShortcutAction.KEY_PRESS }

    fun safeUrl(input: String): String? {
        val url = input.trim()
        if (url.isEmpty() || url.length > 2048 || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(url)?.value ?: return null
        if (scheme.dropLast(1).lowercase() in setOf("file", "content", "javascript", "data", "intent",
                "tel", "telprompt", "sms", "facetime", "facetime-audio", "mailto", "ssh", "ftp", "vbscript")) return null
        if (url == scheme || url == "$scheme//") return url
        return url.takeIf { runCatching { URI(it).let { uri -> uri.isAbsolute && uri.rawUserInfo == null } }.getOrDefault(false) }
    }

    /**
     * [legacyBattery] is the card the single battery card of earlier versions became: its show/hide
     * actions now name that card. Versions before 4 had no conditions and at most 12 rules; an older
     * app reading version 4 rejects the whole set rather than running part of it.
     */
    fun decode(text: String, legacyBattery: BatteryCardTarget = BatteryCardTarget.CENTER): List<ShortcutRule> = runCatching {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(text)
        val version = integer(root, "version")
        require(version in 1L..VERSION.toLong())
        val list = root.getJSONArray("rules")
        val ids = HashSet<String>()
        (0 until list.length()).mapNotNull { index -> runCatching {
            val item = list.getJSONObject(index)
            val steps = item.getJSONArray("steps")
            require(steps.length() in 1..MAX_STEPS)
            val conditions = item.optJSONArray("conditions")
            val rule = ShortcutRule(
                id = item.get("id") as String, name = item.get("name") as String,
                enabled = if (item.has("enabled")) item.get("enabled") as Boolean else false,
                event = ShortcutEvent.valueOf(item.get("event") as String),
                keyCode = integer(item, "keyCode").also { require(it in 0..65535) }.toInt(),
                scanCode = integer(item, "scanCode").also { require(it in 0..65535) }.toInt(),
                clickCount = if (version >= 3) integer(item, "clickCount").also { require(it in 1..4) }.toInt() else 1,
                // An older app drops the whole rule at ShortcutEvent.valueOf("VEHICLE"), never runs it partly.
                vehicleEvent = if (item.has("vehicle")) BydVehicleEvent.fromStored(item.get("vehicle") as String) else null,
                conditions = if (conditions == null) emptyList() else (0 until conditions.length()).map { at ->
                    val condition = conditions.getJSONObject(at)
                    val values = condition.getJSONArray("values")
                    ShortcutCondition(ShortcutConditionKey.valueOf(condition.get("key") as String),
                        (0 until values.length()).mapTo(LinkedHashSet()) { values.get(it) as String })
                },
                steps = (0 until steps.length()).map { stepIndex ->
                    val step = steps.getJSONObject(stepIndex)
                    val action = action(step.get("action") as String, legacyBattery)
                    require(!action.pinch || step.has("zoomCenterX") && step.has("zoomCenterY"))
                    ShortcutStep(action,
                        integer(step, "waitMs"), step.get("url") as String,
                        if (version >= 2) integer(step, "keyCode").also { require(it in -1L..65535L) }.toInt() else -1,
                        if (version >= 2) integer(step, "scanCode").also { require(it in -1L..65535L) }.toInt() else -1,
                        if (step.has("tapX")) (step.get("tapX") as Number).toDouble() else -1.0,
                        if (step.has("tapY")) (step.get("tapY") as Number).toDouble() else -1.0,
                        if (step.has("zoomCenterX")) (step.get("zoomCenterX") as Number).toDouble() else 0.5,
                        if (step.has("zoomCenterY")) (step.get("zoomCenterY") as Number).toDouble() else 0.5,
                        if (step.has("pinchScale")) (step.get("pinchScale") as Number).toDouble()
                            else CarPlayController.DEFAULT_SHORTCUT_PINCH_SCALE,
                        inWindow = step.optString("space") == SPACE_WINDOW)
                },
            )
            val retained = if (rule.steps.any { it.action.legacyWindow }) rule.copy(enabled = false) else rule
            require(valid(retained) && ids.add(retained.id))
            retained
        }.getOrNull() }
    }.getOrDefault(emptyList())

    /** The one battery card of earlier versions became [legacyBattery]. */
    private fun action(name: String, legacyBattery: BatteryCardTarget): ShortcutAction = when (name) {
        "SHOW_BATTERY_CARD" -> if (legacyBattery == BatteryCardTarget.CLUSTER) ShortcutAction.SHOW_CLUSTER_BATTERY_CARD
            else ShortcutAction.SHOW_CENTER_BATTERY_CARD
        "HIDE_BATTERY_CARD" -> if (legacyBattery == BatteryCardTarget.CLUSTER) ShortcutAction.HIDE_CLUSTER_BATTERY_CARD
            else ShortcutAction.HIDE_CENTER_BATTERY_CARD
        else -> ShortcutAction.valueOf(name)
    }

    fun encode(rules: List<ShortcutRule>): String? = runCatching {
        require(rules.all(::valid) && rules.map { it.id }.distinct().size == rules.size)
        JSONObject().put("version", VERSION).put("rules", JSONArray().apply {
            rules.forEach { rule -> put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("enabled", rule.enabled).put("event", rule.event.name).put("keyCode", rule.keyCode)
                .put("scanCode", rule.scanCode).put("clickCount", rule.clickCount).apply {
                    rule.vehicleEvent?.let { put("vehicle", it.name) }
                    if (rule.conditions.isNotEmpty()) put("conditions", JSONArray().apply {
                        rule.conditions.forEach { condition ->
                            put(JSONObject().put("key", condition.key.name).put("values", JSONArray().apply {
                                condition.key.values.filter { it in condition.values }.forEach { put(it) }
                            }))
                        }
                    })
                }.put("steps", JSONArray().apply {
                    rule.steps.forEach { put(JSONObject().put("action", it.action.name).put("waitMs", it.waitMs).put("url", it.url)
                        .put("keyCode", it.keyCode).put("scanCode", it.scanCode).apply {
                            if (it.action == ShortcutAction.TAP) { put("tapX", it.tapX); put("tapY", it.tapY) }
                            if (it.action.pinch) {
                                put("zoomCenterX", it.zoomCenterX); put("zoomCenterY", it.zoomCenterY); put("pinchScale", it.pinchScale)
                            }
                            if (it.inWindow) put("space", SPACE_WINDOW)
                        }) }
                })) }
        }).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }.getOrNull()

    private fun integer(json: JSONObject, key: String): Long = json.get(key).let {
        require(it is Int || it is Long)
        (it as Number).toLong()
    }

    fun actionFor(code: Int, scan: Int): ShortcutAction? = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> ShortcutAction.SELECT
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> ShortcutAction.BACK
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> ShortcutAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> ShortcutAction.RIGHT
        else -> when (CarPlayMediaButton.forWheelKey(code, scan)) {
            CarPlayMediaButton.PLAY_PAUSE -> ShortcutAction.TOGGLE
            CarPlayMediaButton.NEXT -> ShortcutAction.NEXT
            CarPlayMediaButton.PREVIOUS -> ShortcutAction.PREVIOUS
            else -> null
        }
    }
}

/** All callbacks and controller submissions are serialized; Controller results mean queued, not played. */
internal object CarPlayShortcuts {
    /** Longer than the controller's main-audio wait, so a lost outcome cannot stall a sequence. */
    private const val MEDIA_OUTCOME_TIMEOUT_MILLIS = CarPlayController.SHORTCUT_MAIN_AUDIO_WAIT_MILLIS + 2_000L
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var controller: CarPlayController? = null
    private var preferences: SharedPreferences? = null
    private var rules = emptyList<ShortcutRule>()
    private var lastConnected = WeakReference<Any>(null)
    private var run: Run? = null
    private var submittedMedia: Pair<CarPlayController, Any>? = null
    internal data class ReplayOperation(val result: java.util.concurrent.CompletableFuture<String>, val cancel: () -> Unit)
    internal var replaySubmit: (Context, Int, Int, Int, () -> Boolean, () -> com.shilapi.xcertplay.input.ShellKeyReplay.Request) -> ReplayOperation =
        { app, code, scan, display, current, prepare ->
            val operation = com.shilapi.xcertplay.input.ShellKeyReplay.submit(app, code, scan, display, current, prepare)
            ReplayOperation(operation.result, operation::cancel)
        }
    private var replayOperation: ReplayOperation? = null
    private val history = ArrayDeque<String>()
    private data class Press(val device: Int, val code: Int, val scan: Int, val down: Long)
    private val held = LinkedHashSet<Press>()
    private val released = LinkedHashMap<Press, Long>()
    private val captureHeld = LinkedHashSet<Press>()
    const val CLICK_INTERVAL_MILLIS = 400L
    private const val PRESS_TIMEOUT_MILLIS = 10_000L
    private data class Clicks(val connection: TouchSession, val first: Press, val candidates: List<ShortcutRule>,
        var down: Press?, var count: Int = 0, var deadline: Long, val event: KeyEvent)
    private var clicks: Clicks? = null
    private data class MediaEcho(val session: Any, val button: Int, val at: Long)
    private var mediaEcho: MediaEcho? = null
    private val settleClicks = Runnable { synchronized(this) { checkClicks() } }
    private data class Run(val owner: CarPlayController, val session: Any, val stream: Any,
        val steps: List<ShortcutStep>, var index: Int = 0, var due: Long = 0, var awaiting: Any? = null,
        val trigger: KeyEvent? = null)
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "rules") synchronized(this) {
            stop(); rules = context?.let(::load).orEmpty(); controller?.let(::sync)
        }
    }
    private val poll = Runnable { synchronized(this) { controller?.let(::sync) } }
    private val advance = Runnable { synchronized(this) { tick() } }
    data class TouchSession(val owner: CarPlayController, val session: Any, val stream: Any)
    private var touchCapture: TouchSession? = null
    @Synchronized fun touchSession(): TouchSession? {
        if (run != null) return null
        return clickSession()
    }
    /** Like [touchSession], but a running sequence does not block it: a new trigger interrupts that sequence. */
    private fun clickSession(): TouchSession? {
        val owner = controller ?: return null
        if (blocked()) return null
        val session = owner.mediaControlSessionToken() ?: return null
        val stream = owner.activeAirPlaySessionToken() ?: return null
        return TouchSession(owner, session, stream)
    }
    private fun currentTouch(value: TouchSession): Boolean = controller === value.owner &&
        value.owner.mediaControlSessionToken() === value.session &&
        value.owner.activeAirPlaySessionToken() === value.stream && !blocked()
    @Synchronized fun isTouchRecording(): Boolean = touchCapture != null
    @Synchronized fun startTouchRecording(expected: TouchSession,
        onFinished: (List<ShortcutStep>) -> Unit, onCancelled: () -> Unit): Boolean {
        if (hasCapture() || run != null || !currentTouch(expected)) return false
        startRecording(onFinished, {}, onCancelled)
        touchCapture = expected
        main.postDelayed(poll, 250)
        return true
    }
    /** Records a tap at [x], [y] as fractions of DiPlay's CarPlay window (see [ShortcutStep.inWindow]). */
    @Synchronized fun recordTap(x: Double, y: Double): Boolean {
        val capture = touchCapture ?: return false
        if (!currentTouch(capture)) { cancelCapture(); return false }
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return false
        return recordStep(ShortcutStep(ShortcutAction.TAP, tapX = x, tapY = y, inWindow = true))
    }
    private var learned: ((Int, Int) -> Unit)? = null
    private var recorded: ((List<ShortcutStep>) -> Unit)? = null
    data class CaptureUpdate(val code: Int? = null, val scan: Int? = null,
        val steps: List<ShortcutStep> = emptyList(), val accepted: Boolean = true)
    private var unsupported: (() -> Unit)? = null
    private var captureChanged: ((CaptureUpdate) -> Unit)? = null
    private var recording = mutableListOf<ShortcutStep>()
    private var captureStart = 0L
    private var previousAt = 0L
    private var captureGeneration = 0L
    private var learningTimeout: (() -> Unit)? = null
    private var captureDismiss: (() -> Unit)? = null
    private val captureTimeout = Runnable { synchronized(this) {
        if (recorded != null) stopRecording() else {
            val callback = learningTimeout
            cancelCapture(); callback?.let(::postCapture)
        }
    } }
    private fun postCapture(callback: () -> Unit) {
        val generation = captureGeneration
        main.post { synchronized(this) { if (captureGeneration == generation) callback() } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_shortcuts", Context.MODE_PRIVATE)

    /** Choices for how long a right card that appears without the map waits for a late map prompt. */
    val RIGHT_CARD_WAITS = listOf(0L, 10L, 50L, 100L, 300L, 500L, 700L, 1_000L)
    fun rightCardWait(context: Context): Long =
        prefs(context).getLong(RIGHT_CARD_WAIT_KEY, BydVehicleMonitor.rightCardWaitMillis).takeIf { it in RIGHT_CARD_WAITS }
            ?: BydVehicleMonitor.DEFAULT_RIGHT_CARD_WAIT_MILLIS
    fun setRightCardWait(context: Context, millis: Long) {
        if (millis !in RIGHT_CARD_WAITS) return
        prefs(context).edit().putLong(RIGHT_CARD_WAIT_KEY, millis).apply()
        BydVehicleMonitor.rightCardWaitMillis = millis
    }
    private const val RIGHT_CARD_WAIT_KEY = "right_card_wait_ms"
    fun load(context: Context): List<ShortcutRule> = runCatching {
        ShortcutCodec.decode(prefs(context).getString("rules", "").orEmpty(), BatteryCardSettings.legacyTarget(context))
    }.getOrDefault(emptyList())
    fun wanted(context: Context): Boolean = load(context).any { it.enabled }
    @Synchronized fun save(context: Context, rules: List<ShortcutRule>): Boolean {
        val json = ShortcutCodec.encode(rules) ?: return false
        stop()
        prefs(context).edit().putString("rules", json).apply()
        this.rules = load(context)
        return true
    }

    @Synchronized fun attach(context: Context, controller: CarPlayController) {
        if (this.controller !== controller) { stop(); main.removeCallbacks(poll) }
        preferences?.unregisterOnSharedPreferenceChangeListener(changed)
        this.context = context.applicationContext
        this.controller = controller
        preferences = prefs(context).also { it.registerOnSharedPreferenceChangeListener(changed) }
        BydVehicleMonitor.rightCardWaitMillis = rightCardWait(context)
        rules = load(context)
        sync(controller)
    }
    @Synchronized fun detach(controller: CarPlayController?) {
        if (controller == null || this.controller !== controller) return
        stop(); cancelCapture(); main.removeCallbacks(poll)
        followVehicle(emptySet(), emptySet())
        followAgent(false)
        preferences?.unregisterOnSharedPreferenceChangeListener(changed)
        preferences = null; this.controller = null; context = null; mediaEcho = null
    }
    @Synchronized fun sync(controller: CarPlayController) {
        if (this.controller !== controller) return
        main.removeCallbacks(poll)
        val session = controller.mediaControlSessionToken()
        val stream = controller.activeAirPlaySessionToken()
        if (mediaEcho?.session !== session) mediaEcho = null
        if (run?.let { !current(it) } == true) stop()
        if (clicks?.let { !currentTouch(it.connection) } == true) cancelClicks()
        if (touchCapture?.let { !currentTouch(it) } == true) cancelCapture()
        if (touchCapture != null) main.postDelayed(poll, 250)
        // Vehicle reads run only while a session can act on them, and only for what enabled rules use.
        val live = session != null && stream != null
        val enabled = rules.filter { it.enabled }
        followVehicle(if (!live) emptySet() else enabled.filter { it.event == ShortcutEvent.VEHICLE }.mapNotNullTo(HashSet()) { it.vehicleEvent },
            if (!live) emptySet() else ShortcutConditions.groups(enabled))
        // An original-key replay through the resident shell agent takes milliseconds instead of seconds.
        followAgent(live && enabled.any { rule -> rule.steps.any { it.action == ShortcutAction.ORIGINAL_CLICK } })
        if (session != null && stream != null) {
            if (lastConnected.get() !== session) {
                lastConnected = WeakReference(session)
                fire(enabled.filter { it.event == ShortcutEvent.CONNECTED }, null, "connected")
            }
        } else if (rules.any { it.enabled } && !controller.isClosed()) main.postDelayed(poll, 250)
    }

    private var followingVehicle = emptySet<BydVehicleEvent>()
    private var followingGroups = emptySet<BydVehicleSignalGroup>()
    private val vehicleListener = BydVehicleMonitor.EventListener { event -> onVehicleEvent(event) }
    private fun followVehicle(events: Set<BydVehicleEvent>, groups: Set<BydVehicleSignalGroup>) {
        if (events == followingVehicle && groups == followingGroups) return
        val app = context
        val wanted = events.isNotEmpty() || groups.isNotEmpty()
        if (wanted && app == null) return
        followingVehicle = events; followingGroups = groups
        if (wanted) BydVehicleMonitor.addEventListener(app!!, vehicleListener, events, groups)
        else BydVehicleMonitor.removeEventListener(vehicleListener)
        note(if (wanted) "vehicle followed: events=${events.joinToString(",")} conditions=${groups.joinToString(",")}" else "vehicle released")
    }

    private var followingAgent = false
    private fun followAgent(wanted: Boolean) {
        if (wanted == followingAgent) return
        val app = context
        if (wanted && app == null) return
        followingAgent = wanted
        if (wanted) com.shilapi.xcertplay.hud.BydShellAgent.acquire(app!!, "shortcuts")
        else com.shilapi.xcertplay.hud.BydShellAgent.release("shortcuts")
    }

    /** A read-only car change (main thread). Every enabled rule for it whose conditions hold runs. */
    @Synchronized fun onVehicleEvent(event: BydVehicleEvent) {
        if (event !in followingVehicle || hasCapture()) return
        val candidates = rules.filter { it.enabled && it.event == ShortcutEvent.VEHICLE && it.vehicleEvent == event }
        if (candidates.isNotEmpty() && !fire(candidates, null, "vehicle $event")) note("vehicle trigger $event not started")
    }
    private fun blocked(): Boolean = context?.let { inCall(it) } != false ||
        controller?.carPlayPhoneCallActive() == true || NavigationPlayback.snapshot().priorityVoiceActive
    private fun current(run: Run): Boolean = controller === run.owner &&
        run.owner.mediaControlSessionToken() === run.session &&
        run.owner.activeAirPlaySessionToken() === run.stream && !blocked()

    /** A trigger waiting for vehicle values its rules' conditions need, as right after connecting. */
    private data class PendingFire(val rules: List<ShortcutRule>, val trigger: KeyEvent?, val reason: String, val since: Long)
    private var pendingFire: PendingFire? = null
    private val retryFire = Runnable { synchronized(this) {
        val pending = pendingFire ?: return@synchronized
        pendingFire = null
        fire(pending.rules, pending.trigger, pending.reason, pending.since)
    } }

    /**
     * Runs every rule of [candidates] whose conditions hold, in list order, as one sequence; it
     * interrupts a sequence still running. A condition whose value is not known yet makes the
     * trigger wait up to [ShortcutConditions.UNKNOWN_WAIT_MILLIS]; after that it does not hold.
     */
    private fun fire(candidates: List<ShortcutRule>, trigger: KeyEvent?, reason: String,
        since: Long = SystemClock.uptimeMillis()): Boolean {
        pendingFire = null; main.removeCallbacks(retryFire)
        if (candidates.isEmpty() || hasCapture() || blocked()) return false
        val app = context ?: return false
        val read = ShortcutConditions.reader(app, controller)
        val results = candidates.map { it to ShortcutConditions.matches(it.conditions, read) }
        if (results.any { it.second == null } && SystemClock.uptimeMillis() - since < ShortcutConditions.UNKNOWN_WAIT_MILLIS) {
            pendingFire = PendingFire(candidates, trigger?.let(::KeyEvent), reason, since)
            main.postDelayed(retryFire, 150)
            return true
        }
        val matched = results.filter { it.second == true }.map { it.first }
        note("trigger $reason rules=${candidates.size} matched=${matched.size}" +
            results.count { it.second == null }.let { if (it > 0) " unknown=$it" else "" })
        return matched.isNotEmpty() && start(matched, trigger)
    }

    private fun start(rules: List<ShortcutRule>, trigger: KeyEvent? = null): Boolean {
        val runnable = rules.filter { rule ->
            ShortcutCodec.executable(rule) && (rule.steps.none { it.action == ShortcutAction.ORIGINAL_CLICK } ||
                trigger != null && physical(trigger) && trigger.keyCode == rule.keyCode && trigger.scanCode == rule.scanCode)
        }
        if (runnable.isEmpty() || hasCapture() || blocked()) return false
        val owner = controller ?: return false
        val session = owner.mediaControlSessionToken() ?: return false
        val stream = owner.activeAirPlaySessionToken() ?: return false
        // Siri ends a sequence, so rules that ask for it go last; the original key is replayed once.
        val ordered = runnable.filter { rule -> rule.steps.none { it.action == ShortcutAction.SIRI } } +
            runnable.filter { rule -> rule.steps.any { it.action == ShortcutAction.SIRI } }
        var replayed = false
        val steps = ordered.flatMap { it.steps }.filter { step ->
            if (step.action != ShortcutAction.ORIGINAL_CLICK) true else !replayed.also { replayed = true }
        }
        if (run != null) note("interrupted")
        stop()
        run = Run(owner, session, stream, steps, trigger = trigger?.let(::KeyEvent))
        note("started rules=${ordered.size} steps=${steps.size}"); main.post(advance)
        return true
    }
    @Synchronized fun test(rule: ShortcutRule): Boolean = start(listOf(rule))
    @Synchronized fun stop() {
        cancelClicks()
        pendingFire = null; main.removeCallbacks(retryFire)
        val media = submittedMedia
        if (run != null || media != null) note("stopped")
        run = null; submittedMedia = null; main.removeCallbacks(advance)
        replayOperation?.cancel?.invoke(); replayOperation = null
        controller?.cancelShortcutTaps()
        if (media != null && controller === media.first && media.first.mediaControlSessionToken() === media.second) {
            media.first.cancelPendingMediaCommands()
        }
    }
    @Synchronized fun onManualTouch(owner: CarPlayController?) {
        if (owner == null || controller !== owner) return
        stop()
    }
    private fun tick() {
        val active = run ?: return
        if (!current(active)) { stop(); return }
        val now = SystemClock.uptimeMillis()
        if (active.due > now) { main.postDelayed(advance, minOf(100, active.due - now)); return }
        if (active.awaiting != null) {
            active.awaiting = null
            val action = active.steps[active.index - 1].action
            note("step=${active.index} action=$action result=no_outcome")
            if (action.pinch || action == ShortcutAction.ORIGINAL_CLICK) { stop(); return }
        }
        if (active.index == active.steps.size) { note("finished submitting"); run = null; return }
        val step = active.steps[active.index++]
        if (step.action == ShortcutAction.WAIT) active.due = now + step.waitMs
        else if (step.action == ShortcutAction.ORIGINAL_CLICK) {
            val event = active.trigger ?: kotlin.run { stop(); return }
            val app = context ?: kotlin.run { stop(); return }
            val display = if (android.os.Build.VERSION.SDK_INT < 29) 0 else runCatching {
                (KeyEvent::class.java.getMethod("getDisplayId").invoke(event) as Int).let { if (it == -1) 0 else it }
            }.getOrDefault(-2)
            note("original-click start code=${event.keyCode} scan=${event.scanCode} display=$display virtual=true")
            val token = Any()
            active.awaiting = token; active.due = now + 6500
            val operation = runCatching {
                replaySubmit(app, event.keyCode, event.scanCode, display,
                    { synchronized(this) { run === active && active.awaiting === token && current(active) && !hasCapture() } },
                    { OriginalKeyReplayGuard.prepare(event.keyCode, event.scanCode, display) })
            }.getOrElse { note("original-click result=unknown"); stop(); return }
            replayOperation = operation
            operation.result.whenComplete { result, error ->
                main.post { synchronized(this) {
                    val status = result?.takeIf { it in com.shilapi.xcertplay.input.ShellKeyReplay.outcomes } ?: "unknown"
                    note("original-click result=${if (error == null) status else "unknown"} oemVerified=false")
                    if (run === active && active.awaiting === token) {
                        replayOperation = null
                        stop()
                    }
                } }
            }
            main.postDelayed(advance, 100)
            return
        }
        else if (step.action.pinch) {
            val token = Any()
            active.awaiting = token
            active.due = now + 5_000L
            val (centerX, centerY) = pinchTarget(step, active.index)
            val queued = runCatching {
                active.owner.sendShortcutPinch(step.action == ShortcutAction.PINCH_IN,
                    centerX, centerY, active.session, active.stream, step.pinchScale) { result ->
                    main.post { synchronized(this) {
                        if (run === active && active.awaiting === token) {
                            note("step=${active.index} action=${step.action} result=$result")
                            if (result != "written" || !current(active)) stop() else {
                                active.awaiting = null; active.due = 0
                                main.removeCallbacks(advance); main.post(advance)
                            }
                        }
                    } }
                }
            }.getOrDefault(false)
            note("step=${active.index} action=${step.action} queued=$queued")
            if (!queued) stop() else main.postDelayed(advance, 100)
            return
        }
        else if (step.action in ShortcutAction.PLAY..ShortcutAction.PREVIOUS) {
            // Later steps (a URI, another press) would cancel a press still waiting for main audio.
            val token = Any()
            val number = active.index
            active.awaiting = token
            active.due = now + MEDIA_OUTCOME_TIMEOUT_MILLIS
            val queued = runCatching {
                active.owner.sendShortcutMedia(mediaButton(step.action)) { result ->
                    main.post { synchronized(this) { mediaOutcome(active, token, number, step.action, result) } }
                }
            }.getOrDefault(false)
            if (queued) submittedMedia = active.owner to active.session
            note("step=$number action=${step.action} queued=$queued")
            if (!queued) { stop(); return }
            if (active.awaiting === token) main.postDelayed(advance, 100)
            return
        } else {
            val queued = runCatching { when (step.action) {
                ShortcutAction.TAP -> tapTarget(step, active.index).let { (x, y) ->
                    active.owner.sendShortcutTap(x, y, active.session, active.stream)
                }
                ShortcutAction.URI -> active.owner.requestUi(step.url)
                ShortcutAction.SIRI -> active.owner.requestSiri()
                ShortcutAction.SHOW_CLUSTER_MUSIC -> context?.let { ClusterWindowVisibility.setVisible(it, active.owner, ClusterWindowPanel.MUSIC, true) } == true
                ShortcutAction.HIDE_CLUSTER_MUSIC -> context?.let { ClusterWindowVisibility.setVisible(it, active.owner, ClusterWindowPanel.MUSIC, false) } == true
                ShortcutAction.SHOW_CLUSTER_MAP -> context?.let { ClusterWindowVisibility.setVisible(it, active.owner, ClusterWindowPanel.MAP, true) } == true
                ShortcutAction.HIDE_CLUSTER_MAP -> context?.let { ClusterWindowVisibility.setVisible(it, active.owner, ClusterWindowPanel.MAP, false) } == true
                ShortcutAction.SHOW_CENTER_BATTERY_CARD -> context?.let { BatteryCard.setVisible(it, BatteryCardTarget.CENTER, true) } == true
                ShortcutAction.HIDE_CENTER_BATTERY_CARD -> context?.let { BatteryCard.setVisible(it, BatteryCardTarget.CENTER, false) } == true
                ShortcutAction.SHOW_CLUSTER_BATTERY_CARD -> context?.let { BatteryCard.setVisible(it, BatteryCardTarget.CLUSTER, true) } == true
                ShortcutAction.HIDE_CLUSTER_BATTERY_CARD -> context?.let { BatteryCard.setVisible(it, BatteryCardTarget.CLUSTER, false) } == true
                ShortcutAction.CYCLE_CARMETER_CONTENT -> context?.let { CarMeterContent.cycle(it, announce = true); true } == true
                ShortcutAction.SHOW_WINDOW, ShortcutAction.HIDE_WINDOW, ShortcutAction.KEY_PRESS -> false
                ShortcutAction.SELECT -> active.owner.sendKnob(AirPlayKnobState(select = true))
                ShortcutAction.BACK -> active.owner.sendKnob(AirPlayKnobState(back = true))
                ShortcutAction.HOME -> active.owner.sendKnob(AirPlayKnobState(home = true))
                ShortcutAction.LEFT -> active.owner.sendKnob(AirPlayKnobState(wheel = -1))
                ShortcutAction.RIGHT -> active.owner.sendKnob(AirPlayKnobState(wheel = 1))
                else -> false
            } }.getOrDefault(false)
            if (step.action.clusterWindow || step.action.batteryCard || step.action == ShortcutAction.CYCLE_CARMETER_CONTENT) {
                note("step=${active.index} action=${step.action} visibilityRequested=$queued")
            } else note("step=${active.index} action=${step.action} queued=$queued")
            if (!queued || step.action == ShortcutAction.SIRI) { stop(); return }
        }
        main.post(advance)
    }

    /** The canvas point to send for a TAP; see [canvasPoint]. */
    private fun tapTarget(step: ShortcutStep, index: Int): Pair<Double, Double> =
        canvasPoint("step=$index tap", step.tapX, step.tapY, step.inWindow)

    /**
     * The canvas centre to send for a pinch. The gesture needs room around its centre, so a window centre
     * near a letterbox bar is kept inside the range the controller accepts.
     */
    private fun pinchTarget(step: ShortcutStep, index: Int): Pair<Double, Double> =
        canvasPoint("step=$index pinch centre", step.zoomCenterX, step.zoomCenterY, step.inWindow).let { (x, y) ->
            x.coerceIn(0.05, 0.95) to y.coerceIn(0.05, 0.95)
        }

    /**
     * A window point goes through the host's mapping; without a host (a session kept in the background) it is
     * sent as it is, which matches whenever the canvas fills the window. A canvas point is sent as it is.
     */
    private fun canvasPoint(label: String, x: Double, y: Double, inWindow: Boolean): Pair<Double, Double> {
        if (!inWindow) {
            note("$label canvas=${CarPlayWindowTaps.fraction(x)},${CarPlayWindowTaps.fraction(y)} (set on the canvas)")
            return x to y
        }
        val mapped = CarPlayWindowTaps.toCanvas("Shortcut $label", x, y)
        note("$label window=${CarPlayWindowTaps.fraction(x)},${CarPlayWindowTaps.fraction(y)} -> " + (mapped?.let {
            "canvas=${CarPlayWindowTaps.fraction(it.x)},${CarPlayWindowTaps.fraction(it.y)} ${it.detail}"
        } ?: "canvas=unmapped (no CarPlay window), sent as set"))
        return mapped?.let { it.x to it.y } ?: (x to y)
    }

    private fun mediaButton(action: ShortcutAction) = when (action) {
        ShortcutAction.PLAY -> CarPlayMediaButton.PLAY
        ShortcutAction.PAUSE -> CarPlayMediaButton.PAUSE
        ShortcutAction.TOGGLE -> CarPlayMediaButton.PLAY_PAUSE
        ShortcutAction.NEXT -> CarPlayMediaButton.NEXT
        else -> CarPlayMediaButton.PREVIOUS
    }

    /** The controller's transport outcome, which is still not proof that the phone played. */
    private fun mediaOutcome(active: Run, token: Any, number: Int, action: ShortcutAction, result: String) {
        if (active.awaiting !== token) return
        note("step=$number action=$action result=$result")
        if (run !== active) return
        active.awaiting = null; active.due = 0
        main.removeCallbacks(advance); main.post(advance)
    }

    private fun press(event: KeyEvent) = Press(event.deviceId, event.keyCode, event.scanCode, event.downTime)
    private fun consumed(event: KeyEvent, presses: MutableSet<Press>): Boolean {
        val key = press(event)
        presses.removeAll { SystemClock.uptimeMillis() - it.down > 120_000 }
        if (event.action == KeyEvent.ACTION_UP) return presses.remove(key)
        return event.action == KeyEvent.ACTION_DOWN && key in presses
    }
    private fun physical(event: KeyEvent): Boolean = event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
        event.device?.isVirtual != true && event.keyCode in 0..65535 && event.scanCode in 0..65535
    private fun cancelClicks() { clicks = null; main.removeCallbacks(settleClicks) }
    private fun checkClicks() {
        val pending = clicks ?: return
        if (!currentTouch(pending.connection) || hasCapture()) { cancelClicks(); return }
        val remaining = pending.deadline - SystemClock.uptimeMillis()
        if (remaining > 0) { main.postDelayed(settleClicks, minOf(100, remaining)); return }
        if (pending.down != null) { cancelClicks(); return }
        finishClicks(pending)
    }
    private fun finishClicks(pending: Clicks) {
        cancelClicks()
        if (!currentTouch(pending.connection)) return
        val matches = pending.candidates.filter { it.clickCount == pending.count }
        note("trigger clicks=${pending.count} rules=${matches.size}")
        if (matches.isNotEmpty()) fire(matches, pending.event, "key clicks=${pending.count}")
    }

    /** Drain repeats/releases before route priorities change; never claims a new physical press. */
    @Synchronized fun consumeOwnedKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val now = SystemClock.uptimeMillis()
        val key = press(event)
        released.entries.removeAll { now - it.value > 2_000 }
        held.removeAll { now - it.down > 120_000 }
        if (key in released) return true
        if (key !in held) return false
        if (event.action == KeyEvent.ACTION_UP) {
            held.remove(key)
            if (released.size >= 64) released.remove(released.keys.first())
            released[key] = now
            val pending = clicks
            if (pending?.down == key) {
                if (event.isCanceled || now > pending.deadline || !currentTouch(pending.connection)) cancelClicks()
                else {
                    pending.down = null; pending.count++
                    main.removeCallbacks(settleClicks)
                    if (pending.count >= pending.candidates.maxOf { it.clickCount }) finishClicks(pending)
                    else {
                        pending.deadline = now + CLICK_INTERVAL_MILLIS
                        main.postDelayed(settleClicks, 100)
                    }
                }
            }
        }
        return true
    }

    @Synchronized fun onKey(context: Context, event: KeyEvent): Boolean {
        if (OriginalKeyReplayGuard.matches(event)) return false
        if (onLearningKey(context, event)) return true
        if (consumeOwnedKey(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || !physical(event) || inCall(context) || blocked()) return false
        val candidates = rules.filter { it.enabled && it.event == ShortcutEvent.KEY &&
            it.keyCode == event.keyCode && it.scanCode == event.scanCode }
        if (candidates.isEmpty()) return false
        val key = press(event)
        // A press for a rule interrupts a sequence still running once its clicks are counted.
        val connection = clickSession() ?: return false
        val now = SystemClock.uptimeMillis()
        val previous = clicks
        if (previous != null && previous.down == null && now >= previous.deadline) checkClicks()
        val pending = clicks?.takeIf { it.first.device == key.device && it.first.code == key.code &&
            it.first.scan == key.scan && it.down == null && currentTouch(it.connection) }
        if (pending == null) {
            cancelClicks()
            clicks = Clicks(connection, key, candidates, key, deadline = now + PRESS_TIMEOUT_MILLIS, event = KeyEvent(event))
        } else {
            pending.down = key; pending.deadline = now + PRESS_TIMEOUT_MILLIS
        }
        main.removeCallbacks(settleClicks); main.postDelayed(settleClicks, 100)
        if (held.size >= 64) held.remove(held.first())
        held.add(key)
        val media = CarPlayMediaButton.forWheelKey(event.keyCode, event.scanCode)
        controller?.mediaControlSessionToken()?.let { token ->
            if (media != null) mediaEcho = MediaEcho(token, media, SystemClock.uptimeMillis())
        }
        return true
    }

    @Synchronized fun consumeMediaEcho(event: KeyEvent): Boolean {
        val echo = mediaEcho ?: return false
        if (controller?.mediaControlSessionToken() !== echo.session ||
            SystemClock.uptimeMillis() - echo.at !in 0..500 || blocked()) return false
        val mapped = CarPlayMediaButton.forKeyCode(event.keyCode, true) ?: return false
        val same = mapped == echo.button || (mapped in CarPlayMediaButton.PLAY..CarPlayMediaButton.PLAY_PAUSE &&
            echo.button in CarPlayMediaButton.PLAY..CarPlayMediaButton.PLAY_PAUSE)
        if (same) note("media duplicate consumed key=${event.keyCode}")
        return same
    }

    @Synchronized fun hasCapture(): Boolean = learned != null || recorded != null
    @Synchronized fun cancelCapture() = clearCapture(notifyDismiss = true)

    private fun clearCapture(notifyDismiss: Boolean) {
        val dismiss = captureDismiss.takeIf { notifyDismiss }
        captureGeneration++
        learned = null; recorded = null; unsupported = null; learningTimeout = null; captureDismiss = null
        touchCapture = null
        captureChanged = null; recording.clear()
        main.removeCallbacks(captureTimeout)
        if (dismiss != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) dismiss() else main.post { dismiss() }
        }
    }
    @Synchronized fun startLearning(onLearned: (Int, Int) -> Unit, onUnsupported: () -> Unit,
        onTimeout: () -> Unit = {}, onDismiss: () -> Unit = {}, onChanged: (CaptureUpdate) -> Unit = {}) {
        stop(); cancelCapture(); learned = onLearned; unsupported = onUnsupported; learningTimeout = onTimeout
        captureDismiss = onDismiss; captureChanged = onChanged
        main.postDelayed(captureTimeout, 60_000)
    }
    @Synchronized fun startRecording(onFinished: (List<ShortcutStep>) -> Unit, onUnsupported: () -> Unit,
        onDismiss: () -> Unit = {}, onChanged: (CaptureUpdate) -> Unit = {}) {
        stop(); cancelCapture(); recorded = onFinished; unsupported = onUnsupported; captureDismiss = onDismiss
        captureChanged = onChanged
        captureStart = SystemClock.uptimeMillis(); previousAt = captureStart
        main.postDelayed(captureTimeout, 60_000)
    }
    @Synchronized fun stopRecording() {
        val callback = recorded ?: return
        val steps = recording.toList()
        clearCapture(notifyDismiss = false); postCapture { callback(steps) }
    }
    private fun captureUpdate(code: Int? = null, scan: Int? = null, accepted: Boolean = true) {
        val callback = captureChanged ?: return
        val update = CaptureUpdate(code, scan, recording.toList(), accepted)
        postCapture { callback(update) }
    }
    @Synchronized fun recordAction(action: ShortcutAction): Boolean {
        if (touchCapture != null || action.legacyWindow ||
            action in setOf(ShortcutAction.WAIT, ShortcutAction.URI, ShortcutAction.KEY_PRESS, ShortcutAction.TAP, ShortcutAction.ORIGINAL_CLICK)) return false
        return recordStep(ShortcutStep(action))
    }
    private fun recordStep(step: ShortcutStep): Boolean {
        if (recorded == null) return false
        val now = SystemClock.uptimeMillis()
        if (now - captureStart >= 60_000) { stopRecording(); return false }
        val gap = now - previousAt
        val waits = mutableListOf<ShortcutStep>()
        var remaining = gap
        if (recording.isNotEmpty()) while (remaining > 0) {
            val delay = minOf(30_000, remaining)
            waits.add(ShortcutStep(ShortcutAction.WAIT, delay)); remaining -= delay
        }
        if (recording.size + waits.size + 1 > ShortcutCodec.MAX_STEPS) {
            stopRecording(); return false
        }
        recording.addAll(waits); recording.add(step); previousAt = now
        captureUpdate(step.keyCode.takeIf { it >= 0 }, step.scanCode.takeIf { it >= 0 })
        if (step.action == ShortcutAction.SIRI) stopRecording()
        return true
    }
    @Synchronized fun onLearningKey(context: Context, event: KeyEvent): Boolean {
        if (OriginalKeyReplayGuard.matches(event)) return false
        if (consumed(event, captureHeld)) return true
        if (touchCapture != null || !hasCapture() || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (!physical(event) || inCall(context) || controller?.carPlayPhoneCallActive() == true ||
            NavigationPlayback.snapshot().priorityVoiceActive) {
            captureUpdate(event.keyCode, event.scanCode, accepted = false)
            unsupported?.let(::postCapture); return false
        }
        val callback = learned
        if (callback != null) {
            val code = event.keyCode; val scan = event.scanCode
            val changed = captureChanged
            clearCapture(notifyDismiss = false); postCapture {
                changed?.invoke(CaptureUpdate(code, scan))
                callback(code, scan)
            }
        } else {
            val action = ShortcutCodec.actionFor(event.keyCode, event.scanCode) ?: ShortcutAction.KEY_PRESS
            recordStep(ShortcutStep(action, keyCode = event.keyCode, scanCode = event.scanCode))
        }
        if (captureHeld.size >= 64) captureHeld.remove(captureHeld.first())
        captureHeld.add(press(event)); return true
    }
    private fun note(message: String) {
        if (history.size >= 20) history.removeFirst()
        history.addLast(message)
    }
    @Synchronized fun report(): List<String> = listOf("Shortcuts: rules=${rules.size} enabled=${rules.count { it.enabled }} running=${run != null} capture=${hasCapture()} vehicle=$followingVehicle conditions=$followingGroups") + history.toList() + BydVehicleMonitor.report()
}

/**
 * A recorded tap is kept as a fraction of DiPlay's CarPlay window, the spot the driver pressed. Touches
 * reach the iPhone as fractions of the CarPlay canvas, which the window shows letterboxed, or only one view
 * area of; the same spot is a different canvas fraction when the session's canvas or area differs.
 */
internal object ShortcutTapSpace {
    /** Fractions of a [viewWidth] x [viewHeight] window to fractions of the canvas drawn at [content]. */
    fun windowToCanvas(x: Double, y: Double, viewWidth: Int, viewHeight: Int,
        content: com.shilapi.xcertplay.media.CarPlayVideoLayout): Pair<Double, Double> {
        val canvasX = (x * viewWidth - content.left) / content.width.coerceAtLeast(1f)
        val canvasY = (y * viewHeight - content.top) / content.height.coerceAtLeast(1f)
        return canvasX.coerceIn(0.0, 1.0) to canvasY.coerceIn(0.0, 1.0)
    }

    /** A point of a [viewWidth] x [viewHeight] window as fractions of it. */
    fun ofWindow(pointX: Float, pointY: Float, viewWidth: Int, viewHeight: Int): Pair<Double, Double>? {
        if (viewWidth <= 0 || viewHeight <= 0 || !pointX.isFinite() || !pointY.isFinite()) return null
        return (pointX.toDouble() / viewWidth).coerceIn(0.0, 1.0) to (pointY.toDouble() / viewHeight).coerceIn(0.0, 1.0)
    }
}

/**
 * Turns points kept in DiPlay's CarPlay window (shortcut taps and pinch centres, the navigation start and
 * stop taps) into CarPlay canvas points, through the CarPlay host's current layout.
 */
internal object CarPlayWindowTaps {
    /** A canvas point for a window point, with how it was worked out (for the log). */
    data class Mapping(val x: Double, val y: Double, val detail: String)

    /** How long a replay thread waits for the main thread to map a point before sending it as it is. */
    private const val MAIN_THREAD_WAIT_MILLIS = 500L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Set by the CarPlay host while it exists: maps fractions of its CarPlay window to fractions of the
     * session's canvas, as its touches are mapped, and logs it under the label. Called on the main thread.
     */
    @Volatile var mapper: ((label: String, x: Double, y: Double) -> Mapping?)? = null

    /** The canvas point for window point [x], [y], or null without a CarPlay window. Any thread. */
    fun toCanvas(label: String, x: Double, y: Double): Mapping? {
        val map = mapper ?: return null
        val mapped = if (Looper.myLooper() == Looper.getMainLooper()) runCatching { map(label, x, y) }.getOrNull() else {
            val task = java.util.concurrent.FutureTask { map(label, x, y) }
            if (!main.post(task)) return null
            try {
                task.get(MAIN_THREAD_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // The replay is being stopped; let its own sleep see the interrupt.
                Thread.currentThread().interrupt(); null
            } catch (_: Exception) {
                task.cancel(false); null
            }
        }
        return mapped?.takeIf { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 }
    }

    fun fraction(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)
}

/** Observes forwarded CarPlay contacts; never dispatches or intercepts Android touches. */
internal class ShortcutTapTracker {
    private var downAt = -1L
    private var downX = 0f
    private var downY = 0f
    fun reset() { downAt = -1L }
    fun observe(event: android.view.MotionEvent, contact: com.shilapi.xcertplay.airplay.AirPlayContact?,
        forwarded: Boolean, slop: Int): com.shilapi.xcertplay.airplay.AirPlayContact? {
        val action = event.actionMasked
        if (!forwarded || contact == null || event.pointerCount != 1 ||
            action == android.view.MotionEvent.ACTION_CANCEL || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            reset(); return null
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            downAt = event.downTime; downX = event.x; downY = event.y
            return null
        }
        if (downAt < 0 || event.downTime != downAt) return null
        fun moved(x: Float, y: Float) = kotlin.math.abs(x - downX) > slop || kotlin.math.abs(y - downY) > slop
        if (moved(event.x, event.y) || (0 until event.historySize).any {
            moved(event.getHistoricalX(it), event.getHistoricalY(it))
        } || event.eventTime - downAt !in 0..500) {
            reset(); return null
        }
        if (action != android.view.MotionEvent.ACTION_UP) return null
        reset()
        return contact
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Experimental: presses a button on the iPhone's map by replaying taps the driver recorded once.
 *
 * CarPlay has no car-to-phone command for either end of a route. Handing a destination over opens the
 * map's route preview, where AMap still wants "开始导航" pressed; cancelling one has no command at all.
 * The car can inject touch, so the only way to press either is to send the same contacts the driver's
 * own finger would. DiPlay cannot read the picture, so it cannot tell what is under a point: a
 * sequence is recorded by calibration and replayed blindly, and a changed layout (an iOS update,
 * another map app, a different negotiated size) makes it tap the wrong place with no warning. Hence
 * the guards: a recorded sequence is required, and park-only is the default.
 *
 * Coordinates are normalized to the video content, as [CarPlayTouchMapper] produces them, so they hold
 * across overlay sizes as long as the iPhone's own layout does not change. The delays are the driver's
 * own rhythm, measured during calibration, which covers the panel animations of a real map app.
 */
internal object CarPlayGuidanceStopper {
    private const val TAG = "DiPlay-GuidanceStop"

    /**
     * Which button a recorded sequence presses. Two sequences, one mechanism: they differ in when they
     * run and in whether the map has to be brought to the front first.
     */
    enum class Action {
        /** "End route", replayed when the head unit's map stops guiding. */
        STOP,

        /**
         * "开始导航" on the route preview AMap opens when a destination is handed over. Never brings
         * the map to the front: the requestUI that carried the destination has just done that, and
         * sending another would reopen the app and lose the preview the taps are aimed at.
         */
        START,
    }

    /** [delayBeforeMillis] is the pause measured before this tap; the first tap's is the settle time. */
    data class Tap(val x: Double, val y: Double, val delayBeforeMillis: Long) {
        fun label(): String = String.format(Locale.US, "%.0f%%, %.0f%% (+%dms)", x * 100, y * 100, delayBeforeMillis)
    }

    const val MIN_TAPS = 1
    const val MAX_TAPS = 4

    /** How long a replayed contact stays down. Long enough for a tap, short of a long-press. */
    private const val HOLD_MILLIS = 90L

    /** The driver's measured pauses are clamped to this, so one slow calibration cannot stall a replay. */
    const val MIN_DELAY_MILLIS = 250L
    const val MAX_DELAY_MILLIS = 4_000L

    /**
     * The pause between taps, when the driver has chosen one rather than keeping the rhythm measured
     * during calibration. A recorded pause is whatever the finger happened to do, which is a poor fit
     * for a panel animation: too quick and the second tap lands before the first screen has gone, too
     * slow and the drive starts late. Zero means the recorded rhythm is used.
     */
    val TAP_GAP_CHOICES = listOf(0L, 250L, 500L, 750L, 1_000L, 1_500L, 2_000L, 3_000L, 4_000L)

    /**
     * The pause before tap [index], honouring an override. None before the first: the pause recorded
     * before it runs from pressing Start in DiPlay to the finger reaching the CarPlay screen — this
     * page closing, the map coming forward, the driver's hand — none of which a replay goes through.
     * The only waits before the first tap are the ones the driver set: the start delay, and the wait
     * after the map is brought to the front.
     */
    internal fun gapBefore(taps: List<Tap>, index: Int, override: Long): Long = when {
        index == 0 -> 0L
        override > 0 -> override.coerceIn(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS)
        else -> taps[index].delayBeforeMillis
    }


    /** A calibration left running would record an unrelated tap much later. */
    private const val CALIBRATION_TIMEOUT_MILLIS = 120_000L

    enum class Result { REPLAYED, NOT_CALIBRATED, NO_SESSION, NOT_PARKED, BUSY }

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-guidance-stop").apply { isDaemon = true } }
    }

    @Volatile private var replaying = false

    /**
     * Every step of a replay, to the navigation debug window as well as logcat. A blind tap sequence
     * cannot be judged from its outcome — a button that highlights and does nothing looks the same as
     * one that was never pressed — so the only way to tell them apart is a record of what was sent,
     * where, and how long after what. The window shows it live, which a report cannot.
     */
    private fun step(message: String) {
        Log.i(TAG, message)
        NavigationDebugLog.event("Guidance taps: $message")
    }

    private var recording: MutableList<Tap>? = null
    private var recordingAction = Action.STOP
    private var wanted = 0
    private var lastRecordedAt = 0L
    private var startedAt = 0L

    /** Non-null while the driver is recording the sequence; the count still to be recorded. */
    val remainingToRecord: Int?
        get() = synchronized(this) {
            val taps = recording ?: return null
            if (SystemClock.uptimeMillis() - startedAt > CALIBRATION_TIMEOUT_MILLIS) {
                recording = null
                Log.i(TAG, "calibration timed out")
                return null
            }
            wanted - taps.size
        }

    /**
     * Starts recording [count] taps. The caller brings the map to the front first, exactly as a replay
     * does, so the recorded points match the screen the replay will tap.
     */
    fun beginCalibration(count: Int, action: Action = Action.STOP) = synchronized(this) {
        wanted = count.coerceIn(MIN_TAPS, MAX_TAPS)
        recording = ArrayList(wanted)
        recordingAction = action
        startedAt = SystemClock.uptimeMillis()
        lastRecordedAt = startedAt
        step("calibration started for $wanted tap(s) ($action). Each tap is also forwarded to the " +
            "iPhone, so the screen reacts as it would to a finger.")
    }

    fun cancelCalibration() = synchronized(this) {
        recording = null
    }

    /**
     * Records one tap of the calibration at a point normalized to the video content. Returns the number
     * still wanted, or null when this tap was not part of a calibration. Zero means the sequence is
     * complete and has been saved.
     */
    fun recordCalibrationTap(context: Context, x: Double, y: Double): Int? {
        val finished = synchronized(this) {
            remainingToRecord ?: return null
            val taps = recording ?: return null
            val now = SystemClock.uptimeMillis()
            taps.add(Tap(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0),
                (now - lastRecordedAt).coerceIn(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS)))
            lastRecordedAt = now
            if (taps.size < wanted) return wanted - taps.size
            recording = null
            recordingAction to taps.toList()
        }
        val (action, taps) = finished
        save(context, action, encode(taps))
        step("calibration saved ${taps.size} tap(s) ($action): " +
            "[${taps.joinToString(" → ") { it.label() }}]")
        return 0
    }

    fun taps(context: Context, action: Action = Action.STOP): List<Tap> = decode(when (action) {
        Action.STOP -> AirPlayPersistence.loadCarPlayStopTaps(context)
        Action.START -> AirPlayPersistence.loadCarPlayStartTaps(context)
    })

    private fun save(context: Context, action: Action, json: String) = when (action) {
        Action.STOP -> AirPlayPersistence.saveCarPlayStopTaps(context, json)
        Action.START -> AirPlayPersistence.saveCarPlayStartTaps(context, json)
    }

    fun clear(context: Context, action: Action = Action.STOP) {
        cancelCalibration()
        save(context, action, "")
    }

    /** The URL that brings the chosen map app to the front, with no destination of its own. */
    fun foregroundUrl(context: Context): String =
        if (AirPlayPersistence.loadMapDestinationLink(context).let {
                it == HeadUnitMapDestination.Link.APPLE_MAPS || it == HeadUnitMapDestination.Link.APPLE_MAPS_WEB
            }) "maps:" else "iosamap://"

    /** Brings the map app to the front, so a calibration and a replay start from the same screen. */
    fun bringMapToFront(context: Context): Boolean {
        if (!AirPlayPersistence.loadCarPlayStopForeground(context)) return false
        val controller = CarPlayBackgroundSession.snapshot()?.controller ?: return false
        if (controller.activeAirPlaySessionToken() == null) return false
        return controller.requestUi(foregroundUrl(context))
    }

    /**
     * Replays the recorded sequence off the caller's thread, and reports to [onResult] how far it got,
     * on the replay thread. Nothing can confirm that the taps landed on the buttons the driver recorded,
     * so [Result.REPLAYED] means only that they were sent. The gear is read over ADB, which blocks, so
     * that check happens on the replay thread too.
     */
    fun replay(
        context: Context,
        action: Action = Action.STOP,
        /** Added before the first tap. For [Action.START], the driver's wait for the route preview. */
        initialDelayMillis: Long = 0L,
        onResult: (Result) -> Unit = {},
    ) {
        val taps = taps(context, action)
        step("$action requested: ${taps.size} recorded tap(s) " +
            "[${taps.joinToString(" → ") { it.label() }}] initialDelay=${initialDelayMillis}ms")
        if (taps.isEmpty()) {
            step("$action abandoned: no sequence has been recorded")
            return onResult(Result.NOT_CALIBRATED)
        }
        val controller = CarPlayBackgroundSession.snapshot()?.controller ?: run {
            step("$action abandoned: no CarPlay session")
            return onResult(Result.NO_SESSION)
        }
        if (controller.activeAirPlaySessionToken() == null) {
            step("$action abandoned: the CarPlay session has no active AirPlay token")
            return onResult(Result.NO_SESSION)
        }
        synchronized(this) {
            if (replaying) {
                step("$action abandoned: another replay is already running")
                return onResult(Result.BUSY)
            }
            replaying = true
        }
        executor.execute {
            var result = Result.REPLAYED
            try {
                // Unknown gear counts as moving: a blind tap sequence must not run while the car may drive.
                if (AirPlayPersistence.loadCarPlayStopParkedOnly(context)) {
                    val parked = BydNavigationOutputs.parked(context)
                    step("parked-only is on; gear reports parked=$parked")
                    if (parked != true) {
                        step("$action abandoned: the car is not parked (unknown gear counts as moving)")
                        result = Result.NOT_PARKED
                        return@execute
                    }
                }
                // START follows a requestUI that already opened the map on the route preview; sending
                // another would reopen the app and lose the very screen the taps are aimed at.
                val foreground = action == Action.STOP && bringMapToFront(context)
                // The driver's setting, not a constant: it is the only wait between the map coming
                // forward and the first tap.
                val settle = if (foreground) AirPlayPersistence.loadCarPlayStopForegroundWaitMillis(context) else 0L
                step("map brought to front=$foreground" + if (foreground) "; waiting ${settle}ms" else "")
                if (settle > 0) Thread.sleep(settle)
                if (initialDelayMillis > 0) {
                    step("waiting ${initialDelayMillis}ms for the screen the taps are aimed at")
                    Thread.sleep(initialDelayMillis)
                }
                val gap = AirPlayPersistence.loadCarPlayTapGapMillis(context, action)
                if (gap > 0) step("tap spacing is set to ${gap}ms, replacing the recorded rhythm")
                taps.forEachIndexed { index, tap ->
                    val wait = gapBefore(taps, index, gap)
                    Thread.sleep(wait)
                    if (controller.activeAirPlaySessionToken() == null) {
                        step("$action abandoned at tap ${index + 1}: the session ended mid-sequence")
                        result = Result.NO_SESSION
                        return@forEachIndexed
                    }
                    val queued = controller.sendTouch(listOf(AirPlayContact(0, tap.x, tap.y, down = true)))
                    step("tap ${index + 1}/${taps.size} down at ${tap.label()} after ${wait}ms queued=$queued")
                    Thread.sleep(HOLD_MILLIS)
                    // Lift at the point pressed, not with an empty report, which leaves the slot's
                    // coordinates at zero and so releases at the screen's top-left corner. A real
                    // finger keeps its coordinates on ACTION_UP (see CarPlayTouchMapper). The stop
                    // sequence worked with the empty release too, so iOS evidently ignores the
                    // position on a release; this is correctness, not a fix for a known symptom.
                    controller.sendTouch(listOf(AirPlayContact(0, tap.x, tap.y, down = false)))
                    step("tap ${index + 1}/${taps.size} up at ${tap.label()} after ${HOLD_MILLIS}ms held")
                }
                step("$action finished: ${taps.size} tap(s) sent, foreground=$foreground " +
                    "initialDelay=${initialDelayMillis}ms result=$result. " +
                    "Sent is not pressed: DiPlay cannot see what was under the point.")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                step("$action failed: ${error.javaClass.simpleName}")
                Log.w(TAG, "replay failed", error)
            } finally {
                // A contact must never be left down if a step threw between down and up. An empty
                // report is right here: this abandons a half-sent tap rather than completing one.
                runCatching { controller.sendTouch(emptyList()) }
                synchronized(this) { replaying = false }
                runCatching { onResult(result) }
            }
        }
    }

    internal fun encode(taps: List<Tap>): String = JSONArray().apply {
        taps.forEach { put(JSONObject().put("x", it.x).put("y", it.y).put("d", it.delayBeforeMillis)) }
    }.toString()

    internal fun decode(stored: String?): List<Tap> {
        if (stored.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        val taps = ArrayList<Tap>(array.length())
        for (index in 0 until minOf(array.length(), MAX_TAPS)) {
            val item = array.optJSONObject(index) ?: continue
            val x = item.optDouble("x", -1.0)
            val y = item.optDouble("y", -1.0)
            if (x !in 0.0..1.0 || y !in 0.0..1.0) continue
            taps.add(Tap(x, y, item.optLong("d", MIN_DELAY_MILLIS).coerceIn(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS)))
        }
        return taps
    }
}

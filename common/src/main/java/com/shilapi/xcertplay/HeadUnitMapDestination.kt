package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.BydMapDestinationLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Experimental: the destination the head unit's AMap is guiding to, read from AMap's standard broadcast
 * protocol (AUTONAVI_STANDARD_BROADCAST_SEND from the map, ..._RECV from apps asking the map to navigate),
 * and handed to the iPhone as a requestUI URL when the driver asks. CarPlay lets the car pass a place, not
 * a route: the iPhone plans its own route.
 *
 * Which broadcast carries the destination depends on the map build and the firmware, so every key seen is
 * recorded for the diagnostic report by name and value type only. Places and coordinates stay in memory.
 * DiPlay's own guidance broadcasts are addressed to BYD's adapter package and do not arrive here.
 */
internal object HeadUnitMapDestination {
    private const val TAG = "DiPlay-MapDestination"
    const val ACTION_FROM_MAP = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val ACTION_TO_MAP = "AUTONAVI_STANDARD_BROADCAST_RECV"
    private const val MAX_SIGNATURES = 40

    /**
     * How often the map's log is read. A read is a grep on the car and a few lines over a loopback
     * socket, so it is cheap; the interval is what the driver waits. Measured on a Di150VCP at the
     * old three seconds: median 2.7 s between the car planning a route and DiPlay seeing it, worst
     * case 4.7 s. One second puts the median under a second, which reads as immediate.
     */
    private const val LOG_POLL_MILLIS = 1_000L

    private const val MAX_JSON_DEPTH = 4
    private const val MAX_JSON_ARRAY_ITEMS = 5

    /** AMap's "navigate to this point" request (POINAME, LAT, LON, DEV), sent to the map by other apps. */
    private val DIRECT_NAVIGATION_KEY_TYPES = setOf(10038)

    private val LATITUDE = Regex("LATITUDE|LAT")
    private val LONGITUDE = Regex("LONGITUDE|LON|LNG")
    private val NOT_DESTINATION = Regex("CAR|CUR|START|MID|VIA|PASS|(^|[_.\\]])S#|(^|[_.\\]])FROM_#")
    private val DESTINATION = Regex("DEST|END|TERMINAL|TARGET|ARRIV|(^|[_.\\]])D#|(^|[_.\\]])TO_#")

    /**
     * The region names a BYD DiLink 5 map broadcasts for the trip (KEY_TYPE 10002): city and town or
     * district of the destination, without a coordinate or a place. City first, then town, as in
     * "<city><district>"; they repeat for a municipality, so a repeated name is kept once.
     */
    private val REGION_KEYS = listOf("TO_CITY_NAME", "TO_TOWN_NAME", "TO_AD_NAME")

    /** Numeric or boolean state flags, which name no place and are logged with their value. */
    private val STATE_KEYS = Regex("KEY_TYPE|EXTRA_STATE|EXTRA_IS[_A-Z]*|EXTRA_LINKTYPE|IS_BYD_MAP")

    /**
     * Either a point or, when the map broadcasts no coordinate, a region name the iPhone has to look up.
     * [source] names the action and keys it came from, for the report; it holds no place or coordinate.
     */
    data class Destination(
        val latitude: Double?,
        val longitude: Double?,
        val name: String?,
        val keyType: Int?,
        val source: String,
        /** In the order the map plans them, read from its log; the broadcasts carry none. */
        val via: List<Waypoint> = emptyList(),
        /**
         * AMap's own id for the place, as the head unit's map logs it. The same ids identify the same
         * places in the iPhone's AMap, and its URI documentation asks for one in preference to a
         * coordinate, so passing it beats passing a name to look up or a coordinate that may land on
         * a compound's centre rather than its entrance.
         */
        val id: String? = null,
    ) {
        /** True when no coordinate is known, so the iPhone has to find the place from [id] or [name]. */
        val region: Boolean get() = latitude == null || longitude == null

        /**
         * True when the iPhone can be pointed at the exact place: a coordinate, or AMap's own id for
         * it. An id without a coordinate is still exact — more so, the documentation preferring it —
         * so a destination read by name and id is not the vague district a broadcast region is.
         */
        val exact: Boolean get() = !region || id != null

        fun label(): String = name ?: String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }

    /** A via point of the head unit's route. Always has a coordinate; its name is for the driver. */
    data class Waypoint(val name: String?, val latitude: Double, val longitude: Double) {
        fun label(): String = name ?: String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }

    /**
     * How the destination is handed to the iPhone. AMap coordinates are GCJ-02 and are passed unchanged
     * with dev=0, which declares them already encrypted.
     *
     * Only AMap's documented URIs are offered, all supported since the iPhone app's V5.0.0: an
     * undocumented path makes AMap report that the app needs an update. iosamap://navi starts guidance at
     * once but requires lat/lon, so it cannot carry a region. Route planning (iosamap://path and the
     * cross-platform amapuri://route/plan) opens the route page, which needs one more tap, but takes a
     * POI id, a name or a coordinate, so it can carry a destination that has no coordinate at all.
     *
     * No AMap URI carries a vehicle type: the documented parameters are sourceApplication, poiname,
     * poiid, lat, lon, dev and style for navi, and the route planning set for path. The car/electric/
     * motorcycle sheet the iPhone shows is AMap's own, driven by the vehicle saved in the app, and
     * nothing in a link can dismiss it.
     *
     * [carriesVia] marks the links that can hold the head unit's via points. Apple's maps links chain
     * stops as "A+to:B+to:C" in daddr; AMap's route planning takes them as parallel vian/vialons/
     * vialats/vianames lists. Only iosamap://navi cannot, having one destination and no route.
     * CarPlay never carries the route itself, only places: the iPhone plans its own way between them.
     */
    enum class Link(val needsCoordinates: Boolean = false, val carriesVia: Boolean = false) {
        APPLE_MAPS(carriesVia = true),
        APPLE_MAPS_WEB(carriesVia = true),
        AMAP_NAVI(needsCoordinates = true),
        AMAP_PATH(carriesVia = true),
        AMAP_URI(carriesVia = true);

        /** The URL for [destination], or null when this link cannot carry it. */
        fun url(destination: Destination, includeVia: Boolean = true): String? {
            val latitude = destination.latitude
            val longitude = destination.longitude
            val name = destination.name
            val via = if (includeVia && carriesVia) destination.via else emptyList()
            if (latitude == null || longitude == null) {
                if (needsCoordinates) return null
                // AMap's route planning identifies a place by id, by name or by coordinate, in that
                // order of preference; Apple's links have only the name to go on.
                if (this == APPLE_MAPS || this == APPLE_MAPS_WEB) {
                    val query = encode(name ?: return null)
                    return if (this == APPLE_MAPS) "maps:?daddr=$query&dirflg=d"
                    else "https://maps.apple.com/?daddr=$query&dirflg=d"
                }
                if (destination.id == null && name == null) return null
                return amapRoute(destination, null, null, via)
            }
            val point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
            // Via points go in the order the map plans them, with the destination as the last stop.
            val stops = if (via.isNotEmpty()) {
                via.joinToString("+to:", postfix = "+to:$point") {
                    String.format(Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
                }
            } else {
                point
            }
            return when (this) {
                APPLE_MAPS -> "maps:?daddr=$stops&dirflg=d"
                APPLE_MAPS_WEB -> "https://maps.apple.com/?daddr=$stops&dirflg=d"
                // style is ignored by AMap for iPhone since 7.7.4; the app's own route preference wins.
                AMAP_NAVI -> buildString {
                    append(String.format(Locale.US, "iosamap://navi?sourceApplication=DiPlay&lat=%.6f&lon=%.6f",
                        latitude, longitude))
                    name?.let { append("&poiname=").append(encode(it)) }
                    destination.id?.let { append("&poiid=").append(encode(it)) }
                    append("&dev=0&style=0")
                }
                AMAP_PATH, AMAP_URI -> amapRoute(destination, latitude, longitude, via)
            }
        }

        /**
         * AMap's route planning URI. The documentation asks for the POI id in preference to a
         * coordinate, a returned coordinate often being a compound's centre rather than its entrance.
         * Both travel when both are known, so the id places the pin and the coordinate is the fallback.
         */
        private fun amapRoute(
            destination: Destination,
            latitude: Double?,
            longitude: Double?,
            via: List<Waypoint>,
        ): String = buildString {
            append(amapPrefix()).append("?sourceApplication=DiPlay")
            destination.id?.let { append("&did=").append(encode(it)) }
            if (latitude != null && longitude != null) {
                append(String.format(Locale.US, "&dlat=%.6f&dlon=%.6f", latitude, longitude))
            }
            destination.name?.let { append("&dname=").append(encode(it)) }
            if (via.isNotEmpty()) {
                // Four parallel lists, which AMap requires to be the same length.
                append("&vian=").append(via.size)
                append("&vialons=").append(via.joinToString("|") { String.format(Locale.US, "%.6f", it.longitude) })
                append("&vialats=").append(via.joinToString("|") { String.format(Locale.US, "%.6f", it.latitude) })
                append("&vianames=").append(via.joinToString("|") { encode(it.name ?: "") })
            }
            append("&dev=0&t=0")
        }

        private fun amapPrefix(): String = if (this == AMAP_URI) "amapuri://route/plan/" else "iosamap://path"

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }

    enum class SendResult { SENT, NO_DESTINATION, NO_SESSION, NEEDS_COORDINATES }

    /** The point if the map ever broadcast one, otherwise the region. A region never replaces a point. */
    val latest: Destination? get() = latestPoint ?: latestRegion

    @Volatile private var latestPoint: Destination? = null
    @Volatile private var latestRegion: Destination? = null

    /** True once the head unit's map has ended guidance and no new route has been planned since. */
    @Volatile var stopped = false
        private set

    private val signatures = LinkedHashMap<String, Int>()
    private var destinationsFound = 0
    private var receiver: BroadcastReceiver? = null
    private var logExecutor: ScheduledExecutorService? = null
    private var sentRouteKey: String? = null
    private var autoSends = 0
    private var stops = 0

    /**
     * How long a route with no coordinate is held back, in case the map is about to log the same
     * route with one.
     *
     * The map writes one destination as a run of increasingly complete lines: `buildRequestParam`
     * when it starts working the route out, carrying a name and sometimes a POI id but never a
     * point; then the presenter's line with the coordinate once the route is computed; then the
     * name alone again as it is stored. Acting on the first of those is acting on the least of them.
     *
     * Two faults came from that. In one, the id-only line went over and the coordinate arrived three
     * seconds later — by then the same route, so it was never re-sent, and the iPhone was left to
     * resolve a name and asked which of several addresses was meant. In the other, the region went
     * over and the point three seconds after it, a different route by its key, so CarPlay was sent
     * somewhere twice in quick succession.
     *
     * [BydMapDestinationLog.parseAll] already folds such a run into its richest line, but only
     * within one poll's window, and these runs straddle polls. Waiting here covers the rest. The
     * cost is this much delay for firmware that logs no coordinate at all, which is the case the
     * wait cannot help anyway.
     */
    private const val SETTLE_MILLIS = 5_000L

    /** A coordinate-less route waiting out [SETTLE_MILLIS]; null when nothing is held. */
    private var heldRoute: Destination? = null
    private var heldSince = 0L

    /** The last failure of the log reader, or null while it is working. */
    @Volatile private var logError: String? = null

    /**
     * Mirrors each route, stop and hand-over into the host's session log, which is what the diagnostic
     * report carries; the counters alone cannot show the order things happened in. Never given a place
     * or a coordinate, so the report stays free of them.
     */
    @Volatile var onEvent: ((String) -> Unit)? = null

    private fun event(message: String) {
        Log.i(TAG, message)
        onEvent?.invoke(message)
        NavigationDebugLog.event(message)
    }

    /**
     * A probe result, which goes to the session log only. Logging it through [Log] would put it in
     * logcat, where the probe's own next poll would read it back and log it again, nesting on every
     * pass; the shell filters DiPlay's tag as well, so the loop is cut at both ends.
     */
    private fun probeEvent(message: String) {
        onEvent?.invoke(message)
    }

    /** Listens while the setting is on, for the life of the process; idempotent. */
    fun sync(context: Context) = synchronized(this) {
        val app = context.applicationContext
        val enabled = AirPlayPersistence.loadMapDestinationListen(app)
        if (enabled && receiver == null) {
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onBroadcast(intent)
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_FROM_MAP)
                addAction(ACTION_TO_MAP)
            }
            // Sent by the map app and by other apps, so it has to be exported.
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(created, filter, Context.RECEIVER_EXPORTED)
                } else {
                    app.registerReceiver(created, filter)
                }
                receiver = created
                Log.i(TAG, "listening for map broadcasts")
            }.onFailure { Log.w(TAG, "map broadcasts unavailable", it) }
        } else if (!enabled && receiver != null) {
            runCatching { app.unregisterReceiver(receiver) }
            receiver = null
            latestPoint = null
            latestRegion = null
            signatures.clear()
            destinationsFound = 0
            sentRouteKey = null
            stopped = false
            Log.i(TAG, "stopped listening for map broadcasts")
        }
        syncLog(app, enabled && AirPlayPersistence.loadMapDestinationAdb(app))
        syncDebug(app)
    }

    /**
     * Raw reads are collected while either navigation troubleshooting switch is on: the window shows
     * them live, and the report carries them in its own section. Collection is not tied to the window
     * being visible, since the report is usually exported after the drive, not during it. With both
     * off nothing is collected and the buffer is dropped.
     */
    private fun syncDebug(app: Context) {
        val wanted = AirPlayPersistence.loadNavigationDebugOverlay(app) ||
            AirPlayPersistence.loadMapDestinationProbe(app)
        if (wanted) {
            NavigationDebugLog.start()
            BydMapDestinationLog.onRawRead = { command, output ->
                NavigationDebugLog.raw("adb ${command.substringBefore(" | ")}", output)
            }
        } else {
            BydMapDestinationLog.onRawRead = null
            NavigationDebugLog.stop()
        }
    }

    /**
     * The map's broadcasts carry a coordinate on some firmware and only the destination's region on
     * others; its log carries the point either way, so the optional ADB reader fills that gap. One
     * poll thread, never the caller's, and the reader is closed on it so the link is not dropped
     * underneath a read in progress.
     */
    private fun syncLog(app: Context, enabled: Boolean) {
        if (enabled && logExecutor == null) {
            logExecutor = Executors.newSingleThreadScheduledExecutor {
                Thread(it, "diplay-map-destination-log").apply { isDaemon = true }
            }.also {
                it.scheduleWithFixedDelay({ pollLog(app) }, 0, LOG_POLL_MILLIS, TimeUnit.MILLISECONDS)
            }
            Log.i(TAG, "reading the head-unit map log over ADB")
        } else if (!enabled && logExecutor != null) {
            // A followed log never returns by itself; end it first so the close below can run.
            BydMapDestinationLog.cancelFollow()
            logExecutor?.let { executor ->
                executor.execute { BydMapDestinationLog.close() }
                executor.shutdown()
            }
            logExecutor = null
            Log.i(TAG, "stopped reading the head-unit map log")
        }
    }

    private fun pollLog(app: Context) {
        val probe = AirPlayPersistence.loadMapDestinationProbe(app)
        // After the first read has set the state, follow the log: each line arrives as the map writes
        // it, instead of up to two and a half seconds later. This call blocks while the stream runs;
        // when it ends the executor comes back a second later, follows again, or polls if this
        // firmware's logcat cannot follow.
        if (BydMapDestinationLog.canFollow) {
            val executor = logExecutor
            val ok = runCatching {
                BydMapDestinationLog.follow(app, probe,
                    keepGoing = {
                        logExecutor === executor && executor?.isShutdown == false &&
                            AirPlayPersistence.loadMapDestinationProbe(app) == probe
                    },
                    onTick = { flushHeldRoute(app) },
                    onPoll = { handle(app, it) },
                )
            }.getOrDefault(false)
            if (!ok && BydMapDestinationLog.canFollow) event("map log stream ended; following again")
            if (!BydMapDestinationLog.canFollow) event("this head unit's logcat cannot follow; polling every second instead")
            flushHeldRoute(app)
            return
        }
        val poll = runCatching { BydMapDestinationLog.poll(app, probe) }
            .onFailure {
                // Recorded for the report: a reader that never starts is otherwise silent there.
                val failure = "${it.javaClass.simpleName}: ${it.message?.take(160)}"
                if (failure != logError) event("map log unreadable: $failure")
                logError = failure
                Log.w(TAG, "map log unreadable", it)
            }
            .getOrNull() ?: return
        if (logError != null) event("map log readable again")
        logError = null
        handle(app, poll)
    }

    private fun handle(app: Context, poll: BydMapDestinationLog.Poll) {
        // Shapes only, and identical ones fold in the session log, so a quiet poll costs one line.
        poll.candidates.forEach { candidate ->
            // The shape is all the session log can hold; the raw line is what the buffer is for.
            probeEvent("candidate line: ${candidate.shape}")
            NavigationDebugLog.raw("candidate line", candidate.raw)
        }
        // In order: a stop and the route that replaced it can arrive in one window, and acting only
        // on the last of them once ended CarPlay's guidance while the car was still navigating.
        poll.events.forEach { event ->
            when (event) {
                is BydMapDestinationLog.Event.Route -> onRoutePlanned(app, event.entry, poll.initial)
                is BydMapDestinationLog.Event.Stopped -> onNavigationStopped(app, poll.initial)
            }
        }
        flushHeldRoute(app)
    }

    private fun onRoutePlanned(app: Context, entry: BydMapDestinationLog.Entry, initial: Boolean) {
        val place = entry.destination
        val found = Destination(place.latitude, place.longitude, place.name, null, "map log ${entry.at}",
            entry.via.mapNotNull { via ->
                // A via point without a coordinate cannot be a stop in a link, so it is left out.
                val latitude = via.latitude
                val longitude = via.longitude
                if (latitude == null || longitude == null) null else Waypoint(via.name, latitude, longitude)
            },
            place.id)
        val changed = synchronized(this) {
            // A named place has no coordinate, so it is a region: it must not displace a real point
            // of the same route, which is what the AutoSDK line gives when the firmware logs both.
            if (found.region) {
                if (latestPoint != null) return
                latestRegion = found
            } else {
                latestPoint = found
            }
            stopped = false
            destinationsFound++
            val key = routeKey(found)
            (key != sentRouteKey).also { if (it) sentRouteKey = key }
        }
        val follow = AirPlayPersistence.loadMapDestinationFollow(app)
        event("route from the map log at ${entry.at} via=${entry.via.size} newRoute=$changed " +
            "precision=${precision(found)} " +
            "initialRead=$initial follow=$follow")
        // The first read looks back hours, so its route may be finished: it sets the state, never sends.
        if (initial || !follow) return
        if (!changed) {
            // The coordinate a held route was waiting for. The POI id keys both lines, so this one is
            // not a new route, and returning here left the hold to run its full five seconds although
            // the point had arrived after one: the iPhone then got the id instead of the point, late.
            val completesHeld = !found.region && synchronized(this) {
                (heldRoute != null).also { if (it) heldRoute = null }
            }
            if (!completesHeld) return
            event("the coordinate the held route was waiting for arrived; handing it over now")
            handOver(app, "the held route, with its coordinate")
            return
        }
        // A line with no coordinate is the first of a run, not the whole of it: hold it in case the
        // map is about to log the same route with a point. See SETTLE_MILLIS.
        if (found.region) {
            synchronized(this) {
                heldRoute = found
                heldSince = SystemClock.elapsedRealtime()
            }
            event("hand-over held for up to ${SETTLE_MILLIS}ms: this line carries no coordinate, " +
                "and the map usually logs the same route with one a moment later")
            return
        }
        synchronized(this) { heldRoute = null }
        handOver(app, "the new route")
    }

    /**
     * Hands whatever [latest] is to the iPhone and runs the start-guidance taps. Shared by the
     * immediate path and the held one, so a held route is handed over exactly as a direct one is.
     */
    private fun handOver(app: Context, what: String) {
        val result = send(AirPlayPersistence.loadMapDestinationLink(app),
            AirPlayPersistence.loadMapDestinationVia(app))
        synchronized(this) {
            autoSends++
            // A route that could not be handed over must not block the next attempt at the same place.
            if (result != SendResult.SENT) sentRouteKey = null
        }
        event("handed $what to the iPhone: $result")
        // AMap opens its route preview and waits for 开始导航; the recorded tap presses it. Only after
        // a successful hand-over, so nothing is tapped when the destination never reached the phone.
        if (result != SendResult.SENT || !AirPlayPersistence.loadCarPlayStartAuto(app)) return
        val delay = AirPlayPersistence.loadCarPlayStartDelayMillis(app)
        CarPlayGuidanceStopper.replay(app, CarPlayGuidanceStopper.Action.START, delay) {
            event("replayed the recorded start-guidance taps after ${delay}ms: $it")
        }
    }

    /**
     * Hands over a route held for want of a coordinate, once its wait is up. Called from the poll,
     * which runs every second, so no thread of its own is needed.
     */
    private fun flushHeldRoute(app: Context) {
        val held = synchronized(this) {
            val route = heldRoute ?: return
            if (SystemClock.elapsedRealtime() - heldSince < SETTLE_MILLIS) return
            heldRoute = null
            route
        }
        // A stop in the meantime cleared the destination, so there is nothing left to hand over.
        if (latest == null || stopped) {
            event("held hand-over dropped: the map stopped before the wait was up")
            return
        }
        event("hand-over wait is up and no coordinate arrived; " +
            "sending ${precision(held)} as the map logged it")
        handOver(app, "the held route")
    }

    /**
     * The head unit arrived or the driver cancelled. CarPlay has no car-to-phone command that ends the
     * iPhone's guidance, so DiPlay forgets the route, which stops it being handed over again, and
     * optionally replays the driver's recorded "end route" taps (see [CarPlayGuidanceStopper]).
     */
    private fun onNavigationStopped(app: Context, initial: Boolean) {
        if (!AirPlayPersistence.loadMapDestinationFollowStop(app)) return
        synchronized(this) {
            if (stopped) return
            stopped = true
            latestPoint = null
            latestRegion = null
            sentRouteKey = null
            // A route still waiting for its coordinate belongs to the trip that just ended.
            heldRoute = null
            if (!initial) stops++
        }
        event("the head-unit map stopped navigating; destination cleared (initialRead=$initial)")
        // The first read looks back hours, so its stop is history: it must not tap the screen now.
        if (initial || !AirPlayPersistence.loadCarPlayStopAuto(app)) return
        CarPlayGuidanceStopper.replay(app, CarPlayGuidanceStopper.Action.STOP) {
            event("replayed the recorded end-guidance taps: $it")
        }
    }

    /** How precisely the iPhone can be aimed, for the report: an id is as good as a coordinate. */
    private fun precision(destination: Destination): String = when {
        !destination.region -> "point"
        destination.id != null -> "poi id"
        else -> "region"
    }

    /**
     * Identifies a route by its places, so a re-plan to the same stops is not handed over twice.
     *
     * Deliberately not keyed on the name alone, though the name is the one field every form of the
     * line carries: a region and the point that follows it name the same place, and collapsing them
     * would suppress the point — leaving the iPhone on the keyword search the region produced, which
     * is the worse of the two outcomes. Sending the vague one and then the exact one is handled by
     * holding the vague one back instead; see [SETTLE_MILLIS].
     */
    private fun routeKey(destination: Destination): String = buildString {
        destination.via.forEach { append(String.format(Locale.US, "%.6f,%.6f;", it.latitude, it.longitude)) }
        val latitude = destination.latitude
        val longitude = destination.longitude
        when {
            // The id names one place whichever way the log happened to mention it, so it keys best.
            destination.id != null -> append("poi:").append(destination.id)
            latitude != null && longitude != null ->
                append(String.format(Locale.US, "%.6f,%.6f", latitude, longitude))
            else -> append("name:").append(destination.name.orEmpty())
        }
    }

    /** Asks the iPhone to open [link] for the latest destination. */
    fun send(link: Link, includeVia: Boolean = true): SendResult {
        val destination = latest ?: return SendResult.NO_DESTINATION
        val url = link.url(destination, includeVia) ?: return SendResult.NEEDS_COORDINATES
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        if (controller?.activeAirPlaySessionToken() == null) return SendResult.NO_SESSION
        return if (controller.requestUi(url)) SendResult.SENT else SendResult.NO_SESSION
    }

    fun report(): String = synchronized(this) {
        buildString {
            appendLine("listening=${receiver != null} destinationsFound=$destinationsFound")
            appendLine("map log over ADB: reading=${logExecutor != null} reads=${BydMapDestinationLog.reads} " +
                "mode=${if (BydMapDestinationLog.following) "following" else "polling"} " +
                "followFailures=${BydMapDestinationLog.followFailures} " +
                "adbReachable=${BydMapDestinationLog.reachable ?: "not tried"}" +
                (logError?.let { " lastError=$it" } ?: ""))
            appendLine("head-unit navigation: stopped=$stopped autoSends=$autoSends stopsSeen=$stops")
            val last = latest
            appendLine(if (last == null) "last destination: none" else
                "last destination: keyType=${last.keyType} from ${last.source} " +
                    "precision=${precision(last)} viaPoints=${last.via.size} " +
                    "(places and coordinates omitted)")
            appendLine("broadcasts seen (key names and value types only):")
            if (signatures.isEmpty()) appendLine("none")
            signatures.forEach { (signature, count) -> appendLine("x$count $signature") }
        }.trimEnd()
    }

    private fun onBroadcast(intent: Intent) {
        val extras = intent.extras ?: return
        val values = runCatching { flatten(bundleValues(extras)) }.getOrElse {
            Log.w(TAG, "unreadable map broadcast ${intent.action}", it)
            return
        }
        record(intent.action, values)
    }

    internal fun record(action: String?, values: Map<String, Any?>) = synchronized(this) {
        val signature = signature(action, values)
        val count = signatures.remove(signature)
        if (count == null) Log.i(TAG, "new map broadcast: $signature")
        signatures[signature] = (count ?: 0) + 1
        // The report keeps key names and value types; the window shows what the values actually were.
        if (NavigationDebugLog.collecting) {
            NavigationDebugLog.raw("broadcast ${shortAction(action)}",
                values.entries.sortedBy { it.key }.joinToString("\n") { "  ${it.key}=${it.value}" })
        }
        while (signatures.size > MAX_SIGNATURES) signatures.remove(signatures.keys.first())
        destination(action, values)?.let { found ->
            // The map keeps broadcasting the trip's region for a while after guidance ends, which would
            // otherwise resurrect a destination the log reader has just cleared and leave the settings
            // page showing a district for a drive that is over. A real new trip brings a route first.
            if (found.region && stopped) {
                event("broadcast region ignored: the head-unit map has stopped navigating")
                return@let
            }
            val previous = if (found.region) latestRegion else latestPoint
            if (previous?.source != found.source) {
                event("broadcast destination keyType=${found.keyType} " +
                    "precision=${precision(found)} from ${found.source}")
            }
            if (found.region) latestRegion = found else latestPoint = found
            if (!found.region) stopped = false
            destinationsFound++
        }
    }

    /** Stands in for the log reader seeing the map end its guidance. */
    internal fun markStoppedForTest() = synchronized(this) {
        stopped = true
        latestPoint = null
        latestRegion = null
        sentRouteKey = null
    }

    internal fun resetForTest() = synchronized(this) {
        latestPoint = null
        latestRegion = null
        signatures.clear()
        destinationsFound = 0
        sentRouteKey = null
        stopped = false
        autoSends = 0
        stops = 0
        heldRoute = null
        heldSince = 0L
    }

    internal fun signature(action: String?, values: Map<String, Any?>): String {
        val keyType = number(values["KEY_TYPE"])?.toInt()
        // State flags carry their value: what "navigation ended" looks like is firmware-specific, and a
        // report that shows only the type cannot tell a start from an end. They hold no place.
        val keys = values.entries.sortedBy { it.key }.joinToString(", ") { (key, value) ->
            if (STATE_KEYS.matches(key.uppercase(Locale.ROOT))) "$key=$value" else "$key(${typeName(value)})"
        }
        return "${shortAction(action)} keyType=$keyType keys=[$keys]"
    }

    /** The destination in one broadcast, or null when it carries none. A point beats a region. */
    internal fun destination(action: String?, values: Map<String, Any?>): Destination? {
        val byUpper = values.keys.sorted().associateBy { it.uppercase(Locale.ROOT) }
        return point(action, values, byUpper) ?: region(action, values, byUpper)
    }

    private fun point(action: String?, values: Map<String, Any?>, byUpper: Map<String, String>): Destination? {
        val keyType = number(values["KEY_TYPE"])?.toInt()
        for ((upperLat, latKey) in byUpper) {
            val latMatch = LATITUDE.find(upperLat) ?: continue
            val base = upperLat.replaceRange(latMatch.range, "#")
            if (!destinationBase(base, action, keyType)) continue
            val lonKey = byUpper.entries.firstOrNull { (upper, _) ->
                upper != upperLat && LONGITUDE.find(upper)?.let { upper.replaceRange(it.range, "#") } == base
            }?.value ?: continue
            val latitude = number(values[latKey]) ?: continue
            val longitude = number(values[lonKey]) ?: continue
            if (!plausible(latitude, longitude)) continue
            return Destination(latitude, longitude, name(values, byUpper, base, keyType), keyType,
                "${shortAction(action)} $latKey/$lonKey")
        }
        return null
    }

    /**
     * The destination's city and district, which a BYD DiLink 5 map broadcasts without a coordinate.
     * The iPhone looks the name up, so it routes to the district, not to the place the driver chose.
     */
    private fun region(action: String?, values: Map<String, Any?>, byUpper: Map<String, String>): Destination? {
        val present = REGION_KEYS.mapNotNull { key ->
            byUpper[key]?.let { values[it] as? String }?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it }
        }
        if (present.isEmpty()) return null
        val name = present.map { it.second }.distinct().joinToString("")
        return Destination(null, null, name, number(values["KEY_TYPE"])?.toInt(),
            "${shortAction(action)} ${present.joinToString("+") { it.first }}")
    }

    private fun destinationBase(base: String, action: String?, keyType: Int?): Boolean = when {
        NOT_DESTINATION.containsMatchIn(base) -> false
        DESTINATION.containsMatchIn(base) -> true
        else -> base == "#" && action == ACTION_TO_MAP && keyType in DIRECT_NAVIGATION_KEY_TYPES
    }

    private fun name(values: Map<String, Any?>, byUpper: Map<String, String>, base: String, keyType: Int?): String? {
        val candidates = buildList {
            add(base.replace("#", "NAME"))
            if (base == "#" && keyType in DIRECT_NAVIGATION_KEY_TYPES) add("POINAME")
        }
        return candidates.firstNotNullOfOrNull { upper ->
            (byUpper[upper]?.let { values[it] } as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun plausible(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (kotlin.math.abs(latitude) > 1e-4 || kotlin.math.abs(longitude) > 1e-4)

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun shortAction(action: String?): String = when (action) {
        ACTION_FROM_MAP -> "SEND"
        ACTION_TO_MAP -> "RECV"
        else -> action ?: "?"
    }

    private fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is JsonMarker -> "json"
        else -> value.javaClass.simpleName
    }

    /** Stands for a string extra that held JSON; its fields follow as "key.field". */
    private object JsonMarker

    @Suppress("DEPRECATION")
    private fun bundleValues(bundle: Bundle): Map<String, Any?> = bundle.keySet().associateWith { bundle.get(it) }

    /** Opens nested bundles and JSON strings into dotted keys, so a destination inside them is found too. */
    internal fun flatten(values: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        values.forEach { (key, value) -> flattenInto(result, key, value, 0) }
        return result
    }

    private fun flattenInto(result: MutableMap<String, Any?>, key: String, value: Any?, depth: Int) {
        if (depth > MAX_JSON_DEPTH) return
        when (value) {
            is Bundle -> bundleValues(value).forEach { (child, v) -> flattenInto(result, "$key.$child", v, depth + 1) }
            is JSONObject -> value.keys().forEach { child -> flattenInto(result, "$key.$child", value.opt(child), depth + 1) }
            is JSONArray -> for (index in 0 until minOf(value.length(), MAX_JSON_ARRAY_ITEMS)) {
                flattenInto(result, "$key[$index]", value.opt(index), depth + 1)
            }
            is String -> {
                val trimmed = value.trim()
                val json = if (trimmed.startsWith("{") || trimmed.startsWith("[")) runCatching {
                    if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
                }.getOrNull() else null
                if (json == null) {
                    result[key] = value
                } else {
                    result[key] = JsonMarker
                    flattenInto(result, key, json, depth)
                }
            }
            JSONObject.NULL -> result[key] = null
            else -> result[key] = value
        }
    }
}

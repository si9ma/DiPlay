package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional, needs ADB over the network: the route the head unit's map is guiding along, taken from
 * the map's own log. BYD's DiLink map broadcasts only the destination's city and district, but the
 * AutoSDK build underneath it logs each planned route at error level:
 *
 *     E/BaseNaviPresenter(…): …RouteCarResultData{mMidPois=[POI{id='…', name='…',
 *     point=GeoPoint{lon=…, lat=…}}], mFromPoi=POI{…}, mToPoi=POI{id='B0XXXXXXXX', name='…',
 *     point=GeoPoint{lon=…, lat=…}}, …}
 *
 * The destination and the via points are read; mFromPoi is the car's own position and is not. The
 * path itself is not logged, only a PathInfo object address, so the route's shape cannot be read.
 * Coordinates are GCJ-02, as everywhere in AMap, and are passed on unchanged. adbd runs on the same
 * device as DiPlay, so logcat's timestamps share this process's clock and a window can be asked for
 * directly; only lines holding a route cross the link, and DiPlay never logs the places themselves.
 *
 * Blocking; run off the main thread.
 */
object BydMapDestinationLog {
    /** [latitude] and [longitude] are null where the firmware logs the destination by name only. */
    data class Place(
        val id: String?,
        val name: String?,
        val latitude: Double?,
        val longitude: Double?,
    )

    /** [at] is the log's own timestamp, kept for the report so a stale route can be recognised. */
    data class Entry(val at: String, val destination: Place, val via: List<Place>)

    /** The newest thing the map did in the window read. [at] is the log line's own timestamp. */
    sealed interface Event {
        val at: String

        /** A route was planned: a new destination, or the same one planned again. */
        data class Route(val entry: Entry) : Event {
            override val at: String get() = entry.at
        }

        /** Guidance ended, by arriving or by the driver cancelling it. */
        data class Stopped(override val at: String) : Event
    }

    /**
     * [initial] marks the first read, which looks back hours, so its route may be long finished.
     * [candidates] are the shapes of lines that looked like they might carry a destination but that
     * this parser did not recognise; empty unless the probe setting is on.
     */
    data class Poll(
        val event: Event?,
        val initial: Boolean,
        val candidates: List<Candidate> = emptyList(),
        /** Every event newer than the last reported, oldest first; [event] is the last of these. */
        val events: List<Event> = listOfNotNull(event),
    )

    /** An unrecognised line, as written by the car and as a shape safe to share. */
    data class Candidate(val raw: String, val shape: String)

    /**
     * One POI with a point. Used for the route's end and for each via point.
     *
     * Every brace and bracket is escaped, including the closing ones: Android's java.util.regex is
     * ICU-backed and rejects a dangling `}` or `]`, while the desktop JVM accepts it, so an unescaped
     * one compiles in unit tests and throws on the car, which kills this object's initializer.
     */
    internal val POI = Regex(
        "POI\\{id='([^']*)',\\s*name='([^']*)',\\s*point=GeoPoint\\{" +
            "lon=(-?\\d+(?:\\.\\d+)?),\\s*lat=(-?\\d+(?:\\.\\d+)?)\\}"
    )

    /** The route's end. The same line carries mFromPoi and the via points, which must not match here. */
    internal val ROUTE = Regex("mToPoi=${POI.pattern}")

    /** The via points, anchored on the field that follows so a name holding a bracket cannot run on. */
    internal val VIA = Regex("mMidPois=\\[(.*?)\\],\\s*mFromPoi=")

    /** The destination's name where the firmware logs no coordinate; runs to the end of the line. */
    internal val NAMED = Regex("$NAME_MARKER:\\s*toPoi=(.+)$", RegexOption.MULTILINE)

    /** The POI id, logged on its own line right after the name. */
    internal val POI_ID = Regex("$ID_MARKER\\s+poiId:(\\S+)")

    /**
     * The destination in a route request: a name with the POI id run onto its end, as in
     * `end = <name>B0XXXXXXXX, via = []`. AMap's ids are a letter and nine more characters of upper
     * case and digits, which is what separates the two without a delimiter to go by.
     */
    internal val REQUEST_END = Regex("end\\s*=\\s*(.*?)([A-Z][0-9A-Z]{9})?,\\s*via\\s*=")

    /** `logcat -v time` line prefix, in the device's local time. */
    internal val LINE_TIME = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ")

    /** Quoted text in a log line is a name or an id: replaced by its length, which is structure. */
    private val QUOTED = Regex("'[^']*'|\"[^\"]*\"")

    /** Any coordinate, distance or bearing. */
    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    /**
     * An adcode, a path id or a timestamp. The threshold is six because AMap's broadcast state codes
     * are five digits (10002, 10019, 10038) and are what tells one line's meaning from another, while
     * a district's adcode starts at six and does say where the driver is.
     */
    private val LONG_INT = Regex("\\d{6,}")

    /** A place name written outside quotes; dropped rather than guessed at. */
    private val NON_ASCII = Regex("[^\\p{ASCII}]+")

    /**
     * Guidance ending. `onNaviStop` is logged by the AutoSDK presenters; `clearRouteCarResultData` is
     * logged by BYD's own navigation layer, which on some firmware is all that appears — a Di150VCP
     * running the Kld map build writes the latter and never the former.
     */
    private val STOP = Regex("onNaviStop|clearRouteCarResultData")

    /** For the shell's grep, which is ERE and has no Kotlin escaping. */
    private const val STOP_PATTERN = "onNaviStop|clearRouteCarResultData"

    /**
     * The route's end, as the AutoSDK presenters log it. Rich: it carries the via points too, so it is
     * preferred where both appear.
     */
    private const val MARKER = "mToPoi=POI"

    /**
     * The route's end as BYD's own navigation layer logs it, which some firmware writes instead:
     *
     *     E/NavigationUtil(…): [main]storeRouteCarResultData: toPoi=<name>
     *     E/MomentaUtil(…): [SomeIpClient_Thread]createEndPoiInfo poiId:B0XXXXXXXX mapIdsByPoiId:[]
     *
     * A name and, on the next line, the POI id — but no coordinate, so this alone cannot start AMap's
     * navi link. It is still worth reading: a name is a place, where the broadcasts give a district.
     */
    private const val NAME_MARKER = "storeRouteCarResultData"
    private const val ID_MARKER = "createEndPoiInfo"

    /**
     * The route request, logged by BYD's drive module as each route is planned:
     *
     *     E/ModuleDriveServiceImpl(…): [buildRequestParam] start = POI{…}, end = <name>B0XXXXXXXX, via = []
     *
     * `end` is the destination's name with its POI id run together, and no coordinate. Of the markers
     * seen on a Di150VCP this is the one that appeared for every route planned, where the richer
     * `mToPoi` line did not, so it is the dependable fallback. `start` is the car and is not read.
     */
    private const val REQUEST_MARKER = "buildRequestParam"

    /**
     * Wider than [MARKER]: what a destination might plausibly look like on firmware whose map build
     * this parser does not know. Only used while the probe setting is on, because it matches far more
     * of the log — a route's end under another name, a via list, a point of any kind, the result
     * object a route arrives in. The shell greps on the car, so only the matches cross the link.
     */
    private const val CANDIDATE =
        "ToPoi|MidPois|RouteCarResultData|GeoPoint|[Dd]estPoi|[Ee]ndPoi|[Tt]argetPoi|storeRoute|createEndPoi"

    /**
     * DiPlay's own tag. Everything logged through [android.util.Log] reaches logcat, so a candidate
     * written there is matched by the next poll and logged again, each pass wrapping the one before:
     * on a car this nested five deep in two minutes and crowded out the real lines. The shell drops
     * them before they cross the link, which also keeps the transfer small.
     */
    private const val OWN_TAG = "DiPlay-MapDestination"

    /**
     * SELinux's auditd records the argv of every process the shell starts, into logcat:
     *
     *     W/auditd(483): type=1309 audit(0.0:64589): argc=3 a0="grep" a1="-E"
     *     a2="mToPoi=POI|storeRouteCarResultData|…|onNaviStop|clearRouteCarResultData"
     *
     * So a pattern written plainly matches the audit record of the very poll that looked for it. On a
     * Di150VCP this read back as the map having stopped 24 times in one drive, while the car's own
     * navigation logged no stop at all; each one cleared the destination, and the car's next periodic
     * reroute then looked like a new route and was handed over again — CarPlay's guidance ended and
     * restarted, repeatedly, on a drive that never stopped.
     *
     * Two nets. This one drops audit records in the shell, before they cross the link.
     */
    private const val AUDIT_TAG = "auditd"

    /**
     * Rewrites an ERE so that its own text cannot match it, by bracketing the last character of each
     * alternative: `onNaviStop` becomes `onNaviSto[p]`, which still matches "onNaviStop" but no longer
     * matches the pattern's own text, since that now reads `onNaviSto[p]` and has a `[` where the
     * regex wants a `p`. The second net, and the one that does not depend on knowing which component
     * echoed the command — [AUDIT_TAG] only covers the channel that was caught doing it.
     */
    internal fun selfExcluding(pattern: String): String = pattern.split('|').joinToString("|") { part ->
        val last = part.lastOrNull()
        if (last != null && last.isLetterOrDigit()) "${part.dropLast(1)}[$last]" else part
    }

    /**
     * Every poll re-reads the last [OVERLAP_MILLIS], rather than only what is new since the previous
     * one. The coordinate-bearing line is written once per route and nowhere else — a probe of a
     * guiding car held four of them in 23 minutes — so a single window that misses it loses the
     * destination until the next route is planned. Overlapping windows are safe because an event is
     * keyed on the log line's own timestamp and one already reported is dropped.
     *
     * Twenty seconds covers a poll that was slow or skipped while the screen slept, without making
     * every read re-parse minutes of a busy log.
     */
    private const val OVERLAP_MILLIS = 20L * 1000

    /** [OVERLAP_MILLIS] of marker lines, with room for a car that re-plans repeatedly. */
    private const val TAIL = 60

    /**
     * The probe's wider pattern is read by its own command, so its matches can never push a marker
     * out of the markers' own `tail`. Noise evicting signal is exactly what a diagnostic must not do.
     */
    private const val PROBE_TAIL = 40

    /** Per poll, after shapes are deduplicated: enough to see the format, not enough to flood. */
    private const val MAX_CANDIDATES = 6

    /** A shape is structure, not content; anything longer is a payload that will not help. */
    private const val MAX_SHAPE_CHARS = 400

    /** A chained link stays usable; AMap's own limit for a route is well under this. */
    private const val MAX_VIA = 8

    /** First read only: a route planned before DiPlay started still counts. */
    private const val FIRST_LOOKBACK_MILLIS = 6L * 60 * 60 * 1000

    /** The first read covers hours of a busy log, so it needs more than the shell's usual window. */
    private const val FIRST_TIMEOUT_MILLIS = 25_000

    private val shell = BydAdbShell("DiPlay-MapDestinationLog")

    /** Not thread-safe; every call happens on the single poll thread. */
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var since: String? = null

    /** The log timestamp of the newest event already reported, so an overlap repeats nothing. */
    private var lastEventAt: String? = null

    /** Null until the log has been read once, then whether adbd answered. */
    @Volatile var reachable: Boolean? = null
        private set

    @Volatile var reads = 0
        private set

    /**
     * Receives the command and the car's answer for each read, verbatim. Null unless the navigation
     * debug window is up: the text holds the driver's real destination, so it is for the screen in
     * front of them and never for the session log, which a diagnostic report carries.
     */
    @Volatile var onRawRead: ((String, String?) -> Unit)? = null

    /** The newest route or stop not yet reported. [probe] additionally collects [candidates]. */
    fun poll(context: Context, probe: Boolean = false): Poll {
        val now = System.currentTimeMillis()
        val first = since == null
        // The first read reaches back hours for a route planned before DiPlay started; later ones
        // re-read a fixed overlap, so a line is never lost to the gap between two windows.
        val window = if (first) {
            format.format(Date(now - FIRST_LOOKBACK_MILLIS))
        } else {
            format.format(Date(now - OVERLAP_MILLIS))
        }
        val markers = command(window)
        val output = shell.run(context, markers, if (first) FIRST_TIMEOUT_MILLIS else null)
        reads++
        onRawRead?.invoke(markers, output)
        if (output == null) {
            reachable = false
            return Poll(null, first)
        }
        reachable = true
        since = window
        // Every event newer than the last reported, in order: a stop and the route that replaced it
        // can share a window, and acting only on the newest would drop one of the two.
        val events = parseAll(output).filter { fresh(it.at) }
        events.lastOrNull()?.let { lastEventAt = it.at }
        // Its own command, so the wide pattern cannot crowd the markers out of their tail.
        val candidates = if (probe) {
            val probeCommand = probeCommand(window)
            val probeOutput = shell.run(context, probeCommand)
            onRawRead?.invoke(probeCommand, probeOutput)
            candidates(probeOutput.orEmpty())
        } else {
            emptyList()
        }
        return Poll(events.lastOrNull(), first, candidates, events)
    }

    /**
     * True for an event the caller has not been given yet. Timestamps are `MM-dd HH:mm:ss.SSS`, which
     * sorts correctly within a year; the turn of the year can repeat one event, which costs a resend.
     */
    private fun fresh(at: String): Boolean {
        val seen = lastEventAt ?: return true
        return at > seen
    }

    fun close() {
        cancelFollow()
        shell.close()
        since = null
        reachable = null
        lastEventAt = null
        followFailures = 0
    }

    /**
     * Following the log rather than polling it. A poll read the last 20 s every second and parsed it
     * only after a second, wider read for the probe, so a stop the map logged at 19:00:11.855 was
     * acted on at 19:00:14.146 — 1.4 to 2.5 s every time, on top of the replayed taps. A followed log
     * hands each line over as the map writes it.
     *
     * logcat filters by itself (`-e`), with no grep in a pipe: a pipe stage that buffers its output
     * would hold a stop back silently, and there would be no way to tell that from a quiet map.
     */
    internal fun followCommand(since: String, probe: Boolean): String {
        val markers = "$MARKER|$NAME_MARKER|$ID_MARKER|$REQUEST_MARKER|$STOP_PATTERN"
        return "logcat -v time -T '$since' -e '${bracketEveryCharacter(if (probe) "$markers|$CANDIDATE" else markers)}'"
    }

    /**
     * [selfExcluding] for a pattern whose alternatives overlap. Bracketing only the last character is
     * not enough once the probe's words join the markers: the text `mToPoi=PO[I]` still holds `ToPoi`,
     * which `ToPo[i]` matches. With every letter and digit in a class of its own, the pattern's text
     * never has two of them side by side outside an existing class, and every alternative needs two.
     */
    internal fun bracketEveryCharacter(pattern: String): String = buildString {
        var inClass = false
        for (c in pattern) {
            when {
                c == '[' -> { inClass = true; append(c) }
                c == ']' -> { inClass = false; append(c) }
                !inClass && c.isLetterOrDigit() -> append('[').append(c).append(']')
                else -> append(c)
            }
        }
    }

    /** False once following has failed quickly [MAX_FOLLOW_FAILURES] times: the poll is used instead. */
    val canFollow: Boolean get() = since != null && followFailures < MAX_FOLLOW_FAILURES

    @Volatile var following = false
        private set

    /** Quick failures in a row; a firmware whose logcat has no `-e` ends every stream at once. */
    @Volatile var followFailures = 0
        private set

    @Volatile private var followGeneration = 0
    @Volatile private var followAdb: com.shilapi.xcertplay.adb.LocalAdb? = null

    /** Ends a running [follow] from any thread, at once. */
    fun cancelFollow() {
        followGeneration++
        followAdb?.cancelPendingOperations()
    }

    /**
     * Streams the map log until [keepGoing] turns false, the link fails or [cancelFollow] is called,
     * handing every batch of new events to [onPoll] and calling [onTick] about once a second for work
     * that runs on time rather than on lines (the held route's wait). Returns false on a failure.
     */
    fun follow(context: Context, probe: Boolean, keepGoing: () -> Boolean, onTick: () -> Unit, onPoll: (Poll) -> Unit): Boolean {
        val start = since ?: return false
        val generation = followGeneration
        // From the previous window's start: whatever the map wrote between the last poll and now is
        // read again, and the timestamps drop what was already reported.
        val window = maxOf(start, format.format(Date(System.currentTimeMillis() - OVERLAP_MILLIS)))
        val command = followCommand(window, probe)
        val adb = com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(context))
        followAdb = adb
        val pending = java.io.ByteArrayOutputStream()
        val batch = mutableListOf<String>()
        var lastLineAt = 0L
        var firstLineAt = 0L
        var lastTick = SystemClock.elapsedRealtime()
        val started = lastTick

        fun flush() {
            if (batch.isEmpty()) return
            val text = batch.joinToString("\n")
            batch.clear()
            reads++
            onRawRead?.invoke(command, text)
            val events = parseAll(text).filter { fresh(it.at) }
            events.lastOrNull()?.let { lastEventAt = it.at }
            val candidates = if (probe) candidates(text.lineSequence().filter { CANDIDATE_LINE.containsMatchIn(it) }
                .joinToString("\n")) else emptyList()
            if (events.isNotEmpty() || candidates.isNotEmpty()) onPoll(Poll(events.lastOrNull(), false, candidates, events))
        }

        fun stillWanted(): Boolean {
            if (generation != followGeneration || !keepGoing()) return false
            val now = SystemClock.elapsedRealtime()
            // One route is logged as a burst of lines from several components a few milliseconds
            // apart, and the id comes on the line after the name; parsing a burst whole keeps them
            // together, as a poll's window did, at the cost of a fraction of a second.
            if (batch.isNotEmpty() && (now - lastLineAt >= BATCH_QUIET_MILLIS || now - firstLineAt >= BATCH_MAX_MILLIS)) {
                flush()
            }
            if (now - lastTick >= TICK_MILLIS) {
                lastTick = now
                onTick()
            }
            return true
        }

        following = true
        reachable = true
        val ok = try {
            adb.stream(command, IDLE_CHECK_MILLIS, ::stillWanted) { bytes ->
                pending.write(bytes)
                val buffered = pending.toByteArray()
                val end = buffered.lastIndexOf('\n'.code.toByte())
                if (end >= 0) {
                    pending.reset()
                    pending.write(buffered, end + 1, buffered.size - end - 1)
                    val now = SystemClock.elapsedRealtime()
                    String(buffered, 0, end, Charsets.UTF_8).lineSequence()
                        .filter { it.isNotBlank() && !it.contains(OWN_TAG) && !quotesACommand(it) }
                        .forEach {
                            if (batch.isEmpty()) firstLineAt = now
                            batch += it
                            lastLineAt = now
                        }
                }
            }
        } finally {
            following = false
            if (followAdb === adb) followAdb = null
            runCatching { adb.close() }
        }
        flush()
        val lasted = SystemClock.elapsedRealtime() - started
        // A stream that ends at once is one this firmware will not run; one that ran a while and
        // then broke is a dropped link, worth following again.
        if (generation == followGeneration && keepGoing()) {
            followFailures = if (lasted < QUICK_FAILURE_MILLIS) followFailures + 1 else 0
            if (!ok) reachable = false
        }
        return ok
    }

    private const val IDLE_CHECK_MILLIS = 100
    private const val BATCH_QUIET_MILLIS = 150L
    private const val BATCH_MAX_MILLIS = 600L
    private const val TICK_MILLIS = 1_000L
    private const val QUICK_FAILURE_MILLIS = 5_000L
    private const val MAX_FOLLOW_FAILURES = 3
    private val CANDIDATE_LINE by lazy { Regex(CANDIDATE) }

    /** grep keeps the transfer to route and stop lines; tail bounds a log that planned many routes. */
    internal fun command(since: String): String =
        "logcat -d -v time -T '$since' | " +
            "grep -E '${selfExcluding("$MARKER|$NAME_MARKER|$ID_MARKER|$REQUEST_MARKER|$STOP_PATTERN")}' | " +
            "grep -v -F '$OWN_TAG' | grep -v -F '$AUDIT_TAG' | tail -$TAIL"

    /** The probe's own read, so its matches share no `tail` with the markers'. */
    internal fun probeCommand(since: String): String =
        "logcat -d -v time -T '$since' | grep -E '${selfExcluding(CANDIDATE)}' | " +
            "grep -v -F '$OWN_TAG' | grep -v -F '$AUDIT_TAG' | tail -$PROBE_TAIL"

    /**
     * The lines this parser did not recognise, one per distinct shape. Recognised lines are left out:
     * what troubleshooting needs is what the parser is missing, and a firmware whose format is already
     * understood should produce nothing here.
     *
     * Both forms travel: the shape for the session log, which a report shares and which redacts the
     * raw line away anyway, and the line itself for the navigation buffer, where it is the point.
     */
    internal fun candidates(output: String): List<Candidate> {
        val seen = LinkedHashMap<String, String>()
        output.lineSequence()
            .filter { it.isNotBlank() }
            // An audit record quoting this poll's own grep is not a shape the parser is missing.
            .filterNot { quotesACommand(it) || ROUTE.containsMatchIn(it) || it.contains(STOP) }
            .forEach { line -> seen.putIfAbsent(shape(line), line.trim()) }
        return seen.entries.take(MAX_CANDIDATES).map { (shape, raw) -> Candidate(raw, shape) }
    }

    /**
     * A log line reduced to its shape: field names, class names and punctuation kept, every value
     * replaced. The shape is what a parser has to match, so it is all troubleshooting needs, while
     * the values are the driver's destination and have no place in a report meant to be shared.
     * Shapes repeat exactly, so the session log folds them into one line and a count.
     */
    internal fun shape(line: String): String {
        val withoutTime = LINE_TIME.replace(line, "")
        val quoted = QUOTED.replace(withoutTime) { "'…${(it.value.length - 2).coerceAtLeast(0)}'" }
        return NON_ASCII.replace(LONG_INT.replace(DECIMAL.replace(quoted, "#.#"), "#"), "…")
            .trim()
            .take(MAX_SHAPE_CHARS)
    }

    /**
     * The newest event in [output], and every event after the one last reported.
     *
     * A window is re-read with an overlap, so it holds events the caller has already seen as well as
     * new ones. Reporting only the newest is not enough: a stop and the route that followed it can
     * land in the same window, and a stop that has already been acted on must never be acted on
     * again. A stop at 14:02:40 followed by a route at 14:02:31 in the *previous* window once read
     * as "stopped" on the next poll, ending CarPlay's guidance while the car was still navigating.
     *
     * Within one window the newest event wins, except that a route logged several times in a second
     * or two yields the richest of those lines rather than the last — only some of them carry the
     * coordinate, so taking the last would throw a point away for the name logged beneath it.
     */
    internal fun parse(output: String): Event? = parseAll(output).lastOrNull()

    /** Every event in [output], oldest first, each route folded to its richest line. */
    internal fun parseAll(output: String): List<Event> {
        val events = events(output)
        val result = mutableListOf<Event>()
        var run = mutableListOf<Event.Route>()
        fun flushRoutes() {
            if (run.isEmpty()) return
            // The same route, logged by several parts of the map; keep the one that says the most.
            result.add(run.firstOrNull { it.entry.destination.latitude != null } ?: run.last())
            run = mutableListOf()
        }
        events.forEach { event ->
            when (event) {
                is Event.Route -> {
                    // Compared with the line before it, not with the first of the run: one route is
                    // logged by several components, and the earliest of them names it least fully.
                    if (run.isNotEmpty() && !sameRoute(run.last().entry, event.entry)) flushRoutes()
                    run.add(event)
                }
                // One stop writes several lines across the map's components; that is one event, so
                // consecutive stops collapse. A route between two of them keeps them apart.
                is Event.Stopped -> {
                    flushRoutes()
                    if (result.lastOrNull() !is Event.Stopped) result.add(event)
                }
            }
        }
        flushRoutes()
        return result
    }

    /**
     * A line that is quoting a command rather than reporting navigation. The last net under the two
     * in the shell: a marker read out of an audit record is a marker the map never wrote, and acting
     * on one ends guidance on a car that is still driving to somewhere.
     */
    internal fun quotesACommand(line: String): Boolean = AUDIT.containsMatchIn(line)

    /** SELinux audit records, which quote the argv of every process the poll's own shell starts. */
    private val AUDIT = Regex("""\bauditd\b|\btype=\d+ audit\(|\ba0="""")

    /**
     * Whether two log lines describe the same route. One route is logged by several components, each
     * naming it differently: the request line has a name and an id, the presenter a name and a point,
     * the store line a name alone. So any field the two agree on makes them the same route, and only
     * a field they both carry and disagree on makes them different.
     */
    private fun sameRoute(first: Entry, second: Entry): Boolean {
        val a = first.destination
        val b = second.destination
        a.id?.let { left -> b.id?.let { right -> return left == right } }
        a.name?.let { left -> b.name?.let { right -> return left == right } }
        if (a.latitude != null && b.latitude != null) {
            return a.latitude == b.latitude && a.longitude == b.longitude
        }
        // Nothing in common to compare, so they are assumed to be the same route rather than split.
        return true
    }

    private fun events(output: String): List<Event> {
        // The id arrives on its own line just after the name, so it is carried to the entry below it.
        var pendingId: String? = null
        return output.lineSequence().filterNot(::quotesACommand).mapNotNull { line ->
            POI_ID.find(line)?.let { pendingId = it.groupValues[1].takeIf(String::isNotBlank) }
            val at = { LINE_TIME.find(line)?.groupValues?.get(1).orEmpty() }
            place(ROUTE.find(line))?.let { destination ->
                val via = VIA.find(line)?.groupValues?.get(1).orEmpty()
                pendingId = null
                return@mapNotNull Event.Route(Entry(at(), destination,
                    POI.findAll(via).mapNotNull { place(it) }.take(MAX_VIA).toList()))
            }
            // A name without a coordinate: worth less than a point, but better than a district.
            NAMED.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                return@mapNotNull Event.Route(Entry(at(),
                    Place(pendingId, name, null, null), emptyList()))
            }
            if (line.contains(REQUEST_MARKER)) {
                REQUEST_END.find(line)?.let { match ->
                    val name = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    val id = match.groupValues[2].takeIf { it.isNotEmpty() }
                    if (name != null || id != null) {
                        return@mapNotNull Event.Route(Entry(at(), Place(id, name, null, null), emptyList()))
                    }
                }
            }
            if (STOP.containsMatchIn(line)) Event.Stopped(at()) else null
        }.toList()
    }

    private fun place(match: MatchResult?): Place? {
        val values = match?.groupValues ?: return null
        val longitude = values[3].toDoubleOrNull() ?: return null
        val latitude = values[4].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (kotlin.math.abs(latitude) < 1e-4 && kotlin.math.abs(longitude) < 1e-4) return null
        return Place(
            id = values[1].takeIf { it.isNotBlank() },
            name = values[2].trim().takeIf { it.isNotEmpty() },
            latitude = latitude,
            longitude = longitude,
        )
    }
}

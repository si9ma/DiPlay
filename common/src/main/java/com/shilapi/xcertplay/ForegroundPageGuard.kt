package com.shilapi.xcertplay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Decides whether the floating CarPlay window should step aside for whatever the car has in front.
 * App overlays sit above every activity and cannot be placed under one, so the window is hidden
 * instead. The signal is Usage Access: activities count as visible from RESUMED until
 * STOPPED/DESTROYED, and events from other displays (the instrument cluster runs com.byd.sr /
 * com.byd.launchermap activities too) do not decide what is in front here.
 *
 * With "only on pages" on, CarPlay is shown only while one of the chosen pages (or DiPlay itself) is
 * the resumed activity. A page that pauses hides CarPlay as soon as the next activity resumes, which
 * is the earliest signal Usage Access offers (STOPPED arrives only after the new screen has drawn).
 * Without Usage Access that rule is skipped, never hiding.
 * "Split screen only" additionally needs another app resumed beside the page: on Android 10+ both
 * halves of a split are resumed at once, while a page going full screen (BYD auto parking runs in
 * the same com.byd.sr/.MainActivity) pauses the other half.
 * An empty page list leaves the rule inactive: hiding everything would look like a broken overlay.
 * Pages on the "never" list hide CarPlay while resumed, in every mode and ahead of the "only on"
 * list. An entry that also matches a chosen page is contradictory (BYD auto parking is the very
 * same com.byd.sr/.MainActivity as the lane view) and is ignored, or CarPlay would never show.
 * Recently resumed pages are kept in [recentPages] so the settings screen can offer them.
 * Hiding is immediate; showing again waits [RESTORE_DELAY_MILLIS]. [onChange] runs on the main
 * thread with (hide, reason).
 */
internal class ForegroundPageGuard(
    context: Context,
    private val log: (String) -> Unit,
    private val onChange: (Boolean, String) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var executor: ScheduledExecutorService? = null

    // Guard thread only.
    private var since = 0L
    private data class ActivityKey(val pkg: String, val cls: String?, val instance: Int) {
        override fun toString() = if (cls == null) pkg else "$pkg/${cls.removePrefix(pkg)}"
        fun matches(entry: String) = entry == pkg || entry == cls || entry == "$pkg/$cls" ||
            (cls != null && entry.startsWith("$pkg/.") && "$pkg${entry.substring(pkg.length + 1)}" == cls)
    }
    private data class EventKey(val pkg: String?, val cls: String?, val instance: Int, val type: Int, val time: Long)
    private val visible = LinkedHashMap<ActivityKey, Long>()
    /** Resumed and not yet paused: the activity in front on its display. */
    private val resumed = HashMap<ActivityKey, Long>()
    /** Uptime when a resumed activity paused and nothing has resumed since; 0 when settled. */
    private var transitionSince = 0L
    private var lastEventTime = 0L
    private var reportedResumed: Set<ActivityKey> = emptySet()
    private val seen = HashMap<EventKey, Long>()
    private var reportedVisible: List<ActivityKey> = emptyList()
    private val instanceIdMethod = runCatching { UsageEvents.Event::class.java.getMethod("getInstanceId") }.getOrNull()
    private var ticks = 0
    private var lastActive = false
    private var inactiveSince = 0L
    private var usageAccess = false
    private var onlyOnWarned = false
    private var conflictWarned = false

    /** Main thread. */
    fun start() {
        if (executor != null) return
        // From boot, so a page opened long before CarPlay started is still known to be on screen.
        since = bootTime(); visible.clear(); resumed.clear(); seen.clear(); reportedVisible = emptyList(); ticks = 0
        reportedResumed = emptySet(); transitionSince = 0L; lastEventTime = 0L
        lastActive = false; inactiveSince = 0L
        usageAccess = HomeScreenMonitor.hasAccess(context); onlyOnWarned = false; conflictWarned = false
        executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-page-guard").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS) }
        log("Page guard started usageAccess=$usageAccess " +
            "onlyOn=${if (AirPlayPersistence.loadCarPlayOnlyOn(context)) AirPlayPersistence.loadCarPlayOnlyOnPackages(context).sorted() else "off"} " +
            "requireSplit=${AirPlayPersistence.loadCarPlayRequireSplit(context)} " +
            "neverOn=${AirPlayPersistence.loadCarPlayNeverOnPackages(context).sorted()}")
    }

    /** Main thread. */
    fun stop() {
        val running = executor ?: return
        executor = null
        running.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    /**
     * Whether any rule needs to know what is in front. The "never" list works on its own, so it has
     * to be asked for separately: gating the tracking on "only on pages" alone would leave a chosen
     * hiding page with nothing watching for it.
     */
    private fun tracking(): Boolean = AirPlayPersistence.loadCarPlayOnlyOn(context) ||
        AirPlayPersistence.loadCarPlayNeverOnPackages(context).isNotEmpty()

    private fun poll() {
        if (ticks % ACCESS_EVERY_TICKS == 0) usageAccess = HomeScreenMonitor.hasAccess(context)
        if (tracking()) {
            val queryStart = SystemClock.uptimeMillis()
            trackActivities()
            val queryMs = SystemClock.uptimeMillis() - queryStart
            val now = visible.entries.sortedByDescending { it.value }.map { it.key }
            val front = resumed.keys.toSet()
            if (now != reportedVisible || front != reportedResumed) {
                reportedVisible = now; reportedResumed = front
                val lag = eventLag()
                val listed = now.map { if (it in front) "$it*" else "$it" }
                // Logged so a CarPlay page can be identified from a report; * = resumed.
                val split = splitPartner()?.let { "split with $it" } ?: "no split"
                post { log("Page guard: foreground app ${front()?.pkg} visible=$listed $split lag=${lag}ms query=${queryMs}ms") }
            }
        } else if (reportedVisible.isNotEmpty()) {
            reportedVisible = emptyList(); reportedResumed = emptySet()
            visible.clear(); resumed.clear(); seen.clear(); since = bootTime(); transitionSince = 0L
        }
        ticks++
        evaluate()
    }

    /** The most recently resumed activity still in front, else the newest visible one. */
    private fun front(): ActivityKey? =
        resumed.maxByOrNull { it.value }?.key ?: visible.maxByOrNull { it.value }?.key

    private fun eventLag(): Long =
        if (lastEventTime == 0L) -1 else (System.currentTimeMillis() - lastEventTime).coerceAtLeast(0)

    private fun evaluate() {
        // Between DiPlay pausing and the next activity resuming nothing is in front; keep the current
        // state instead of hiding for that instant (it would flicker when returning to a page).
        if (transitionSince != 0L) {
            if (SystemClock.uptimeMillis() - transitionSince < TRANSITION_HOLD_MILLIS) return
            transitionSince = 0L
        }
        val reasons = buildList {
            onlyOnReason()?.let(::add)
            val never = neverOnPages()
            if (never.isNotEmpty()) resumed.keys.filter { key -> never.any(key::matches) }
                .forEach { add("page $it hides CarPlay") }
        }
        val active = reasons.isNotEmpty()
        val now = System.currentTimeMillis()
        val report = when {
            active && !lastActive -> true
            !active && lastActive -> {
                if (inactiveSince == 0L) inactiveSince = now
                now - inactiveSince >= RESTORE_DELAY_MILLIS
            }
            else -> false
        }
        if (active) inactiveSince = 0L
        if (report) {
            lastActive = active
            inactiveSince = 0L
            val lag = eventLag()
            val reason = (if (active) reasons.joinToString(", ") else "nothing to step aside for") +
                if (lag in 0..5_000) "; ${lag}ms after the activity event" else ""
            post { onChange(active, reason) }
        }
    }

    /**
     * Guard thread. The "never" list without entries that would also cancel a chosen page: with both
     * lists naming the same activity CarPlay could never show, which looks like a broken overlay.
     * "Only in split screen" is the way to separate full-screen parking from the split lane view.
     */
    private fun neverOnPages(): Set<String> {
        val never = AirPlayPersistence.loadCarPlayNeverOnPackages(context)
        if (never.isEmpty() || !AirPlayPersistence.loadCarPlayOnlyOn(context)) return never
        val pages = AirPlayPersistence.loadCarPlayOnlyOnPackages(context)
        // Same text in both lists, or a package entry that swallows a chosen page (and the reverse).
        val conflicting = never.filter { entry -> entry in pages || pages.any { page -> covers(entry, page) || covers(page, entry) } }
        if (conflicting.isEmpty()) return never
        if (!conflictWarned) {
            conflictWarned = true
            post { log("Page guard: $conflicting is in both the CarPlay pages and the hiding pages, " +
                "so it is ignored (CarPlay would never show). Use 'only in split screen' to hide the " +
                "full-screen page instead.") }
        }
        return never - conflicting.toSet()
    }

    /** True when [wide] matches everything [narrow] matches, e.g. "com.byd.sr" vs "com.byd.sr/.MainActivity". */
    private fun covers(wide: String, narrow: String): Boolean =
        !wide.contains('/') && (narrow == wide || narrow.startsWith("$wide/") || narrow.startsWith("$wide."))

    /** Guard thread. Non-null when "only on pages" wants CarPlay hidden for the current foreground app. */
    private fun onlyOnReason(): String? {
        if (!AirPlayPersistence.loadCarPlayOnlyOn(context)) return null
        if (!usageAccess) {
            if (!onlyOnWarned) {
                onlyOnWarned = true
                post { log("Page guard: only-on-pages is on but Usage Access is missing; CarPlay is not hidden") }
            }
            return null
        }
        val pages = AirPlayPersistence.loadCarPlayOnlyOnPackages(context)
        if (pages.isEmpty()) {
            if (!onlyOnWarned) {
                onlyOnWarned = true
                post { log("Page guard: only-on-pages is on but no page is listed; the rule is ignored. " +
                    "Add a page (default com.byd.sr) in DiPlay settings.") }
            }
            return null
        }
        val front = front()
        if (front?.pkg == context.packageName) return null
        val never = neverOnPages()
        val page = resumed.keys.firstOrNull { activity -> pages.any(activity::matches) && never.none(activity::matches) }
            ?: return "no CarPlay page in front (front ${front ?: "unknown"})"
        if (!AirPlayPersistence.loadCarPlayRequireSplit(context)) return null
        if (splitPartner(page) != null) return null
        return "CarPlay page $page is full screen (no second app in front)"
    }

    /**
     * Guard thread. Another resumed app beside [page] (default: any CarPlay page in front), i.e. the
     * other half of a split screen. Usage events carry no display id, so activities known to live on
     * the cluster/passenger screens and transparent helper activities are not accepted as partners.
     */
    private fun splitPartner(page: ActivityKey? = null): ActivityKey? {
        val pages = AirPlayPersistence.loadCarPlayOnlyOnPackages(context)
        val anchor = page ?: resumed.keys.firstOrNull { activity -> pages.any(activity::matches) } ?: return null
        return resumed.keys
            .filter { it != anchor && it.pkg != context.packageName && it.pkg != anchor.pkg && !offScreen(it) }
            .maxByOrNull { resumed[it] ?: 0L }
    }

    /** Resumed but not a visible half of the centre screen: another display, or a helper window. */
    private fun offScreen(activity: ActivityKey): Boolean =
        OTHER_SCREEN_ACTIVITIES.any { activity.matches(it) } ||
            activity.cls?.substringAfterLast('.')?.let { name ->
                name.startsWith("Empty") || name.endsWith("EmptyActivity")
            } == true

    private fun bootTime() = (System.currentTimeMillis() - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    /** Guard thread. Keeps [visible] in step with the usage event log. */
    private fun trackActivities() {
        val now = System.currentTimeMillis()
        if (now < since) { // Clock moved back: rebuild from boot.
            visible.clear(); resumed.clear(); seen.clear(); since = bootTime(); transitionSince = 0L
        }
        val events = runCatching { context.getSystemService(UsageStatsManager::class.java).queryEvents(since, now) }
            .getOrNull() ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type == UsageEvents.Event.DEVICE_SHUTDOWN || type == UsageEvents.Event.DEVICE_STARTUP) {
                visible.clear(); resumed.clear(); transitionSince = 0L
                continue
            }
            if (type != RESUMED && type != PAUSED && type != STOPPED && type != DESTROYED) continue
            val pkg = event.packageName ?: continue
            val cls = event.className
            // Instrument cluster activities are on another display.
            if (ClusterActivityState.accepted(pkg, cls)) continue
            val instance = runCatching { instanceIdMethod?.invoke(event) as? Int }.getOrNull() ?: 0
            if (seen.put(EventKey(pkg, cls, instance, type, event.timeStamp), event.timeStamp) != null) continue
            val key = ActivityKey(pkg, cls, instance)
            when (type) {
                RESUMED -> {
                    visible[key] = event.timeStamp; resumed[key] = event.timeStamp
                    transitionSince = 0L
                    if (pkg != context.packageName) rememberPage(key.toString(), event.timeStamp)
                }
                // Still on screen until STOPPED, but no longer in front.
                // Only DiPlay leaving holds the state: it goes back to the page CarPlay was shown on,
                // and hiding for that instant would flicker. A CarPlay page pausing hides right away.
                PAUSED -> if (resumed.remove(key) != null && pkg == context.packageName) {
                    transitionSince = SystemClock.uptimeMillis()
                }
                else -> { visible.remove(key); resumed.remove(key) }
            }
            if (event.timeStamp > lastEventTime) lastEventTime = event.timeStamp
        }
        since = (now - OVERLAP_MILLIS).coerceAtLeast(since)
        seen.entries.removeAll { it.value < since }
    }

    private fun post(block: () -> Unit) = main.post { if (executor != null) block() }

    companion object {
        private val recent = LinkedHashMap<String, Long>()

        private fun rememberPage(name: String, time: Long) = synchronized(recent) {
            recent.remove(name)
            recent[name] = time
            while (recent.size > RECENT_PAGES) recent.remove(recent.keys.first())
        }

        /** Pages resumed while CarPlay floated in this process, newest first, with their times. */
        fun recentPages(): List<Pair<String, Long>> = synchronized(recent) {
            recent.entries.map { it.key to it.value }.reversed()
        }

        /**
         * Activities that are resumed while another screen shows them, so they are never the other
         * half of a centre-screen split. Extend from a report's "visible=" line when a page here is
         * wrongly treated as a split partner.
         */
        private val OTHER_SCREEN_ACTIVITIES = setOf(
            // Dashboard turn-by-turn map (instrument display).
            "com.byd.launchermap/com.byd.automap.meter.MeterTbtActivity",
            "com.byd.launchermap/com.byd.automap.meter.MeterActivity",
            "com.byd.launchermap/com.byd.automap.meter.MeterSmallScreenActivity",
            // Transparent helpers of the stock projection service.
            "com.byd.projection.management/.service.TopEmptyActivity",
            "com.byd.projection.management/.service.BottomEmptyActivity",
        )

        private const val RECENT_PAGES = 30
        private const val POLL_MILLIS = 80L
        private const val ACCESS_EVERY_TICKS = 60
        private const val OVERLAP_MILLIS = 2_000L
        private const val RESUMED = 1 // UsageEvents ACTIVITY_RESUMED (MOVE_TO_FOREGROUND)
        private const val PAUSED = 2 // ACTIVITY_PAUSED: leaves the front, may still be visible
        private const val STOPPED = 23 // ACTIVITY_STOPPED
        private const val DESTROYED = 24 // ACTIVITY_DESTROYED
        // Showing needs a positive signal (a page resumed in front), so no extra settle time.
        private const val RESTORE_DELAY_MILLIS = 0L
        private const val TRANSITION_HOLD_MILLIS = 500L
    }
}

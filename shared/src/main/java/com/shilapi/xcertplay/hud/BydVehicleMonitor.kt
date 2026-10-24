package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.BydSignalBatch.isErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Read-only vehicle polling (autoservice getters only): cluster cards, gear, stock navigation and
 * charging for shortcut events, and battery details for the battery card. It runs only while a
 * listener is registered, reads only what its listeners asked for, and reads through the resident
 * [BydShellAgent] when it is up (its own adb shell otherwise). With the agent up, cluster changes
 * are also pushed by BYD's own listeners and evaluated at once; the poll stays the source of truth.
 * Listeners are called on the main thread.
 */
object BydVehicleMonitor {
    fun interface EventListener { fun onVehicleEvent(event: BydVehicleEvent) }
    fun interface BatteryListener { fun onBattery(update: BatteryUpdate) }

    enum class Link { CONNECTING, READY, NO_ADB, UNSUPPORTED }

    /** [details] is the latest sample, null until one was read; [link] says why it may be missing. */
    data class BatteryUpdate(val details: BydBatteryDetails?, val link: Link)

    private const val TAG = "DiPlay-BYD-Vehicle"
    private const val AGENT_OWNER = "vehicle"
    // The delay after each read; through the agent a read takes tens of ms, through `service call` far more.
    private const val TICK_MILLIS = 250L
    private const val BATTERY_MILLIS = 2_000L
    private const val READ_TIMEOUT_MILLIS = 6_000

    private val shell = BydAdbShell(TAG)
    private val main = Handler(Looper.getMainLooper())
    private val events = ConcurrentHashMap<EventListener, Set<BydVehicleEvent>>()
    private val battery = CopyOnWriteArraySet<BatteryListener>()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-vehicle-monitor").apply { isDaemon = true } }
    private val scheduleLock = Any()
    private var future: ScheduledFuture<*>? = null
    @Volatile private var context: Context? = null
    @Volatile private var latestBattery: BatteryUpdate = BatteryUpdate(null, Link.CONNECTING)
    private val pushed = BydShellAgent.PushListener { device, fid, value -> executor.execute { onPush(device, fid, value) } }

    // Worker-thread state.
    private val detector = BydVehicleEventDetector()
    private var protocol: BydBatteryProtocol? = null
    private var protocolKnown = false
    private var lastBatteryMillis = 0L
    private var eventSignals = emptyList<BydSignal>()
    private val latest = HashMap<BydSignal, Int?>()
    private var subscribedGeneration = -1
    private var subscribedSignals = emptyList<BydSignal>()
    private var pushApplied = 0L
    private val history = ArrayDeque<String>()

    /** Tests replace the shell and the CarPlay-guidance check. */
    @Volatile internal var shellRun: (Context, String) -> String? = { app, command -> shell.run(app, command, READ_TIMEOUT_MILLIS) }
    @Volatile internal var carPlayGuidance: () -> Boolean = { BydNavigationOutputs.carPlayGuidanceActive() }

    /** [wanted] are the events this listener acts on; the monitor reads only what they need. Adding again updates them. */
    fun addEventListener(context: Context, listener: EventListener, wanted: Set<BydVehicleEvent> = BydVehicleEvent.entries.toSet()) {
        this.context = context.applicationContext
        if (events.put(listener, wanted.toSet()) != wanted) note("event listener ${events.size}: ${wanted.joinToString(",")}")
        schedule()
    }

    fun removeEventListener(listener: EventListener) {
        if (events.remove(listener) != null) note("event listener removed (${events.size})")
        unscheduleIfIdle()
    }

    /** The listener gets the latest reading at once and then every [BATTERY_MILLIS]. */
    fun addBatteryListener(context: Context, listener: BatteryListener) {
        this.context = context.applicationContext
        val added = battery.add(listener)
        if (added) {
            val latest = latestBattery
            main.post { if (battery.contains(listener)) listener.onBattery(latest) }
            executor.execute { lastBatteryMillis = 0L }
        }
        schedule()
    }

    fun removeBatteryListener(listener: BatteryListener) {
        battery.remove(listener)
        unscheduleIfIdle()
    }

    fun report(): List<String> = synchronized(history) {
        listOf("Vehicle monitor: running=${future != null} eventListeners=${events.size} batteryListeners=${battery.size} " +
            "protocol=${protocol ?: if (protocolKnown) "unknown" else "unread"} battery=${latestBattery.link} " +
            "eventRead=$readSummary pushApplied=$pushApplied",
            "Battery sources: ${latestBattery.details?.sources?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: "none"}") +
            history + BydShellAgent.report()
    }

    private fun schedule() {
        val app = context ?: return
        synchronized(scheduleLock) {
            if (future != null) return
            future = executor.scheduleWithFixedDelay(::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
        }
        BydShellAgent.addPushListener(pushed)
        BydShellAgent.acquire(app, AGENT_OWNER)
    }

    private fun unscheduleIfIdle() {
        synchronized(scheduleLock) {
            if (events.isNotEmpty() || battery.isNotEmpty()) return
            future?.cancel(false)
            future = null
        }
        BydShellAgent.removePushListener(pushed)
        BydShellAgent.release(AGENT_OWNER)
        executor.execute {
            if (synchronized(scheduleLock) { future != null }) return@execute
            detector.reset()
            protocolKnown = false
            eventSignals = emptyList(); latest.clear()
            subscribedGeneration = -1; subscribedSignals = emptyList()
            latestBattery = BatteryUpdate(null, Link.CONNECTING)
            shell.close()
        }
    }

    private fun tick() {
        runCatching { poll() }.onFailure { Log.w(TAG, "vehicle poll failed", it) }
    }

    private fun wantedEvents(): Set<BydVehicleEvent> = events.values.flatMapTo(HashSet()) { it }

    /** Through the agent when it is up, else one `service call` per signal in one shell round trip. */
    private fun read(app: Context, signals: List<BydSignal>): Map<BydSignal, Int?>? {
        if (signals.isEmpty()) return emptyMap()
        BydShellAgent.read(signals)?.let { return it }
        return shellRun(app, BydSignalBatch.command(signals))?.let { BydSignalBatch.parse(it, signals) }
    }

    private fun poll() {
        val app = context ?: return
        val wanted = wantedEvents()
        val now = SystemClock.elapsedRealtime()
        val wantBattery = battery.isNotEmpty() && now - lastBatteryMillis >= BATTERY_MILLIS
        if (wanted.isEmpty() && !wantBattery) return syncPush(emptyList())
        if (!protocolKnown && (wantBattery || BydVehicleSignals.needsProtocol(wanted))) {
            val property = shellRun(app, "getprop ro.car.protocol")?.trim()
            if (property == null) return unavailable(wantBattery, now)
            val value = property.ifEmpty { shellRun(app, "getprop sys.car.protocol")?.trim().orEmpty() }
            protocol = when (value) { "CAN" -> BydBatteryProtocol.CAN; "CANFD" -> BydBatteryProtocol.CANFD; else -> null }
            protocolKnown = true
            note("protocol=${protocol ?: "unknown ($value)"}")
        }
        val signals = BydVehicleSignals.events(protocol, wanted)
        if (signals != eventSignals) {
            // Another read set: values kept from the old one would replay an old change as new.
            eventSignals = signals
            latest.clear()
            detector.reset()
        }
        if (signals.isNotEmpty()) {
            val started = SystemClock.elapsedRealtime()
            val values = read(app, signals) ?: return unavailable(wantBattery, now)
            recordReadMillis(SystemClock.elapsedRealtime() - started)
            latest.clear(); latest.putAll(values)
            evaluate()
        }
        syncPush(signals)
        // A separate, later read: the larger battery batch never delays an event already seen.
        if (wantBattery) {
            val reads = BydBatterySignals.all(protocol)
            val values = read(app, reads) ?: return unavailable(true, now)
            lastBatteryMillis = now
            val details = BydBatterySignals.decode(values, protocol)
            publish(BatteryUpdate(details, if (details.values.isEmpty()) Link.UNSUPPORTED else Link.READY))
        }
    }

    private fun evaluate() {
        val state = BydVehicleSignals.state(latest, protocol)
        for (event in detector.accept(state, carPlayGuidance(), SystemClock.elapsedRealtime())) {
            note("event $event")
            main.post { events.keys.forEach { it.onVehicleEvent(event) } }
        }
    }

    /** Keeps the agent's subscription on [signals]; a new agent needs it again. */
    private fun syncPush(signals: List<BydSignal>) {
        if (!BydShellAgent.ready || BydShellAgent.pushDisabled != null) return
        val generation = BydShellAgent.generation
        if (generation == subscribedGeneration && signals == subscribedSignals) return
        if (signals.isEmpty() && subscribedSignals.isEmpty()) { subscribedGeneration = generation; return }
        val results = BydShellAgent.subscribe(signals.map { it.device to it.fid }) ?: return
        subscribedGeneration = generation
        subscribedSignals = signals
        if (results.isNotEmpty()) note("push ${results.count { it == "ok" }}/${results.size} registered")
    }

    /** A value BYD's listener pushed between two polls; the next poll confirms or corrects it. */
    private fun onPush(device: Int, fid: Int, value: Int) {
        if (latest.isEmpty()) return
        val signal = eventSignals.firstOrNull { it.device == device && it.fid == fid } ?: return
        val decoded = value.takeUnless { it.isErrorCode() }
        if (latest[signal] == decoded) return
        latest[signal] = decoded
        pushApplied++
        evaluate()
    }

    // Worker thread; the report shows how long one event read takes on this car.
    private var readCount = 0L
    private var readTotalMillis = 0L
    private var readMaxMillis = 0L
    @Volatile private var readSummary = "none"
    private fun recordReadMillis(millis: Long) {
        readCount++; readTotalMillis += millis; readMaxMillis = maxOf(readMaxMillis, millis)
        readSummary = "last=${millis}ms avg=${readTotalMillis / readCount}ms max=${readMaxMillis}ms n=$readCount " +
            "via=${if (BydShellAgent.ready) "agent" else "shell"}"
    }

    private fun unavailable(wantBattery: Boolean, now: Long) {
        // A lost link starts a new baseline, so a change made while offline is not replayed.
        detector.reset()
        latest.clear()
        if (latestBattery.link != Link.NO_ADB) note("adb unavailable: ${shell.lastAccess}")
        if (wantBattery) {
            lastBatteryMillis = now
            publish(BatteryUpdate(null, Link.NO_ADB))
        } else if (latestBattery.link != Link.NO_ADB) latestBattery = BatteryUpdate(null, Link.NO_ADB)
    }

    private fun publish(update: BatteryUpdate) {
        latestBattery = update
        main.post { battery.forEach { it.onBattery(update) } }
    }

    private fun note(message: String) {
        synchronized(history) {
            if (history.size >= 20) history.removeFirst()
            history.addLast(message)
        }
        Log.i(TAG, message)
    }
}

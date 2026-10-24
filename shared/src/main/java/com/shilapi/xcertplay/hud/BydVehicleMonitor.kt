package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Read-only vehicle polling through the adb shell (autoservice getters only): cluster cards, gear,
 * stock navigation and charging for shortcut events, and battery details for the battery card. It
 * runs only while a listener is registered and closes its adb link when the last one leaves.
 * Listeners are called on the main thread.
 */
object BydVehicleMonitor {
    fun interface EventListener { fun onVehicleEvent(event: BydVehicleEvent) }
    fun interface BatteryListener { fun onBattery(update: BatteryUpdate) }

    enum class Link { CONNECTING, READY, NO_ADB, UNSUPPORTED }

    /** [details] is the latest sample, null until one was read; [link] says why it may be missing. */
    data class BatteryUpdate(val details: BydBatteryDetails?, val link: Link)

    private const val TAG = "DiPlay-BYD-Vehicle"
    // The delay after each read: the read itself (one adb round trip, one `service call` per signal) adds to it.
    private const val TICK_MILLIS = 250L
    private const val BATTERY_MILLIS = 2_000L
    private const val READ_TIMEOUT_MILLIS = 6_000

    private val shell = BydAdbShell(TAG)
    private val main = Handler(Looper.getMainLooper())
    private val events = CopyOnWriteArraySet<EventListener>()
    private val battery = CopyOnWriteArraySet<BatteryListener>()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-vehicle-monitor").apply { isDaemon = true } }
    private val scheduleLock = Any()
    private var future: ScheduledFuture<*>? = null
    @Volatile private var context: Context? = null
    @Volatile private var latestBattery: BatteryUpdate = BatteryUpdate(null, Link.CONNECTING)

    // Worker-thread state.
    private val detector = BydVehicleEventDetector()
    private var protocol: BydBatteryProtocol? = null
    private var protocolKnown = false
    private var lastBatteryMillis = 0L
    private val history = ArrayDeque<String>()

    /** Tests replace the shell and the CarPlay-guidance check. */
    @Volatile internal var shellRun: (Context, String) -> String? = { app, command -> shell.run(app, command, READ_TIMEOUT_MILLIS) }
    @Volatile internal var carPlayGuidance: () -> Boolean = { BydNavigationOutputs.carPlayGuidanceActive() }

    fun addEventListener(context: Context, listener: EventListener) {
        this.context = context.applicationContext
        if (events.add(listener)) note("event listener added (${events.size})")
        schedule()
    }

    fun removeEventListener(listener: EventListener) {
        if (events.remove(listener)) note("event listener removed (${events.size})")
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
            "protocol=${protocol ?: if (protocolKnown) "unknown" else "unread"} battery=${latestBattery.link} eventRead=$readSummary") + history
    }

    private fun schedule() {
        synchronized(scheduleLock) {
            if (future == null) future = executor.scheduleWithFixedDelay(::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun unscheduleIfIdle() {
        synchronized(scheduleLock) {
            if (events.isNotEmpty() || battery.isNotEmpty()) return
            future?.cancel(false)
            future = null
        }
        executor.execute {
            if (synchronized(scheduleLock) { future != null }) return@execute
            detector.reset()
            protocolKnown = false
            latestBattery = BatteryUpdate(null, Link.CONNECTING)
            shell.close()
        }
    }

    private fun tick() {
        runCatching { poll() }.onFailure { Log.w(TAG, "vehicle poll failed", it) }
    }

    private fun poll() {
        val app = context ?: return
        val wantEvents = events.isNotEmpty()
        val now = SystemClock.elapsedRealtime()
        val wantBattery = battery.isNotEmpty() && now - lastBatteryMillis >= BATTERY_MILLIS
        if (!wantEvents && !wantBattery) return
        if (!protocolKnown) {
            val property = shellRun(app, "getprop ro.car.protocol")?.trim()
            if (property == null) return unavailable(wantBattery, now)
            val value = property.ifEmpty { shellRun(app, "getprop sys.car.protocol")?.trim().orEmpty() }
            protocol = when (value) { "CAN" -> BydBatteryProtocol.CAN; "CANFD" -> BydBatteryProtocol.CANFD; else -> null }
            protocolKnown = true
            note("protocol=${protocol ?: "unknown ($value)"}")
        }
        if (wantEvents) {
            val signals = BydVehicleSignals.events(protocol)
            val started = SystemClock.elapsedRealtime()
            val output = shellRun(app, BydSignalBatch.command(signals)) ?: return unavailable(wantBattery, now)
            recordReadMillis(SystemClock.elapsedRealtime() - started)
            val state = BydVehicleSignals.state(BydSignalBatch.parse(output, signals), protocol)
            for (event in detector.accept(state, carPlayGuidance())) {
                note("event $event")
                main.post { events.forEach { it.onVehicleEvent(event) } }
            }
        }
        // A separate, later read: the larger battery batch never delays an event already seen.
        if (wantBattery) {
            val signals = BydBatterySignals.all(protocol)
            val output = shellRun(app, BydSignalBatch.command(signals)) ?: return unavailable(true, now)
            lastBatteryMillis = now
            val details = BydBatterySignals.decode(BydSignalBatch.parse(output, signals), protocol)
            publish(BatteryUpdate(details, if (details.values.isEmpty()) Link.UNSUPPORTED else Link.READY))
        }
    }

    // Worker thread; the report shows how long one event read takes on this car.
    private var readCount = 0L
    private var readTotalMillis = 0L
    private var readMaxMillis = 0L
    @Volatile private var readSummary = "none"
    private fun recordReadMillis(millis: Long) {
        readCount++; readTotalMillis += millis; readMaxMillis = maxOf(readMaxMillis, millis)
        readSummary = "last=${millis}ms avg=${readTotalMillis / readCount}ms max=${readMaxMillis}ms n=$readCount"
    }

    private fun unavailable(wantBattery: Boolean, now: Long) {
        // A lost link starts a new baseline, so a change made while offline is not replayed.
        detector.reset()
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

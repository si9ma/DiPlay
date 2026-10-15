package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Optional radio pause using already-authorized local ADB; pairing is never changed. */
object BydBluetoothSuspend {
    private const val TAG = "DiPlay-BT-Suspend"
    private const val PREFS = "diplay_bt_suspend"
    private const val KEY_RESTORE_ENABLED = "restore_initially_enabled"
    private const val STATE_TIMEOUT_MILLIS = 8_000L

    /**
     * Turning the radio back on takes BYD's Bluetooth stack far longer than turning it off: on a
     * Di150VCP, about 20 s after a pause that had outlived a killed DiPlay. Waiting 8 s failed the
     * attempt, rebuilt the hotspot and backed off twice before the radio was up, so connecting took
     * 28 s where 5 s is usual. Waiting longer for this one transition costs nothing when it is quick.
     */
    internal const val ENABLE_TIMEOUT_MILLIS = 30_000L

    /** How the last pre-handshake recovery went, for the session log. */
    @Volatile var lastRecovery: String = "not run"
        private set
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bt-suspend").apply { isDaemon = true }
    }
    private val gate = Any()
    private var lease: BluetoothSuspendLease? = null
    private var pending: ScheduledFuture<*>? = null

    private fun lease(context: Context): BluetoothSuspendLease = synchronized(gate) {
        lease ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            BluetoothSuspendLease(
                journal = object : BluetoothRestoreJournal {
                    override fun pending(): Boolean = prefs.getBoolean(KEY_RESTORE_ENABLED, false)
                    override fun write(pending: Boolean): Boolean = prefs.edit()
                        .putBoolean(KEY_RESTORE_ENABLED, pending).commit()
                },
                readEnabled = { radioEnabled(app) },
                requestEnabled = { enabled ->
                    // A non-null shell reply is not evidence that the radio changed state.
                    shell.run(app, if (enabled) "svc bluetooth enable" else "svc bluetooth disable")
                },
                awaitEnabled = { enabled -> awaitRadio(app, enabled) },
            ).also { lease = it }
        }
    }

    /**
     * [stillActive] is checked under the same lock as [resume]: an owner that closes, or a session
     * that ends, before the pause begins never leaves a lease that nothing will release.
     */
    fun suspend(
        context: Context,
        owner: Any,
        delayMillis: Long = 10_000L,
        log: (String) -> Unit = {},
        stillActive: () -> Boolean = { true },
    ) {
        synchronized(gate) {
            if (!stillActive()) return
            val state = lease(context)
            val ticket = state.begin(owner) ?: return
            pending?.cancel(false)
            val delay = delayMillis.coerceAtLeast(0)
            log("car Bluetooth pause in ${TimeUnit.MILLISECONDS.toSeconds(delay)} s")
            pending = worker.schedule({
                val radioBefore = state.radioEnabled()
                val paused = state.suspend(ticket)
                val outcome = BluetoothPauseOutcome.describe(paused, radioBefore, paused || state.isCurrent(ticket),
                    shell.lastAccess?.name)
                if (paused) Log.i(TAG, outcome) else Log.w(TAG, "$outcome; recovery retained if needed")
                log(outcome)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Retire only this controller's work; a late old-controller close cannot release a new one. */
    fun resume(context: Context, owner: Any, log: (String) -> Unit = {}) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.end(owner) ?: state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket, log) }
        }
    }

    fun onAppOpened(context: Context) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket) }
        }
    }

    /** Bounded recovery before wireless bootstrap; never enables a radio that was already OFF. */
    fun resumeAndWait(
        context: Context,
        adapter: BluetoothAdapter?,
        // A queued restore from onAppOpened may hold the worker for its own full enable wait first.
        timeoutMillis: Long = ENABLE_TIMEOUT_MILLIS + STATE_TIMEOUT_MILLIS,
    ): Boolean {
        val app = context.applicationContext
        val started = SystemClock.elapsedRealtime()
        val owned = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RESTORE_ENABLED, false)
        val before = radioEnabled(app)
        val task = synchronized(gate) {
            val state = lease(context)
            val ticket = state.beforeHandshake()
            pending?.cancel(false)
            pending = null
            worker.submit<Boolean> { state.restore(ticket) }
        }
        val restored = runCatching { task.get(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        val enabled = adapter?.isEnabled == true
        // "pausedByDiPlay" separates a radio this app turned off from one the driver or car did.
        lastRecovery = "pausedByDiPlay=$owned radioBefore=${describe(before)} restored=$restored " +
            "enabledNow=$enabled stateNow=${describe(radioEnabled(app))} " +
            "tookMs=${SystemClock.elapsedRealtime() - started}"
        return restored && enabled
    }

    private fun describe(enabled: Boolean?): String = when (enabled) {
        true -> "on"
        false -> "off"
        null -> "changing"
    }

    fun isSuspendedByUs(context: Context): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_ENABLED, false) && radioEnabled(app) == false
    }

    private fun restore(state: BluetoothSuspendLease, ticket: Long, log: (String) -> Unit = {}) {
        val wasPaused = state.isSuspended()
        if (!state.restore(ticket)) {
            Log.w(TAG, "Bluetooth recovery incomplete or superseded")
            if (wasPaused) log("car Bluetooth recovery incomplete; DiPlay retries when it opens again")
        } else if (wasPaused) {
            log("car Bluetooth back on")
        }
    }

    private fun radioEnabled(context: Context): Boolean? = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter() ?: return@runCatching null
        when (adapter.state) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> null
        }
    }.getOrNull()

    private fun awaitRadio(context: Context, enabled: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + if (enabled) ENABLE_TIMEOUT_MILLIS else STATE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (radioEnabled(context) == enabled) return true
            SystemClock.sleep(100)
        }
        return radioEnabled(context) == enabled
    }
}

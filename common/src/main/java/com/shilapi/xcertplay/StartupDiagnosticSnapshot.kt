package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/** Only the latest received boot event and launch result; no Intent extras or device identifiers. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    /**
     * When the system last started, from the one clock that needs no permission: wall time minus the
     * time since boot. Suspend/resume does not reset it, which is the distinction that matters here —
     * a head unit that only sleeps when the car is switched off never broadcasts BOOT_COMPLETED.
     */
    private fun bootedAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Whether the system has started since DiPlay was last installed or updated. Without this,
     * `received=false` is ambiguous: a build installed minutes ago has had no boot to miss, and
     * reads exactly like firmware that refuses to deliver one. Beta builds are replaced often, so
     * this is the usual explanation rather than an edge case.
     */
    private fun bootedSinceInstall(context: Context): Boolean? = runCatching {
        bootedAt() > context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()

    private fun since(millis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (days > 0) "${days}d ${hours}h" else if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /**
     * What the recorded evidence actually shows, in a sentence, so a report does not have to be read
     * by someone who knows which of these facts cancels which.
     */
    fun verdict(context: Context): String = runCatching {
        val receivedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("receivedAt", 0)
        val booted = bootedAt()
        when (bootedSinceInstall(context)) {
            null -> "Boot verdict: unavailable."
            false -> "Boot verdict: the head unit has NOT restarted since DiPlay was last installed " +
                "or updated, so no boot delivery is expected yet and received=false shows nothing. " +
                "Restart the head unit, then export a report."
            true -> when {
                receivedAt == 0L ->
                    "Boot verdict: the head unit HAS restarted since DiPlay was installed and no boot " +
                        "delivery was recorded, so this firmware did not hand BOOT_COMPLETED to DiPlay."
                receivedAt < booted ->
                    "Boot verdict: the recorded delivery is older than the current start-up, so the " +
                        "most recent restart did not hand BOOT_COMPLETED to DiPlay."
                else -> "Boot verdict: BOOT_COMPLETED was delivered on the current start-up."
            }
        }
    }.getOrDefault("Boot verdict: unavailable.")

    /** Facts about this start-up that make [report] readable; none of them identify the car. */
    fun environment(context: Context): String = runCatching {
        val booted = bootedAt()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Start-up environment: upFor=${since(SystemClock.elapsedRealtime())} bootedAtEpochMs=$booted " +
            "installedAtEpochMs=${info.firstInstallTime} updatedAtEpochMs=${info.lastUpdateTime} " +
            "bootedSinceInstall=${bootedSinceInstall(context)} user=${context.applicationInfo.uid / 100_000}"
    }.getOrDefault("Start-up environment: unavailable.")

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass").apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receivedAt = prefs.getLong("receivedAt", 0)
        if (receivedAt == 0L) {
            "Boot diagnostics received=false; no boot delivery has been recorded by this version."
        } else {
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}

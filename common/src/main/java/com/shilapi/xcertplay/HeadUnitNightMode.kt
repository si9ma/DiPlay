package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * The head unit's own day/night state, which is what [CarPlayNightMode.SYSTEM] follows.
 *
 * Android's night mode is not enough on DiLink: BYD keeps its own day/night switch in a settings key
 * and leaves `ui_night_mode` fixed, so the car can change while every Configuration still reports day.
 * The key is read first, then the Android inputs, each of which some firmware updates and others do
 * not. Forcing day or night, the light sensor and the schedule are modes of [CarPlayNightMode]; this
 * object only answers "what does the car say right now".
 */
internal object HeadUnitNightMode {
    /**
     * BYD's own day/night switch. Observed on the car: 1 = night, 2 = day, the opposite of Android's
     * ui_night_mode. The namespace differs between firmware, so all three are checked.
     */
    const val BYD_NIGHT_KEY = "key_byd_night_mode"

    /**
     * Which input [read] believes. [AUTO] takes the first that reports anything, BYD's key first, which
     * suits firmware whose key tracks the car. Firmware where it does not needs the choice: without it
     * a car that follows, say, only its Configuration cannot be followed at all.
     */
    enum class Source(val key: String) {
        AUTO("auto"),
        BYD("byd"),
        SCREEN("screen"),
        APP("app"),
        SYSTEM("system"),
        UI_MODE_MANAGER("uiModeManager");

        companion object {
            fun fromKey(key: String?): Source = entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    /** Where the pre-8.0.19 day/night card kept its source, migrated once so a choice is not lost. */
    private const val LEGACY_PREFS = "diplay_day_night"
    private const val LEGACY_KEY = "source"

    /** The names the old card stored, for the two of its options that are not modes of their own. */
    private val LEGACY_SOURCES = mapOf(
        "BYD_SETTING" to Source.BYD,
        "ACTIVITY_CONFIG" to Source.SCREEN,
        "APP_CONFIG" to Source.APP,
        "UI_MODE_MANAGER" to Source.UI_MODE_MANAGER,
    )

    /**
     * Reads the old card's choice once, so a driver who had named the input their car follows keeps it.
     * ALWAYS_DAY and ALWAYS_NIGHT are modes of [CarPlayNightMode] now, so they leave the source alone.
     */
    fun migrateLegacySource(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val stored = legacy.getString(LEGACY_KEY, null) ?: return
        LEGACY_SOURCES[stored]?.let { AirPlayPersistence.saveNightSource(context, it) }
        legacy.edit().remove(LEGACY_KEY).apply()
    }

    /**
     * Called when an observed input changes, so the host re-evaluates without waiting for its poll.
     * The argument names the key and its new raw value, for the session log: a change the mapping does
     * not recognise produces no day/night decision and would otherwise leave no trace at all.
     */
    @Volatile var onChanged: ((String) -> Unit)? = null

    /** Which input the last non-null reading came from, for the session log. */
    @Volatile var lastSource: String = "none"
        private set

    @Volatile private var lastValue: Boolean? = null
    @Volatile private var installed = false

    /** Observes the settings keys that change without a configuration change. Safe to call repeatedly. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: "?"
                onChanged?.invoke("$key changed; $BYD_NIGHT_KEY=${bydRaw(app)}")
            }
        }
        listOf(
            Settings.System.getUriFor(BYD_NIGHT_KEY),
            Settings.Secure.getUriFor(BYD_NIGHT_KEY),
            Settings.Global.getUriFor(BYD_NIGHT_KEY),
            Settings.Secure.getUriFor("ui_night_mode"),
        ).forEach { uri ->
            runCatching { app.contentResolver.registerContentObserver(uri, false, observer) }
        }
    }

    /**
     * The car's night state, or null when no input reports one, in which case the caller keeps the
     * value it already has. [activityConfig] is the CarPlay screen's own configuration, which keeps
     * the 0.2.9 behaviour ahead of the process-wide ones.
     */
    fun read(context: Context, activityConfig: Configuration? = null): Boolean? {
        val uiModeManager = runCatching { context.getSystemService(UiModeManager::class.java)?.nightMode }.getOrNull()
        // The raw reading travels with the source: the 1/2 meaning is firmware-specific, and a log
        // that shows only "night" cannot be checked against what the driver actually selected.
        val raw = bydRaw(context)
        val inputs = listOf(
            Source.BYD to (byd(raw) to "BYD $BYD_NIGHT_KEY ($raw)"),
            Source.SCREEN to (activityConfig?.let { nightModeOrNull(it.uiMode) } to "CarPlay screen configuration"),
            Source.APP to (nightModeOrNull(context.applicationContext.resources.configuration.uiMode)
                to "app configuration"),
            Source.UI_MODE_MANAGER to (when (uiModeManager) {
                UiModeManager.MODE_NIGHT_YES -> true
                UiModeManager.MODE_NIGHT_NO -> false
                // AUTO/CUSTOM: the platform decides, and the configuration already shows the result.
                else -> null
            } to "UiModeManager"),
            Source.SYSTEM to (nightModeOrNull(Resources.getSystem().configuration.uiMode) to "system resources"),
        )
        val wanted = AirPlayPersistence.loadNightSource(context)
        // A named source answers alone: falling back would reintroduce the input it was chosen to avoid.
        val (value, source) = if (wanted == Source.AUTO) {
            inputs.map { it.second }.firstOrNull { it.first != null } ?: (null to "no explicit input")
        } else {
            inputs.first { it.first == wanted }.second
        }
        if (value != null) { lastValue = value; lastSource = source }
        return value
    }

    /** A one-line view of every input, for the session log when a session starts. */
    fun describe(context: Context, activityConfig: Configuration? = null): String {
        val uiModeManager = runCatching { context.getSystemService(UiModeManager::class.java)?.nightMode }.getOrNull()
        return "$BYD_NIGHT_KEY=${bydRaw(context)}; " +
            "screen=${label(activityConfig?.let { nightModeOrNull(it.uiMode) })}; " +
            "app=${label(nightModeOrNull(context.applicationContext.resources.configuration.uiMode))}; " +
            "system=${label(nightModeOrNull(Resources.getSystem().configuration.uiMode))}; " +
            "uiModeManager=${modeLabel(uiModeManager)}; " +
            "follow=${AirPlayPersistence.loadNightSource(context).key}; " +
            "using ${label(lastValue)} from $lastSource"
    }

    fun label(night: Boolean?): String = when (night) { true -> "night"; false -> "day"; null -> "undefined" }

    /** [raw] is "namespace=value"; an unrecognised value reports nothing rather than guessing. */
    private fun byd(raw: String): Boolean? = when (raw.substringAfter('=', "")) {
        "1" -> true
        "2" -> false
        else -> null
    }

    /** "namespace=value" for BYD's key, or "not found" where this firmware has none. */
    private fun bydRaw(context: Context): String {
        val resolver = context.contentResolver
        return listOf(
            "system" to { runCatching { Settings.System.getString(resolver, BYD_NIGHT_KEY) }.getOrNull() },
            "secure" to { runCatching { Settings.Secure.getString(resolver, BYD_NIGHT_KEY) }.getOrNull() },
            "global" to { runCatching { Settings.Global.getString(resolver, BYD_NIGHT_KEY) }.getOrNull() },
        ).firstNotNullOfOrNull { (space, read) -> read()?.trim()?.let { "$space=$it" } } ?: "not found"
    }

    private fun modeLabel(mode: Int?): String = when (mode) {
        UiModeManager.MODE_NIGHT_AUTO -> "auto"
        UiModeManager.MODE_NIGHT_NO -> "no"
        UiModeManager.MODE_NIGHT_YES -> "yes"
        UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
        null -> "unknown"
        else -> mode.toString()
    }
}

package com.shilapi.xcertplay.media

import android.content.Context

object AmbientMusicSettings {
    private const val PREFS = "ambient_music"
    data class Values(val enabled: Boolean = false, val music: Boolean = true,
        val colorMode: AmbientColorMode = AmbientColorMode.BEAT,
        val speed: AmbientColorSpeed = AmbientColorSpeed.STANDARD, val selectedColors: List<Int> = listOf(1), val colorCycle: Boolean = false, val color: Int = 1, val brightness: Int = 0, val area: Int = 3,
        val colorSource: AmbientColorSource = AmbientColorSource.SELECTED,
        val idle: AmbientIdleBehavior = AmbientIdleBehavior.STEADY,
        // The car's own music rhythm while CarPlay music plays; DiPlay then leaves the lamps to the car.
        val carRhythm: Boolean = false,
        // Sends the played music's spectrum to the car for that rhythm, where the car makes none of its own.
        val carRhythmFeed: Boolean = false)

    /** Color codes are kept in the range of this head unit's palette (see [AmbientColorProfile]). */
    fun load(context: Context): Values = load(context, AmbientColorProfile.current(context))

    internal fun load(context: Context, palette: AmbientPalette): Values {
        val range = palette.colors
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun boolean(key: String, fallback: Boolean) = runCatching { p.getBoolean(key, fallback) }.getOrDefault(fallback)
        fun integer(key: String, fallback: Int) = runCatching { p.getInt(key, fallback) }.getOrDefault(fallback)
        fun text(key: String, fallback: String) = runCatching { p.getString(key, fallback) ?: fallback }.getOrDefault(fallback)
        val color = integer("color", 1).coerceIn(range.first, range.last)
        val stored = runCatching { p.getStringSet("selectedColors", null)?.mapNotNull { it.toIntOrNull() } }.getOrNull()
        return Values(
            enabled = boolean("enabled", false),
            music = boolean("music", true),
            colorMode = runCatching { AmbientColorMode.valueOf(text("colorMode", "BEAT")) }.getOrDefault(AmbientColorMode.BEAT),
            speed = runCatching { AmbientColorSpeed.valueOf(text("speed", "STANDARD")) }.getOrDefault(AmbientColorSpeed.STANDARD),
            selectedColors = normalizeAmbientPalette(stored ?: listOf(color), color, range),
            colorCycle = boolean("colorCycle", false),
            color = color,
            brightness = integer("brightness", 0).coerceIn(0, 6),
            area = integer("area", 3).coerceIn(1, 3),
            colorSource = runCatching { AmbientColorSource.valueOf(text("colorSource", "SELECTED")) }.getOrDefault(AmbientColorSource.SELECTED),
                        idle = runCatching { AmbientIdleBehavior.valueOf(text("idle", "STEADY")) }.getOrDefault(AmbientIdleBehavior.STEADY),
            carRhythm = boolean("carRhythm", false),
            carRhythmFeed = boolean("carRhythmFeed", false),
                    )
    }

    fun save(context: Context, values: Values) {
        val range = AmbientColorProfile.current(context).colors
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", values.enabled).putBoolean("music", values.music)
            .putBoolean("colorCycle", values.colorCycle).putString("colorMode", values.colorMode.name)
            .putString("speed", values.speed.name).putString("colorSource", values.colorSource.name)
            .putString("idle", values.idle.name)
            .putStringSet("selectedColors", normalizeAmbientPalette(values.selectedColors, values.color, range).map { it.toString() }.toSet())
            .putInt("color", values.color.coerceIn(range.first, range.last))
            .putInt("brightness", values.brightness.coerceIn(0, 6))
            .putInt("area", values.area.coerceIn(1, 3)).putBoolean("carRhythm", values.carRhythm)
            .putBoolean("carRhythmFeed", values.carRhythmFeed).apply()
        AmbientMusicController.settingsChanged(context)
    }
}

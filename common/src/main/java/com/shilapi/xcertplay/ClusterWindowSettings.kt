package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlayInsets

internal data class ClusterWindowRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun pixels(w: Int, h: Int): Rect {
        val rw = (w * width.coerceIn(10, 95) / 100).coerceIn(1, w.coerceAtLeast(1))
        val rh = (h * height.coerceIn(10, 95) / 100).coerceIn(1, h.coerceAtLeast(1))
        val left = (w * x.coerceIn(0, 100) / 100 - rw / 2).coerceIn(0, (w - rw).coerceAtLeast(0))
        val top = (h * y.coerceIn(0, 100) / 100 - rh / 2).coerceIn(0, (h - rh).coerceAtLeast(0))
        return Rect(left, top, left + rw, top + rh)
    }
}

internal enum class ClusterCoverSide { LEFT, RIGHT }
internal enum class ClusterMusicMode { MINI, CARD }
internal enum class ClusterMusicAlignment { LEFT, CENTER, RIGHT }
internal enum class ClusterMusicInfoLines(val separator: String?) {
    ORIGINAL(null), DASH(" - "), DOT(" · "), SLASH(" / ")
}
internal enum class ClusterMapDisplayMode { FIT_WINDOW, PANEL_CROP }

internal enum class ClusterMapCanvas(val width: Int, val height: Int) {
    WINDOW(0, 0), HD(1280, 720), FULL_HD(1920, 1080), SQUARE_HD(1280, 1280), SQUARE_FULL_HD(1920, 1920),
    PANEL_67(1280, 480), PANEL_83(1600, 600), PANEL_NATIVE(1920, 720), PANEL_125(2408, 904);

    val independent get() = this != WINDOW
    fun candidates(): List<ClusterMapCanvas> = when (this) {
        WINDOW -> emptyList()
        FULL_HD -> listOf(FULL_HD, HD)
        SQUARE_FULL_HD -> listOf(SQUARE_FULL_HD, SQUARE_HD)
        PANEL_125 -> listOf(PANEL_125, PANEL_NATIVE, PANEL_83, PANEL_67)
        PANEL_NATIVE -> listOf(PANEL_NATIVE, PANEL_83, PANEL_67)
        PANEL_83 -> listOf(PANEL_83, PANEL_67)
        else -> listOf(this)
    }
}

internal data class ClusterWindowConfig(
    val music: Boolean, val map: Boolean, val displayName: String, val elevated: Boolean,
    val musicRect: ClusterWindowRect, val mapRect: ClusterWindowRect,
    val cover: Boolean, val text: Boolean, val font: Int, val opacity: Int,
    val crop: Boolean, val mapContent: CarPlayClusterDisplay.Content,
    val safeArea: AirPlayInsets = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
    /** Percent of the stream discarded on each edge before fitting; blending fades the remaining picture. */
    val trim: AirPlayInsets = AirPlayInsets(), val coverSide: ClusterCoverSide = ClusterCoverSide.LEFT,
    val autoZoomSteps: Int = 0,
    val mapBlend: Boolean = false, val mapFeatherPercent: Int = ClusterWindowSettings.MAP_FEATHER_DEFAULT,
    val mapCornerPercent: Int = 4,
    val mapCanvas: ClusterMapCanvas = ClusterMapCanvas.WINDOW,
    val musicMode: ClusterMusicMode = ClusterMusicMode.MINI,
    val musicCardRect: ClusterWindowRect = ClusterWindowRect(30, 50, 50, 50),
    val trimInPlace: Boolean = false,
    val mapDisplayMode: ClusterMapDisplayMode = ClusterMapDisplayMode.FIT_WINDOW,
    val mapCenterX: Int = 50, val mapCenterY: Int = 50,
    val musicCardCoverSize: Int = 160,
    val coverCornerPercent: Int = 15,
    val titleFont: Int = 32, val artistFont: Int = 24,
    val cardCoverAlignment: ClusterMusicAlignment = ClusterMusicAlignment.CENTER,
    val cardLyricAlignment: ClusterMusicAlignment = ClusterMusicAlignment.LEFT,
    val cardInfoAlignment: ClusterMusicAlignment = ClusterMusicAlignment.LEFT,
    val cardInfoLines: ClusterMusicInfoLines = ClusterMusicInfoLines.ORIGINAL,
) {
    val enabled get() = music || map
    val activeMusicRect get() = if (musicMode == ClusterMusicMode.CARD) musicCardRect else musicRect
    val musicRectKey get() = if (musicMode == ClusterMusicMode.CARD) "music_card" else "music"
}

internal object ClusterWindowSettings {
    val CARD_COVER_SIZE_RANGE = 60..480
    val COVER_CORNER_RANGE = 0..50
    val MUSIC_FONT_RANGE = 12..72
    val SAFE_AREA_RANGE = 0..40
    val TRIM_RANGE = 0..40
    val MAP_FEATHER_PERCENT_RANGE = 0..30
    const val MAP_FEATHER_DEFAULT = 10
    val MAP_CORNER_PERCENT_RANGE = 0..25
    val AUTO_ZOOM_STEPS = listOf(0, 1, 2, 3, 4, 5, -1, -2)
    private const val SAFE_AREA_DEFAULT = 8
    private const val PREFIX = "cluster_windows_"
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun load(context: Context): ClusterWindowConfig {
        val p = prefs(context)
        fun bool(key: String, fallback: Boolean = false) = p.getBoolean(PREFIX + key, fallback)
        fun number(key: String, fallback: Int, range: IntRange) = p.getInt(PREFIX + key, fallback).coerceIn(range)
        fun rect(key: String, x: Int, y: Int, w: Int, h: Int) = ClusterWindowRect(
            number("${key}_x", x, 0..100), number("${key}_y", y, 0..100),
            number("${key}_width", w, 10..95), number("${key}_height", h, 10..95))
        val content = p.getString(PREFIX + "content", null)
        return ClusterWindowConfig(bool("music"), bool("map"), p.getString(PREFIX + "display", "").orEmpty(),
            bool("elevated"), rect("music", 25, 65, 30, 25), rect("map", 72, 65, 45, 45),
            bool("cover", true), bool("text", true), number("font", 100, 60..180), number("opacity", 80, 0..100),
            bool("crop"), CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == content }
                ?.takeUnless { it == CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD } ?: CarPlayClusterDisplay.Content.MAP,
            safeArea = AirPlayInsets(
                top = number("safe_top", SAFE_AREA_DEFAULT, SAFE_AREA_RANGE),
                bottom = number("safe_bottom", SAFE_AREA_DEFAULT, SAFE_AREA_RANGE),
                left = number("safe_left", SAFE_AREA_DEFAULT, SAFE_AREA_RANGE),
                right = number("safe_right", SAFE_AREA_DEFAULT, SAFE_AREA_RANGE)),
            trim = AirPlayInsets(
                top = number("trim_top", 0, TRIM_RANGE),
                bottom = number("trim_bottom", 0, TRIM_RANGE),
                // Earlier releases stored only the left edge as "left_trim".
                left = number("trim_left", p.getInt(PREFIX + "left_trim", 0), TRIM_RANGE),
                right = number("trim_right", 0, TRIM_RANGE)),
            coverSide = ClusterCoverSide.entries.firstOrNull { it.name == p.getString(PREFIX + "cover_side", null) }
                ?: ClusterCoverSide.LEFT,
            autoZoomSteps = p.getInt(PREFIX + "auto_zoom_steps", 0).takeIf { it in AUTO_ZOOM_STEPS } ?: 0,
            mapBlend = bool("map_blend"),
            mapFeatherPercent = number("map_feather_percent", MAP_FEATHER_DEFAULT, MAP_FEATHER_PERCENT_RANGE),
            mapCornerPercent = number("map_corner_percent", 4, MAP_CORNER_PERCENT_RANGE),
            mapCanvas = ClusterMapCanvas.entries.firstOrNull { it.name == p.getString(PREFIX + "map_canvas", null) }
                ?: ClusterMapCanvas.WINDOW,
            musicMode = ClusterMusicMode.entries.firstOrNull { it.name == p.getString(PREFIX + "music_mode", null) }
                ?: ClusterMusicMode.MINI,
            musicCardRect = rect("music_card", 30, 50, 50, 50),
            trimInPlace = bool("trim_in_place"),
            mapDisplayMode = ClusterMapDisplayMode.entries.firstOrNull { it.name == p.getString(PREFIX + "map_display_mode", null) }
                ?: ClusterMapDisplayMode.FIT_WINDOW,
            mapCenterX = number("map_center_x", 50, 0..100), mapCenterY = number("map_center_y", 50, 0..100),
            musicCardCoverSize = number("music_card_cover_size", 160, CARD_COVER_SIZE_RANGE),
            coverCornerPercent = number("cover_corner_percent", 15, COVER_CORNER_RANGE),
            titleFont = number("title_font", 32, MUSIC_FONT_RANGE),
            artistFont = number("artist_font", 24, MUSIC_FONT_RANGE),
            cardCoverAlignment = ClusterMusicAlignment.entries.firstOrNull { it.name == p.getString(PREFIX + "card_cover_alignment", null) }
                ?: ClusterMusicAlignment.CENTER,
            cardLyricAlignment = ClusterMusicAlignment.entries.firstOrNull { it.name == p.getString(PREFIX + "card_lyric_alignment", null) }
                ?: ClusterMusicAlignment.LEFT,
            cardInfoAlignment = ClusterMusicAlignment.entries.firstOrNull { it.name == p.getString(PREFIX + "card_info_alignment", null) }
                ?: ClusterMusicAlignment.LEFT,
            cardInfoLines = ClusterMusicInfoLines.entries.firstOrNull { it.name == p.getString(PREFIX + "card_info_lines", null) }
                ?: ClusterMusicInfoLines.ORIGINAL)
    }
    fun save(context: Context, key: String, value: Boolean) {
        require(key in setOf("music", "map", "elevated", "cover", "text", "crop", "map_blend", "trim_in_place"))
        prefs(context).edit().putBoolean(PREFIX + key, value).apply()
    }
    fun save(context: Context, key: String, value: Int) {
        require(key in setOf("music_x", "music_y", "music_width", "music_height", "map_x", "map_y", "map_width", "map_height", "font", "opacity",
            "safe_top", "safe_bottom", "safe_left", "safe_right", "trim_top", "trim_bottom", "trim_left", "trim_right",
            "music_card_x", "music_card_y", "music_card_width", "music_card_height",
            "music_card_cover_size", "cover_corner_percent", "title_font", "artist_font",
            "auto_zoom_steps", "map_feather_percent", "map_corner_percent", "map_center_x", "map_center_y"))
        if (key == "auto_zoom_steps" && value !in AUTO_ZOOM_STEPS) return
        val range = when {
            key.startsWith("safe_") -> SAFE_AREA_RANGE
            key.startsWith("trim_") -> TRIM_RANGE
            key == "auto_zoom_steps" -> -2..5
            key == "map_feather_percent" -> MAP_FEATHER_PERCENT_RANGE
            key == "map_corner_percent" -> MAP_CORNER_PERCENT_RANGE
            key.endsWith("width") || key.endsWith("height") -> 10..95
            key == "music_card_cover_size" -> CARD_COVER_SIZE_RANGE
            key == "cover_corner_percent" -> COVER_CORNER_RANGE
            key == "font" || key == "title_font" || key == "artist_font" -> MUSIC_FONT_RANGE
            else -> 0..100
        }
        prefs(context).edit().putInt(PREFIX + key, value.coerceIn(range)).apply()
    }
    fun save(context: Context, key: String, value: String) {
        require(key in setOf("display", "content", "cover_side", "map_canvas", "music_mode", "map_display_mode",
            "card_cover_alignment", "card_lyric_alignment", "card_info_alignment", "card_info_lines"))
        require(key !in setOf("card_cover_alignment", "card_lyric_alignment", "card_info_alignment") ||
            ClusterMusicAlignment.entries.any { it.name == value })
        require(key != "card_info_lines" || ClusterMusicInfoLines.entries.any { it.name == value })
        require(key != "cover_side" || ClusterCoverSide.entries.any { it.name == value })
        require(key != "map_canvas" || ClusterMapCanvas.entries.any { it.name == value })
        require(key != "music_mode" || ClusterMusicMode.entries.any { it.name == value })
        require(key != "map_display_mode" || ClusterMapDisplayMode.entries.any { it.name == value })
        prefs(context).edit().putString(PREFIX + key, value.take(120)).apply()
    }
    fun resetTrim(context: Context) {
        prefs(context).edit().apply {
            for (side in listOf("top", "bottom", "left", "right")) putInt(PREFIX + "trim_" + side, 0)
        }.apply()
    }
    fun resetSafeArea(context: Context) {
        prefs(context).edit().apply {
            for (side in listOf("top", "bottom", "left", "right")) putInt(PREFIX + "safe_" + side, SAFE_AREA_DEFAULT)
        }.apply()
    }
    fun disable(context: Context) = prefs(context).edit().putBoolean(PREFIX + "music", false).putBoolean(PREFIX + "map", false).apply()
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || key.startsWith(PREFIX)) changed() }
        p.registerOnSharedPreferenceChangeListener(listener)
        return { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

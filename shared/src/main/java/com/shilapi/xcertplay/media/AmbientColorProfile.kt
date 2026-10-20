package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import kotlin.math.abs

/** The color numbering the car's lamps use. Every stored or written color code is interpreted in one of these. */
enum class AmbientPalette(val colors: IntRange) {
    /** The verified 31-color cars. */
    LEGACY31(BydAmbientLightPolicy.LEGACY_COLORS),
    /** The observed OEM 127-color firmware, verified by a parked write, read-back and restore test. */
    OEM127(BydAmbientLightPolicy.OEM127_COLORS);

    val size: Int get() = colors.last

    /** Stock UI reference color for a raw code in this palette, opaque. */
    fun previewRgb(code: Int): Int = when (this) {
        LEGACY31 -> AmbientAlbumPalette.previewRgb(code)
        OEM127 -> Oem127Palette.previewRgb(code)
    }

    /** The closest colored code for an album color; whites are chosen separately by [white]. */
    fun albumCode(rgb: Int): Int = when (this) {
        LEGACY31 -> AmbientAlbumPalette.bydColor(rgb)
        OEM127 -> nearestByHue(rgb)
    }

    fun white(cold: Boolean): Int = when (this) {
        LEGACY31 -> if (cold) 30 else 29
        OEM127 -> if (cold) 119 else 116
    }

    /** Up to five codes of a similar hue, for an album color that should vary a little with the music. */
    fun neighbors(center: Int): List<Int> = when (this) {
        LEGACY31 -> AmbientAlbumPalette.neighbors(center)
        OEM127 -> {
            val whites = setOf(white(false), white(true))
            if (center in whites) whites.toList().sorted() else {
                val target = AmbientAlbumPalette.hueOf(previewRgb(center))
                colors.filter { AmbientAlbumPalette.saturationOf(previewRgb(it)) >= 0.20 &&
                    AmbientAlbumPalette.hueDistance(target, AmbientAlbumPalette.hueOf(previewRgb(it))) <= 35 }
                    .sortedWith(compareBy({ AmbientAlbumPalette.hueDistance(target, AmbientAlbumPalette.hueOf(previewRgb(it))) }, { abs(it - center) }))
                    .take(5).ifEmpty { listOf(center) }
            }
        }
    }

    // Same metric as the 31-color match: hue first, saturation only breaks close matches; whites excluded.
    private fun nearestByHue(rgb: Int): Int {
        val hue = AmbientAlbumPalette.hueOf(rgb)
        val saturation = AmbientAlbumPalette.saturationOf(rgb)
        return colors.filter { AmbientAlbumPalette.saturationOf(previewRgb(it)) >= 0.20 }.minBy {
            val preview = previewRgb(it)
            AmbientAlbumPalette.hueDistance(hue, AmbientAlbumPalette.hueOf(preview)) +
                abs(saturation - AmbientAlbumPalette.saturationOf(preview)) * 3
        }
    }
}

/**
 * Which palette this head unit uses, learned from a lamp read (COLOR_CONFIG) on this exact firmware.
 * A different fingerprint, such as after an update, falls back to the 31-color palette until read again.
 */
object AmbientColorProfile {
    private const val PREFS = "ambient_profile"

    fun current(context: Context): AmbientPalette {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return resolve(runCatching { prefs.getString("fingerprint", null) }.getOrNull(),
            runCatching { prefs.getString("palette", null) }.getOrNull(), android.os.Build.FINGERPRINT)
    }

    /** Records the palette from a read COLOR_CONFIG. Returns true when it changed. */
    fun record(context: Context, fingerprint: String?, colorConfig: Int): Boolean {
        val palette = if (BydAmbientLightPolicy.observedColorProfile(fingerprint, colorConfig) != null)
            AmbientPalette.OEM127 else AmbientPalette.LEGACY31
        return save(context, fingerprint, palette)
    }

    /** A parked write test may only begin on the observed 127 profile, so its begin confirms it. */
    fun recordOem127(context: Context): Boolean = save(context, android.os.Build.FINGERPRINT, AmbientPalette.OEM127)

    private fun save(context: Context, fingerprint: String?, palette: AmbientPalette): Boolean {
        if (fingerprint.isNullOrEmpty()) return false
        val before = current(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("fingerprint", fingerprint).putString("palette", palette.name).commit()
        return current(context) != before
    }

    internal fun resolve(storedFingerprint: String?, storedPalette: String?, fingerprint: String?): AmbientPalette {
        if (storedFingerprint.isNullOrEmpty() || storedFingerprint != fingerprint) return AmbientPalette.LEGACY31
        return AmbientPalette.entries.firstOrNull { it.name == storedPalette } ?: AmbientPalette.LEGACY31
    }
}

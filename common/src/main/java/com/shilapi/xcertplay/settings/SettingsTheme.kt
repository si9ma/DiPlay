package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.DiPlayPalette

internal object SettingsControlMetrics {
    const val HEIGHT_DP = 60
    const val GAP_DP = 12
    const val BLOCK_GAP_DP = 16
    const val OUTLINE_DP = 1
    const val FOCUS_OUTLINE_DP = 3
    private const val MAX_RADIUS_DP = 24
    private const val REFERENCE_RADIUS_DP = 20
    private const val REFERENCE_HEIGHT_DP = 56

    fun radius(heightPx: Int, density: Float): Float =
        minOf(MAX_RADIUS_DP * density, heightPx.toFloat() * REFERENCE_RADIUS_DP / REFERENCE_HEIGHT_DP)
}

class SettingsTheme private constructor(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
    val ripple: Int,
    val focusRing: Int,
) {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** Dark aliases keep existing overlay callers source-compatible until Phase 4. */
        @JvmField val CARD = card(DiPlayPalette.DARK)
        @JvmField val OVERLAY = overlay(DiPlayPalette.DARK)
        @JvmField val entries = listOf(CARD, OVERLAY)

        internal fun card(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = false,
            textPrimary = palette.primaryText,
            textSecondary = palette.secondaryText,
            accent = palette.accent,
            accentTrack = palette.accentTrack,
            trackOff = palette.trackOff,
            buttonText = palette.onAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )

        internal fun overlay(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = true,
            textPrimary = palette.overlayPrimaryText,
            textSecondary = palette.overlaySecondaryText,
            accent = palette.overlayAccent,
            accentTrack = palette.overlayAccentTrack,
            trackOff = palette.overlayTrackOff,
            buttonText = palette.overlayOnAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )
    }
}

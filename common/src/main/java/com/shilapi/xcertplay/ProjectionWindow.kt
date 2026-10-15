package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Sizes the CarPlay host window to a share of the screen width. Below 100% the window becomes
 * translucent and non-touch-modal so the car's own screen stays visible and usable beside it.
 * The TextureView then reports the narrower size and CarPlay renegotiates its canvas.
 */
object ProjectionWindow {
    const val FULL_WIDTH_PERCENT = 100
    /** Below this CarPlay's layout becomes unusable on typical 1920-px-wide head units. */
    const val MIN_WIDTH_PERCENT = 30
    val WIDTH_PERCENTS = listOf(100, 90, 80, 75, 70, 60, 50)

    enum class Alignment(val gravity: Int) {
        LEFT(Gravity.LEFT),
        CENTER(Gravity.CENTER_HORIZONTAL),
        RIGHT(Gravity.RIGHT),
    }

    private val translucentState = java.util.WeakHashMap<Activity, Boolean>()

    /** Width the running CarPlay session negotiated its canvas with; survives activity recreation. */
    @Volatile var sessionPercent: Int? = null

    fun isPartial(context: android.content.Context): Boolean =
        AirPlayPersistence.loadProjectionWidthPercent(context) < FULL_WIDTH_PERCENT

    private const val PASS_THROUGH_FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    /** Applies the configured width and returns a one-line summary for the session log. */
    fun apply(activity: Activity, edgeToEdge: Boolean, overlay: Boolean = false): String {
        val percent = AirPlayPersistence.loadProjectionWidthPercent(activity)
        // In overlay mode CarPlay is drawn by ProjectionOverlay; this activity is only the full-screen status page.
        val partial = percent < FULL_WIDTH_PERCENT && !overlay
        val window = activity.window
        // The theme is translucent; return to opaque at full width so the car UI behind stops drawing.
        // Before Android 11 there is no public toggle and the window simply stays translucent.
        var translucent: Any = "theme"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (translucentState[activity] != partial) {
                translucentState[activity] = partial
                translucent = activity.setTranslucent(partial)
            } else translucent = partial
        }
        if (!partial) {
            window.clearFlags(PASS_THROUGH_FLAGS)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The session-log redactor drops any line containing "pass"; avoid that word here.
            return "Projection window full width translucent=$translucent outsideTouchToCarUi=false overlayMode=$overlay"
        }
        val available = availableWidth(activity, edgeToEdge)
        val width = (available * percent / 100f).toInt()
        val alignment = AirPlayPersistence.loadProjectionAlignment(activity)
        // NOT_TOUCH_MODAL: touches outside this window go to whatever window is behind (the car UI).
        // WATCH_OUTSIDE_TOUCH: we still get ACTION_OUTSIDE, used only to log that passthrough happens.
        window.addFlags(PASS_THROUGH_FLAGS)
        window.setGravity(alignment.gravity or Gravity.TOP)
        window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
        return "Projection window width=${width}px of ${available}px ($percent%) align=$alignment " +
            "edgeToEdge=$edgeToEdge translucent=$translucent outsideTouchToCarUi=true"
    }

    internal fun availableWidth(activity: Activity, edgeToEdge: Boolean): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = activity.windowManager.maximumWindowMetrics
            if (edgeToEdge) return metrics.bounds.width()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return metrics.bounds.width() - insets.left - insets.right
        }
        @Suppress("DEPRECATION")
        return Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }.x
    }
}

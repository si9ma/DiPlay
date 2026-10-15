package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A small pill on one edge of the CarPlay picture. Tap it for a short menu of whatever [actions] the
 * host passes; drag it up or down to move it. It replaces the three-finger swipe, which some
 * head units reserve for their own controls. The container itself never consumes touches, so
 * CarPlay keeps everything except the pill's own small target.
 */
internal class ProjectionHandle(
    context: Context,
    /** Gravity.LEFT or Gravity.RIGHT. */
    private val side: Int,
    private val actions: List<Pair<String, () -> Unit>>,
) {
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val pill = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(3).toFloat()
        }
    }
    private val target = FrameLayout(context).apply {
        contentDescription = "DiPlay"
        addView(pill, FrameLayout.LayoutParams(dp(6), dp(PILL_HEIGHT_DP), Gravity.CENTER))
        alpha = IDLE_ALPHA
    }
    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.argb(235, 21, 30, 44)); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(47, 62, 84))
        }
        visibility = View.GONE
        isClickable = true // touches on the menu background must not reach CarPlay
        elevation = dp(8).toFloat()
    }

    /** Add with MATCH_PARENT on top of the CarPlay touch layer. */
    val view = FrameLayout(context)

    private val fade = Runnable { target.animate().alpha(IDLE_ALPHA).setDuration(300).start() }
    private val closeMenu = Runnable { setMenuVisible(false) }

    init {
        actions.forEach { (label, action) ->
            menu.addView(TextView(context).apply {
                text = label; textSize = 18f; setTextColor(Color.rgb(241, 245, 252))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                minWidth = dp(180)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) }
                setOnClickListener { setMenuVisible(false); action() }
            }, LinearLayout.LayoutParams(-1, dp(56)))
        }
        view.addView(target, FrameLayout.LayoutParams(dp(TARGET_WIDTH_DP), dp(TARGET_HEIGHT_DP), side or Gravity.TOP))
        view.addView(menu, FrameLayout.LayoutParams(-2, -2, side or Gravity.TOP).apply {
            if (side == Gravity.LEFT) leftMargin = dp(TARGET_WIDTH_DP + 4) else rightMargin = dp(TARGET_WIDTH_DP + 4)
        })
        target.setOnTouchListener(::onHandleTouch)
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) place(prefs.getFloat(KEY_Y, DEFAULT_Y))
        }
    }

    private var downY = 0f
    private var startTop = 0
    private var dragging = false

    private fun onHandleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(fade)
                target.animate().cancel(); target.alpha = 1f
                downY = event.rawY
                startTop = (target.layoutParams as FrameLayout.LayoutParams).topMargin
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawY - downY
                if (!dragging && abs(delta) > slop) { dragging = true; setMenuVisible(false) }
                if (dragging) moveTo(startTop + delta.toInt())
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) saveFraction() else setMenuVisible(menu.visibility != View.VISIBLE)
                main.postDelayed(fade, FADE_MILLIS)
            }
            MotionEvent.ACTION_CANCEL -> main.postDelayed(fade, FADE_MILLIS)
        }
        return true
    }

    private fun setMenuVisible(visible: Boolean) {
        main.removeCallbacks(closeMenu)
        menu.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val params = menu.layoutParams as FrameLayout.LayoutParams
            menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val top = (target.layoutParams as FrameLayout.LayoutParams).topMargin
            params.topMargin = (top + dp(TARGET_HEIGHT_DP) / 2 - menu.measuredHeight / 2)
                .coerceIn(0, (view.height - menu.measuredHeight).coerceAtLeast(0))
            menu.layoutParams = params
            main.postDelayed(closeMenu, MENU_MILLIS)
        }
    }

    private fun maxTop(): Int = (view.height - dp(TARGET_HEIGHT_DP)).coerceAtLeast(0)

    private fun moveTo(top: Int) {
        val params = target.layoutParams as FrameLayout.LayoutParams
        params.topMargin = top.coerceIn(0, maxTop())
        target.layoutParams = params
    }

    private fun place(fraction: Float) = view.post { moveTo((maxTop() * fraction).toInt()) }

    private fun saveFraction() {
        val max = maxTop().takeIf { it > 0 } ?: return
        prefs.edit().putFloat(KEY_Y, (target.layoutParams as FrameLayout.LayoutParams).topMargin / max.toFloat()).apply()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val PREFS = "diplay_projection_handle"
        const val KEY_Y = "y_fraction"
        const val DEFAULT_Y = 0.5f
        const val PILL_HEIGHT_DP = 56
        const val TARGET_WIDTH_DP = 24
        const val TARGET_HEIGHT_DP = 80
        const val IDLE_ALPHA = 0.35f
        const val FADE_MILLIS = 3_000L
        const val MENU_MILLIS = 6_000L
    }
}

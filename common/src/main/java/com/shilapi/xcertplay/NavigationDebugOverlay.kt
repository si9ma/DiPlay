package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.abs

/**
 * A small draggable window showing what the head unit's map is telling DiPlay, for troubleshooting a
 * destination that is not being read. It shows DiPlay's own decisions and the raw text of each ADB
 * read, which the diagnostic report cannot carry because it holds the driver's real destination.
 *
 * Needs "display over other apps". Nothing is collected unless it is on; see [NavigationDebugLog].
 */
internal object NavigationDebugOverlay {
    const val TAG = "DiPlay-NavDebug"

    private const val PREFS = "diplay_nav_debug"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    private const val WIDTH_FRACTION = 0.42
    private const val HEIGHT_FRACTION = 0.38
    private const val MIN_WIDTH_DP = 220
    private const val MIN_HEIGHT_DP = 140

    /**
     * The whole display, not this context's window.
     *
     * In overlay mode the CarPlay host's own window is only as wide as the projection — 1267 of 1920
     * px on a Di150VCP — and `resources.displayMetrics` follows the window. Clamping a drag to that
     * confines this window to the projection, which is the one place it is in the way. The overlay is
     * a display-level window, so the free strip beside CarPlay is reachable once the bounds are right.
     */
    private fun displayBounds(context: Context): android.graphics.Rect {
        val windows = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windows != null) {
            return android.graphics.Rect(windows.maximumWindowMetrics.bounds)
        }
        @Suppress("DEPRECATION")
        val size = android.graphics.Point().also { windows?.defaultDisplay?.getRealSize(it) }
        return android.graphics.Rect(0, 0, size.x, size.y)
    }

    /** The strip CarPlay is not using; see [NavigationDebugPlacement.freeStrip]. */
    private fun freeStrip(context: Context, displayWidth: Int): IntRange? =
        NavigationDebugPlacement.freeStrip(displayWidth,
            AirPlayPersistence.loadProjectionWidthPercent(context),
            AirPlayPersistence.loadProjectionAlignment(context))

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var text: TextView? = null
    private var scroll: ScrollView? = null

    /** The CarPlay screen, asked to put the window up; it owns a usable context. */
    @Volatile var requestShow: (() -> Unit)? = null

    val shown: Boolean get() = root != null

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun clampX(x: Int, width: Int, screenWidth: Int, density: Float): Int =
        NavigationDebugPlacement.clampX(x, width, screenWidth, density)

    private fun clampY(y: Int, height: Int, screenHeight: Int, density: Float): Int =
        NavigationDebugPlacement.clampY(y, height, screenHeight, density)

    /** Puts the window up or takes it down to match the setting. Main thread. */
    fun sync(context: Context) {
        if (AirPlayPersistence.loadNavigationDebugOverlay(context)) show(context) else hide()
    }

    fun show(context: Context): Boolean {
        if (root != null) return true
        if (!permitted(context)) {
            Log.w(TAG, "no permission to display over other apps")
            return false
        }
        val windows = context.getSystemService(WindowManager::class.java) ?: return false
        val bounds = displayBounds(context)
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val density = context.resources.displayMetrics.density
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val width = prefs.getInt(KEY_WIDTH, (screenWidth * WIDTH_FRACTION).toInt())
            .coerceIn((MIN_WIDTH_DP * density).toInt().coerceAtMost(screenWidth), screenWidth)
        val height = prefs.getInt(KEY_HEIGHT, (screenHeight * HEIGHT_FRACTION).toInt())
            .coerceIn((MIN_HEIGHT_DP * density).toInt().coerceAtMost(screenHeight), screenHeight)
        val radius = 16f * density
        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Focusable would take the keyboard and the back key from the car's own screens.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Opens on the strip CarPlay is not using, which is where it is wanted and out of the way.
            val default = freeStrip(context, screenWidth)?.first ?: (16 * density).toInt()
            x = clampX(prefs.getInt(KEY_X, default), width, screenWidth, density)
            y = clampY(prefs.getInt(KEY_Y, (16 * density).toInt()), height, screenHeight, density)
            title = "DiPlay navigation debug"
        }

        val body = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(false)
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            text = context.getString(R.string.nav_debug_waiting)
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        // A visible bar, because it is the only way to move the window and the only tappable part.
        val title = TextView(context).apply {
            setTextColor(Color.parseColor("#9ED0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.parseColor("#1E2A38"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            text = context.getString(R.string.nav_debug_title)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.parseColor("#E6101418"))
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#3D5A80"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        installDrag(card, title, windows, params, prefs, screenWidth, screenHeight, density)

        return try {
            windows.addView(card, params)
            root = card
            text = body
            scroll = scroller
            NavigationDebugLog.onLine = { main.post { append(it) } }
            render()
            Log.i(TAG, "navigation debug window shown ${width}x$height")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "navigation debug window failed", error)
            text = null
            scroll = null
            false
        }
    }

    /**
     * The title bar drags, so a drag never competes with the log's own scrolling. A long press on it
     * resizes instead, which avoids a second gesture inside a window this small.
     */
    private fun installDrag(
        card: View,
        handle: View,
        windows: WindowManager,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ) {
        val slop = ViewConfiguration.get(handle.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startWidth = 0
        var startHeight = 0
        var dragging = false
        var resizing = false
        val startResize = Runnable {
            if (!dragging) {
                resizing = true
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    startWidth = params.width
                    startHeight = params.height
                    dragging = false
                    resizing = false
                    main.postDelayed(startResize, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (resizing) {
                        params.width = (startWidth + dx).toInt()
                            .coerceIn((MIN_WIDTH_DP * density).toInt(), screenWidth)
                        params.height = (startHeight + dy).toInt()
                            .coerceIn((MIN_HEIGHT_DP * density).toInt(), screenHeight)
                        runCatching { windows.updateViewLayout(card, params) }
                    } else if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        main.removeCallbacks(startResize)
                        params.x = clampX((startX + dx).toInt(), params.width, screenWidth, density)
                        params.y = clampY((startY + dy).toInt(), params.height, screenHeight, density)
                        runCatching { windows.updateViewLayout(card, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(startResize)
                    when {
                        dragging || resizing -> save(prefs, params)
                        // A tap, which on a screen this wide beats dragging the window across it.
                        event.actionMasked == MotionEvent.ACTION_UP ->
                            snapToFreeStrip(handle.context, card, windows, params, prefs, screenWidth, density)
                    }
                }
            }
            true
        }
    }

    private fun save(prefs: android.content.SharedPreferences, params: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y)
            .putInt(KEY_WIDTH, params.width).putInt(KEY_HEIGHT, params.height).apply()
    }

    /**
     * Tapping the title bar puts the window on the strip CarPlay is not using, and a second tap puts
     * it back where it was. Dragging works, but on a 1920 px head unit a tap is the quicker way to get
     * the window off the projection and back onto it to compare the two.
     */
    private fun snapToFreeStrip(
        context: Context,
        card: View,
        windows: WindowManager,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences,
        screenWidth: Int,
        density: Float,
    ) {
        val strip = freeStrip(context, screenWidth)
        if (strip == null) {
            Log.i(TAG, "CarPlay covers the screen; nowhere to snap to")
            return
        }
        val target = if (params.x in strip) previousX ?: 0 else strip.first.also { previousX = params.x }
        params.x = clampX(target, params.width, screenWidth, density)
        runCatching { windows.updateViewLayout(card, params) }
        save(prefs, params)
        Log.i(TAG, "navigation debug window snapped to x=${params.x}")
    }

    /** Where a snap came from, so tapping again puts the window back. */
    private var previousX: Int? = null

    fun hide() {
        val view = root ?: return
        root = null
        text = null
        scroll = null
        NavigationDebugLog.onLine = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        Log.i(TAG, "navigation debug window hidden")
    }

    private fun render() {
        val view = text ?: return
        val lines = NavigationDebugLog.snapshot()
        val context = view.context
        view.text = if (lines.isEmpty()) {
            context.getString(R.string.nav_debug_waiting)
        } else {
            buildString {
                val dropped = NavigationDebugLog.droppedCount()
                if (dropped > 0) appendLine(context.getString(R.string.nav_debug_dropped, dropped))
                lines.forEach { appendLine(NavigationDebugLog.format(it)) }
            }.trimEnd()
        }
        scrollToBottom()
    }

    private fun append(line: NavigationDebugLog.Line) {
        val view = text ?: return
        // Re-rendering the whole buffer on every line is wasteful on a head unit's CPU.
        if (NavigationDebugLog.snapshot().size <= 1) render() else {
            view.append("\n" + NavigationDebugLog.format(line))
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        val scroller = scroll ?: return
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }
}

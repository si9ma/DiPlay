package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.graphics.Outline
import kotlin.math.abs
import android.widget.TextView

/**
 * Narrowed CarPlay drawn in its own floating window, the same way the system bars are separate
 * windows beside an app. The car's launcher (or any app) then stays the real foreground app and
 * receives every touch outside this window; the CarPlay activity moves to the back once video
 * runs. A narrowed *activity* window cannot do this reliably: the app behind it is paused and
 * many head units do not route touches to it.
 */
object ProjectionOverlay {
    private const val TAG = "DiPlayProjection"

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    /** Rounded, clipped container of the CarPlay picture; [root] behind it fills the corners. */
    private var content: FrameLayout? = null
    private var cornerColor: Int? = null

    /** The video view of the current window; null when hidden. */
    var texture: TextureView? = null
        private set

    /** Set by the CarPlay host while it exists; used to restore the window after DiPlay's own screens. */
    var requestShow: (() -> Unit)? = null

    /** Called after [hide] removes the window. */
    var onHidden: (() -> Unit)? = null

    val shown: Boolean get() = root != null

    internal fun showShortcutRecordingBar(bar: View): Boolean {
        val parent = content ?: return false
        (bar.parent as? ViewGroup)?.removeView(bar)
        parent.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return true
    }

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Overlay mode applies only below full width and needs "draw over other apps". */
    fun wanted(context: Context): Boolean = ProjectionWindow.isPartial(context) && permitted(context)

    /** Adds the window and returns a log summary, or null if it already exists or failed. Main thread. */
    fun show(
        activity: Activity,
        listener: TextureView.SurfaceTextureListener,
        onTouch: (View, MotionEvent) -> Boolean,
        statusText: String,
        /** Optional edge handle, added above the touch layer. */
        handle: View? = null,
    ): String? {
        if (root != null || !permitted(activity)) return null
        val app = activity.applicationContext
        val windows = app.getSystemService(WindowManager::class.java) ?: return null
        val percent = AirPlayPersistence.loadProjectionWidthPercent(activity)
        val alignment = AirPlayPersistence.loadProjectionAlignment(activity)
        // Like the system bars, the overlay is laid out beside them rather than under them.
        val available = ProjectionWindow.availableWidth(activity, edgeToEdge = false)
        val width = (available * percent / 100f).toInt()
        val layout = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps keyboard focus with the app behind and implies NOT_TOUCH_MODAL,
            // so touches outside this window go to whatever is underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // TextureView needs it
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = alignment.gravity or Gravity.TOP
            title = "DiPlay CarPlay"
        }
        val video = TextureView(app).apply {
            isOpaque = false
            surfaceTextureListener = listener
        }
        val touch = View(app).apply {
            isClickable = true
            setOnTouchListener { view, event -> onTouch(view, event) }
        }
        val label = TextView(app).apply {
            text = statusText
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(241, 245, 252))
            visibility = View.GONE
        }
        val radius = AirPlayPersistence.loadOverlayCornerRadiusDp(activity) * app.resources.displayMetrics.density
        val inner = FrameLayout(app).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(label, FrameLayout.LayoutParams(-1, -1))
            addView(touch, FrameLayout.LayoutParams(-1, -1))
            handle?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            if (radius > 0f) {
                // Hardware outline clipping also clips the TextureView's video.
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                clipToOutline = true
            }
        }
        val frame = FrameLayout(app).apply { addView(inner, FrameLayout.LayoutParams(-1, -1)) }
        cornerColor = if (AirPlayPersistence.loadOverlayCornerColored(activity)) AirPlayPersistence.loadOverlayCornerColor(activity) else null
        return try {
            windows.addView(frame, layout)
            root = frame; params = layout; texture = video; status = label; content = inner
            bubbleSide = if (alignment == ProjectionWindow.Alignment.RIGHT) Gravity.RIGHT else Gravity.LEFT
            "Projection overlay shown width=${width}px of ${available}px ($percent%) align=$alignment " +
                "corners=${AirPlayPersistence.loadOverlayCornerRadiusDp(activity)}dp " +
                "fill=${cornerColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: "transparent"}"
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay failed", error)
            null
        }
    }

    /**
     * Streaming: opaque, touchable CarPlay. Not streaming with the host activity in front: fully
     * transparent and untouchable so the host's own status and buttons work. Not streaming in
     * the background: a touchable status card; a tap brings the host back.
     */
    fun update(streaming: Boolean, hostInFront: Boolean, statusText: String) {
        lastStreaming = streaming; lastHostInFront = hostInFront; lastStatus = statusText
        val frame = root ?: return
        val layout = params ?: return
        // Minimised: keep the window and its surface size (no CarPlay reconnect), but draw nothing
        // and take no touches. Opacity 0 also satisfies Android 12+ untrusted-touch rules.
        val hidden = minimized || suppressed || hostMenuOpen
        val alpha = if (hidden) 0f else 1f
        val touchable = !hidden && (streaming || !hostInFront)
        val picture = when {
            streaming -> Color.BLACK
            hostInFront -> Color.TRANSPARENT
            else -> Color.argb(230, 12, 17, 27)
        }
        (content ?: frame).setBackgroundColor(picture)
        // Corners: the window behind the rounded content. Filled only while something is drawn.
        if (content != null) frame.setBackgroundColor(if (picture != Color.TRANSPARENT) cornerColor ?: Color.TRANSPARENT else Color.TRANSPARENT)
        status?.text = statusText
        status?.visibility = if (!streaming && !hostInFront) View.VISIBLE else View.GONE
        val flags = if (touchable) layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags != layout.flags || alpha != layout.alpha) {
            layout.flags = flags
            layout.alpha = alpha
            runCatching { frame.context.getSystemService(WindowManager::class.java)?.updateViewLayout(frame, layout) }
        }
    }

    /** Removes the window; its TextureView reports the surface destroyed first. Main thread. */
    fun hide() {
        removeBubble()
        minimized = false
        suppressed = false
        hostMenuOpen = false
        val frame = root ?: return
        root = null; params = null; texture = null; status = null; content = null
        runCatching { frame.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(frame) }
        onHidden?.invoke()
    }

    // ---- minimise to a small bubble -------------------------------------------------------

    @Volatile var minimized = false
        private set

    /**
     * The host activity shows its settings menu underneath: step aside completely, or the floating
     * window would cover the menu and swallow its touches.
     */
    @Volatile var hostMenuOpen = false
        private set

    fun setHostMenuOpen(value: Boolean) {
        if (hostMenuOpen == value) return
        hostMenuOpen = value
        bubble?.visibility = if (value) View.GONE else View.VISIBLE
        update(lastStreaming, lastHostInFront, lastStatus)
    }

    /** The car's camera is on screen: hide CarPlay and its bubble, independently of [minimized]. */
    @Volatile var suppressed = false
        private set

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        bubble?.visibility = if (value) View.GONE else View.VISIBLE
        update(lastStreaming, lastHostInFront, lastStatus)
    }
    private var bubble: View? = null
    private var bubbleSide = Gravity.LEFT
    private var lastStreaming = false
    private var lastHostInFront = false
    private var lastStatus = ""

    /** Hides CarPlay (audio keeps playing) and shows a small edge bubble that restores it. */
    fun minimize(context: Context): Boolean {
        if (root == null || minimized) return false
        if (!addBubble(context.applicationContext)) return false
        minimized = true
        update(lastStreaming, lastHostInFront, lastStatus)
        return true
    }

    fun restore() {
        if (!minimized) return
        minimized = false
        removeBubble()
        update(lastStreaming, lastHostInFront, lastStatus)
    }

    private fun addBubble(app: Context): Boolean {
        if (bubble != null) return true
        val windows = app.getSystemService(WindowManager::class.java) ?: return false
        val density = app.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val prefs = app.getSharedPreferences("diplay_projection_handle", Context.MODE_PRIVATE)
        val layout = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = bubbleSide or Gravity.TOP
            x = (8 * density).toInt()
            y = prefs.getInt("bubble_y", (120 * density).toInt())
            title = "DiPlay CarPlay bubble"
        }
        val icon = ImageView(app).apply {
            setImageResource(com.shilapi.xcertplay.host.R.drawable.ic_carplay)
            val inset = (10 * density).toInt()
            setPadding(inset, inset, inset, inset)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 21, 30, 44)); setStroke((1 * density).toInt(), Color.rgb(47, 62, 84))
            }
            alpha = 0.85f
            contentDescription = "CarPlay"
        }
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downY = 0f; var startY = 0; var dragging = false
        icon.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; startY = layout.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (!dragging && abs(delta) > slop) dragging = true
                    if (dragging) {
                        layout.y = (startY + delta.toInt()).coerceAtLeast(0)
                        runCatching { windows.updateViewLayout(view, layout) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) prefs.edit().putInt("bubble_y", layout.y).apply() else restore()
            }
            true
        }
        return try {
            if (suppressed || hostMenuOpen) icon.visibility = View.GONE
            windows.addView(icon, layout); bubble = icon; true
        } catch (error: RuntimeException) {
            Log.w(TAG, "bubble failed", error); false
        }
    }

    private fun removeBubble() {
        val view = bubble ?: return
        bubble = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }
}

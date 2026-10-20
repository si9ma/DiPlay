package com.shilapi.xcertplay

import android.app.Activity
import android.app.Presentation
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowManager

/** Manual five-second probe only. No video surfaces, OEM writes, ADB, or persisted running state. */
internal object ClusterTransparencyProbe {
    private val main = Handler(Looper.getMainLooper())
    enum class Backend { PRESENTATION, OVERLAY }
    private var shown: android.app.Dialog? = null
    private var targetId: Int? = null
    private var manager: DisplayManager? = null
    private var monitor: Runnable? = null
    private val events = ArrayDeque<String>()
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) { if (targetId == id) stop("display_removed") }
        override fun onDisplayChanged(id: Int) { if (targetId == id) stop("display_changed") }
    }
    private fun note(text: String) = synchronized(events) {
        if (events.size == 30) events.removeFirst()
        events.addLast("${SystemClock.elapsedRealtime()}ms $text")
    }
    fun report(): List<String> = synchronized(events) { events.toList() }
    fun eligible(display: Display): Boolean = display.isValid && display.displayId != Display.DEFAULT_DISPLAY &&
        display.flags and Display.FLAG_PRESENTATION != 0 && display.flags and Display.FLAG_PRIVATE == 0
    fun displays(activity: Activity): List<Display> = activity.getSystemService(DisplayManager::class.java)
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).filter(::eligible)

    fun start(activity: Activity, target: Display, x: Int, y: Int, confirmedParked: Boolean,
        backend: Backend = Backend.PRESENTATION): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop("replaced")
        if (!confirmedParked || activity.isFinishing || activity.isDestroyed || CarPlayBackgroundSession.hasSession() ||
            ClusterMapPresentation.hasOpenWindow() || ClusterActivityOutput.launchPending || ClusterActivityOutput.surface != null) {
            note("rejected=not_confirmed_or_projection_busy")
            return false
        }
        val dm = activity.getSystemService(DisplayManager::class.java)
        val current = dm.getDisplay(target.displayId)
        if (current == null || !eligible(current) || current.name != target.name ||
            ClusterMapPresentation.sizeOf(current) != ClusterMapPresentation.sizeOf(target)) {
            note("rejected=display_changed")
            return false
        }
        if (backend == Backend.OVERLAY && !CustomClusterWindows.canElevate(activity)) {
            note("rejected=overlay_permission_missing")
            return false
        }
        val start = SystemClock.elapsedRealtime()
        return try {
            val presentation = if (backend == Backend.PRESENTATION) {
                ProbePresentation(activity, current, x.coerceIn(5, 95), y.coerceIn(5, 95))
            } else ProbeOverlay(activity, current, x.coerceIn(5, 95), y.coerceIn(5, 95))
            shown = presentation
            targetId = current.displayId
            presentation.setOnDismissListener { if (shown === presentation) stop("dismissed") }
            manager = dm
            dm.registerDisplayListener(displayListener, main)
            presentation.show()
            note("shown display=${current.displayId} backend=$backend flags=${current.flags} x=$x y=$y parked=user_confirmed autoVerified=false durationMs=5000")
            fun recordAttributes() {
                if (shown !== presentation) return
                val window = presentation.window ?: return
                val attached = window.decorView.layoutParams as? WindowManager.LayoutParams
                note("attributes backend=$backend display=${current.displayId} requestedAlpha=1.0 " +
                    "appAlpha=${window.attributes.alpha} attachedAlpha=${attached?.alpha} type=${window.attributes.type} " +
                    "flags=${window.attributes.flags} systemAlpha=not_measured")
            }
            recordAttributes()
            presentation.window?.decorView?.post { recordAttributes() }
            val watch = object : Runnable {
                override fun run() {
                    if (shown !== presentation) return
                    val reason = when {
                        SystemClock.elapsedRealtime() - start >= 5_000 -> "timeout"
                        activity.isFinishing || activity.isDestroyed -> "activity_closed"
                        CarPlayBackgroundSession.hasSession() -> "carplay_started"
                        ClusterActivityOutput.launchPending || ClusterActivityOutput.surface != null -> "projection_started"
                        else -> null
                    }
                    if (reason != null) stop(reason) else main.postDelayed(this, 100)
                }
            }
            monitor = watch
            main.post(watch)
            true
        } catch (error: Exception) {
            note("show_failed=${error.javaClass.simpleName}")
            stop("show_failed")
            false
        }
    }

    fun stop(reason: String = "user") {
        monitor?.let(main::removeCallbacks); monitor = null
        manager?.unregisterDisplayListener(displayListener); manager = null
        val old = shown; shown = null; targetId = null
        if (old != null) {
            old.setOnDismissListener(null)
            runCatching { old.dismiss() }
            note("stopped=$reason")
        }
    }

    internal class ProbePresentation(activity: Activity, display: Display, private val x: Int, private val y: Int) :
        Presentation(activity, display, com.shilapi.xcertplay.host.R.style.Theme_ClusterTransparencyProbe) {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            configureProbe(this, x, y)
        }
    }

    internal class ProbeOverlay(activity: Activity, display: Display, private val x: Int, private val y: Int) :
        android.app.Dialog(CustomClusterWindows.overlayContext(activity, display),
            com.shilapi.xcertplay.host.R.style.Theme_ClusterTransparencyProbe) {
        init { window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            configureProbe(this, x, y)
        }
    }

    private fun configureProbe(dialog: android.app.Dialog, x: Int, y: Int) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            attributes = attributes.apply { alpha = 1f; dimAmount = 0f; windowAnimations = 0 }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setContentView(ProbeView(dialog.context, x, y, recordDraw = true))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    internal class ProbeView(context: android.content.Context, private val x: Int, private val y: Int,
        private val recordDraw: Boolean = false) : View(context) {
        private val paint = Paint()
        private var recorded = false
        init { setBackgroundColor(Color.TRANSPARENT) }
        override fun onDraw(canvas: Canvas) {
            val rects = rectangles(width, height, x, y)
            paint.color = Color.rgb(0, 190, 210); canvas.drawRect(rects.first, paint)
            paint.color = Color.rgb(245, 170, 40); canvas.drawRect(rects.second, paint)
            if (recordDraw && !recorded) {
                recorded = true
                note("drawn display=${display?.displayId} size=${width}x$height rectA=${rects.first} rectB=${rects.second} otherPixels=transparent")
            }
        }
    }

    fun rectangles(width: Int, height: Int, x: Int, y: Int): Pair<RectF, RectF> {
        val side = minOf(24f, width / 10f, height / 10f).coerceAtLeast(0f)
        val left = (width * x / 100f - side * 1.5f).coerceIn(0f, (width - side * 3).coerceAtLeast(0f))
        val top = (height * y / 100f - side / 2).coerceIn(0f, (height - side).coerceAtLeast(0f))
        return RectF(left, top, left + side, top + side) to RectF(left + side * 2, top, left + side * 3, top + side)
    }
}

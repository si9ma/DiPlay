package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.vision.CarPlayScreenState
import com.shilapi.xcertplay.vision.CarPlayVision
import com.shilapi.xcertplay.vision.VisionModels
import com.shilapi.xcertplay.vision.VisionResult
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Picture recognition test (Diagnostics): a small floating panel with "Recognize" and "Exit".
 * "Recognize" copies CarPlay's current picture, reads it on a background thread with the models in
 * assets/vision/models, and draws what was found, with the time it took, over the CarPlay picture.
 * It never touches the decoder, the touch path or the connection; the drawing passes touches through.
 */
internal class CarPlayVisionTest(
    private val context: Context,
    palette: DiPlayPalette,
    /** Content area of CarPlay's canvas in the container the views are added to; null while unknown. */
    private val contentBounds: () -> RectF?,
    private val recognize: () -> Unit,
    private val exit: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private var closed = false
    private var running = false

    private val status = TextView(context).apply {
        textSize = 14f
        setTextColor(palette.overlayPrimaryText)
        text = context.getString(R.string.vision_test_hint)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 3
    }
    private val recognizeButton = DiPlayButton(context).apply {
        text = context.getString(R.string.vision_test_recognize)
        style(DiPlayButtonKind.PRIMARY, palette, overlay = true)
        setOnClickListener { if (!running) recognize() }
    }

    /** Add with WRAP_CONTENT at the bottom of the CarPlay container. */
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(palette.overlayBackground); cornerRadius = dp(16).toFloat()
        }
        isClickable = true // touches on the panel must not reach CarPlay
        elevation = dp(8).toFloat()
        addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        addView(recognizeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
        addView(DiPlayButton(context).apply {
            text = context.getString(R.string.vision_test_exit)
            style(DiPlayButtonKind.SECONDARY, palette, overlay = true)
            setOnClickListener { exit() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    /** Add with MATCH_PARENT over the CarPlay picture; it never takes touches. */
    val overlay = ResultView(context)

    /** Width for [panel]: wide enough for the timing line, never wider than the window. */
    fun panelWidth(containerWidth: Int): Int = minOf(dp(560), (containerWidth - dp(24)).coerceAtLeast(dp(200)))

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        listOf(panel, overlay).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        // A read still running on the worker finishes with its own references; only drop ours.
        if (!running) frame?.recycle()
        frame = null
    }

    /**
     * The bitmap the next copy should fill (null: allocate one). It and the pixel buffer are kept
     * between reads, so a read does not allocate two frame-sized buffers each time.
     */
    var frame: Bitmap? = null
        private set
    private var pixels: IntArray? = null

    /** After a failed copy the copier may still own [frame] (a late PixelCopy); never reuse or recycle it. */
    fun dropFrame() { frame = null }

    fun showBusy() {
        running = true
        recognizeButton.isEnabled = false
        status.text = context.getString(R.string.vision_test_running)
    }

    fun showError(reason: String) {
        running = false
        recognizeButton.isEnabled = true
        status.text = context.getString(R.string.vision_test_failed, reason)
        overlay.show(null, "")
    }

    /**
     * Reads [bitmap] on the recognition thread and keeps it for the next copy. [copyMs] is the time
     * the frame copy took; [nowMin] the minute of the day it was taken, to check a route card's arrival time.
     */
    fun read(bitmap: Bitmap, copyMs: Long, nowMin: Double, log: (String) -> Unit) {
        if (frame !== bitmap) frame?.recycle()
        frame = bitmap
        try {
            worker.execute {
                val outcome = runCatching {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val loaded = models
                    val m = loaded ?: VisionModels.load(VisionModels.Assets(context.applicationContext.assets)).also { models = it }
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    val n = bitmap.width * bitmap.height
                    val buffer = pixels?.takeIf { it.size == n } ?: IntArray(n).also { pixels = it }
                    val frame = CarPlayVision.frameOf(bitmap, buffer)
                    val t2 = SystemClock.elapsedRealtimeNanos()
                    val result = CarPlayVision.analyze(frame, m, nowMin)
                    val t3 = SystemClock.elapsedRealtimeNanos()
                    Timing(copyMs, (t3 - t2) / 1_000_000, (t2 - t1) / 1_000_000,
                        if (loaded == null) (t1 - t0) / 1_000_000 else 0L) to result
                }
                main.post {
                    if (closed) { bitmap.recycle(); return@post }
                    running = false
                    recognizeButton.isEnabled = true
                    outcome.onSuccess { (timing, result) ->
                        val line = timing.describe(context)
                        status.text = line
                        overlay.show(result, line)
                        log("Picture recognition: ${timing.recognizeMs} ms (copy ${timing.copyMs} ms, " +
                            "pixels ${timing.convertMs} ms, models ${timing.loadMs} ms) ${CarPlayVision.describe(result)}")
                    }.onFailure { error ->
                        showError(error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
                        log("Picture recognition failed: $error")
                    }
                }
            }
        } catch (_: RuntimeException) {
            showError("worker_unavailable")
        }
    }

    private data class Timing(val copyMs: Long, val recognizeMs: Long, val convertMs: Long, val loadMs: Long) {
        fun describe(context: Context): String {
            val base = context.getString(R.string.vision_test_timing, recognizeMs, copyMs + convertMs) +
                " ($copyMs + $convertMs)"
            return if (loadMs > 0) base + " · " + context.getString(R.string.vision_test_models_loaded, loadMs) else base
        }
    }

    /** Draws the recognised boxes and labels over the CarPlay picture. */
    @SuppressLint("ViewConstructor")
    inner class ResultView(context: Context) : View(context) {
        private var result: VisionResult? = null
        private var header = ""
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val back = Paint().apply { color = Color.argb(185, 0, 0, 0) }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun show(result: VisionResult?, header: String) {
            this.result = result
            this.header = header
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = result ?: return
            val area = contentBounds() ?: return
            if (r.frameWidth <= 0 || r.frameHeight <= 0) return
            val sx = area.width() / r.frameWidth
            val sy = area.height() / r.frameHeight
            fun x(v: Number) = area.left + v.toFloat() * sx
            fun y(v: Number) = area.top + v.toFloat() * sy
            fun rect(b: IntArray, color: Int) {
                box.color = color
                canvas.drawRect(x(b[0]), y(b[1]), x(b[2]), y(b[3]), box)
            }
            val ref = r.scale // input px per reference px
            // Mode: the side bar icon it was read from.
            val dock = CarPlayScreenState.DOCK_ICON_ROI
            rect(IntArray(4) { (dock[it] * ref).toInt() }, MAGENTA)
            label(canvas, r.mode, x(dock[0] * ref), y(dock[1] * ref) - 4 * density, MAGENTA, above = true)
            if (r.activeWidth < r.frameWidth) {
                box.color = MAGENTA
                canvas.drawLine(x(r.activeWidth), area.top, x(r.activeWidth), area.bottom, box)
            }
            r.topButtons.forEach { b ->
                rect(b.box, GREEN)
                label(canvas, "${b.label} ${fmt(b.score)}", x(b.box[0]), y(b.box[3]) + 4 * density, GREEN)
            }
            r.cornerButtons.forEach { b ->
                rect(b.box, CYAN)
                label(canvas, "${b.label} ${fmt(b.score)}", x(b.box[0]) - 6 * density, (y(b.box[1]) + y(b.box[3])) / 2, CYAN,
                    alignRight = true, centred = true)
            }
            val p = r.panel
            p.startBtn?.let { b ->
                rect(b, RED)
                label(canvas, "${p.page} · ${p.greenText ?: "?"} ${fmt(p.greenScore)}", x(b[2]) + 6 * density,
                    (y(b[1]) + y(b[3])) / 2, RED, centred = true)
            }
            p.routes.forEachIndexed { i, route ->
                val color = if (route.selected == true) YELLOW else ORANGE
                val left = (ROUTE_X0 * ref).toInt(); val right = (ROUTE_X1 * ref).toInt()
                rect(intArrayOf(left, route.y[0], right, route.y[1]), color)
                val texts = route.text?.entries?.joinToString(" / ") { (k, v) ->
                    "${v ?: "?"}(${fmt(route.conf?.get(k) ?: 0.0)})"
                } ?: "-"
                val fields = if (route.ok == true) listOfNotNull(
                    route.etaMin?.let { "${it}min" }, route.distKm?.let { "${it}km" },
                    route.lights?.let { "lights $it" }, route.toll?.let { "¥$it" }, route.arrive,
                ).joinToString(" ") else "✗"
                val mark = when (route.selected) { true -> " ●"; false -> " ○"; null -> "" }
                label(canvas, "#$i$mark $texts → $fields", x(right) + 6 * density, (y(route.y[0]) + y(route.y[1])) / 2, color,
                    centred = true)
            }
            val summary = "mode=${r.mode} app=${r.app ?: "-"} panel=${p.page}" +
                (p.selectedIndex?.let { " selected=#$it" } ?: "")
            val hx = x(HEADER_X * ref); val hy = y(HEADER_Y * ref)
            label(canvas, summary, hx, hy, Color.WHITE)
            if (header.isNotEmpty()) label(canvas, header, hx, hy + text.textSize * 1.5f, Color.WHITE)
        }

        private fun label(canvas: Canvas, s: String, x: Float, y: Float, color: Int, alignRight: Boolean = false,
            centred: Boolean = false, above: Boolean = false) {
            val pad = 4 * density
            val w = text.measureText(s)
            val fm = text.fontMetrics
            val h = fm.descent - fm.ascent
            val left = (if (alignRight) x - w - 2 * pad else x).coerceIn(0f, (width - w - 2 * pad).coerceAtLeast(0f))
            val top = when {
                centred -> y - h / 2 - pad
                above -> y - h - 2 * pad
                else -> y
            }.coerceIn(0f, (height - h - 2 * pad).coerceAtLeast(0f))
            canvas.drawRect(left, top, left + w + 2 * pad, top + h + 2 * pad, back)
            text.color = color
            canvas.drawText(s, left + pad, top + pad - fm.ascent, text)
        }

        private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    }

    companion object {
        private val GREEN = Color.rgb(52, 199, 89)
        private val CYAN = Color.rgb(50, 173, 230)
        private val RED = Color.rgb(255, 69, 58)
        private val YELLOW = Color.rgb(255, 214, 10)
        private val ORANGE = Color.rgb(255, 159, 10)
        private val MAGENTA = Color.rgb(255, 55, 200)
        /** Route rows span the text and the radio circle (route_reader TEXT_X[0]..RADIO_X[1]), reference px. */
        private const val ROUTE_X0 = 130
        private const val ROUTE_X1 = 540
        /** Summary lines: right of the route panel, below the top buttons, reference px. */
        private const val HEADER_X = 590
        private const val HEADER_Y = 100

        /** One recognition at a time, off the main thread; the models stay loaded once read. */
        private val worker by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "diplay-vision").apply { isDaemon = true } }
        }
        @Volatile private var models: VisionModels? = null

        /** The head unit's minute of the day now, as tools/vision `frame_minute`. */
        fun minuteOfDay(now: Calendar = Calendar.getInstance()): Double =
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0
    }
}

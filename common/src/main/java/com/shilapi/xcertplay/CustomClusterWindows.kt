package com.shilapi.xcertplay

import android.app.Dialog
import android.app.Presentation
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

internal enum class ClusterWindowPanel { MUSIC, MAP }

/** Main-thread state follows the media session, not the lifetime of a Presentation or Activity. */
internal object ClusterWindowVisibility {
    private class State {
        var music = true
        var map = true
        val windows = mutableListOf<java.lang.ref.WeakReference<CustomClusterWindows>>()
        fun apply(window: CustomClusterWindows) = window.setPanelVisibility(music, map)
    }
    private val sessions = java.util.WeakHashMap<Any, State>()

    fun attach(session: Any, window: CustomClusterWindows) {
        detach(window)
        val state = sessions.getOrPut(session) { State() }
        state.windows.add(java.lang.ref.WeakReference(window))
        state.apply(window)
    }

    fun detach(window: CustomClusterWindows) {
        sessions.values.forEach { it.windows.removeAll { ref -> ref.get().let { value -> value == null || value === window } } }
    }

    fun rawMapWindow(session: Any): CustomClusterWindows? = sessions[session]?.windows
        ?.mapNotNull { it.get() }?.lastOrNull { it.rawMapSurface() != null }

    fun setVisible(context: Context, controller: com.shilapi.xcertplay.orchestration.CarPlayController,
        panel: ClusterWindowPanel, visible: Boolean): Boolean {
        val session = controller.mediaAttachmentToken() ?: return false
        val config = ClusterWindowSettings.load(context)
        val unavailable = when {
            panel == ClusterWindowPanel.MUSIC && !config.music -> "music_disabled"
            panel == ClusterWindowPanel.MAP && !config.map -> "map_disabled"
            panel == ClusterWindowPanel.MAP && controller.configuredClusterSize() == null -> "map_not_negotiated"
            CustomClusterWindows.findDisplay(context, config) == null -> "no_display"
            else -> null
        }
        if (unavailable != null) {
            ClusterMusicState.note("custom visibility rejected panel=$panel reason=$unavailable")
            return false
        }
        val state = sessions.getOrPut(session) { State() }
        when (panel) {
            ClusterWindowPanel.MUSIC -> state.music = visible
            ClusterWindowPanel.MAP -> state.map = visible
        }
        state.windows.removeAll { it.get() == null }
        state.windows.mapNotNull { it.get() }.forEach(state::apply)
        ClusterMusicState.note("custom visibility requested panel=$panel visible=$visible targets=${state.windows.size}")
        return true
    }
}

/** One transparent instrument host; video and music have independent rectangles and visibility. */
internal class CustomClusterWindows(
    context: Context, val display: Display, val elevated: Boolean,
    private val streamWidth: Int, private val streamHeight: Int,
    initial: ClusterWindowConfig, private val onVisibilityChanged: () -> Unit = {},
    private val onSurface: (Surface?) -> Unit,
) {
    private var settings = initial
    private val host: Dialog = if (elevated) {
        object : Dialog(overlayContext(context, display), R.style.Theme_ClusterTransparencyProbe) {
            override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); createContent() }
            override fun onStop() { super.onStop(); stopped() }
        }.apply {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            window?.setGravity(Gravity.FILL)
            setCanceledOnTouchOutside(false)
        }
    } else {
        object : Presentation(context, display, R.style.Theme_ClusterTransparencyProbe) {
            override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); createContent() }
            override fun onStop() { super.onStop(); stopped() }
            override fun show() {
                try { super.show() } catch (error: RuntimeException) {
                    // Presentation registers a display listener before WindowManager.addView.
                    super.onStop()
                    throw error
                }
            }
        }
    }
    val context: Context get() = host.context
    val window: Window? get() = host.window
    val isShowing: Boolean get() = shown && host.isShowing
    val backend: String get() = if (elevated) "overlay_dialog" else "presentation"
    val requestedType: Int = windowType(display, initial.elevated)
    val contextType: Int = windowType(display, elevated)
    val actualType: Int? get() = (window?.peekDecorView()?.takeIf { it.isAttachedToWindow }
        ?.layoutParams as? WindowManager.LayoutParams)?.type ?: window?.attributes?.type
    fun diagnosticState() = "backend=$backend requested=$requestedType contextType=$contextType actual=$actualType display=${display.displayId}"
    private var shown = false
    private var dismissReason = "system_dismissed"
    private var observingDisplay = false
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == display.displayId) dismiss("display_removed")
        }
    }

    fun create() = host.create()
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) = host.setOnDismissListener(listener)
    fun show() {
        if (isShowing) return
        dismissReason = "system_dismissed"
        try {
            if (elevated && !observingDisplay) {
                displayManager?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
                observingDisplay = true
            }
            host.show()
            check(host.isShowing) { "Window did not become visible" }
            shown = true
            ClusterTransparencyProbe.stop("custom_windows_started")
            ClusterMusicState.note("custom windows shown ${diagnosticState()} music=${settings.music} map=${settings.map}")
        } catch (error: RuntimeException) {
            runCatching { dismiss("show_failed") }
            throw error
        }
    }
    fun dismiss(reason: String = "owner_dismissed") {
        dismissReason = reason
        try { host.dismiss() } finally { stopped() }
    }
    private fun stopped() {
        if (observingDisplay) {
            displayManager?.unregisterDisplayListener(displayListener)
            observingDisplay = false
        }
        ClusterWindowVisibility.detach(this)
        releaseOutputSurface()
        if (shown) {
            shown = false
            ClusterMusicState.note("custom windows stopped ${diagnosticState()} reason=$dismissReason")
        }
    }
    private fun releaseOutputSurface() {
        mapFrameReceived = false
        val surface = outputSurface ?: return
        outputSurface = null
        try { onSurface(null) } finally { surface.release() }
    }
    private lateinit var root: FrameLayout
    private var video: TextureView? = null
    private var mapFrame: MapPanel? = null
    private var waiting: TextView? = null
    private var music: MusicPanel? = null
    private var night = HeadUnitNightMode.read(context, context.resources.configuration) ?: true

    fun setNightMode(value: Boolean) {
        night = value
        applyPanelTheme()
    }

    private fun applyPanelTheme() {
        val colors = panelColors(night, settings.opacity)
        mapFrame?.setBackgroundColor(colors.background)
        waiting?.setBackgroundColor(Color.TRANSPARENT)
        waiting?.setTextColor(colors.text)
        music?.night = night
    }
    var outputSurface: Surface? = null
        private set
    var mapActive = false
        private set
    private var mapFrameReceived = false
    fun rawMapSurface(): Surface? = outputSurface?.takeIf {
        isShowing && mapActive && mapVisible && mapFrameReceived && it.isValid
    }
    val hasMap get() = settings.map
    private var showMusic = true
    private var showMap = true
    val musicVisible get() = settings.music && showMusic
    val mapVisible get() = settings.map && showMap

    fun setPanelVisibility(musicVisible: Boolean, mapVisible: Boolean) {
        val changed = showMusic != musicVisible || showMap != mapVisible
        showMusic = musicVisible; showMap = mapVisible
        // Alpha keeps the map TextureView attached and its negotiated surface alive.
        music?.alpha = if (this.musicVisible) 1f else 0f
        mapFrame?.alpha = if (this.mapVisible) 1f else 0f
        if (changed) {
            ClusterMusicState.note("custom visibility music=${this.musicVisible} map=${this.mapVisible}")
            onVisibilityChanged()
        }
    }

    private fun createContent() {
        window?.apply {
            setFormat(PixelFormat.TRANSLUCENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        if (settings.map) {
            mapFrame = MapPanel(context).apply {
                setBackgroundColor(Color.rgb(16, 23, 32))
                // The video fills the panel, so panel and video coordinates match.
                contentBounds = { w, h -> displayTransform(w, h, settings.trim).content }
                untrimmedContentBounds = { w, h -> displayTransform(w, h, com.shilapi.xcertplay.airplay.AirPlayInsets()).content }
                applyBlend(settings)
            }
            video = TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(streamWidth, streamHeight)
                        transform()
                        outputSurface = Surface(texture).also(onSurface)
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { mapFrameReceived = true }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        mapFrameReceived = false
                        releaseOutputSurface()
                        return true
                    }
                }
            }
            mapFrame!!.addView(video, FrameLayout.LayoutParams(-1, -1))
            waiting = TextView(context).apply {
                text = context.getString(R.string.cluster_waiting_for_map)
                setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(16, 23, 32))
            }
            mapFrame!!.addView(waiting, FrameLayout.LayoutParams(-1, -1))
            root.addView(mapFrame)
        }
        if (settings.music) { music = MusicPanel(context, settings); root.addView(music) }
        applyPanelTheme()
        setPanelVisibility(showMusic, showMap)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPanels() }
        host.setContentView(root)
        window?.setLayout(-1, -1)
        layoutPanels()
    }

    fun apply(next: ClusterWindowConfig) {
        settings = next; music?.config = next
        mapFrame?.applyBlend(next)
        applyPanelTheme()
        setPanelVisibility(showMusic, showMap)
        layoutPanels()
        transform()
    }
    fun setStreamActive(active: Boolean) {
        if (!active) mapFrameReceived = false
        mapActive = active
        waiting?.visibility = if (active) View.GONE else View.VISIBLE
        video?.alpha = if (active) 1f else 0f
    }
    private fun layoutPanels() {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return
        fun place(view: View?, bounds: ClusterWindowRect) {
            if (view == null) return
            val r = bounds.pixels(root.width, root.height)
            val old = view.layoutParams as? FrameLayout.LayoutParams
            if (old?.width == r.width() && old.height == r.height() && old.leftMargin == r.left && old.topMargin == r.top) return
            view.layoutParams = FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP or Gravity.LEFT).apply { leftMargin = r.left; topMargin = r.top }
        }
        place(mapFrame, settings.mapRect); place(music, settings.activeMusicRect)
        music?.invalidate(); transform()
    }
    private fun transform() {
        val view = video ?: return
        if (view.width <= 0 || view.height <= 0) return
        val result = displayTransform(view.width, view.height, settings.trim)
        view.setTransform(result.matrix)
        view.clipBounds = result.clip
        mapFrame?.refreshContent()
    }

    private fun displayTransform(w: Int, h: Int, trim: com.shilapi.xcertplay.airplay.AirPlayInsets): MapTransform {
        if (settings.mapDisplayMode == ClusterMapDisplayMode.FIT_WINDOW) {
            return mapTransform(streamWidth, streamHeight, w, h, settings.crop, trim, settings.trimInPlace)
        }
        val size = ClusterMapPresentation.sizeOf(display)
        val referenceWidth = if (::root.isInitialized && root.width > 0) root.width else size.x.coerceAtLeast(1)
        val referenceHeight = if (::root.isInitialized && root.height > 0) root.height else size.y.coerceAtLeast(1)
        return panelCropTransform(streamWidth, streamHeight, w, h, referenceWidth, referenceHeight,
            settings.mapCenterX, settings.mapCenterY, trim)
    }

    /** [content] is the cropped picture in view coordinates, limited to the window. */
    internal data class MapTransform(val matrix: Matrix, val clip: Rect, val content: RectF)
    internal data class PanelColors(val background: Int, val text: Int, val detail: Int, val placeholder: Int)
    internal data class MusicLayout(val cover: RectF?, val text: RectF)

    internal class MapPanel(context: Context) : FrameLayout(context) {
        private var blend = false
        private var trimInPlace = false
        private var panelCrop = false
        private var featherPercent = ClusterWindowSettings.MAP_FEATHER_DEFAULT
        private var cornerPercent = 4
        private val bounds = RectF()
        /** Cropped video area for a panel size, in panel coordinates; null means the whole panel. */
        var contentBounds: ((Int, Int) -> RectF)? = null
            set(value) { field = value; refreshContent() }
        var untrimmedContentBounds: ((Int, Int) -> RectF)? = null
            set(value) { field = value; refreshContent() }
        private var maskArea: RectF? = null
        private var maskReferenceSide = 0f
        private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private var mask: Bitmap? = null

        init { setWillNotDraw(false) }

        fun applyBlend(config: ClusterWindowConfig) {
            val feather = config.mapFeatherPercent.coerceIn(ClusterWindowSettings.MAP_FEATHER_PERCENT_RANGE)
            val corner = config.mapCornerPercent.coerceIn(ClusterWindowSettings.MAP_CORNER_PERCENT_RANGE)
            val referenceCrop = config.mapDisplayMode == ClusterMapDisplayMode.PANEL_CROP
            if (blend == config.mapBlend && trimInPlace == config.trimInPlace && panelCrop == referenceCrop && featherPercent == feather && cornerPercent == corner) {
                refreshContent()
                return
            }
            blend = config.mapBlend; trimInPlace = config.trimInPlace; panelCrop = referenceCrop
            featherPercent = feather; cornerPercent = corner
            rebuildMask()
            invalidate()
        }

        /** Rebuilds the mask only when the visible picture or its feather reference changes. */
        fun refreshContent() {
            if (width <= 0 || height <= 0) return
            if (trimInPlace || panelCrop) invalidate()
            if (!blend) return
            val area = visibleArea()
            if (area == maskArea && referenceSide(area) == maskReferenceSide) return
            rebuildMask()
            invalidate()
        }

        override fun isOpaque(): Boolean = !blend && !trimInPlace && !panelCrop && super.isOpaque()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildMask()
        }

        private fun visibleArea(): RectF {
            val area = RectF(0f, 0f, width.toFloat(), height.toFloat())
            contentBounds?.invoke(width, height)?.let { if (!area.intersect(it)) area.setEmpty() }
            return area
        }

        private fun referenceSide(area: RectF): Float {
            val reference = if (trimInPlace || panelCrop) untrimmedContentBounds?.invoke(width, height) ?: bounds else area
            return minOf(reference.width(), reference.height()).coerceAtLeast(0f)
        }

        private fun rebuildMask() {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            // Not recycled: a display list on the render thread may still reference the old mask.
            mask = null; maskArea = null
            if (!blend || width <= 0 || height <= 0 || (featherPercent == 0 && cornerPercent == 0)) return
            val area = visibleArea()
            maskReferenceSide = referenceSide(area)
            mask = blendMask(width, height, area, featherPercent, cornerPercent, maskReferenceSide)
            maskArea = area
        }

        override fun draw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val area = if (trimInPlace || panelCrop) visibleArea() else null
            if (area?.isEmpty == true) return
            val save = canvas.save()
            try {
                if (area != null) canvas.clipRect(area)
                val image = mask
                if (!blend || image == null) {
                    super.draw(canvas)
                } else {
                    val layer = canvas.saveLayer(bounds, null)
                    super.draw(canvas)
                    canvas.drawBitmap(image, null, bounds, maskPaint)
                    canvas.restoreToCount(layer)
                }
            } finally { canvas.restoreToCount(save) }
        }
    }

    internal class MusicPanel(context: Context, var config: ClusterWindowConfig) : View(context) {
        var night = true
            set(value) { field = value; invalidate() }
        private var snapshot = ClusterMusicState.Snapshot()
        private var stop: (() -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            stop = ClusterMusicState.observe { snapshot = it; invalidate() }
        }
        override fun onDetachedFromWindow() { stop?.invoke(); stop = null; snapshot = ClusterMusicState.Snapshot(); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val pad = minOf(width, height) * .07f
            val colors = panelColors(night, config.opacity)
            paint.color = colors.background
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pad * 1.4f, pad * 1.4f, paint)
            val art = snapshot.artwork?.takeUnless { it.isRecycled }
            val bounds = musicLayout(width, height, config, art != null)
            bounds.cover?.let { rect ->
                if (art != null) { paint.color = Color.WHITE; canvas.drawBitmap(art, null, rect, paint) }
                else { paint.color = colors.placeholder; canvas.drawRoundRect(rect, pad, pad, paint) }
            }
            if (!config.text) return
            val available = bounds.text.width().coerceAtLeast(1f).toInt()
            val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
                else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
            fun textLayout(text: String, lines: Int) = android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, available)
                .setTextDirection(direction).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            val title = snapshot.info.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_cluster_window_waiting)
            if (config.musicMode == ClusterMusicMode.CARD) {
                val titlePaint = android.text.TextPaint(textPaint).apply {
                    color = colors.text
                    textSize = musicTextSizes(height, config, resources.configuration.fontScale).first
                }
                val detailPaint = android.text.TextPaint(textPaint).apply {
                    color = colors.detail
                    textSize = musicTextSizes(height, config, resources.configuration.fontScale).second
                }
                val detail = listOfNotNull(snapshot.info.artist?.takeIf { it.isNotBlank() },
                    snapshot.info.album?.takeIf { it.isNotBlank() }).distinct().joinToString("\n")
                fun layout(text: String, paint: android.text.TextPaint, lines: Int) =
                    android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
                        .setTextDirection(direction).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
                fun fitted(text: String, paint: android.text.TextPaint, maxLines: Int, budget: Float): android.text.StaticLayout {
                    var lines = maxLines
                    var result = layout(text, paint, lines)
                    while (result.height > budget && lines > 1) result = layout(text, paint, --lines)
                    if (result.height > budget) {
                        paint.textSize *= (budget / result.height * .98f).coerceIn(.01f, 1f)
                        result = layout(text, paint, lines)
                    }
                    return result
                }
                val gap = if (detail.isBlank()) 0f else bounds.text.height() * .035f
                val detailBudget = if (detail.isBlank()) 0f else bounds.text.height() * .32f
                val titleLayout = fitted(title, titlePaint, 4, bounds.text.height() - detailBudget - gap)
                val detailLayout = fitted(detail, detailPaint, 2, detailBudget.coerceAtLeast(1f))
                val save = canvas.save()
                canvas.clipRect(bounds.text)
                canvas.translate(bounds.text.left, bounds.text.top)
                titleLayout.draw(canvas)
                canvas.translate(0f, titleLayout.height + gap)
                detailLayout.draw(canvas)
                canvas.restoreToCount(save)
                return
            }
            textPaint.color = colors.text
            textPaint.textSize = musicTextSizes(height, config, resources.configuration.fontScale).first
            val titleLayout = textLayout(title, 2)
            val save = canvas.save()
            canvas.clipRect(bounds.text)
            canvas.translate(bounds.text.left, bounds.text.top)
            titleLayout.draw(canvas)
            textPaint.textSize = musicTextSizes(height, config, resources.configuration.fontScale).second
            textPaint.color = colors.detail
            val detail = snapshot.info.artist?.takeIf { it.isNotBlank() } ?: snapshot.info.album.orEmpty()
            canvas.translate(0f, titleLayout.height + textPaint.textSize * .5f)
            textLayout(detail, 1).draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        /** Fit onto the full panel, then translate a viewport without changing surviving pixels' scale. */
        fun panelCropTransform(streamWidth: Int, streamHeight: Int, width: Int, height: Int,
            panelWidth: Int, panelHeight: Int, centerX: Int, centerY: Int,
            trim: com.shilapi.xcertplay.airplay.AirPlayInsets): MapTransform {
            require(width > 0 && height > 0 && panelWidth > 0 && panelHeight > 0)
            val baseline = mapTransform(streamWidth, streamHeight, panelWidth, panelHeight, false,
                com.shilapi.xcertplay.airplay.AirPlayInsets())
            val values = FloatArray(9).also(baseline.matrix::getValues)
            fun centre(percent: Int, panel: Int, window: Int): Float {
                val requested = panel * percent.coerceIn(0, 100) / 100f
                return if (window <= panel) requested.coerceIn(window / 2f, panel - window / 2f) else panel / 2f
            }
            val dx = width / 2f - centre(centerX, panelWidth, width)
            val dy = height / 2f - centre(centerY, panelHeight, height)
            val matrix = Matrix().apply {
                setScale(values[Matrix.MSCALE_X] * panelWidth / width,
                    values[Matrix.MSCALE_Y] * panelHeight / height)
                postTranslate(values[Matrix.MTRANS_X] + dx, values[Matrix.MTRANS_Y] + dy)
            }
            fun edge(percent: Int, size: Int) = size * percent.coerceIn(ClusterWindowSettings.TRIM_RANGE) / 100f
            val retained = RectF(edge(trim.left, panelWidth), edge(trim.top, panelHeight),
                panelWidth - edge(trim.right, panelWidth), panelHeight - edge(trim.bottom, panelHeight))
            baseline.matrix.mapRect(retained)
            if (!retained.intersect(0f, 0f, panelWidth.toFloat(), panelHeight.toFloat())) retained.setEmpty()
            if (!retained.isEmpty) {
                retained.offset(dx, dy)
                if (!retained.intersect(0f, 0f, width.toFloat(), height.toFloat())) retained.setEmpty()
            }
            val clip = if (retained.isEmpty) Rect() else Rect(kotlin.math.ceil(retained.left).toInt(),
                kotlin.math.ceil(retained.top).toInt(), kotlin.math.floor(retained.right).toInt(),
                kotlin.math.floor(retained.bottom).toInt())
            if (clip.isEmpty) clip.setEmpty()
            return MapTransform(matrix, clip, retained)
        }

        fun mapTransform(streamWidth: Int, streamHeight: Int, width: Int, height: Int, crop: Boolean,
            trim: com.shilapi.xcertplay.airplay.AirPlayInsets, trimInPlace: Boolean = false): MapTransform {
            require(streamWidth > 0 && streamHeight > 0 && width > 0 && height > 0)
            fun edge(percent: Int, size: Int) = size * percent.coerceIn(ClusterWindowSettings.TRIM_RANGE) / 100f
            val trimLeft = edge(trim.left, streamWidth); val trimRight = edge(trim.right, streamWidth)
            val trimTop = edge(trim.top, streamHeight); val trimBottom = edge(trim.bottom, streamHeight)
            if (trimInPlace) {
                val baseline = mapTransform(streamWidth, streamHeight, width, height, crop,
                    com.shilapi.xcertplay.airplay.AirPlayInsets())
                val content = RectF(edge(trim.left, width), edge(trim.top, height),
                    width - edge(trim.right, width), height - edge(trim.bottom, height))
                baseline.matrix.mapRect(content)
                if (!content.intersect(0f, 0f, width.toFloat(), height.toFloat())) content.setEmpty()
                val clip = if (content.isEmpty) Rect() else Rect(
                    kotlin.math.ceil(content.left).toInt(), kotlin.math.ceil(content.top).toInt(),
                    kotlin.math.floor(content.right).toInt(), kotlin.math.floor(content.bottom).toInt())
                if (clip.isEmpty) clip.setEmpty()
                return MapTransform(baseline.matrix, clip, content)
            }
            val sourceWidth = streamWidth - trimLeft - trimRight
            val sourceHeight = streamHeight - trimTop - trimBottom
            val widthScale = width / sourceWidth
            val heightScale = height / sourceHeight
            val fit = minOf(widthScale, heightScale)
            val cover = maxOf(widthScale, heightScale)
            // Stream alignment to eight pixels can otherwise leave a narrow letterbox strip.
            val fills = crop || cover / fit <= 1.01f
            val scale = if (fills) cover else fit
            val left = if (scale == widthScale) 0f else (width - sourceWidth * scale) / 2f
            val top = if (scale == heightScale) 0f else (height - sourceHeight * scale) / 2f
            val matrix = Matrix().apply {
                setScale(streamWidth * scale / width, streamHeight * scale / height)
                postTranslate(left - trimLeft * scale, top - trimTop * scale)
            }
            // The retained source is centred, so each far edge mirrors its near edge exactly.
            // Only discarded source that lands inside the window needs clipping; rounding other edges adds seams.
            val clip = Rect(
                if (trimLeft > 0f && left > 0f) kotlin.math.ceil(left).toInt().coerceIn(0, width) else 0,
                if (trimTop > 0f && top > 0f) kotlin.math.ceil(top).toInt().coerceIn(0, height) else 0,
                if (trimRight > 0f && left > 0f) kotlin.math.floor(width - left).toInt().coerceIn(0, width) else width,
                if (trimBottom > 0f && top > 0f) kotlin.math.floor(height - top).toInt().coerceIn(0, height) else height)
            val visibleLeft = left.coerceAtLeast(0f); val visibleTop = top.coerceAtLeast(0f)
            val content = RectF(visibleLeft, visibleTop, width - visibleLeft, height - visibleTop)
            return MapTransform(matrix, clip, content)
        }

        private const val MASK_MAX_SIDE = 1024

        /** Zero slope and curvature at both ends, so the fade has no visible start or end line. */
        private fun smootherstep(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

        /**
         * Alpha mask for a [width] x [height] panel: opaque inside [area], fading to transparent at its edge over
         * [featherPercent] of [referenceShortSide], clamped to fit the area. The outer radius is at least the fade width, so every alpha
         * contour stays rounded and corners have no diagonal crease.
         */
        internal fun blendMask(width: Int, height: Int, area: RectF, featherPercent: Int, cornerPercent: Int,
            referenceShortSide: Float = minOf(area.width(), area.height())): Bitmap {
            require(width > 0 && height > 0)
            val scale = minOf(1f, MASK_MAX_SIDE.toFloat() / maxOf(width, height))
            val maskWidth = kotlin.math.ceil(width * scale).toInt().coerceAtLeast(1)
            val maskHeight = kotlin.math.ceil(height * scale).toInt().coerceAtLeast(1)
            val pixels = IntArray(maskWidth * maskHeight)
            if (!area.isEmpty) {
                val shortSide = minOf(area.width(), area.height())
                val reference = referenceShortSide.coerceAtLeast(0f)
                val feather = (reference * featherPercent.coerceIn(ClusterWindowSettings.MAP_FEATHER_PERCENT_RANGE) / 100f).coerceAtMost(shortSide / 2f)
                val radius = maxOf(reference * cornerPercent.coerceIn(ClusterWindowSettings.MAP_CORNER_PERCENT_RANGE) / 100f, feather).coerceAtMost(shortSide / 2f)
                val inner = (radius - feather).coerceAtLeast(0f)
                val minX = area.left + radius; val maxX = area.right - radius
                val minY = area.top + radius; val maxY = area.bottom - radius
                for (y in 0 until maskHeight) {
                    val py = (y + .5f) / scale
                    val dy = when { py < minY -> minY - py; py > maxY -> py - maxY; else -> 0f }
                    for (x in 0 until maskWidth) {
                        val px = (x + .5f) / scale
                        val dx = when { px < minX -> minX - px; px > maxX -> px - maxX; else -> 0f }
                        val alpha = if (dx == 0f && dy == 0f) 1f else {
                            // Distance outside the fully opaque rounded core.
                            val d = kotlin.math.sqrt(dx * dx + dy * dy) - inner
                            if (feather > 0f) 1f - smootherstep((d / feather).coerceIn(0f, 1f))
                            else (.5f - d * scale).coerceIn(0f, 1f)
                        }
                        pixels[y * maskWidth + x] = (alpha * 255f + .5f).toInt() shl 24
                    }
                }
            }
            return Bitmap.createBitmap(pixels, maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        }

        fun panelColors(night: Boolean, opacity: Int): PanelColors {
            val alpha = opacity.coerceIn(0, 100) * 255 / 100
            return if (night) PanelColors(Color.argb(alpha, 14, 20, 30), Color.WHITE, Color.rgb(205, 214, 226), Color.DKGRAY)
                else PanelColors(Color.argb(alpha, 242, 246, 251), Color.rgb(22, 31, 43), Color.rgb(69, 83, 101), Color.LTGRAY)
        }

        internal fun musicTextSizes(height: Int, config: ClusterWindowConfig, fontScale: Float): Pair<Float, Float> {
            val ratio = if (config.musicMode == ClusterMusicMode.CARD) .115f else .19f
            val base = (height * ratio * fontScale).coerceAtLeast(minOf(12f, height * ratio))
            return base * config.titleFont.coerceIn(ClusterWindowSettings.MUSIC_FONT_RANGE) / 100f to
                base * .75f * config.artistFont.coerceIn(ClusterWindowSettings.MUSIC_FONT_RANGE) / 100f
        }

        fun musicLayout(width: Int, height: Int, config: ClusterWindowConfig, hasArtwork: Boolean): MusicLayout {
            val pad = minOf(width, height) * .07f
            val text = RectF(pad, pad, width - pad, height - pad)
            if (!config.cover || (config.text && !hasArtwork)) return MusicLayout(null, text)
            val coverScale = config.musicCardCoverSize.coerceIn(ClusterWindowSettings.CARD_COVER_SIZE_RANGE) / 100f
            if (config.musicMode == ClusterMusicMode.CARD && config.text) {
                val side = minOf(minOf(text.width(), (text.height() - pad) * .55f) * coverScale,
                    text.width(), (text.height() - pad) * .7f)
                val left = (width - side) / 2f
                val cover = RectF(left, pad, left + side, pad + side)
                text.top = cover.bottom + pad
                return MusicLayout(cover, text)
            }
            val fullCoverSide = minOf(height - 2 * pad, width - 2 * pad)
            val side = if (config.text) minOf(height - 2 * pad, width * .37f)
                else if (config.musicMode == ClusterMusicMode.CARD) minOf(fullCoverSide * coverScale, fullCoverSide)
                else fullCoverSide
            val x = when {
                !config.text -> (width - side) / 2
                config.coverSide == ClusterCoverSide.RIGHT -> width - pad - side
                else -> pad
            }
            val cover = RectF(x, (height - side) / 2, x + side, (height + side) / 2)
            if (config.text) {
                if (config.coverSide == ClusterCoverSide.RIGHT) text.right = cover.left - pad
                else text.left = cover.right + pad
            }
            return MusicLayout(cover, text)
        }

        // AOSP TYPE_PRESENTATION is hidden from the public SDK; this value is for diagnostics only.
        private const val PRESENTATION_WINDOW_TYPE = 2037

        fun windowType(display: Display, elevated: Boolean): Int = when {
            elevated -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            display.flags and Display.FLAG_PRIVATE != 0 -> WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION
            else -> PRESENTATION_WINDOW_TYPE
        }

        internal fun overlayContext(context: Context, display: Display): Context {
            require(Build.VERSION.SDK_INT >= 26)
            val displayContext = context.createDisplayContext(display)
            // Presentation binds a different WindowContext type on Android 12+; never retag it.
            return if (Build.VERSION.SDK_INT >= 30) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else displayContext
        }

        fun failureSummary(error: Throwable): String {
            val causes = generateSequence(error) { it.cause }.take(3).toList()
            val reason = when {
                causes.any { it.message?.contains("type mismatch", ignoreCase = true) == true } -> "window_type_mismatch"
                causes.any { it is SecurityException || it.message?.contains("permission denied", ignoreCase = true) == true } -> "permission_denied"
                causes.any { it is WindowManager.InvalidDisplayException } -> "invalid_display"
                causes.any { it is WindowManager.BadTokenException } -> "bad_token"
                else -> "window_operation_failed"
            }
            // Exception messages may include tokens, paths or app data. Keep only code locations.
            val stack = causes.joinToString(" <- ") { cause ->
                "${cause.javaClass.simpleName}:" + cause.stackTrace.take(4).joinToString("|") {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                }
            }.take(1400)
            return "reason=$reason stack=$stack"
        }

        fun findDisplay(context: Context, config: ClusterWindowConfig): Display? {
            val all = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty().filter(ClusterTransparencyProbe::eligible)
            return if (config.displayName.isNotEmpty()) all.firstOrNull { it.name == config.displayName }
            else ClusterMapPresentation.findDisplay(context)?.takeIf { candidate -> all.any { it.displayId == candidate.displayId } }
        }
        fun canElevate(context: Context) = android.os.Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(context)
    }
}

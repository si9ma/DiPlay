package com.shilapi.xcertplay.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.max

/** Everything recognised on one CarPlay frame (tools/vision/pipeline.py `analyze_frame`). Input-frame pixels. */
internal data class VisionResult(
    /** dashboard | fullscreen | unknown (the side bar's bottom icon). */
    val mode: String,
    /** map | other | null (null unless fullscreen). */
    val app: String?,
    val topButtons: List<VisionButton>,
    val cornerButtons: List<VisionButton>,
    val panel: VisionPanel,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Width CarPlay draws in; the black columns on the right of a wider canvas are not read. */
    val activeWidth: Int,
    /** Input pixels per reference pixel (frame height / 900). */
    val scale: Double,
)

/** The trained templates (tools/vision/models, shipped as assets/vision/models). */
internal class VisionModels(
    val buttons: List<VisionTemplate>,
    val corner: List<VisionTemplate>,
    val green: List<VisionTemplate>,
    /** Font name -> glyph model; null when none is available (panel geometry only). */
    val route: Map<String, GlyphModel>?,
) {
    /** Where the model files come from: assets on the device, a folder in tests. */
    interface Source {
        /** File names in [dir] (no sub-folders needed). */
        fun list(dir: String): List<String>
        fun open(path: String): InputStream?
    }

    class Assets(private val assets: AssetManager, private val root: String = ASSET_DIR) : Source {
        override fun list(dir: String): List<String> = assets.list("$root/$dir")?.toList() ?: emptyList()
        override fun open(path: String): InputStream? = runCatching { assets.open("$root/$path") }.getOrNull()
    }

    companion object {
        const val ASSET_DIR = "vision/models"

        fun load(source: Source): VisionModels {
            val route = LinkedHashMap<String, GlyphModel>()
            for (font in RouteReader.FONTS.keys) loadGlyphs(source, "route/$font")?.let { route[font] = it }
            return VisionModels(templates(source, "buttons"), templates(source, "corner"), templates(source, "green"),
                route.ifEmpty { null })
        }

        /** `load_templates`: *.png not starting with "_", sorted; label = name up to the first "_". */
        private fun templates(source: Source, dir: String): List<VisionTemplate> =
            source.list(dir).filter { it.lowercase().endsWith(".png") && !it.startsWith("_") }.sorted().mapNotNull { name ->
                val img = source.open("$dir/$name")?.use { decodeGray(it) } ?: return@mapNotNull null
                VisionTemplate(name.substringBeforeLast('.').substringBefore('_'), img)
            }

        /** glyph_ocr `load`: meta.json lists the token files and their characters, in model order. */
        private fun loadGlyphs(source: Source, dir: String): GlyphModel? {
            val meta = source.open("$dir/meta.json")?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } ?: return null
            val tokens = meta.getJSONObject("tokens")
            val list = ArrayList<Pair<String, Gray>>()
            val keys = tokens.keys()
            while (keys.hasNext()) {
                val file = keys.next()
                val img = source.open("$dir/$file")?.use { decodeGray(it) } ?: continue
                list += tokens.getJSONObject(file).getString("char") to img
            }
            return GlyphModel(meta.getInt("H"), meta.getInt("slack"), list)
        }

        /** cv2.imread(..., IMREAD_GRAYSCALE) for the 8-bit grey template PNGs. */
        fun decodeGray(input: InputStream): Gray? {
            val bitmap = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }) ?: return null
            return try {
                val f = CarPlayVision.frameOf(bitmap)
                f.gray()
            } finally { bitmap.recycle() }
        }
    }
}

internal object CarPlayVision {
    const val REF_H = 900
    private const val BLACK_MAX = 2
    private const val BLACK_MIN_RUN = 32
    private const val BLACK_ROW_STEP = 6

    fun frameOf(bitmap: Bitmap): BgrFrame {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return BgrFrame.fromArgb(pixels, w, h)
    }

    /** dataset.py `active_width`: drop the pure black columns right of what CarPlay draws. */
    fun activeWidth(f: BgrFrame): Int {
        var last = -1
        for (x in f.w - 1 downTo 0) {
            var y = 0
            var lit = false
            while (y < f.h) {
                val i = y * f.w + x
                if (f.b[i] > BLACK_MAX || f.g[i] > BLACK_MAX || f.r[i] > BLACK_MAX) { lit = true; break }
                y += BLACK_ROW_STEP
            }
            if (lit) { last = x; break }
        }
        if (last < 0) return f.w
        val x1 = last + 1
        return if (f.w - x1 >= BLACK_MIN_RUN) x1 else f.w
    }

    private fun scaleBox(b: VisionButton, s: Double) =
        b.copy(box = IntArray(4) { Math.rint(b.box[it] * s).toInt() })

    /**
     * One frame -> everything recognised. [nowMin] is the head unit's minute of the day, used to
     * check a route card's arrival time; null skips that check.
     */
    fun analyze(frame: BgrFrame, models: VisionModels, nowMin: Double? = null): VisionResult {
        val aw = activeWidth(frame)
        val bgr = frame.cropWidth(aw)
        val s = bgr.h / REF_H.toDouble()
        // dataset.to_ref_height: INTER_AREA when shrinking, INTER_LINEAR when enlarging.
        val ref = if (bgr.h == REF_H) bgr else bgr.resize(max(1, Math.rint(bgr.w / s).toInt()), REF_H, area = s > 1)
        val gray = ref.gray()
        val mode = when (CarPlayScreenState.dockIcon(gray)) {
            CarPlayScreenState.Dock.GRID -> "dashboard"
            CarPlayScreenState.Dock.SQUARE -> "fullscreen"
            CarPlayScreenState.Dock.UNKNOWN -> "unknown"
        }
        val none = VisionResult(mode, null, emptyList(), emptyList(), VisionPanel.NONE, frame.w, frame.h, aw, s)
        if (mode != "fullscreen") return none
        val top = CarPlayScreenState.readButtons(gray, models.buttons).map { scaleBox(it, s) }
        val corner = CarPlayScreenState.readCorner(gray, models.corner).map { scaleBox(it, s) }
        // route_reader.to_ref always uses INTER_AREA; it differs from the frame above only when enlarging.
        val routeRef = if (s >= 1.0) ref else RouteReader.toRef(bgr).first
        val panel = RouteReader.readRoutePage(routeRef, s, models.route, models.green, nowMin)
        val isMap = corner.isNotEmpty() || top.isNotEmpty() || panel.page != "none"
        return none.copy(app = if (isMap) "map" else "other", topButtons = top, cornerButtons = corner, panel = panel)
    }

    /** One line per item, for the session log. */
    fun describe(r: VisionResult): String = buildString {
        append("mode=").append(r.mode).append(" app=").append(r.app)
        append(" frame=").append(r.frameWidth).append('x').append(r.frameHeight).append(" active=").append(r.activeWidth)
        if (r.topButtons.isNotEmpty()) append(" top=").append(r.topButtons.joinToString(",") { "${it.label}(${it.score})" })
        if (r.cornerButtons.isNotEmpty()) append(" corner=").append(r.cornerButtons.joinToString(",") { "${it.label}(${it.score})" })
        append(" panel=").append(r.panel.page)
        r.panel.greenText?.let { append('/').append(it) }
        r.panel.routes.forEachIndexed { i, route ->
            append(" #").append(i).append(if (route.selected == true) "*" else "")
            append(route.text?.values?.joinToString("|") ?: "-").append(if (route.ok == true) " ok" else "")
        }
    }
}

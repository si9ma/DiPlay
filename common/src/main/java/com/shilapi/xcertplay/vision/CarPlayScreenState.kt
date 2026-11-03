package com.shilapi.xcertplay.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A recognised button: [label], match [score], box (x0, y0, x1, y1). */
internal data class VisionButton(val label: String, val score: Double, val box: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is VisionButton && other.label == label && other.score == score && other.box.contentEquals(box)
    override fun hashCode(): Int = (label.hashCode() * 31 + score.hashCode()) * 31 + box.contentHashCode()
}

/** A labelled template image (file name up to the first "_" is the label). */
internal class VisionTemplate(val label: String, val image: Gray) {
    val float: FImage by lazy { image.toFloat() }
}

/**
 * Mode (side bar icon), top button candidates and the map's corner buttons:
 * tools/vision/carplay_state.py and button_text.py. Coordinates are on the 900-high reference frame.
 */
internal object CarPlayScreenState {
    const val REF_H = 900

    // carplay_state.py
    val DOCK_ICON_ROI = intArrayOf(15, 810, 105, 895)
    private const val DOCK_FG_MIN = 60.0
    private val DOCK_FG_FRACS = doubleArrayOf(0.0, 0.40, 0.55)
    private const val TOP_BAND_X0 = 95
    private const val TOP_BAND_Y1 = 110
    const val TEXT_YC = 45
    const val PILL_HALF_TEXT = 17
    const val GLYPH_HALF = 21
    private const val THROUGH_GAP = 23
    private const val THROUGH_SPAN = 10
    private const val TEXT_GAP = 14
    private const val TEXT_MIN_W = 20
    private const val TEXT_MAX_W = 240
    private const val TEXT_MIN_H = 16
    const val PAD = 12
    private const val HP_K = 9
    private const val GLYPH_HP = 30
    private const val CORNER_DX = 45
    private const val CORNER_Y0 = 854
    private const val CORNER_PITCH = 91
    private const val CORNER_SLOTS = 5
    const val CORNER_R = 36
    private val YC_SEARCH = intArrayOf(TEXT_YC - 2, TEXT_YC, TEXT_YC + 2)

    // button_text.py
    private const val SIZE_TOL_W = 0.20
    private const val SHIFT = 3
    private const val VSLACK = 4
    private const val RECENTER = 10
    private const val TPL_H = 2 * PILL_HALF_TEXT
    private const val MATCH_TH = 0.60
    const val MARGIN = 0.10
    private const val CORNER_SLACK = 4
    private const val CORNER_MATCH_TH = 0.80
    private val CORNER_INSETS = intArrayOf(0, 4, 8, 12, 16, 20)

    enum class Dock { GRID, SQUARE, UNKNOWN }

    data class Pill(val s: Int, val e: Int, val frac: Double, val yc: Int)

    // ------------------------------------------------------------ utils
    /** `_to_ref`: crop an ROI given on the reference frame ([x1] null = right edge) and scale it to it. */
    fun toRef(gray: Gray, x0: Int, y0: Int, x1: Int?, y1: Int): Gray {
        val s = gray.h / REF_H.toDouble()
        val fx1 = if (x1 == null) gray.w / s else x1.toDouble()
        val ax0 = (x0 * s).toInt(); val ay0 = (y0 * s).toInt()
        val ax1 = min((fx1 * s).toInt(), gray.w); val ay1 = (y1 * s).toInt()
        val crop = gray.crop(ax0, ay0, ax1, ay1)
        if (s == 1.0 || crop.isEmpty) return crop
        val rw = max(1, Math.rint((ax1 - ax0) / s).toInt())
        return Cv.resize(crop.toFloat(), rw, y1 - y0, area = true).roundToGray()
    }

    fun highpass(band: Gray): Gray {
        val blur = Cv.boxBlur(band, HP_K)
        return Gray(band.w, band.h, IntArray(band.px.size) { abs(band.px[it] - blur.px[it]) })
    }

    // ------------------------------------------------------------ 1. dock icon
    fun dockIcon(gray: Gray): Dock {
        val r = DOCK_ICON_ROI
        val roi = Cv.gaussian3(toRef(gray, r[0], r[1], r[2], r[3]))
        if (roi.isEmpty) return Dock.UNKNOWN
        val bg = Hist.of(roi).median()
        val bgi = bg.toInt()
        val diff = IntArray(roi.px.size) { abs(roi.px[it] - bgi) }
        val p99 = Hist.of(diff).percentile(99.0)
        for (frac in DOCK_FG_FRACS) {
            val t = max(DOCK_FG_MIN, frac * p99)
            val fg = BooleanArray(diff.size) { diff[it] > t }
            val icon = dockShape(fg, roi, bg)
            if (icon != Dock.UNKNOWN) return icon
        }
        return Dock.UNKNOWN
    }

    private fun dockShape(fg: BooleanArray, roi: Gray, bg: Double): Dock {
        val wFull = roi.w; val hFull = roi.h
        val comps = Cv.components(fg, wFull, hFull, eight = true).filter { c ->
            !(c.area < 6 || c.x == 0 || c.y == 0 || c.x + c.w >= wFull || c.y + c.h >= hFull)
        }
        val small = comps.filter { it.w in 3..14 && it.h in 3..14 }
        if (small.size >= 7) {
            val uniform = Cv.relStd(small.map { it.area }) < 0.5
            val xs = small.map { it.x + it.w / 2.0 }; val ys = small.map { it.y + it.h / 2.0 }
            val spanX = xs.max() - xs.min(); val spanY = ys.max() - ys.min()
            if (uniform && spanX > 8 && spanX < 40 && spanY > 8 && spanY < 40 &&
                spanX / max(spanY, 1.0) > 0.7 && spanX / max(spanY, 1.0) < 1.4) return Dock.GRID
        }
        for (c in comps) {
            if (c.w !in 14..45 || c.h !in 14..45) continue
            val ratio = c.w / c.h.toDouble()
            if (!(ratio > 0.75 && ratio < 1.33)) continue
            val fill = c.area / (c.w * c.h).toDouble()
            val cx = c.x + c.w / 2; val cy = c.y + c.h / 2
            var on = 0; var total = 0
            for (y in (cy - c.h / 5).coerceAtLeast(0) until min(cy + c.h / 5 + 1, hFull))
                for (x in (cx - c.w / 5).coerceAtLeast(0) until min(cx + c.w / 5 + 1, wFull)) {
                    total++; if (fg[y * wFull + x]) on++
                }
            val centerEmpty = total > 0 && on / total.toDouble() < 0.1
            if (fill < 0.55 && centerEmpty) return Dock.SQUARE
            if (fill >= 0.55 && gridInside(roi.crop(c.x, c.y, c.x + c.w, c.y + c.h), bg)) return Dock.GRID
        }
        return Dock.UNKNOWN
    }

    private fun gridInside(patch: Gray, bg: Double): Boolean {
        val p = Cv.resize(patch.toFloat(), patch.w * 2, patch.h * 2, area = false).roundToGray()
        val t = Cv.otsu(p)
        val invert = bg > t
        val m = BooleanArray(p.px.size) { (p.px[it] > t) != invert }
        if (m.none { it }) return false
        val cells = Cv.components(m, p.w, p.h, eight = false).filter { it.area >= 6 }
        if (cells.size < 7) return false
        return Cv.relStd(cells.map { it.area }) < 0.5
    }

    // ------------------------------------------------------------ 2. top buttons
    /** The top band (side bar to the right edge), blurred, as a high-pass picture. */
    fun bandHighpass(gray: Gray): Gray = highpass(Cv.gaussian3(toRef(gray, TOP_BAND_X0, 0, null, TOP_BAND_Y1)))

    private fun findPills(hp: Gray, yc: Int): List<Pill> {
        val t0 = yc - GLYPH_HALF; val t1 = yc + GLYPH_HALF
        val a0 = yc - THROUGH_GAP - THROUGH_SPAN; val a1 = yc - THROUGH_GAP
        val b0 = yc + THROUGH_GAP; val b1 = yc + THROUGH_GAP + THROUGH_SPAN
        if (a0 < 0 || b1 > hp.h) return emptyList()
        val w = hp.w
        val glyphCol = BooleanArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in t0 until t1) if (hp[x, y] > GLYPH_HP) c++
            var above = false; var below = false
            for (y in a0 until a1) if (hp[x, y] > GLYPH_HP) { above = true; break }
            for (y in b0 until b1) if (hp[x, y] > GLYPH_HP) { below = true; break }
            glyphCol[x] = c >= 2 && !(above && below)
        }
        val pills = ArrayList<Pill>()
        for (run in Cv.runs(glyphCol, TEXT_GAP)) {
            val tw = run.len
            if (tw < TEXT_MIN_W || tw > TEXT_MAX_W) continue
            var on = 0
            for (x in run.s until run.e) if (glyphCol[x]) on++
            val frac = on / tw.toDouble()
            if (frac < 0.3) continue
            var first = -1; var last = -1
            for (y in t0 until t1) {
                var c = 0
                for (x in run.s until run.e) if (hp[x, y] > GLYPH_HP) c++
                if (c >= 2) { if (first < 0) first = y; last = y }
            }
            val th = if (first >= 0) last - first + 1 else 0
            if (th < TEXT_MIN_H) continue
            pills += Pill(run.s, run.e, Math.round(frac * 100) / 100.0, yc)
        }
        return pills
    }

    /** `top_buttons`: candidates from left to right; x is on the top band (frame x = x + 95). */
    fun topCandidates(hp: Gray): List<Pill> {
        val cands = YC_SEARCH.flatMap { findPills(hp, it) }
            .sortedWith(compareBy<Pill>({ abs(it.yc - TEXT_YC) }, { it.s }))
        val kept = ArrayList<Pill>()
        for (p in cands) {
            if (kept.any { q -> p.s < q.e && q.s < p.e }) continue
            kept += p
        }
        return kept.sortedWith(compareBy<Pill>({ it.s }, { it.e }, { it.frac }, { it.yc }))
    }

    private fun extractGlyph(hp: Gray, pill: Pill): Gray? {
        val (s, e, _, yc0) = pill
        val win = PILL_HALF_TEXT + RECENTER
        val w0 = max(yc0 - win, 0)
        val wnd = hp.crop(s, w0, e, yc0 + win)
        if (wnd.isEmpty) return null
        val thr = max(0.5 * GLYPH_HP, 0.3 * Hist.of(wnd).percentile(99.0))
        var first = -1; var last = -1
        for (y in 0 until wnd.h) {
            var c = 0
            for (x in 0 until wnd.w) if (wnd[x, y] > thr) c++
            if (c >= 2) { if (first < 0) first = y; last = y }
        }
        val yc = if (first >= 0) w0 + (first + last) / 2 else yc0
        val y0 = yc - PILL_HALF_TEXT - VSLACK; val y1 = yc + PILL_HALF_TEXT + VSLACK
        val crop = hp.crop(s, max(y0, 0), e, min(y1, hp.h)).border(max(0, -y0), max(0, y1 - hp.h), 0, 0)
        val coreH = min(TPL_H, crop.h - VSLACK)
        if (coreH <= 0 || crop.w == 0) return null
        var c0 = -1; var c1 = -1; var nCols = 0
        for (x in 0 until crop.w) {
            var any = false
            for (y in VSLACK until VSLACK + coreH) if (crop[x, y] > thr) { any = true; break }
            if (any) { if (c0 < 0) c0 = x; c1 = x; nCols++ }
        }
        if (nCols < 5) return null
        val f = crop.crop(c0, 0, c1 + 1, crop.h).toFloat()
        val p98 = max(Hist.ofWhole(f).percentile(98.0), 1.0).toFloat()
        for (i in f.px.indices) f.px[i] = (f.px[i] / p98).coerceIn(0f, 1f) * 255f
        return Cv.gaussian3(f).truncateToGray()
    }

    /** `_ncc`: best match of [t] sliding ±SHIFT across and ±VSLACK down the glyph feature. */
    private fun ncc(feat: Gray, t: FImage): Double {
        val padx = SHIFT + max(0, t.w - feat.w)
        val cand = feat.border(0, max(0, t.h - feat.h), padx, padx).toFloat()
        return Cv.peak(Cv.matchTemplate(cand, t)).toDouble()
    }

    private data class Match(val label: String?, val best: Double)

    private fun ranked(scores: Map<String, Double>, threshold: Double): Match {
        if (scores.isEmpty()) return Match(null, 0.0)
        val order = scores.entries.sortedByDescending { it.value }
        val s1 = order[0].value
        val s2 = if (order.size > 1) order[1].value else 0.0
        return Match(if (s1 >= threshold && s1 - s2 >= MARGIN) order[0].key else null, s1)
    }

    private fun matchButton(feat: Gray, tpls: List<VisionTemplate>): Match {
        val best = LinkedHashMap<String, Double>()
        for (t in tpls) {
            val tw = t.image.w
            if (abs(feat.w - tw) > SIZE_TOL_W * tw) continue
            best[t.label] = max(best[t.label] ?: -1.0, ncc(feat, t.float))
        }
        return ranked(best, MATCH_TH)
    }

    /** `read_buttons`: recognised top buttons, boxes on the reference frame. */
    fun readButtons(gray: Gray, tpls: List<VisionTemplate>): List<VisionButton> {
        val hp = bandHighpass(gray)
        val out = ArrayList<VisionButton>()
        for (p in topCandidates(hp)) {
            val feat = extractGlyph(hp, p) ?: continue
            val m = matchButton(feat, tpls)
            val label = m.label ?: continue
            out += VisionButton(label, round3(m.best), intArrayOf(TOP_BAND_X0 + p.s - PAD, p.yc - GLYPH_HALF,
                TOP_BAND_X0 + p.e + PAD, p.yc + GLYPH_HALF))
        }
        return out
    }

    // ------------------------------------------------------------ 3. corner buttons
    private fun cornerSlots(width: Int): List<Pair<Int, Int>> =
        (0 until CORNER_SLOTS).map { (width - CORNER_DX) to (CORNER_Y0 - it * CORNER_PITCH) }

    private val discCache = HashMap<Pair<Int, Int>, BooleanArray>()

    private fun disc(size: Int, r: Int): BooleanArray = synchronized(discCache) {
        discCache.getOrPut(size to r) {
            val c = (size - 1) / 2.0
            BooleanArray(size * size) { val y = it / size; val x = it % size; (y - c) * (y - c) + (x - c) * (x - c) <= r * r }
        }
    }

    private fun cornerFeature(gray: Gray, cx: Int, cy: Int): FImage? {
        val r = CORNER_R + CORNER_SLACK
        val x0 = cx - r; val y0 = cy - r; val x1 = cx + r + 1; val y1 = cy + r + 1
        if (x0 < 0 || y0 < 0 || x1 > gray.w || y1 > gray.h) return null
        val hp = highpass(Cv.gaussian3(gray.crop(x0, y0, x1, y1))).toFloat()
        val mask = disc(hp.w, CORNER_R + CORNER_SLACK / 2)
        for (i in hp.px.indices) if (!mask[i]) hp.px[i] = 0f
        val p99 = max(Hist.ofWhole(hp).percentile(99.0), 1.0).toFloat()
        for (i in hp.px.indices) hp.px[i] = (hp.px[i] / p99).coerceIn(0f, 1f) * 255f
        return hp.truncateToGray().toFloat()
    }

    private fun matchCorner(feat: FImage, tpls: List<VisionTemplate>): Match {
        val best = LinkedHashMap<String, Double>()
        for (t in tpls) {
            if (t.image.w > feat.w || t.image.h > feat.h) continue
            best[t.label] = max(best[t.label] ?: -1.0, Cv.peak(Cv.matchTemplate(feat, t.float)).toDouble())
        }
        return ranked(best, CORNER_MATCH_TH)
    }

    /** `read_corner`: the map's round corner buttons from top to bottom, boxes on the reference frame. */
    fun readCorner(gray: Gray, tpls: List<VisionTemplate>): List<VisionButton> {
        val w = gray.w
        var inset = 0; var best = -1.0
        for (dx in CORNER_INSETS) {
            val (cx, cy) = cornerSlots(w - dx)[0]
            val f = cornerFeature(gray, cx, cy) ?: continue
            val m = matchCorner(f, tpls)
            if (m.label != null && m.best > best) { inset = dx; best = m.best }
        }
        val out = ArrayList<VisionButton>()
        for ((cx, cy) in cornerSlots(w - inset)) {
            val f = cornerFeature(gray, cx, cy) ?: continue
            val m = matchCorner(f, tpls)
            val label = m.label ?: continue
            val r = CORNER_R
            out += VisionButton(label, round3(m.best), intArrayOf(cx - r, cy - r, cx + r, cy + r))
        }
        return out.sortedBy { it.box[1] }
    }

    fun round3(v: Double): Double = Math.round(v * 1000) / 1000.0
}

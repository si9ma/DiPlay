package com.shilapi.xcertplay.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One font's glyph templates (tools/vision/models/route/<font>): band height [h], row [slack]. */
internal class GlyphModel(val h: Int, val slack: Int, val tokens: List<Pair<String, Gray>>) {
    val floats: List<FImage> by lazy { tokens.map { it.second.toFloat() } }
}

internal data class GlyphLine(val text: String, val scores: List<Double>, val skipped: Double, val margins: List<Double>)

/**
 * Closed-character-set line reader: templates + dynamic programming (tools/vision/glyph_ocr.py).
 * Only recognition is ported; training stays in Python.
 */
internal object GlyphOcr {
    const val SLACK = 3
    private const val FEAT_DIFF = 120.0
    private const val INK_T = 80
    private const val PAD = 4
    private const val THETA = 0.45
    const val ACCEPT = 0.65
    private const val SKIP_PEN = 1.0
    private const val MAX_SKIP_INK = 0.5
    private const val MARGIN_SHIFT = 2
    private const val MARGIN_WIDTH = 0.35
    private const val NARROW = ".·:,"
    private const val NARROW_ACCEPT = 0.50
    private const val DIGIT_MARGIN = 0.02

    /** `line_feature`: a line of height [h] + 2 slack centred on [cy], |I - bg| scaled, cropped to its ink. */
    fun lineFeature(gray: Gray, cy: Int, x0: Int, x1: Int, h: Int, slack: Int = SLACK): Gray? {
        val y0 = cy - h / 2 - slack
        val y1 = y0 + h + 2 * slack
        val band = gray.crop(x0, max(y0, 0), x1, min(y1, gray.h))
            .border(max(0, -y0), max(0, y1 - gray.h), 0, 0, replicate = true)
        if (band.isEmpty) return null
        val bg = Hist.of(band).median().toFloat()
        val scale = (255.0 / FEAT_DIFF).toFloat()
        val f = Gray(band.w, band.h, IntArray(band.px.size) {
            (abs(band.px[it].toFloat() - bg) * scale).coerceIn(0f, 255f).toInt()
        })
        var c0 = -1; var c1 = -1
        for (x in 0 until f.w) {
            var ink = false
            for (y in slack until min(slack + h, f.h)) if (f[x, y] > INK_T) { ink = true; break }
            if (ink) { if (c0 < 0) c0 = x; c1 = x }
        }
        if (c0 < 0) return null
        return f.crop(c0, 0, c1 + 1, f.h).border(0, 0, PAD, PAD)
    }

    private fun colInk(f: Gray, slack: Int): DoubleArray {
        val rows = (f.h - 2 * slack).coerceAtLeast(0)
        return DoubleArray(f.w) { x ->
            if (rows == 0) 0.0 else {
                var c = 0
                for (y in slack until f.h - slack) if (f[x, y] > INK_T) c++
                c / rows.toDouble()
            }
        }
    }

    /** `recognize_ex`: text, per-character scores, skipped ink, per-character margins over look-alikes. */
    fun recognize(f: Gray?, model: GlyphModel): GlyphLine {
        val h = model.h; val s = model.slack
        if (f == null || f.h < h) return GlyphLine("", emptyList(), 0.0, emptyList())
        val w = f.w
        val ink = colInk(f, s)
        val ff = f.toFloat()
        val toks = ArrayList<String>(); val ws = ArrayList<Int>(); val rs = ArrayList<FloatArray>()
        model.tokens.forEachIndexed { i, (t, img) ->
            if (img.w > w || img.h != h) return@forEachIndexed
            val m = Cv.matchTemplate(ff, model.floats[i])
            val col = FloatArray(m.w) { x ->
                var best = Float.NEGATIVE_INFINITY
                for (y in 0 until m.h) best = max(best, m[x, y])
                if (best.isNaN()) 0f else best
            }
            toks += t; ws += img.w; rs += col
        }
        val best = DoubleArray(w + 1) { Double.NEGATIVE_INFINITY }
        best[0] = 0.0
        // back: previous x, template index (-1 = skip), score, skipped ink, template start.
        val backPx = IntArray(w + 1) { -1 }
        val backJ = IntArray(w + 1); val backSc = DoubleArray(w + 1); val backSk = DoubleArray(w + 1); val backP = IntArray(w + 1)
        val theta = THETA.toFloat()
        for (x in 0 until w) {
            val bx = best[x]
            if (bx == Double.NEGATIVE_INFINITY) continue
            val v = bx - SKIP_PEN * ink[x]
            if (v > best[x + 1]) {
                best[x + 1] = v; backPx[x + 1] = x; backJ[x + 1] = -1; backSc[x + 1] = 0.0; backSk[x + 1] = ink[x]; backP[x + 1] = x
            }
            for (j in toks.indices) {
                val tw = ws[j]; val r = rs[j]
                for (o in 0..min(3, tw / 4)) {
                    val p = x - o
                    if (p < 0 || p >= r.size) continue
                    val sc = r[p]
                    if (sc <= theta) continue
                    val nx = p + tw
                    // float32 like NumPy: (sc - theta) * w, added to the float64 total.
                    val gain = bx + ((sc - theta) * tw).toDouble()
                    if (nx > x && gain > best[nx]) {
                        best[nx] = gain; backPx[nx] = x; backJ[nx] = j; backSc[nx] = sc.toDouble(); backSk[nx] = 0.0; backP[nx] = p
                    }
                }
            }
        }
        val out = ArrayList<String>(); val scores = ArrayList<Double>(); val margins = ArrayList<Double>()
        var skipped = 0.0
        var x = w
        while (x > 0 && backPx[x] >= 0) {
            val j = backJ[x]
            if (j >= 0) {
                val p = backP[x]; val tw = ws[j]
                val lo = max(0, p - MARGIN_SHIFT); val hi = p + MARGIN_SHIFT + 1
                var rival = Double.NEGATIVE_INFINITY
                for (k in toks.indices) {
                    if (k == j || abs(ws[k] - tw) > MARGIN_WIDTH * tw) continue
                    val r = rs[k]
                    val end = min(hi, r.size)
                    if (lo >= end) continue
                    for (q in lo until end) rival = max(rival, r[q].toDouble())
                }
                if (rival == Double.NEGATIVE_INFINITY) rival = 0.0
                out += toks[j]; scores += backSc[x]; margins += backSc[x] - rival
            } else {
                skipped += backSk[x]
            }
            x = backPx[x]
        }
        return GlyphLine(out.reversed().joinToString(""), scores.reversed(), skipped, margins.reversed())
    }

    /** `confidence`: the weakest character, 0 when unknown ink was skipped or a digit has a close look-alike. */
    fun confidence(text: String, scores: List<Double>, skipped: Double, margins: List<Double>?): Double {
        if (scores.isEmpty() || skipped > MAX_SKIP_INK) return 0.0
        if (margins != null && text.indices.any { it < margins.size && isDigit(text[it]) && margins[it] < DIGIT_MARGIN }) return 0.0
        var m = Double.POSITIVE_INFINITY
        text.forEachIndexed { i, c ->
            if (i < scores.size) m = min(m, if (c in NARROW) scores[i] * ACCEPT / NARROW_ACCEPT else scores[i])
        }
        return min(m, 1.0)
    }

    /** Python's str.isdigit for the characters these models know. */
    fun isDigit(c: Char): Boolean = c in '0'..'9'
}

package com.shilapi.xcertplay.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Image primitives for the CarPlay picture recognition, ported from tools/vision (Python + OpenCV).
 * Each function names the OpenCV / NumPy call it stands for and keeps its semantics (borders,
 * rounding, truncation), so the Kotlin results match the trained models and the Python validation.
 */

/** 8-bit single-channel picture (values 0..255), row-major; an OpenCV CV_8UC1 Mat. */
internal class Gray(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init { require(w >= 0 && h >= 0 && px.size == w * h) }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]
    val isEmpty: Boolean get() = w == 0 || h == 0

    /** NumPy `a[y0:y1, x0:x1]` with non-negative starts: the bounds are clamped to the picture. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Gray {
        val ax0 = x0.coerceIn(0, w); val ax1 = x1.coerceIn(ax0, w)
        val ay0 = y0.coerceIn(0, h); val ay1 = y1.coerceIn(ay0, h)
        val out = Gray(ax1 - ax0, ay1 - ay0)
        for (y in 0 until out.h) System.arraycopy(px, (ay0 + y) * w + ax0, out.px, y * out.w, out.w)
        return out
    }

    fun toFloat(): FImage = FImage(w, h, FloatArray(px.size) { px[it].toFloat() })

    /** cv2.copyMakeBorder(..., BORDER_CONSTANT, value) or BORDER_REPLICATE. */
    fun border(top: Int, bottom: Int, left: Int, right: Int, replicate: Boolean = false, value: Int = 0): Gray {
        val out = Gray(w + left + right, h + top + bottom)
        for (y in 0 until out.h) for (x in 0 until out.w) {
            val sx = x - left; val sy = y - top
            out.px[y * out.w + x] = when {
                sx in 0 until w && sy in 0 until h -> px[sy * w + sx]
                replicate && w > 0 && h > 0 -> px[sy.coerceIn(0, h - 1) * w + sx.coerceIn(0, w - 1)]
                else -> value
            }
        }
        return out
    }
}

/** 32-bit float single-channel picture; an OpenCV CV_32FC1 Mat. */
internal class FImage(val w: Int, val h: Int, val px: FloatArray = FloatArray(w * h)) {
    init { require(w >= 0 && h >= 0 && px.size == w * h) }

    operator fun get(x: Int, y: Int): Float = px[y * w + x]
    val isEmpty: Boolean get() = w == 0 || h == 0

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): FImage {
        val ax0 = x0.coerceIn(0, w); val ax1 = x1.coerceIn(ax0, w)
        val ay0 = y0.coerceIn(0, h); val ay1 = y1.coerceIn(ay0, h)
        val out = FImage(ax1 - ax0, ay1 - ay0)
        for (y in 0 until out.h) System.arraycopy(px, (ay0 + y) * w + ax0, out.px, y * out.w, out.w)
        return out
    }

    /** NumPy `.astype(np.uint8)` for values already in 0..255: truncates toward zero. */
    fun truncateToGray(): Gray = Gray(w, h, IntArray(px.size) { px[it].toInt().coerceIn(0, 255) })

    /** OpenCV's saturate_cast<uchar>: rounds half to even and clamps. */
    fun roundToGray(): Gray = Gray(w, h, IntArray(px.size) { Math.rint(px[it].toDouble()).toInt().coerceIn(0, 255) })
}

/** A BGR picture as three 8-bit planes (cv2.imread order). */
internal class BgrFrame(val w: Int, val h: Int, val b: IntArray, val g: IntArray, val r: IntArray) {
    init { require(b.size == w * h && g.size == w * h && r.size == w * h) }

    /** cv2.cvtColor(BGR2GRAY) on 8-bit: OpenCV's fixed-point weights (15 fractional bits). */
    fun gray(): Gray = Gray(w, h, IntArray(w * h) { (r[it] * 9798 + g[it] * 19235 + b[it] * 3735 + 16384) shr 15 })

    /** `bgr[:, :width]`. */
    fun cropWidth(width: Int): BgrFrame {
        val nw = width.coerceIn(0, w)
        if (nw == w) return this
        fun plane(p: IntArray) = IntArray(nw * h).also { out ->
            for (y in 0 until h) System.arraycopy(p, y * w, out, y * nw, nw)
        }
        return BgrFrame(nw, h, plane(b), plane(g), plane(r))
    }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): BgrFrame {
        val bp = Gray(w, h, b).crop(x0, y0, x1, y1)
        return BgrFrame(bp.w, bp.h, bp.px, Gray(w, h, g).crop(x0, y0, x1, y1).px, Gray(w, h, r).crop(x0, y0, x1, y1).px)
    }

    /** cv2.resize of an 8-bit BGR picture, plane by plane. */
    fun resize(dw: Int, dh: Int, area: Boolean): BgrFrame {
        fun plane(p: IntArray) = Cv.resize(Gray(w, h, p).toFloat(), dw, dh, area).roundToGray().px
        return BgrFrame(dw, dh, plane(b), plane(g), plane(r))
    }

    companion object {
        /** From Android ARGB_8888 pixels (Bitmap.getPixels); alpha is ignored. */
        fun fromArgb(pixels: IntArray, w: Int, h: Int): BgrFrame {
            val n = w * h
            val b = IntArray(n); val g = IntArray(n); val r = IntArray(n)
            for (i in 0 until n) {
                val c = pixels[i]
                r[i] = (c shr 16) and 0xFF; g[i] = (c shr 8) and 0xFF; b[i] = c and 0xFF
            }
            return BgrFrame(w, h, b, g, r)
        }
    }
}

/** Half-open run [s, e) of true entries. */
internal data class Run(val s: Int, val e: Int) {
    val len: Int get() = e - s
}

/** Histogram of 0..255 values; NumPy's median and linear percentile over them. */
internal class Hist {
    private val c = IntArray(256)
    var n = 0
        private set

    fun add(v: Int) { c[v.coerceIn(0, 255)]++; n++ }

    private fun kth(k: Int): Int {
        var acc = 0
        for (v in 0..255) { acc += c[v]; if (acc > k) return v }
        return 255
    }

    /** np.median. */
    fun median(): Double {
        if (n == 0) return Double.NaN
        return if (n % 2 == 1) kth(n / 2).toDouble() else (kth(n / 2 - 1) + kth(n / 2)) / 2.0
    }

    /** np.percentile(a, q) with the default linear method. */
    fun percentile(q: Double): Double {
        if (n == 0) return Double.NaN
        val pos = (q / 100.0) * (n - 1)
        val lo = floor(pos).toInt().coerceIn(0, n - 1)
        val hi = min(lo + 1, n - 1)
        val t = pos - lo
        val a = kth(lo).toDouble(); val b = kth(hi).toDouble()
        return if (t < 0.5) a + (b - a) * t else b - (b - a) * (1 - t)
    }

    companion object {
        fun of(img: Gray): Hist = Hist().apply { img.px.forEach { add(it) } }
        fun of(values: IntArray): Hist = Hist().apply { values.forEach { add(it) } }
        /** Float pictures whose values are whole numbers 0..255 (masked or cast 8-bit pictures). */
        fun ofWhole(img: FImage): Hist = Hist().apply { img.px.forEach { add(it.toInt()) } }
    }
}

/** One connected component: bounding box and area (cv2.connectedComponentsWithStats). */
internal data class Comp(val x: Int, val y: Int, val w: Int, val h: Int, val area: Int)

internal object Cv {
    /** BORDER_REFLECT_101 (OpenCV's default border). */
    fun reflect101(i: Int, n: Int): Int {
        if (n == 1) return 0
        var p = i
        while (p < 0 || p >= n) p = if (p < 0) -p else 2 * n - 2 - p
        return p
    }

    /** cv2.GaussianBlur(src, (3, 3), 0) on 8-bit: kernel [1 2 1]/4 both ways, rounded half up. */
    fun gaussian3(src: Gray): Gray {
        val w = src.w; val h = src.h
        val out = Gray(w, h)
        if (src.isEmpty) return out
        val s = src.px
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) tmp[row + x] = s[row + reflect101(x - 1, w)] + 2 * s[row + x] + s[row + reflect101(x + 1, w)]
        }
        for (y in 0 until h) {
            val up = reflect101(y - 1, h) * w; val dn = reflect101(y + 1, h) * w; val row = y * w
            for (x in 0 until w) out.px[row + x] = (tmp[up + x] + 2 * tmp[row + x] + tmp[dn + x] + 8) shr 4
        }
        return out
    }

    /** cv2.GaussianBlur(src, (3, 3), 0) on 32-bit float (OpenCV's symmetric small filter). */
    fun gaussian3(src: FImage): FImage {
        val w = src.w; val h = src.h
        val out = FImage(w, h)
        if (src.isEmpty) return out
        val s = src.px
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) tmp[row + x] = s[row + x] * 0.5f + (s[row + reflect101(x - 1, w)] + s[row + reflect101(x + 1, w)]) * 0.25f
        }
        for (y in 0 until h) {
            val up = reflect101(y - 1, h) * w; val dn = reflect101(y + 1, h) * w; val row = y * w
            for (x in 0 until w) out.px[row + x] = tmp[row + x] * 0.5f + (tmp[up + x] + tmp[dn + x]) * 0.25f
        }
        return out
    }

    /** cv2.blur(src, (k, k)) on 8-bit (normalized box filter, BORDER_REFLECT_101, rounded). */
    fun boxBlur(src: Gray, k: Int): Gray {
        val w = src.w; val h = src.h
        val out = Gray(w, h)
        if (src.isEmpty) return out
        val r = k / 2
        val s = src.px
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0
                for (d in -r..r) sum += s[row + reflect101(x + d, w)]
                tmp[row + x] = sum
            }
        }
        val area = k * k
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (d in -r..r) sum += tmp[reflect101(y + d, h) * w + x]
            out.px[y * w + x] = (2 * sum + area) / (2 * area)
        }
        return out
    }

    /** cv2.resize(src, (dw, dh), interpolation=INTER_AREA if [area] else INTER_LINEAR); unrounded values. */
    fun resize(src: FImage, dw: Int, dh: Int, area: Boolean): FImage {
        require(dw > 0 && dh > 0 && !src.isEmpty)
        if (dw == src.w && dh == src.h) return FImage(dw, dh, src.px.copyOf())
        // OpenCV derives the scale as 1 / (dst / src); it rounds differently from src / dst.
        val sx = 1.0 / (dw.toDouble() / src.w); val sy = 1.0 / (dh.toDouble() / src.h)
        return if (area && sx >= 1 && sy >= 1) resizeAreaDown(src, dw, dh, sx, sy) else resizeLinear(src, dw, dh, area)
    }

    private class Tab(val index: IntArray, val weight: DoubleArray)

    /** OpenCV computeResizeAreaTab: source cells covering each destination cell, by coverage. */
    private fun areaTab(ssize: Int, dsize: Int, scale: Double): Array<Tab> = Array(dsize) { dx ->
        val idx = ArrayList<Int>(); val wts = ArrayList<Double>()
        val fsx1 = dx * scale
        val fsx2 = fsx1 + scale
        val cellWidth = min(scale, ssize - fsx1)
        var sx2 = floor(fsx2).toInt()
        sx2 = min(sx2, ssize - 1)
        val sx1 = min(ceil(fsx1).toInt(), sx2)
        if (sx1 - fsx1 > 1e-3) { idx += sx1 - 1; wts += (sx1 - fsx1) / cellWidth }
        for (sx in sx1 until sx2) { idx += sx; wts += 1.0 / cellWidth }
        if (fsx2 - sx2 > 1e-3) { idx += sx2; wts += min(min(fsx2 - sx2, 1.0), cellWidth) / cellWidth }
        Tab(idx.toIntArray(), wts.toDoubleArray())
    }

    private fun resizeAreaDown(src: FImage, dw: Int, dh: Int, sx: Double, sy: Double): FImage {
        val xt = areaTab(src.w, dw, sx); val yt = areaTab(src.h, dh, sy)
        val out = FImage(dw, dh)
        val rowBuf = DoubleArray(dw)
        for (dy in 0 until dh) {
            val ty = yt[dy]
            val acc = DoubleArray(dw)
            for (k in ty.index.indices) {
                val row = ty.index[k] * src.w
                for (dx in 0 until dw) {
                    val tx = xt[dx]
                    var s = 0.0
                    for (j in tx.index.indices) s += src.px[row + tx.index[j]] * tx.weight[j]
                    rowBuf[dx] = s
                }
                val wy = ty.weight[k]
                for (dx in 0 until dw) acc[dx] += wy * rowBuf[dx]
            }
            for (dx in 0 until dw) out.px[dy * dw + dx] = acc[dx].toFloat()
        }
        return out
    }

    /** OpenCV's bilinear resize; with [areaMode] the INTER_AREA coefficients used when enlarging. */
    private fun resizeLinear(src: FImage, dw: Int, dh: Int, areaMode: Boolean): FImage {
        fun coeffs(ssize: Int, dsize: Int): Triple<IntArray, IntArray, FloatArray> {
            val inv = dsize.toDouble() / ssize
            val scale = 1.0 / inv
            val i0 = IntArray(dsize); val i1 = IntArray(dsize); val f = FloatArray(dsize)
            for (d in 0 until dsize) {
                var s: Int
                var fx: Float
                if (!areaMode) {
                    val v = ((d + 0.5) * scale - 0.5).toFloat()
                    s = floor(v.toDouble()).toInt(); fx = v - s
                } else {
                    s = floor(d * scale).toInt()
                    fx = ((d + 1) - (s + 1) * inv).toFloat()
                    fx = if (fx <= 0f) 0f else fx - floor(fx.toDouble()).toFloat()
                }
                if (s < 0) { fx = 0f; s = 0 }
                if (s >= ssize - 1) { fx = 0f; s = ssize - 1 }
                i0[d] = s; i1[d] = min(s + 1, ssize - 1); f[d] = fx
            }
            return Triple(i0, i1, f)
        }
        val (x0, x1, fx) = coeffs(src.w, dw)
        val (y0, y1, fy) = coeffs(src.h, dh)
        val out = FImage(dw, dh)
        val s = src.px
        for (dy in 0 until dh) {
            val ra = y0[dy] * src.w; val rb = y1[dy] * src.w; val wy = fy[dy]
            for (dx in 0 until dw) {
                val wx = fx[dx]
                val top = s[ra + x0[dx]] * (1 - wx) + s[ra + x1[dx]] * wx
                val bot = s[rb + x0[dx]] * (1 - wx) + s[rb + x1[dx]] * wx
                out.px[dy * dw + dx] = top * (1 - wy) + bot * wy
            }
        }
        return out
    }

    /** cv2.threshold(src, 0, 1, THRESH_BINARY + THRESH_OTSU): returns the threshold OpenCV picks. */
    fun otsu(src: Gray): Int {
        val hist = DoubleArray(256)
        src.px.forEach { hist[it]++ }
        val scale = 1.0 / max(src.px.size, 1)
        var mu = 0.0
        for (i in 0 until 256) { hist[i] *= scale; mu += i * hist[i] }
        var mu1 = 0.0; var q1 = 0.0
        var maxSigma = 0.0; var maxVal = 0
        val eps = 1.1920928955078125E-7 // FLT_EPSILON
        for (i in 0 until 256) {
            val p = hist[i]
            mu1 *= q1
            q1 += p
            val q2 = 1.0 - q1
            if (min(q1, q2) < eps || max(q1, q2) > 1.0 - eps) continue
            mu1 = (mu1 + i * p) / q1
            val mu2 = (mu - q1 * mu1) / q2
            val sigma = q1 * q2 * (mu1 - mu2) * (mu1 - mu2)
            if (sigma > maxSigma) { maxSigma = sigma; maxVal = i }
        }
        return maxVal
    }

    /**
     * cv2.connectedComponentsWithStats without the background, in OpenCV's label order:
     * raster order of the first pixel for 4-connectivity, of the first 2x2 block for 8-connectivity
     * (OpenCV's block-based labelling).
     */
    fun components(mask: BooleanArray, w: Int, h: Int, eight: Boolean): List<Comp> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        data class C(val comp: Comp, val key: Long)
        val out = ArrayList<C>()
        var next = 0
        for (start in 0 until w * h) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start; label[start] = next
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var area = 0
            var key = Long.MAX_VALUE
            val blocksPerRow = (w + 1) / 2 + 1L
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                area++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                val k = (y / 2) * blocksPerRow + x / 2
                if (k < key) key = k
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!eight && dx != 0 && dy != 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q }
                }
            }
            out += C(Comp(minX, minY, maxX - minX + 1, maxY - minY + 1, area), if (eight) key else start.toLong())
        }
        return out.sortedBy { it.key }.map { it.comp }
    }

    /** cv2.matchTemplate(img, tpl, TM_CCOEFF_NORMED), including OpenCV's handling of flat windows. */
    fun matchTemplate(img: FImage, tpl: FImage): FImage {
        val rw = img.w - tpl.w + 1; val rh = img.h - tpl.h + 1
        require(rw > 0 && rh > 0) { "template larger than image" }
        val n = tpl.w * tpl.h
        var tsum = 0.0
        tpl.px.forEach { tsum += it }
        val tmean = tsum / n
        val tc = DoubleArray(n) { tpl.px[it] - tmean }
        var tvar = 0.0
        tc.forEach { tvar += it * it }
        val out = FImage(rw, rh)
        if (tvar / n < 2.220446049250313E-16) { out.px.fill(1f); return out } // flat template: OpenCV returns 1
        val templNorm = sqrt(tvar)
        // Integral images of I and I^2 for the window sums.
        val iw = img.w + 1
        val s1 = DoubleArray(iw * (img.h + 1)); val s2 = DoubleArray(iw * (img.h + 1))
        for (y in 0 until img.h) {
            var r1 = 0.0; var r2 = 0.0
            for (x in 0 until img.w) {
                val v = img.px[y * img.w + x].toDouble()
                r1 += v; r2 += v * v
                s1[(y + 1) * iw + x + 1] = s1[y * iw + x + 1] + r1
                s2[(y + 1) * iw + x + 1] = s2[y * iw + x + 1] + r2
            }
        }
        val tw = tpl.w; val th = tpl.h
        for (v in 0 until rh) for (u in 0 until rw) {
            var num = 0.0
            for (ty in 0 until th) {
                val ir = (v + ty) * img.w + u
                val tr = ty * tw
                for (tx in 0 until tw) num += tc[tr + tx] * img.px[ir + tx]
            }
            val a = v * iw + u; val b = v * iw + u + tw; val c = (v + th) * iw + u; val d = (v + th) * iw + u + tw
            val wsum = s1[d] - s1[b] - s1[c] + s1[a]
            val wsum2 = s2[d] - s2[b] - s2[c] + s2[a]
            val t = sqrt(max(wsum2 - wsum * wsum / n, 0.0)) * templNorm
            out.px[v * rw + u] = when {
                abs(num) < t -> (num / t).toFloat()
                abs(num) < t * 1.125 -> if (num > 0) 1f else -1f
                else -> 0f
            }
        }
        return out
    }

    fun peak(img: FImage): Float {
        var m = Float.NEGATIVE_INFINITY
        img.px.forEach { if (it > m) m = it }
        return m
    }

    /** Runs of true entries allowing gaps of at most [maxGap] false entries (tools/vision `_runs`). */
    fun runs(mask: BooleanArray, maxGap: Int): List<Run> {
        val out = ArrayList<Run>()
        var s = -1; var p = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            if (s < 0) { s = i; p = i; continue }
            if (i - p > maxGap + 1) { out += Run(s, p + 1); s = i }
            p = i
        }
        if (s >= 0) out += Run(s, p + 1)
        return out
    }

    /** NumPy population standard deviation / mean, as `areas.std() / areas.mean()`. */
    fun relStd(values: List<Int>): Double {
        val mean = values.average()
        var v = 0.0
        values.forEach { v += (it - mean) * (it - mean) }
        return sqrt(v / values.size) / mean
    }

    /** cv2.cvtColor(BGR2HSV) on 8-bit (OpenCV's integer tables); returns H (0..179), S, V. */
    fun hsv(b: Int, g: Int, r: Int, out: IntArray) {
        val v = max(b, max(g, r)); val vmin = min(b, min(g, r))
        val diff = v - vmin
        val s = (diff * SDIV[v] + (1 shl 11)) shr 12
        var hh = when (v) {
            r -> g - b
            g -> b - r + 2 * diff
            else -> r - g + 4 * diff
        }
        hh = (hh * HDIV[diff] + (1 shl 11)) shr 12
        if (hh < 0) hh += 180
        out[0] = hh; out[1] = s; out[2] = v
    }

    private val SDIV = IntArray(256) { if (it == 0) 0 else Math.rint((255 shl 12) / it.toDouble()).toInt() }
    private val HDIV = IntArray(256) { if (it == 0) 0 else Math.rint((180 shl 12) / (6.0 * it)).toInt() }
}

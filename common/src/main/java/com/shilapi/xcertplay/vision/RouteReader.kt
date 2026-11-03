package com.shilapi.xcertplay.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One route row (list) or the single route card; coordinates on the input frame. */
internal data class VisionRoute(
    val selected: Boolean?,
    val card: Boolean,
    val y: IntArray,
    val tap: IntArray,
    val text: Map<String, String?>?,
    val conf: Map<String, Double>?,
    val ok: Boolean?,
    val etaMin: Int? = null,
    val distKm: Double? = null,
    val lights: Int? = null,
    val toll: Double? = null,
    val arrive: String? = null,
)

/** `read_route_page` result: page is route | switch_route | green_panel | none. */
internal data class VisionPanel(
    val page: String,
    val greenText: String? = null,
    val greenScore: Double = 0.0,
    val startBtn: IntArray? = null,
    val tap: IntArray? = null,
    val routes: List<VisionRoute> = emptyList(),
    val selectedIndex: Int? = null,
) {
    companion object { val NONE = VisionPanel("none") }
}

/** Route planning panel on the left (tools/vision/route_reader.py). Reference-frame constants. */
internal object RouteReader {
    private const val REF_H = 900
    private val PANEL_X = 110 to 563
    private val TEXT_X = 130 to 460
    private val RADIO_X = 462 to 540
    private val SEARCH_Y = 90 to 860
    /** Line band height per font, in the order Python zips route lines with them. */
    val FONTS = linkedMapOf("eta" to 34, "detail" to 26, "arrive" to 26)

    private const val GREEN_ROW_FRAC = 0.45
    private const val BTN_MIN_H = 36
    private const val BTN_MAX_H = 110
    private const val INK_DIFF = 40
    private const val SEP_MAX_H = 6
    private const val SEP_DIFF = 15
    private const val SEP_IQR_MAX = 12
    private const val RADIO_DIFF = 35
    private const val TEXT_MIN_H = 10

    private val ETA_RE = Regex("^(?:(\\d+)小时)?(?:(\\d+)分钟?)?$")
    private val ARRIVE_RE = Regex("^(\\d{1,2}):(\\d{2})到达$")
    private val SUMMARY_RE = Regex("^(?:(\\d+):(\\d{2})小时|(?:(\\d+)小时)?(?:(\\d+)分钟?)?)·(\\d+(?:\\.\\d+)?)(公里|米)$")
    private val LIGHTS_RE = Regex("^红绿灯(\\d+)$")
    private val DETAIL_RE = Regex("^(\\d+(?:\\.\\d+)?)(公里|米)(?:红绿灯(\\d+))?(?:¥(\\d+(?:\\.\\d+)?))?$")

    val LINE_FONT = mapOf("eta" to "eta", "detail" to "detail", "lights" to "detail", "arrive" to "arrive", "summary" to "detail")
    private val NO_SEPARATOR = setOf("detail", "lights")
    private val CARD_LINE_H = 14..27
    private const val CARD_BRIGHT = 0.72

    private const val GREEN_W = 128
    private const val GREEN_H = 32
    private const val GREEN_DIFF = 60.0
    private const val GREEN_MATCH_TH = 0.70
    private const val GREEN_MARGIN = 0.10
    private const val ARRIVE_TOL_MIN = 2

    private class Group(val y0: Int, val y1: Int, val selected: Boolean?, val nLines: Int, val lines: LinkedHashMap<String, Gray?>)

    private class Geometry(val btn: IntArray, val gray: Gray, val routes: List<Group>, val card: Group?)

    // ------------------------------------------------------------ geometry
    /** `find_start_button`: the green button (start / resume navigation), or null. */
    fun findStartButton(bgr: BgrFrame): IntArray? {
        val x0 = PANEL_X.first; val y0 = SEARCH_Y.first
        val reg = bgr.crop(x0, y0, PANEL_X.second, SEARCH_Y.second)
        if (reg.w == 0 || reg.h == 0) return null
        val green = BooleanArray(reg.w * reg.h)
        val hsv = IntArray(3)
        for (i in green.indices) {
            Cv.hsv(reg.b[i], reg.g[i], reg.r[i], hsv)
            green[i] = hsv[0] > 35 && hsv[0] < 85 && hsv[1] > 110 && hsv[2] > 110
        }
        val rowMask = BooleanArray(reg.h) { y ->
            var c = 0
            for (x in 0 until reg.w) if (green[y * reg.w + x]) c++
            c / reg.w.toDouble() > GREEN_ROW_FRAC
        }
        val runs = Cv.runs(rowMask, 3).filter { it.len in BTN_MIN_H..BTN_MAX_H }
        if (runs.isEmpty()) return null
        var pick = runs[0]
        for (r in runs) if (r.len > pick.len) pick = r
        val rows = pick.len
        var c0 = -1; var c1 = -1; var n = 0
        for (x in 0 until reg.w) {
            var c = 0
            for (y in pick.s until pick.e) if (green[y * reg.w + x]) c++
            if (c / rows.toDouble() > 0.5) { if (c0 < 0) c0 = x; c1 = x; n++ }
        }
        if (n < 0.6 * (PANEL_X.second - PANEL_X.first)) return null
        return intArrayOf(x0 + c0, y0 + pick.s, x0 + c1 + 1, y0 + pick.e)
    }

    private fun rowMedian(values: IntArray): Double = Hist.of(values).median()

    /** `find_routes`: text lines grouped between the thin separators above the green button. */
    private fun findRoutes(gray: Gray, btn: IntArray): Pair<List<Group>, Int> {
        val y0 = SEARCH_Y.first; val y1 = btn[1] - 3
        val x0 = TEXT_X.first; val x1 = TEXT_X.second
        val reg = gray.crop(x0, y0, x1, y1)
        if (reg.isEmpty) return emptyList<Group>() to 0
        val bg = Hist.of(reg).median().toInt()
        val n = reg.h
        val rf = DoubleArray(n); val sep = BooleanArray(n)
        val row = IntArray(reg.w)
        for (y in 0 until n) {
            var ink = 0
            for (x in 0 until reg.w) { val v = reg[x, y]; row[x] = v; if (abs(v - bg) > INK_DIFF) ink++ }
            rf[y] = ink / reg.w.toDouble()
            val h = Hist.of(row)
            sep[y] = abs(h.median() - bg) >= SEP_DIFF && h.percentile(75.0) - h.percentile(25.0) <= SEP_IQR_MAX
        }
        val thin = BooleanArray(n)
        for (r in Cv.runs(sep, 0)) if (r.len <= SEP_MAX_H) for (i in r.s until r.e) thin[i] = true
        data class Mark(val s: Int, val e: Int, val kind: String)
        val marks = ArrayList<Mark>()
        Cv.runs(thin, 0).forEach { marks += Mark(it.s, it.e, "sep") }
        Cv.runs(BooleanArray(n) { rf[it] > 0.03 && !thin[it] }, 2).filter { it.len >= TEXT_MIN_H }
            .forEach { marks += Mark(it.s, it.e, "text") }
        marks.sortWith(compareBy<Mark>({ it.s }, { it.e }, { it.kind }))
        val groups = ArrayList<List<Pair<Int, Int>>>()
        var cur = ArrayList<Pair<Int, Int>>()
        for (m in marks) {
            if (m.kind == "sep") {
                if (cur.isNotEmpty()) groups += cur
                cur = ArrayList()
            } else {
                cur += (y0 + m.s) to (y0 + m.e)
            }
        }
        if (cur.isNotEmpty()) groups += cur
        val routes = groups.map { texts ->
            val ry0 = texts.first().first - 6; val ry1 = texts.last().second + 6
            val lines = LinkedHashMap<String, Gray?>()
            FONTS.entries.zip(texts).forEach { (font, span) ->
                lines[font.key] = GlyphOcr.lineFeature(gray, (span.first + span.second) / 2, x0, x1, font.value)
            }
            Group(ry0, ry1, radioState(gray, ry0, ry1, bg), texts.size, lines)
        }
        return routes to bg
    }

    /** `radio_state`: true = filled (selected), false = ring, null = no circle. */
    private fun radioState(gray: Gray, y0: Int, y1: Int, bg: Int): Boolean? {
        val reg = gray.crop(RADIO_X.first, y0, RADIO_X.second, y1)
        var count = 0; var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until reg.h) for (x in 0 until reg.w) if (reg[x, y] > bg + RADIO_DIFF) {
            count++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (count < 30) return null
        val h = maxY - minY + 1; val w = maxX - minX + 1
        val ratio = w / h.toDouble()
        if (!(ratio > 0.7 && ratio < 1.4 && h >= 20)) return null
        return count / (h * w).toDouble() > 0.5
    }

    /** `find_card`: the white lines of the single-route card above the green button. */
    private fun findCard(gray: Gray, btn: IntArray): Group? {
        val y0 = SEARCH_Y.first; val y1 = btn[1] - 3
        val x0 = TEXT_X.first; val x1 = TEXT_X.second
        val reg = gray.crop(x0, y0, x1, y1)
        if (reg.isEmpty) return null
        val bg = Hist.of(reg).median().toInt()
        val inkRow = BooleanArray(reg.h) { y ->
            var c = 0
            for (x in 0 until reg.w) if (abs(reg[x, y] - bg) > INK_DIFF) c++
            c / reg.w.toDouble() > 0.01
        }
        data class Cand(val s: Int, val e: Int, val b: Double)
        val cand = ArrayList<Cand>()
        for (r in Cv.runs(inkRow, 2)) {
            val hist = Hist()
            for (y in r.s until r.e) for (x in 0 until reg.w) { val v = reg[x, y]; if (abs(v - bg) > INK_DIFF) hist.add(v) }
            if (hist.n > 0) cand += Cand(r.s, r.e, hist.percentile(90.0) - bg)
        }
        if (cand.isEmpty()) return null
        val top = cand.maxOf { it.b }
        val rows = cand.filter { it.e - it.s in CARD_LINE_H && it.b >= CARD_BRIGHT * top && it.b >= INK_DIFF }
            .map { (y0 + it.s) to (y0 + it.e) }
        if (rows.size < 2) return null
        val lines = LinkedHashMap<String, Gray?>()
        listOf("summary", "arrive", "lights").zip(rows.takeLast(3).reversed()).forEach { (kind, span) ->
            lines[kind] = GlyphOcr.lineFeature(gray, (span.first + span.second) / 2, x0, x1, FONTS.getValue(LINE_FONT.getValue(kind)))
        }
        return Group(rows[rows.size - min(3, rows.size)].first - 6, rows.last().second + 6, true, lines.size, lines)
    }

    private fun analyzeGeometry(bgr: BgrFrame): Geometry? {
        val btn = findStartButton(bgr) ?: return null
        val gray = bgr.gray()
        val (found, _) = findRoutes(gray, btn)
        val isList = found.any { it.selected != null }
        val routes = found.filter { it.selected != null || (isList && it.nLines == 2) }.map { g ->
            Group(g.y0, g.y1, g.selected, g.nLines, linkedMapOf("eta" to g.lines["eta"], "detail" to g.lines["detail"]))
        }
        val card = if (routes.isEmpty()) findCard(gray, btn) else null
        return Geometry(btn, gray, routes, card)
    }

    // ------------------------------------------------------------ green button text
    private fun greenTextFeature(gray: Gray, btn: IntArray): Gray? {
        val reg = gray.crop(btn[0] + 20, btn[1] + 6, btn[2] - 20, btn[3] - 6)
        if (reg.isEmpty) return null
        val med = Hist.of(reg).median().toFloat()
        val scale = (255.0 / GREEN_DIFF).toFloat()
        val f = FImage(reg.w, reg.h, FloatArray(reg.px.size) { (abs(reg.px[it] - med) * scale).coerceIn(0f, 255f) })
        val rowOk = BooleanArray(f.h) { y -> var c = 0; for (x in 0 until f.w) if (f[x, y] > 100f) c++; c >= 2 }
        val colOk = BooleanArray(f.w) { x -> var c = 0; for (y in 0 until f.h) if (f[x, y] > 100f) c++; c >= 2 }
        val rows = rowOk.indices.filter { rowOk[it] }; val cols = colOk.indices.filter { colOk[it] }
        if (rows.size < 8 || cols.size < 20) return null
        val cut = f.crop(cols.first(), rows.first(), cols.last() + 1, rows.last() + 1)
        return Cv.gaussian3(Cv.resize(cut, GREEN_W, GREEN_H, area = true)).truncateToGray()
    }

    private fun matchGreen(feat: Gray?, tpls: List<VisionTemplate>): Pair<String?, Double> {
        if (feat == null || tpls.isEmpty()) return null to 0.0
        val ff = feat.toFloat()
        val best = LinkedHashMap<String, Double>()
        for (t in tpls) {
            if (t.image.w > ff.w || t.image.h > ff.h) continue
            best[t.label] = max(best[t.label] ?: -1.0, Cv.peak(Cv.matchTemplate(ff, t.float)).toDouble())
        }
        if (best.isEmpty()) return null to 0.0
        val ranked = best.entries.sortedByDescending { it.value }
        val s1 = ranked[0].value
        val s2 = if (ranked.size > 1) ranked[1].value else 0.0
        return (if (s1 >= GREEN_MATCH_TH && s1 - s2 >= GREEN_MARGIN) ranked[0].key else null) to s1
    }

    // ------------------------------------------------------------ text
    /** `stray_dot_keep`: a "." only between two digits; of repeated "·" the best-scoring one. */
    fun strayDotKeep(text: String, scores: List<Double>): List<Int> {
        val keep = text.indices.filter { i ->
            text[i] != '.' || (i > 0 && i < text.length - 1 && GlyphOcr.isDigit(text[i - 1]) && GlyphOcr.isDigit(text[i + 1]))
        }
        val out = ArrayList<Int>()
        for (i in keep) {
            if (out.isNotEmpty() && text[i] == '·' && text[out.last()] == '·') {
                if (scores[i] > scores[out.last()]) out[out.size - 1] = i
                continue
            }
            out += i
        }
        return out
    }

    private fun MatchResult.group(i: Int): String? = groups[i]?.value

    fun parseEta(s: String?): Int? {
        val m = ETA_RE.find(s ?: "") ?: return null
        if (m.group(1) == null && m.group(2) == null) return null
        return (m.group(1)?.toInt() ?: 0) * 60 + (m.group(2)?.toInt() ?: 0)
    }

    data class Detail(val distKm: Double, val lights: Int?, val toll: Double?)

    fun parseDetail(s: String?): Detail? {
        val m = DETAIL_RE.find(s ?: "") ?: return null
        val d = m.group(1)!!.toDouble()
        return Detail(if (m.group(2) == "公里") d else d / 1000.0, m.group(3)?.toInt(), m.group(4)?.toDouble())
    }

    fun parseArrive(s: String?): String? {
        val m = ARRIVE_RE.find(s ?: "") ?: return null
        val hh = m.group(1)!!.toInt(); val mm = m.group(2)!!
        if (hh > 23 || mm.toInt() > 59) return null
        return "%02d:%s".format(java.util.Locale.US, hh, mm)
    }

    fun parseSummary(s: String?): Pair<Int, Double>? {
        val m = SUMMARY_RE.find(s ?: "") ?: return null
        val eta = when {
            m.group(1) != null -> m.group(1)!!.toInt() * 60 + m.group(2)!!.toInt()
            m.group(3) != null || m.group(4) != null -> (m.group(3)?.toInt() ?: 0) * 60 + (m.group(4)?.toInt() ?: 0)
            else -> return null
        }
        val d = m.group(5)!!.toDouble()
        return eta to (if (m.group(6) == "公里") d else d / 1000.0)
    }

    fun parseLights(s: String?): Int? = LIGHTS_RE.find(s ?: "")?.group(1)?.toInt()

    fun arriveConsistent(arrive: String, etaMin: Int, nowMin: Double): Boolean {
        val (h, m) = arrive.split(":").map { it.toInt() }
        val raw = (h * 60 + m) - (nowMin + etaMin)
        val diff = ((raw % 1440) + 1440) % 1440
        return min(diff, 1440 - diff) <= ARRIVE_TOL_MIN
    }

    private class Fields(val etaMin: Int, val distKm: Double, val lights: Int?, val toll: Double?, val arrive: String?)

    /** `parse_fields`: list rows (eta + detail) or the card (summary + arrive [+ lights]). */
    private fun parseFields(texts: Map<String, String?>, nowMin: Double?): Fields? {
        if ("eta" in texts || "detail" in texts) {
            val eta = parseEta(texts["eta"]) ?: return null
            val det = parseDetail(texts["detail"]) ?: return null
            return Fields(eta, det.distKm, det.lights, det.toll, null)
        }
        val summ = parseSummary(texts["summary"]) ?: return null
        val arr = parseArrive(texts["arrive"]) ?: return null
        if (nowMin != null && !arriveConsistent(arr, summ.first, nowMin)) return null
        var lights: Int? = null
        if ("lights" in texts) lights = parseLights(texts["lights"]) ?: return null
        return Fields(summ.first, summ.second, lights, null, arr)
    }

    private fun readLines(lines: Map<String, Gray?>, models: Map<String, GlyphModel>): Triple<Map<String, String?>, Map<String, Double>, Boolean> {
        val texts = LinkedHashMap<String, String?>(); val confs = LinkedHashMap<String, Double>()
        var ok = true
        for ((kind, f) in lines) {
            val m = models[LINE_FONT.getValue(kind)]
            if (f == null || m == null) { texts[kind] = null; confs[kind] = 0.0; ok = false; continue }
            val line = GlyphOcr.recognize(f, m)
            var idx = strayDotKeep(line.text, line.scores)
            if (kind in NO_SEPARATOR) idx = idx.filter { line.text[it] != '·' }
            val txt = idx.map { line.text[it] }.joinToString("")
            val conf = GlyphOcr.confidence(txt, idx.map { line.scores[it] }, line.skipped, idx.map { line.margins[it] })
            texts[kind] = txt; confs[kind] = CarPlayScreenState.round3(conf)
            ok = ok && conf >= GlyphOcr.ACCEPT
        }
        return Triple(texts, confs, ok)
    }

    /** `to_ref`: scale to the reference height with INTER_AREA; returns the frame and input px per reference px. */
    fun toRef(bgr: BgrFrame): Pair<BgrFrame, Double> {
        if (bgr.h == REF_H) return bgr to 1.0
        val s = bgr.h / REF_H.toDouble()
        return bgr.resize(Math.rint(bgr.w / s).toInt(), REF_H, area = true) to s
    }

    /**
     * `read_route_page` on a picture already at the reference height ([ref], [sc] = input px per
     * reference px). [models] null = geometry only. Coordinates are returned on the input frame.
     */
    fun readRoutePage(ref: BgrFrame, sc: Double, models: Map<String, GlyphModel>?, greenTpls: List<VisionTemplate>,
        nowMin: Double?): VisionPanel {
        val geo = analyzeGeometry(ref) ?: return VisionPanel.NONE
        val b = geo.btn
        val (text, tscore) = matchGreen(greenTextFeature(geo.gray, b), greenTpls)
        val page = when {
            geo.routes.isNotEmpty() -> "route"
            geo.card != null && text == "开始导航" -> "route"
            geo.card != null && text == "继续导航" -> "switch_route"
            else -> "green_panel"
        }
        val startBtn = intArrayOf((b[0] * sc).toInt(), (b[1] * sc).toInt(), (b[2] * sc).toInt(), (b[3] * sc).toInt())
        val tap = intArrayOf(((b[0] + b[2]) / 2.0 * sc).toInt(), ((b[1] + b[3]) / 2.0 * sc).toInt())
        val base = VisionPanel(page, text, CarPlayScreenState.round3(tscore), startBtn, tap)
        if (page == "green_panel") return base
        val rows = geo.routes.ifEmpty { listOf(geo.card!!) }
        val isCard = geo.routes.isEmpty()
        val routes = rows.map { r ->
            val y = intArrayOf((r.y0 * sc).toInt(), (r.y1 * sc).toInt())
            val rtap = intArrayOf(((TEXT_X.first + RADIO_X.second) / 2.0 * sc).toInt(), ((r.y0 + r.y1) / 2.0 * sc).toInt())
            if (models.isNullOrEmpty()) return@map VisionRoute(r.selected, isCard, y, rtap, null, null, null)
            val (texts, confs, ok) = readLines(r.lines, models)
            val f = if (ok) parseFields(texts, nowMin) else null
            VisionRoute(r.selected, isCard, y, rtap, texts, confs, f != null,
                f?.etaMin, f?.distKm, f?.lights, f?.toll, f?.arrive)
        }
        val sel = routes.indices.filter { routes[it].selected == true }
        return base.copy(routes = routes, selectedIndex = if (sel.size == 1) sel[0] else null)
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.hud.BydBatteryDetails
import com.shilapi.xcertplay.hud.BydBatteryField
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Values as the card prints them; "—" when a value could not be read. */
internal object BatteryCardFormat {
    const val MISSING = "—"

    fun value(field: BydBatteryField, value: Double?): String {
        if (value == null) return MISSING
        fun f(pattern: String, number: Double) = String.format(Locale.US, pattern, number)
        return when (field) {
            BydBatteryField.SOC, BydBatteryField.SOH, BydBatteryField.CHARGE_LIMIT -> "${value.roundToInt()}%"
            BydBatteryField.RANGE -> "${value.roundToInt()} km"
            BydBatteryField.REMAINING_ENERGY -> f("%.1f kWh", value)
            // Charging reads as a negative power; the card shows its size with a charging mark.
            BydBatteryField.POWER -> if (value < -0.05) "⚡" + f("%.1f kW", abs(value)) else f("%.1f kW", abs(value))
            BydBatteryField.VOLTAGE -> f("%.0f V", value)
            BydBatteryField.CURRENT -> f("%.1f A", value)
            BydBatteryField.CELL_VOLTAGE_MAX, BydBatteryField.CELL_VOLTAGE_MIN -> f("%.3f V", value)
            BydBatteryField.CELL_VOLTAGE_DIFF -> f("%.0f mV", value)
            BydBatteryField.TEMP_MAX, BydBatteryField.TEMP_MIN, BydBatteryField.TEMP_AVG -> f("%.0f°C", value)
            BydBatteryField.INSULATION -> if (value >= 1000) f("%.1f MΩ", value / 1000) else f("%.0f kΩ", value)
            BydBatteryField.MAX_CHARGE_POWER, BydBatteryField.MAX_DISCHARGE_POWER -> f("%.0f kW", value)
            BydBatteryField.CHARGE_TIME_LEFT -> value.roundToInt().let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) }
        }
    }

    /** A wide value for each field, so the card keeps its size while the numbers change. */
    fun sample(field: BydBatteryField): String = when (field) {
        BydBatteryField.SOC, BydBatteryField.SOH, BydBatteryField.CHARGE_LIMIT -> "100%"
        BydBatteryField.RANGE -> "8888 km"
        BydBatteryField.REMAINING_ENERGY -> "188.8 kWh"
        BydBatteryField.POWER -> "⚡888.8 kW"
        BydBatteryField.VOLTAGE -> "888 V"
        BydBatteryField.CURRENT -> "-888.8 A"
        BydBatteryField.CELL_VOLTAGE_MAX, BydBatteryField.CELL_VOLTAGE_MIN -> "8.888 V"
        BydBatteryField.CELL_VOLTAGE_DIFF -> "888 mV"
        BydBatteryField.TEMP_MAX, BydBatteryField.TEMP_MIN, BydBatteryField.TEMP_AVG -> "-88°C"
        BydBatteryField.INSULATION -> "888 kΩ"
        BydBatteryField.MAX_CHARGE_POWER, BydBatteryField.MAX_DISCHARGE_POWER -> "8888 kW"
        BydBatteryField.CHARGE_TIME_LEFT -> "88:88"
    }

    /** Mini cards print values without labels; these marks keep similar values apart. */
    fun miniPrefix(field: BydBatteryField): String = when (field) {
        BydBatteryField.SOH -> "SOH "
        BydBatteryField.CELL_VOLTAGE_MAX, BydBatteryField.TEMP_MAX -> "↑"
        BydBatteryField.CELL_VOLTAGE_MIN, BydBatteryField.TEMP_MIN -> "↓"
        BydBatteryField.CELL_VOLTAGE_DIFF -> "Δ"
        BydBatteryField.CHARGE_LIMIT -> "⇥"
        else -> ""
    }
}

/**
 * Draws the battery card in three styles. Sizes come from the style, the chosen fields and the
 * scale only, never from the current numbers, so the window does not jump while values change.
 */
internal class BatteryCardView(context: Context, private val text: BatteryCardText) : View(context) {
    var config: BatteryCardConfig = BatteryCardConfig()
        set(value) { field = value; requestLayout(); invalidate() }
    var update: BydVehicleMonitor.BatteryUpdate? = null
        set(value) { field = value; invalidate() }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun u(dp: Float) = dp * density * config.scale / 100f
    private val details: BydBatteryDetails? get() = update?.details
    private fun valueOf(field: BydBatteryField) = BatteryCardFormat.value(field, details?.get(field))
    private val charging: Boolean get() = details?.charging == true

    /** Fields printed below the header (or after the mini percentage). */
    private fun bodyFields(): List<BydBatteryField> = when (config.style) {
        BatteryCardStyle.MINI -> config.fields.filter { it != BydBatteryField.SOC }.take(MINI_FIELDS)
        BatteryCardStyle.STANDARD -> config.fields.filter { it != BydBatteryField.SOC }
        BatteryCardStyle.RICH -> config.fields.filter { it != BydBatteryField.SOC && it != BydBatteryField.POWER && it != BydBatteryField.RANGE }
    }

    private fun status(): String? {
        val current = update
        return when {
            current == null -> text.reading
            current.details != null -> null
            current.link == BydVehicleMonitor.Link.NO_ADB -> text.noAdb
            current.link == BydVehicleMonitor.Link.UNSUPPORTED -> text.unsupported
            else -> text.reading
        }
    }

    private fun textWidth(value: String, sizeDp: Float, bold: Boolean): Float {
        paint.textSize = u(sizeDp); paint.typeface = if (bold) medium else regular
        return paint.measureText(value)
    }

    fun contentSize(): Pair<Int, Int> = when (config.style) {
        BatteryCardStyle.MINI -> {
            val fields = bodyFields().sumOf {
                (u(MINI_GAP) + textWidth(BatteryCardFormat.miniPrefix(it) + BatteryCardFormat.sample(it), 15f, true)).toDouble()
            }.toFloat()
            val message = listOf(text.reading, text.noAdb, text.unsupported).maxOf { textWidth(it, 14f, false) } + u(MINI_GAP)
            val width = u(14f) + u(GLYPH_MINI_W + 3) + u(8f) + textWidth("100%", 17f, true) + maxOf(fields, message) + u(16f)
            ceil(width).toInt() to ceil(u(44f)).toInt()
        }
        BatteryCardStyle.STANDARD -> {
            val rows = ceil(bodyFields().size / 2.0).toInt()
            ceil(u(STANDARD_WIDTH)).toInt() to ceil(u(16f + 52f + 16f) + rows * u(GRID_ROW)).toInt()
        }
        BatteryCardStyle.RICH -> {
            val rows = ceil(bodyFields().size / 3.0).toInt()
            val power = if (BydBatteryField.POWER in config.fields) u(POWER_BLOCK) else 0f
            ceil(u(RICH_WIDTH)).toInt() to ceil(u(18f + 60f + 26f) + power + rows * u(GRID_ROW) + u(14f)).toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (width, height) = contentSize()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = if (config.style == BatteryCardStyle.MINI) height / 2f else u(20f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(config.opacity * 255 / 100, 16, 20, 26)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = u(1f); paint.color = BORDER
        rect.inset(u(0.5f), u(0.5f))
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        when (config.style) {
            BatteryCardStyle.MINI -> drawMini(canvas)
            BatteryCardStyle.STANDARD -> drawStandard(canvas)
            BatteryCardStyle.RICH -> drawRich(canvas)
        }
    }

    private fun drawMini(canvas: Canvas) {
        val centre = height / 2f
        var x = u(14f)
        drawGlyph(canvas, x, centre - u(GLYPH_MINI_H) / 2, u(GLYPH_MINI_W), u(GLYPH_MINI_H))
        x += u(GLYPH_MINI_W + 3) + u(8f)
        x += drawText(canvas, valueOf(BydBatteryField.SOC), x, centre, 17f, true, socColor())
        val status = status()
        if (status != null) {
            drawText(canvas, status, x + u(MINI_GAP), centre, 14f, false, SECONDARY)
            return
        }
        for (field in bodyFields()) {
            x += u(MINI_GAP)
            paint.color = SEPARATOR
            canvas.drawCircle(x - u(MINI_GAP) / 2, centre, u(1.6f), paint)
            val color = if (field == BydBatteryField.POWER && charging) CHARGING else PRIMARY
            x += drawText(canvas, BatteryCardFormat.miniPrefix(field) + valueOf(field), x, centre, 15f, true, color)
        }
    }

    private fun drawStandard(canvas: Canvas) {
        val pad = u(16f)
        val header = pad + u(26f)
        drawGlyph(canvas, pad, header - u(9f), u(34f), u(18f))
        drawText(canvas, valueOf(BydBatteryField.SOC), pad + u(44f), header, 30f, true, socColor())
        drawStatusEnd(canvas, width - pad, header, 15f)
        drawGrid(canvas, bodyFields(), 2, pad, pad + u(52f + 8f), width - 2 * pad)
    }

    private fun drawRich(canvas: Canvas) {
        val pad = u(18f)
        val header = pad + u(24f)
        drawGlyph(canvas, pad, header - u(11f), u(40f), u(22f))
        drawText(canvas, valueOf(BydBatteryField.SOC), pad + u(52f), header, 36f, true, socColor())
        drawStatusEnd(canvas, width - pad, header - u(9f), 15f)
        if (status() == null) drawTextEnd(canvas, valueOf(BydBatteryField.RANGE), width - pad, header + u(13f), 14f, false, SECONDARY)
        var y = pad + u(60f)
        drawSocBar(canvas, pad, y, width - 2 * pad)
        y += u(26f)
        if (BydBatteryField.POWER in config.fields) {
            drawPower(canvas, pad, y, width - 2 * pad)
            y += u(POWER_BLOCK)
        }
        drawGrid(canvas, bodyFields(), 3, pad, y, width - 2 * pad)
    }

    /** Charging, or a status message, or the card title, aligned to [end]. */
    private fun drawStatusEnd(canvas: Canvas, end: Float, centre: Float, size: Float) {
        val status = status()
        when {
            status != null -> drawTextEnd(canvas, status, end, centre, size, false, SECONDARY)
            charging -> drawTextEnd(canvas, "⚡ " + text.charging, end, centre, size, true, CHARGING)
            else -> drawTextEnd(canvas, text.title, end, centre, size, false, SECONDARY)
        }
    }

    private fun drawGrid(canvas: Canvas, fields: List<BydBatteryField>, columns: Int, left: Float, top: Float, width: Float) {
        val cell = width / columns
        fields.forEachIndexed { index, field ->
            val x = left + (index % columns) * cell
            val y = top + (index / columns) * u(GRID_ROW)
            fitText(canvas, text.labels[field].orEmpty(), x, y + u(10f), cell - u(8f), 12f, false, SECONDARY)
            val color = if (field == BydBatteryField.POWER && charging) CHARGING else PRIMARY
            fitText(canvas, valueOf(field), x, y + u(30f), cell - u(8f), 18f, true, color)
        }
    }

    private fun drawSocBar(canvas: Canvas, left: Float, top: Float, width: Float) {
        val height = u(12f)
        rect.set(left, top, left + width, top + height)
        paint.color = TRACK
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
        val soc = details?.get(BydBatteryField.SOC) ?: return
        rect.right = left + width * (soc / 100).coerceIn(0.0, 1.0).toFloat()
        paint.color = if (charging) CHARGING else socColor()
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
        details?.get(BydBatteryField.CHARGE_LIMIT)?.takeIf { it in 1.0..99.0 }?.let { limit ->
            val x = left + width * (limit / 100).toFloat()
            paint.color = PRIMARY
            canvas.drawRect(x - u(1f), top - u(3f), x + u(1f), top + height + u(3f), paint)
        }
    }

    /** A centred bar: charging grows to the left, driving to the right. */
    private fun drawPower(canvas: Canvas, left: Float, top: Float, width: Float) {
        fitText(canvas, text.labels[BydBatteryField.POWER].orEmpty(), left, top + u(10f), width / 2, 12f, false, SECONDARY)
        val power = details?.get(BydBatteryField.POWER)
        drawTextEnd(canvas, valueOf(BydBatteryField.POWER), left + width, top + u(10f), 15f, true, if (charging) CHARGING else PRIMARY)
        val barTop = top + u(22f)
        val height = u(8f)
        rect.set(left, barTop, left + width, barTop + height)
        paint.color = TRACK
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
        val middle = left + width / 2
        paint.color = SEPARATOR
        canvas.drawRect(middle - u(1f), barTop - u(3f), middle + u(1f), barTop + height + u(3f), paint)
        if (power == null) return
        val full = maxOf(POWER_SCALE_KW, abs(power))
        val reach = (width / 2) * (abs(power) / full).toFloat()
        if (power < 0) rect.set(middle - reach, barTop, middle, barTop + height) else rect.set(middle, barTop, middle + reach, barTop + height)
        paint.color = if (power < 0) CHARGING else DRIVING
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
    }

    private fun drawGlyph(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val corner = height * 0.22f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(u(1.5f), height * 0.1f)
        paint.color = PRIMARY
        rect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.FILL
        val nub = height * 0.4f
        rect.set(left + width, top + (height - nub) / 2, left + width + height * 0.16f, top + (height + nub) / 2)
        canvas.drawRoundRect(rect, corner / 2, corner / 2, paint)
        val soc = details?.get(BydBatteryField.SOC) ?: return
        val inset = paint.strokeWidth * 1.4f
        rect.set(left + inset, top + inset, left + inset + (width - 2 * inset) * (soc / 100).coerceIn(0.0, 1.0).toFloat(), top + height - inset)
        paint.color = if (charging) CHARGING else socColor()
        canvas.drawRoundRect(rect, corner / 2, corner / 2, paint)
    }

    private fun socColor(): Int {
        val soc = details?.get(BydBatteryField.SOC) ?: return PRIMARY
        return when {
            soc >= 50 -> GOOD
            soc >= 20 -> WARN
            else -> LOW
        }
    }

    /** Draws [value] vertically centred on [centre]; returns its width. */
    private fun drawText(canvas: Canvas, value: String, x: Float, centre: Float, size: Float, bold: Boolean, color: Int): Float {
        paint.textSize = u(size); paint.typeface = if (bold) medium else regular; paint.color = color
        paint.textAlign = Paint.Align.LEFT
        val metrics = paint.fontMetrics
        canvas.drawText(value, x, centre - (metrics.ascent + metrics.descent) / 2, paint)
        return paint.measureText(value)
    }

    private fun drawTextEnd(canvas: Canvas, value: String, end: Float, centre: Float, size: Float, bold: Boolean, color: Int) {
        paint.textSize = u(size); paint.typeface = if (bold) medium else regular; paint.color = color
        drawText(canvas, value, end - paint.measureText(value), centre, size, bold, color)
    }

    /** Shrinks [value] until it fits [maxWidth], down to 70 % of [size]. */
    private fun fitText(canvas: Canvas, value: String, x: Float, centre: Float, maxWidth: Float, size: Float, bold: Boolean, color: Int) {
        var fitted = size
        while (fitted > size * 0.7f && textWidth(value, fitted, bold) > maxWidth) fitted -= 0.5f
        drawText(canvas, value, x, centre, fitted, bold, color)
    }

    private companion object {
        const val MINI_FIELDS = 3
        const val MINI_GAP = 14f
        const val GLYPH_MINI_W = 26f
        const val GLYPH_MINI_H = 13f
        const val STANDARD_WIDTH = 300f
        const val RICH_WIDTH = 380f
        const val GRID_ROW = 46f
        const val POWER_BLOCK = 44f
        const val POWER_SCALE_KW = 150.0
        val PRIMARY = Color.WHITE
        val SECONDARY = Color.rgb(0x9A, 0xA4, 0xAE)
        val SEPARATOR = Color.argb(0x80, 0xFF, 0xFF, 0xFF)
        val TRACK = Color.argb(0x38, 0xFF, 0xFF, 0xFF)
        val BORDER = Color.argb(0x22, 0xFF, 0xFF, 0xFF)
        val GOOD = Color.rgb(0x34, 0xC7, 0x59)
        val WARN = Color.rgb(0xFF, 0xB3, 0x40)
        val LOW = Color.rgb(0xFF, 0x45, 0x3A)
        val CHARGING = Color.rgb(0x40, 0xC8, 0xFF)
        val DRIVING = Color.rgb(0xFF, 0x9F, 0x0A)
    }
}

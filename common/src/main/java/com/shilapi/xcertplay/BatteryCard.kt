package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydBatteryField
import com.shilapi.xcertplay.hud.BydVehicleMonitor
import kotlin.math.abs

internal enum class BatteryCardTarget { CENTER, CLUSTER }
internal enum class BatteryCardStyle { MINI, STANDARD, RICH }
/** [FOLLOW] uses the head unit's day/night, read the way the CarPlay screen's "follow" reads it. */
internal enum class BatteryCardTheme { FOLLOW, DAY, NIGHT }

/** [scale] and [opacity] in %; [clusterX]/[clusterY] the card centre in % of the dashboard; [centerX]/[centerY] px, -1 = default. */
internal data class BatteryCardConfig(
    val visible: Boolean = false,
    val target: BatteryCardTarget = BatteryCardTarget.CENTER,
    val style: BatteryCardStyle = BatteryCardStyle.STANDARD,
    val fields: List<BydBatteryField> = BatteryCardSettings.DEFAULT_FIELDS,
    val scale: Int = 100,
    val opacity: Int = 85,
    val clusterX: Int = 50,
    val clusterY: Int = 16,
    val centerX: Int = -1,
    val centerY: Int = -1,
    val theme: BatteryCardTheme = BatteryCardTheme.FOLLOW,
)

internal object BatteryCardSettings {
    const val PREFS = "diplay_battery_card"
    val SCALES = (60..160 step 5).toList()
    val OPACITIES = (30..100 step 5).toList()
    val DEFAULT_FIELDS = listOf(BydBatteryField.SOC, BydBatteryField.RANGE, BydBatteryField.POWER,
        BydBatteryField.REMAINING_ENERGY, BydBatteryField.SOH, BydBatteryField.TEMP_MAX, BydBatteryField.CELL_VOLTAGE_DIFF)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): BatteryCardConfig {
        val p = prefs(context)
        fun <T : Enum<T>> enum(key: String, values: Array<T>, default: T) =
            values.firstOrNull { it.name == p.getString(key, null) } ?: default
        val fields = p.getString("fields", null)?.split(',')?.toSet()
            ?.let { names -> BydBatteryField.entries.filter { it.name in names } }
        return BatteryCardConfig(
            visible = p.getBoolean("visible", false),
            target = enum("target", BatteryCardTarget.values(), BatteryCardTarget.CENTER),
            style = enum("style", BatteryCardStyle.values(), BatteryCardStyle.STANDARD),
            fields = fields ?: DEFAULT_FIELDS,
            scale = p.getInt("scale", 100).coerceIn(SCALES.first(), SCALES.last()),
            opacity = p.getInt("opacity", 85).coerceIn(OPACITIES.first(), OPACITIES.last()),
            clusterX = p.getInt("cluster_x", 50).coerceIn(0, 100),
            clusterY = p.getInt("cluster_y", 16).coerceIn(0, 100),
            centerX = p.getInt("center_x", -1),
            centerY = p.getInt("center_y", -1),
            theme = enum("theme", BatteryCardTheme.values(), BatteryCardTheme.FOLLOW),
        )
    }

    fun save(context: Context, config: BatteryCardConfig) {
        prefs(context).edit()
            .putBoolean("visible", config.visible)
            .putString("target", config.target.name)
            .putString("style", config.style.name)
            .putString("fields", config.fields.joinToString(",") { it.name })
            .putInt("scale", config.scale)
            .putInt("opacity", config.opacity)
            .putInt("cluster_x", config.clusterX)
            .putInt("cluster_y", config.clusterY)
            .putInt("center_x", config.centerX)
            .putInt("center_y", config.centerY)
            .putString("theme", config.theme.name)
            .apply()
    }

    fun update(context: Context, change: (BatteryCardConfig) -> BatteryCardConfig) = save(context, change(load(context)))
}

/**
 * The battery card: a floating window on the centre screen (draggable) or on the dashboard (placed
 * from Settings, not touchable). It does not depend on a CarPlay session; it reads the battery
 * through [BydVehicleMonitor] only while it is shown. Needs "display over other apps". Main thread.
 */
internal object BatteryCard {
    private const val TAG = "DiPlay-BatteryCard"
    private const val PREFERRED_CLUSTER_LAYER = "bosdisplay2"

    private var app: Context? = null
    private var view: BatteryCardView? = null
    private var windows: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownOn: Pair<BatteryCardTarget, Int>? = null
    private var observing = false
    private val battery = BydVehicleMonitor.BatteryListener { update -> view?.update = update }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> app?.let(::refresh) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The last day/night the head unit reported, kept while no input reports one. */
    private var lastNight = true
    private val nightChanged: () -> Unit = { applyTheme() }
    /** Some inputs (a Configuration) change without a settings change, so "follow" also rechecks. */
    private val recheck = object : Runnable {
        override fun run() {
            applyTheme()
            if (view != null) handler.postDelayed(this, NIGHT_RECHECK_MILLIS)
        }
    }

    val shown: Boolean get() = view != null

    fun permitted(context: Context) = Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(context)

    /** App start: brings back a card that was left visible. */
    fun restore(context: Context) {
        observe(context)
        refresh(context)
    }

    /** For a shortcut or the Settings switch; false when the card could not be drawn. */
    fun setVisible(context: Context, visible: Boolean): Boolean {
        observe(context)
        BatteryCardSettings.update(context) { it.copy(visible = visible) }
        if (!visible) { hide(); return true }
        return show(context)
    }

    private fun observe(context: Context) {
        app = context.applicationContext
        if (observing) return
        observing = true
        BatteryCardSettings.prefs(context).registerOnSharedPreferenceChangeListener(changed)
    }

    private fun refresh(context: Context) {
        if (BatteryCardSettings.load(context).visible) show(context) else hide()
    }

    private fun show(context: Context): Boolean {
        val app = context.applicationContext
        this.app = app
        val config = BatteryCardSettings.load(app)
        if (!permitted(app)) {
            Log.i(TAG, "overlay permission missing")
            hide()
            return false
        }
        val display = display(app, config.target)
        if (display == null) {
            Log.i(TAG, "no ${config.target} display")
            hide()
            return false
        }
        val current = view
        if (current != null && shownOn == config.target to display.displayId) {
            current.config = config
            applyTheme()
            relayout()
            return true
        }
        hide()
        return try {
            val windowContext = CustomClusterWindows.overlayContext(app, display)
            val manager = windowContext.getSystemService(WindowManager::class.java) ?: return false
            val card = BatteryCardView(windowContext, BatteryCardText.of(context)).also { it.config = config }
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (config.target == BatteryCardTarget.CLUSTER) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val layout = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "DiPlay battery card"
            }
            place(card, manager, layout, config)
            if (config.target == BatteryCardTarget.CENTER) drag(card, manager, layout)
            manager.addView(card, layout)
            view = card; windows = manager; params = layout; shownOn = config.target to display.displayId
            BydVehicleMonitor.addBatteryListener(app, battery)
            HeadUnitNightMode.install(app)
            HeadUnitNightMode.addListener(nightChanged)
            handler.removeCallbacks(recheck)
            recheck.run()
            Log.i(TAG, "shown on display ${display.displayId} ${layout.width}x${layout.height}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "card failed: ${CustomClusterWindows.failureSummary(error)}")
            hide()
            false
        }
    }

    fun hide() {
        BydVehicleMonitor.removeBatteryListener(battery)
        HeadUnitNightMode.removeListener(nightChanged)
        handler.removeCallbacks(recheck)
        val card = view ?: return
        runCatching { windows?.removeViewImmediate(card) }
        view = null; windows = null; params = null; shownOn = null
        Log.i(TAG, "hidden")
    }

    /** Day or night colours from the card's theme; "follow" keeps the last known state while no input reports one. */
    private fun applyTheme() {
        val card = view ?: return
        val context = app ?: return
        card.night = when (card.config.theme) {
            BatteryCardTheme.DAY -> false
            BatteryCardTheme.NIGHT -> true
            BatteryCardTheme.FOLLOW -> (HeadUnitNightMode.read(context) ?: lastNight).also { lastNight = it }
        }
    }

    private fun relayout() {
        val card = view ?: return
        val manager = windows ?: return
        val layout = params ?: return
        place(card, manager, layout, card.config)
        runCatching { manager.updateViewLayout(card, layout) }
    }

    private fun bounds(manager: WindowManager, card: BatteryCardView): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else card.context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun place(card: BatteryCardView, manager: WindowManager, layout: WindowManager.LayoutParams, config: BatteryCardConfig) {
        val (width, height) = card.contentSize()
        val (screenWidth, screenHeight) = bounds(manager, card)
        val density = card.context.resources.displayMetrics.density
        layout.width = width
        layout.height = height
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        if (config.target == BatteryCardTarget.CLUSTER) {
            layout.x = (screenWidth * config.clusterX / 100 - width / 2).coerceIn(0, maxX)
            layout.y = (screenHeight * config.clusterY / 100 - height / 2).coerceIn(0, maxY)
        } else {
            layout.x = (if (config.centerX >= 0) config.centerX else screenWidth - width - (32 * density).toInt()).coerceIn(0, maxX)
            layout.y = (if (config.centerY >= 0) config.centerY else (120 * density).toInt()).coerceIn(0, maxY)
        }
    }

    private fun drag(card: BatteryCardView, manager: WindowManager, layout: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(card.context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        val (screenWidth, screenHeight) = bounds(manager, card)
                        layout.x = (startX + dx).toInt().coerceIn(0, (screenWidth - layout.width).coerceAtLeast(0))
                        layout.y = (startY + dy).toInt().coerceIn(0, (screenHeight - layout.height).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(view, layout) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    BatteryCardSettings.update(card.context) { it.copy(centerX = layout.x, centerY = layout.y) }
                }
            }
            true
        }
    }

    /** The configured custom-window dashboard layer, else the top cluster layer, else any presentation display. */
    private fun display(context: Context, target: BatteryCardTarget): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        if (target == BatteryCardTarget.CENTER) return manager.getDisplay(Display.DEFAULT_DISPLAY)
        val all = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).filter(ClusterTransparencyProbe::eligible)
        val configured = ClusterWindowSettings.load(context).displayName
        return all.firstOrNull { configured.isNotEmpty() && it.name == configured }
            ?: all.firstOrNull { it.name == PREFERRED_CLUSTER_LAYER }
            ?: all.firstOrNull()
    }

    fun diagnostics(context: Context): List<String> {
        val config = BatteryCardSettings.load(context)
        return listOf("Battery card: visible=${config.visible} shown=$shown on=$shownOn target=${config.target} " +
            "style=${config.style} theme=${config.theme} night=${view?.night} fields=${config.fields.size} permitted=${permitted(context)}")
    }

    private const val NIGHT_RECHECK_MILLIS = 5_000L
}

/** Localized copy for [BatteryCardView], read once when the card is created. */
internal data class BatteryCardText(
    val title: String, val charging: String, val reading: String, val noAdb: String, val unsupported: String,
    val labels: Map<BydBatteryField, String>,
) {
    companion object {
        val LABELS = mapOf(
            BydBatteryField.SOC to R.string.battery_field_soc,
            BydBatteryField.SOH to R.string.battery_field_soh,
            BydBatteryField.RANGE to R.string.battery_field_range,
            BydBatteryField.REMAINING_ENERGY to R.string.battery_field_remaining_energy,
            BydBatteryField.POWER to R.string.battery_field_power,
            BydBatteryField.VOLTAGE to R.string.battery_field_voltage,
            BydBatteryField.CURRENT to R.string.battery_field_current,
            BydBatteryField.CELL_VOLTAGE_MAX to R.string.battery_field_cell_max,
            BydBatteryField.CELL_VOLTAGE_MIN to R.string.battery_field_cell_min,
            BydBatteryField.CELL_VOLTAGE_DIFF to R.string.battery_field_cell_diff,
            BydBatteryField.TEMP_MAX to R.string.battery_field_temp_max,
            BydBatteryField.TEMP_MIN to R.string.battery_field_temp_min,
            BydBatteryField.TEMP_AVG to R.string.battery_field_temp_avg,
            BydBatteryField.INSULATION to R.string.battery_field_insulation,
            BydBatteryField.MAX_CHARGE_POWER to R.string.battery_field_max_charge_power,
            BydBatteryField.MAX_DISCHARGE_POWER to R.string.battery_field_max_discharge_power,
            BydBatteryField.CHARGE_LIMIT to R.string.battery_field_charge_limit,
            BydBatteryField.CHARGE_TIME_LEFT to R.string.battery_field_charge_time_left,
            BydBatteryField.CHARGED_ENERGY to R.string.battery_field_charged_energy,
        )

        fun of(context: Context) = BatteryCardText(
            title = context.getString(R.string.battery_card_title),
            charging = context.getString(R.string.battery_card_charging),
            reading = context.getString(R.string.battery_card_reading),
            noAdb = context.getString(R.string.battery_card_no_adb),
            unsupported = context.getString(R.string.battery_card_unsupported),
            labels = LABELS.mapValues { context.getString(it.value) },
        )
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

/** The two battery cards: one on the centre screen, one on the dashboard, each with its own settings. */
internal enum class BatteryCardTarget { CENTER, CLUSTER }
internal enum class BatteryCardStyle { MINI, STANDARD, RICH }
/** [FOLLOW] uses the head unit's day/night, read the way the CarPlay screen's "follow" reads it. */
internal enum class BatteryCardTheme { FOLLOW, DAY, NIGHT }

/**
 * One card's settings. [scale] and [opacity] in %; [clusterX]/[clusterY] the dashboard card's centre
 * in % of the dashboard; [centerX]/[centerY] the centre-screen card's corner in px, -1 = default.
 */
internal data class BatteryCardConfig(
    val visible: Boolean = false,
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
    /** Where the single card of earlier versions kept its settings, with the display it was on. */
    private const val LEGACY_PREFS = "diplay_battery_card"
    private const val MIGRATED_KEY = "migrated_to"
    val SCALES = (60..160 step 5).toList()
    val OPACITIES = (30..100 step 5).toList()
    val DEFAULT_FIELDS = listOf(BydBatteryField.SOC, BydBatteryField.RANGE, BydBatteryField.POWER,
        BydBatteryField.REMAINING_ENERGY, BydBatteryField.SOH, BydBatteryField.TEMP_MAX, BydBatteryField.CELL_VOLTAGE_DIFF)

    fun prefsName(target: BatteryCardTarget) = "diplay_battery_card_${target.name.lowercase()}"

    fun prefs(context: Context, target: BatteryCardTarget): SharedPreferences {
        migrate(context)
        return context.getSharedPreferences(prefsName(target), Context.MODE_PRIVATE)
    }

    /** The card the single card of earlier versions became; its shortcut actions now name this card. */
    fun legacyTarget(context: Context): BatteryCardTarget {
        migrate(context)
        val name = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(MIGRATED_KEY, null)
        return BatteryCardTarget.entries.firstOrNull { it.name == name } ?: BatteryCardTarget.CENTER
    }

    /** Once: the single card's settings become the settings of the display it was on; the other card starts hidden. */
    @Synchronized private fun migrate(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.contains(MIGRATED_KEY)) return
        val all = legacy.all
        if (all.isEmpty()) return
        val target = BatteryCardTarget.entries.firstOrNull { it.name == all["target"] } ?: BatteryCardTarget.CENTER
        val destination = context.getSharedPreferences(prefsName(target), Context.MODE_PRIVATE)
        if (destination.all.isEmpty()) {
            val editor = destination.edit()
            for ((key, value) in all) {
                if (key == "target") continue
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.commit()
        }
        legacy.edit().clear().putString(MIGRATED_KEY, target.name).commit()
    }

    fun load(context: Context, target: BatteryCardTarget): BatteryCardConfig {
        val p = prefs(context, target)
        fun <T : Enum<T>> enum(key: String, values: Array<T>, default: T) =
            values.firstOrNull { it.name == p.getString(key, null) } ?: default
        val fields = p.getString("fields", null)?.split(',')?.toSet()
            ?.let { names -> BydBatteryField.entries.filter { it.name in names } }
        return BatteryCardConfig(
            visible = p.getBoolean("visible", false),
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

    fun save(context: Context, target: BatteryCardTarget, config: BatteryCardConfig) {
        prefs(context, target).edit()
            .putBoolean("visible", config.visible)
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

    fun update(context: Context, target: BatteryCardTarget, change: (BatteryCardConfig) -> BatteryCardConfig) =
        save(context, target, change(load(context, target)))
}

/**
 * The battery cards: floating windows on the centre screen (draggable) and on the dashboard (placed
 * from Settings, not touchable), each shown or hidden by itself. They do not depend on a CarPlay
 * session; they share one battery reading through [BydVehicleMonitor], made only while a card is
 * shown. Needs "display over other apps". Main thread.
 */
internal object BatteryCard {
    private const val TAG = "DiPlay-BatteryCard"
    private const val PREFERRED_CLUSTER_LAYER = "bosdisplay2"
    private const val NIGHT_RECHECK_MILLIS = 5_000L

    private var app: Context? = null
    private var observing = false
    private val handler = Handler(Looper.getMainLooper())
    private var latestUpdate: BydVehicleMonitor.BatteryUpdate? = null
    private val battery = BydVehicleMonitor.BatteryListener { update ->
        latestUpdate = update
        cards.values.forEach { it.view?.update = update }
    }
    private val nightChanged: () -> Unit = { cards.values.forEach { it.applyTheme() } }
    private var following = false
    private val cards = BatteryCardTarget.entries.associateWith { Card(it) }

    private class Card(val target: BatteryCardTarget) {
        var view: BatteryCardView? = null
        var windows: WindowManager? = null
        var params: WindowManager.LayoutParams? = null
        var displayId: Int? = null
        /** The last day/night the head unit reported, kept while no input reports one. */
        var lastNight = true
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> app?.let { refresh(it, target) } }
        /** Some inputs (a Configuration) change without a settings change, so "follow" also rechecks. */
        val recheck = object : Runnable {
            override fun run() {
                applyTheme()
                if (view != null) handler.postDelayed(this, NIGHT_RECHECK_MILLIS)
            }
        }

        /** Day or night colours from the card's theme. */
        fun applyTheme() {
            val card = view ?: return
            val context = app ?: return
            card.night = when (card.config.theme) {
                BatteryCardTheme.DAY -> false
                BatteryCardTheme.NIGHT -> true
                BatteryCardTheme.FOLLOW -> (HeadUnitNightMode.read(context) ?: lastNight).also { lastNight = it }
            }
        }
    }

    fun shown(target: BatteryCardTarget): Boolean = cards.getValue(target).view != null

    fun permitted(context: Context) = Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(context)

    /** App start: brings back the cards that were left visible. */
    fun restore(context: Context) {
        observe(context)
        BatteryCardTarget.entries.forEach { refresh(context, it) }
    }

    /** For a shortcut or the Settings switch; false when the card could not be drawn. */
    fun setVisible(context: Context, target: BatteryCardTarget, visible: Boolean): Boolean {
        observe(context)
        BatteryCardSettings.update(context, target) { it.copy(visible = visible) }
        if (!visible) { hide(target); return true }
        return show(context, target)
    }

    private fun observe(context: Context) {
        app = context.applicationContext
        if (observing) return
        observing = true
        cards.values.forEach { BatteryCardSettings.prefs(context, it.target).registerOnSharedPreferenceChangeListener(it.changed) }
    }

    private fun refresh(context: Context, target: BatteryCardTarget) {
        if (BatteryCardSettings.load(context, target).visible) show(context, target) else hide(target)
    }

    private fun show(context: Context, target: BatteryCardTarget): Boolean {
        val app = context.applicationContext
        this.app = app
        val card = cards.getValue(target)
        val config = BatteryCardSettings.load(app, target)
        if (!permitted(app)) {
            Log.i(TAG, "overlay permission missing")
            hide(target)
            return false
        }
        val display = display(app, target)
        if (display == null) {
            Log.i(TAG, "no $target display")
            hide(target)
            return false
        }
        val current = card.view
        if (current != null && card.displayId == display.displayId) {
            current.config = config
            card.applyTheme()
            relayout(card)
            return true
        }
        hide(target)
        return try {
            val windowContext = CustomClusterWindows.overlayContext(app, display)
            val manager = windowContext.getSystemService(WindowManager::class.java) ?: return false
            val view = BatteryCardView(windowContext, BatteryCardText.of(context)).also {
                it.config = config
                it.update = latestUpdate
            }
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (target == BatteryCardTarget.CLUSTER) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val layout = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "DiPlay battery card ${target.name.lowercase()}"
            }
            place(target, view, manager, layout, config)
            if (target == BatteryCardTarget.CENTER) drag(view, manager, layout)
            manager.addView(view, layout)
            card.view = view; card.windows = manager; card.params = layout; card.displayId = display.displayId
            follow(app)
            handler.removeCallbacks(card.recheck)
            card.recheck.run()
            Log.i(TAG, "$target shown on display ${display.displayId} ${layout.width}x${layout.height}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "$target card failed: ${CustomClusterWindows.failureSummary(error)}")
            hide(target)
            false
        }
    }

    fun hide(target: BatteryCardTarget) {
        val card = cards.getValue(target)
        handler.removeCallbacks(card.recheck)
        val view = card.view
        if (view != null) {
            runCatching { card.windows?.removeViewImmediate(view) }
            card.view = null; card.windows = null; card.params = null; card.displayId = null
            Log.i(TAG, "$target hidden")
        }
        follow(app)
    }

    /** One battery reading and one day/night observer while any card is shown. */
    private fun follow(context: Context?) {
        val wanted = cards.values.any { it.view != null }
        if (wanted == following) return
        if (wanted) {
            val app = context ?: return
            following = true
            BydVehicleMonitor.addBatteryListener(app, battery)
            HeadUnitNightMode.install(app)
            HeadUnitNightMode.addListener(nightChanged)
        } else {
            following = false
            BydVehicleMonitor.removeBatteryListener(battery)
            HeadUnitNightMode.removeListener(nightChanged)
            latestUpdate = null
        }
    }

    private fun relayout(card: Card) {
        val view = card.view ?: return
        val manager = card.windows ?: return
        val layout = card.params ?: return
        place(card.target, view, manager, layout, view.config)
        runCatching { manager.updateViewLayout(view, layout) }
    }

    private fun bounds(manager: WindowManager, card: BatteryCardView): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else card.context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun place(target: BatteryCardTarget, card: BatteryCardView, manager: WindowManager,
        layout: WindowManager.LayoutParams, config: BatteryCardConfig) {
        val (width, height) = card.contentSize()
        val (screenWidth, screenHeight) = bounds(manager, card)
        val density = card.context.resources.displayMetrics.density
        layout.width = width
        layout.height = height
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        if (target == BatteryCardTarget.CLUSTER) {
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
                    BatteryCardSettings.update(card.context, BatteryCardTarget.CENTER) { it.copy(centerX = layout.x, centerY = layout.y) }
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

    fun diagnostics(context: Context): List<String> = BatteryCardTarget.entries.map { target ->
        val config = BatteryCardSettings.load(context, target)
        val card = cards.getValue(target)
        "Battery card $target: visible=${config.visible} shown=${card.view != null} display=${card.displayId} " +
            "style=${config.style} theme=${config.theme} night=${card.view?.night} fields=${config.fields.size} " +
            "permitted=${permitted(context)}"
    } + "Battery card legacy actions -> ${BatteryCardSettings.legacyTarget(context)}"
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

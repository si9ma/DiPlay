package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.doOnLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydVehicleEvent
import com.shilapi.xcertplay.settings.SettingsControlMetrics
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** A local draft and one themed window: subpages never stack dialogs or persist partial edits. */
internal object ShortcutEditor {
    private const val BLOCK_GAP_DP = SettingsControlMetrics.BLOCK_GAP_DP
    private const val CONTROL_GAP_DP = SettingsControlMetrics.GAP_DP
    private const val CONTROL_HEIGHT_DP = SettingsControlMetrics.HEIGHT_DP
    /** Chips share one compact height; two text lines still fit, so no locale clips. */
    private const val COMPACT_CONTROL_HEIGHT_DP = 48
    /** Trigger chips are the smallest controls in the editor; each carries one short label. */
    private const val TRIGGER_CHIP_HEIGHT_DP = 40
    /** Accent outline of the step tools row, set apart from the neutral step buttons. */
    private const val TOOL_OUTLINE_DP = 2
    /** Drag handle width; its height matches the step button it sits beside. */
    private const val HANDLE_WIDTH_DP = 40
    private const val OUTLINE_DP = SettingsControlMetrics.OUTLINE_DP
    private const val FOCUS_OUTLINE_DP = SettingsControlMetrics.FOCUS_OUTLINE_DP
    private const val PAGE_MAX_WIDTH_DP = 760
    private const val HEIGHT_WATCH_MILLIS = 250L
    /** Tests replace the room the editor may use, as a keyboard opening and closing would change it. */
    @Volatile internal var testAvailableHeight: (() -> Int)? = null
    private const val NAME_MAX_LENGTH = 64
    private val labels = mapOf(
        ShortcutAction.WAIT to R.string.shortcut_wait, ShortcutAction.URI to R.string.shortcut_uri,
        ShortcutAction.PLAY to R.string.shortcut_play, ShortcutAction.PAUSE to R.string.shortcut_pause,
        ShortcutAction.TOGGLE to R.string.shortcut_toggle, ShortcutAction.NEXT to R.string.shortcut_next,
        ShortcutAction.PREVIOUS to R.string.shortcut_previous, ShortcutAction.SIRI to R.string.shortcut_siri,
        ShortcutAction.SELECT to R.string.shortcut_select, ShortcutAction.BACK to R.string.shortcut_back,
        ShortcutAction.LEFT to R.string.shortcut_left, ShortcutAction.RIGHT to R.string.shortcut_right,
        ShortcutAction.HOME to R.string.shortcut_home, ShortcutAction.KEY_PRESS to R.string.shortcut_key_unmapped,
        ShortcutAction.TAP to R.string.shortcut_tap,
        ShortcutAction.PINCH_IN to R.string.settings_shortcut_pinch_in,
        ShortcutAction.PINCH_OUT to R.string.settings_shortcut_pinch_out,
        ShortcutAction.ORIGINAL_CLICK to R.string.shortcut_original_click,
        ShortcutAction.SHOW_WINDOW to R.string.shortcut_legacy_window,
        ShortcutAction.HIDE_WINDOW to R.string.shortcut_legacy_window,
        ShortcutAction.SHOW_CLUSTER_MUSIC to R.string.shortcut_show_cluster_music,
        ShortcutAction.HIDE_CLUSTER_MUSIC to R.string.shortcut_hide_cluster_music,
        ShortcutAction.SHOW_CLUSTER_MAP to R.string.shortcut_show_cluster_map,
        ShortcutAction.HIDE_CLUSTER_MAP to R.string.shortcut_hide_cluster_map,
        ShortcutAction.SHOW_CENTER_BATTERY_CARD to R.string.shortcut_show_center_battery_card,
        ShortcutAction.HIDE_CENTER_BATTERY_CARD to R.string.shortcut_hide_center_battery_card,
        ShortcutAction.SHOW_CLUSTER_BATTERY_CARD to R.string.shortcut_show_cluster_battery_card,
        ShortcutAction.HIDE_CLUSTER_BATTERY_CARD to R.string.shortcut_hide_cluster_battery_card,
        ShortcutAction.CYCLE_CARMETER_CONTENT to R.string.shortcut_cycle_carmeter_content,
    )
    private val vehicleLabels = mapOf(
        BydVehicleEvent.LEFT_CARD_SHOWN to R.string.shortcut_vehicle_left_card_shown,
        BydVehicleEvent.LEFT_CARD_HIDDEN to R.string.shortcut_vehicle_left_card_hidden,
        BydVehicleEvent.RIGHT_CARD_SHOWN to R.string.shortcut_vehicle_right_card_shown,
        BydVehicleEvent.RIGHT_MAP_CARD_SHOWN to R.string.shortcut_vehicle_right_map_card_shown,
        BydVehicleEvent.RIGHT_CARD_HIDDEN to R.string.shortcut_vehicle_right_card_hidden,
        BydVehicleEvent.CARPLAY_NAVIGATION_STARTED to R.string.shortcut_vehicle_carplay_navigation_started,
        BydVehicleEvent.CARPLAY_NAVIGATION_ENDED to R.string.shortcut_vehicle_carplay_navigation_ended,
        BydVehicleEvent.GEAR_P to R.string.shortcut_vehicle_gear_p,
        BydVehicleEvent.GEAR_R to R.string.shortcut_vehicle_gear_r,
        BydVehicleEvent.GEAR_D to R.string.shortcut_vehicle_gear_d,
        BydVehicleEvent.CHARGING_STARTED to R.string.shortcut_vehicle_charging_started,
        BydVehicleEvent.CHARGING_ENDED to R.string.shortcut_vehicle_charging_ended,
    )
    private val conditionLabels = mapOf(
        ShortcutConditionKey.GEAR to R.string.shortcut_condition_gear,
        ShortcutConditionKey.CHARGING to R.string.shortcut_condition_charging,
        ShortcutConditionKey.LEFT_CARD to R.string.shortcut_condition_left_card,
        ShortcutConditionKey.RIGHT_CARD to R.string.shortcut_condition_right_card,
        ShortcutConditionKey.CARPLAY_NAVIGATING to R.string.shortcut_condition_carplay_navigating,
        ShortcutConditionKey.CLUSTER_MUSIC_WINDOW to R.string.shortcut_condition_cluster_music,
        ShortcutConditionKey.CLUSTER_MAP_WINDOW to R.string.shortcut_condition_cluster_map,
        ShortcutConditionKey.CENTER_BATTERY_CARD to R.string.shortcut_condition_center_battery,
        ShortcutConditionKey.CLUSTER_BATTERY_CARD to R.string.shortcut_condition_cluster_battery,
        ShortcutConditionKey.NIGHT to R.string.shortcut_condition_night,
        ShortcutConditionKey.MEDIA_PLAYING to R.string.shortcut_condition_media_playing,
    )
    private fun valueLabel(activity: Activity, key: ShortcutConditionKey, value: String): String = when (value) {
        "P", "R", "N", "D" -> value
        "SHOWN" -> activity.getString(R.string.shortcut_value_shown)
        "HIDDEN" -> activity.getString(if (key == ShortcutConditionKey.RIGHT_CARD) R.string.shortcut_value_none else R.string.shortcut_value_hidden)
        "YES" -> activity.getString(R.string.shortcut_value_yes)
        "NO" -> activity.getString(R.string.shortcut_value_no)
        "CARD" -> activity.getString(R.string.shortcut_value_card)
        "MAP_TURN" -> activity.getString(R.string.shortcut_value_map_turn)
        "MAP_MINI" -> activity.getString(R.string.shortcut_value_map_mini)
        "CHARGING" -> activity.getString(R.string.shortcut_value_charging)
        "PAUSED" -> activity.getString(R.string.shortcut_value_paused)
        "IDLE" -> activity.getString(R.string.shortcut_value_idle)
        "DAY" -> activity.getString(R.string.shortcut_value_day)
        "NIGHT" -> activity.getString(R.string.shortcut_value_night)
        else -> value
    }
    private fun conditionSummary(activity: Activity, condition: ShortcutCondition): String =
        "${activity.getString(conditionLabels.getValue(condition.key))}: " +
            condition.key.values.filter { it in condition.values }.joinToString(" / ") { valueLabel(activity, condition.key, it) }
    private val selectableActions get() = ShortcutAction.entries.filterNot {
        it.legacyWindow || it == ShortcutAction.KEY_PRESS || it == ShortcutAction.TAP
    }
    private fun live(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
    private fun message(activity: Activity, resource: Int) {
        if (live(activity)) Toast.makeText(activity, resource, Toast.LENGTH_LONG).show()
    }
    private fun message(activity: Activity, text: String) {
        if (live(activity)) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
    private fun clipboard(activity: Activity, text: String) {
        activity.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("DiPlay shortcuts", text))
    }
    private fun clipboardText(activity: Activity): String? =
        activity.getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()
    private fun keyCodes(activity: Activity, code: Int, scan: Int): String = BidiFormatter.getInstance(
        activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        .unicodeWrap(activity.getString(R.string.shortcut_key_codes, code, scan), TextDirectionHeuristicsCompat.LTR).orEmpty()

    private fun clickLabel(activity: Activity, count: Int) = activity.getString(when (count) {
        2 -> R.string.shortcut_click_double
        3 -> R.string.shortcut_click_triple
        4 -> R.string.shortcut_click_quadruple
        else -> R.string.shortcut_click_single
    })

    private fun trigger(activity: Activity, rule: ShortcutRule) = when (rule.event) {
        ShortcutEvent.CONNECTED -> activity.getString(R.string.shortcut_connected)
        ShortcutEvent.VEHICLE -> "${activity.getString(R.string.shortcut_vehicle_event)} · " +
            (rule.vehicleEvent?.let { activity.getString(vehicleLabels.getValue(it)) } ?: "")
        ShortcutEvent.KEY -> "${activity.getString(R.string.shortcut_key)} · ${keyCodes(activity, rule.keyCode, rule.scanCode)} · ${clickLabel(activity, rule.clickCount)}"
    }

    private fun summary(activity: Activity, step: ShortcutStep): String = buildString {
        append(activity.getString(labels.getValue(step.action)))
        when (step.action) {
            ShortcutAction.WAIT -> append(" · ${step.waitMs} ms")
            ShortcutAction.URI -> append(" · ${step.url.substringBefore(':')}:")
            ShortcutAction.PINCH_IN, ShortcutAction.PINCH_OUT -> {
                append(" · " + activity.getString(R.string.settings_shortcut_pinch_center,
                    Math.round(step.zoomCenterX * 100).toInt(), Math.round(step.zoomCenterY * 100).toInt()))
                append(" · " + activity.getString(R.string.settings_shortcut_pinch_scale_value, step.pinchScale))
            }
            ShortcutAction.TAP -> {
                if (step.tapX in 0.0..1.0 && step.tapY in 0.0..1.0) {
                    append(" · " + activity.getString(R.string.shortcut_tap_point,
                        Math.round(step.tapX * 100).toInt(), Math.round(step.tapY * 100).toInt()))
                }
            }
            else -> Unit
        }
        if (step.keyCode >= 0 && step.scanCode >= 0) {
            append('\n'); append(keyCodes(activity, step.keyCode, step.scanCode))
        }
    }

    /** The category owns the list, rather than a second dialog behind the editor. */
    fun renderList(activity: Activity, parent: LinearLayout) {
        val ui = Ui(activity)
        val list = ui.column().apply { tag = "shortcut_rule_list" }
        val frame = FrameLayout(ui.context).apply {
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        parent.addView(frame, LinearLayout.LayoutParams(-1, -2))
        fun refresh() {
            list.removeAllViews()
            ui.add(list, ui.text(activity.getString(R.string.shortcut_list_hint), secondary = true))
            val rules = CarPlayShortcuts.load(activity)
            if (rules.isEmpty()) ui.add(list, ui.text(activity.getString(R.string.shortcut_empty)).apply { tag = "shortcut_empty" })
            rules.forEach { rule ->
                val row = ui.column().apply {
                    tag = "shortcut_rule_${rule.id}"
                    setPadding(ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP))
                    background = RippleDrawable(ColorStateList.valueOf(ui.theme.ripple), ui.shape(ui.palette.button), null)
                    isFocusable = true
                    foreground = ui.focusRing()
                    contentDescription = listOf(rule.name, trigger(activity, rule),
                        activity.getString(if (rule.enabled) R.string.shortcut_enabled else R.string.shortcut_disabled),
                        activity.getString(R.string.shortcut_steps_count, rule.steps.size),
                        activity.getString(R.string.shortcut_edit)).joinToString(". ")
                    setOnClickListener { show(activity, rule, ::refresh) }
                }
                ui.add(row, ui.text(rule.name, heading = true).apply {
                    compoundDrawablePadding = ui.dp(CONTROL_GAP_DP)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                        ui.context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(ui.theme.accent) }, null)
                })
                ui.add(row, ui.text(trigger(activity, rule), secondary = true))
                if (rule.conditions.isNotEmpty()) ui.add(row, ui.text(rule.conditions.joinToString("\n") { conditionSummary(activity, it) }, secondary = true))
                ui.add(row, ui.text("${activity.getString(if (rule.enabled) R.string.shortcut_enabled else R.string.shortcut_disabled)} · ${activity.getString(R.string.shortcut_steps_count, rule.steps.size)}", secondary = true))
                ui.add(list, row, BLOCK_GAP_DP)
            }
            ui.add(list, ui.button(R.string.shortcut_new, "shortcut_new", primary = true) {
                show(activity, ShortcutRule(), ::refresh)
            })
            val transfer = ui.row()
            ui.addWeighted(transfer, ui.button(R.string.shortcut_export, "shortcut_export") { export(activity, ::refresh) }, CONTROL_HEIGHT_DP)
            ui.addWeighted(transfer, ui.button(R.string.shortcut_import, "shortcut_import") { import(activity, ::refresh) }, CONTROL_HEIGHT_DP)
            ui.add(list, transfer)
            ui.add(list, rightCardWaitButton(activity, ui))
            ui.add(list, ui.text(activity.getString(R.string.shortcut_right_card_wait_hint), secondary = true))
            ui.add(list, ui.button(R.string.shortcut_stop, "shortcut_stop_all") {
                CarPlayShortcuts.stop(); CarPlayShortcuts.cancelCapture()
            })
        }
        refresh()
    }

    /** Copies the chosen rules to the clipboard as versioned JSON, so another DiPlay can import them. */
    private fun export(activity: Activity, refresh: () -> Unit) {
        val rules = CarPlayShortcuts.load(activity)
        if (rules.isEmpty()) { message(activity, R.string.shortcut_empty); return }
        val checked = BooleanArray(rules.size)
        activity.appDialogBuilder()
            .setTitle(R.string.shortcut_export)
            .setMultiChoiceItems(rules.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.shortcut_export) { _, _ ->
                val selected = rules.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) { message(activity, R.string.shortcut_invalid); return@setPositiveButton }
                val json = ShortcutCodec.encode(selected)
                if (json == null) message(activity, R.string.shortcut_invalid)
                else { clipboard(activity, json); message(activity, R.string.shortcut_exported) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Merges rules read from the clipboard, keeping existing ids untouched. */
    private fun import(activity: Activity, refresh: () -> Unit) {
        val text = clipboardText(activity)
        if (text.isNullOrBlank()) { message(activity, R.string.shortcut_import_empty); return }
        val imported = runCatching { ShortcutCodec.decode(text, BatteryCardSettings.legacyTarget(activity)) }
            .getOrDefault(emptyList())
        if (imported.isEmpty()) { message(activity, R.string.shortcut_import_invalid); return }
        val existing = CarPlayShortcuts.load(activity).toMutableList()
        var added = 0
        for (rule in imported) {
            if (existing.none { it.id == rule.id }) { existing.add(rule); added++ }
        }
        if (added == 0) { message(activity, R.string.shortcut_import_none); return }
        if (CarPlayShortcuts.save(activity, existing)) {
            message(activity, activity.getString(R.string.shortcut_imported, added))
            refresh()
        } else message(activity, R.string.shortcut_invalid)
    }

    private fun waitLabel(activity: Activity, millis: Long) =
        if (millis == 0L) activity.getString(R.string.shortcut_right_card_wait_none)
        else activity.getString(R.string.shortcut_right_card_wait_ms, millis)

    /** The one vehicle setting shared by all rules: how long a plain right card waits for a late map prompt. */
    private fun rightCardWaitButton(activity: Activity, ui: Ui): View {
        val title = activity.getString(R.string.shortcut_right_card_wait)
        fun text() = "$title · ${waitLabel(activity, CarPlayShortcuts.rightCardWait(activity))}"
        val button = ui.button(text(), "shortcut_right_card_wait") {}
        button.setOnClickListener {
            val choices = CarPlayShortcuts.RIGHT_CARD_WAITS
            activity.appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(choices.map { waitLabel(activity, it) }.toTypedArray(),
                    choices.indexOf(CarPlayShortcuts.rightCardWait(activity))) { dialog, index ->
                    CarPlayShortcuts.setRightCardWait(activity, choices[index])
                    button.text = text()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        return button
    }

    fun show(activity: Activity, draft: ShortcutRule = ShortcutRule(), onSaved: () -> Unit = {}) {
        if (live(activity)) Editor(activity, draft, onSaved).show()
    }

    const val RECORD_TOKEN = "shortcut_record_token"
    private data class Transfer(val token: String, val draft: ShortcutRule,
        val session: CarPlayShortcuts.TouchSession, val createdAt: Long,
        var started: Boolean = false, var completed: Boolean = false, var steps: List<ShortcutStep>? = null)
    private var transfer: Transfer? = null
    private fun pending(): Transfer? {
        val value = transfer ?: return null
        if (android.os.SystemClock.uptimeMillis() - value.createdAt > 300_000) {
            transfer = null
            return null
        }
        return value
    }
    private fun prepareTouchRecording(draft: ShortcutRule): String? {
        if (pending() != null || CarPlayShortcuts.hasCapture()) return null
        val session = CarPlayShortcuts.touchSession() ?: return null
        if (draft.name.length > NAME_MAX_LENGTH || draft.steps.size > ShortcutCodec.MAX_STEPS) return null
        return java.util.UUID.randomUUID().toString().also {
            transfer = Transfer(it, draft.copy(steps = draft.steps.toList()), session, android.os.SystemClock.uptimeMillis())
        }
    }
    fun startTouchRecording(token: String, finished: () -> Unit): Boolean {
        val value = pending()?.takeIf { it.token == token && !it.started && !it.completed } ?: return false
        value.started = true
        val started = CarPlayShortcuts.startTouchRecording(value.session, { steps ->
            if (transfer === value && !value.completed) { value.steps = steps.toList(); value.completed = true; finished() }
        }, {
            if (transfer === value && !value.completed) { value.steps = null; value.completed = true; finished() }
        })
        if (!started) value.completed = true
        return started
    }
    fun cancelTouchTransfer(token: String) {
        val value = pending()?.takeIf { it.token == token } ?: return
        value.steps = null; value.completed = true
        if (CarPlayShortcuts.isTouchRecording()) CarPlayShortcuts.cancelCapture()
    }
    fun hasTouchTransfer() = pending() != null
    fun resumeTouchDraft(activity: Activity, onSaved: () -> Unit): Boolean {
        val value = pending() ?: return false
        if (!value.completed) {
            value.completed = true
            CarPlayShortcuts.cancelCapture()
        }
        transfer = null
        if (live(activity)) Editor(activity, value.draft, onSaved).apply {
            show()
            value.steps?.let(::reviewRecording)
        }
        return true
    }

    private class Ui(val activity: Activity) {
        val context = activity.appDialogContext()
        val palette = DiPlayPalette.of((activity as? AppAppearanceOwner)?.currentAppNight ?: activity.resolveAppNightNow())
        val theme = SettingsTheme.card(palette)
        fun dp(value: Int) = theme.dp(context, value)
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        /** Equal-width children of one height, so a row of chips never mixes sizes. */
        fun addWeighted(parent: LinearLayout, child: View, height: Int) {
            parent.addView(child, LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                marginStart = if (parent.childCount == 0) 0 else dp(CONTROL_GAP_DP)
            })
        }
        fun add(parent: LinearLayout, child: View, gap: Int = CONTROL_GAP_DP) {
            parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
        }
        fun shape(color: Int, height: Int = dp(CONTROL_HEIGHT_DP), strokeColor: Int = palette.outline,
            strokeWidth: Int = dp(OUTLINE_DP)) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = SettingsControlMetrics.radius(height, context.resources.displayMetrics.density)
            setStroke(strokeWidth, strokeColor)
        }
        fun focusRing(height: Int = dp(CONTROL_HEIGHT_DP)) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.TRANSPARENT, height).apply {
                setStroke(dp(FOCUS_OUTLINE_DP), theme.focusRing)
            })
        }
        fun text(value: String, secondary: Boolean = false, heading: Boolean = false) = TextView(context).apply {
            text = value
            textSize = if (heading) 20f else if (secondary) 14f else 17f
            setTextColor(if (secondary) theme.textSecondary else theme.textPrimary)
            typeface = Typeface.create(if (heading) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        fun button(resource: Int, tag: String, primary: Boolean = false, click: () -> Unit) =
            button(activity.getString(resource), tag, primary, click)
        fun button(title: String, tag: String, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
            text = title; this.tag = tag; isAllCaps = false; textSize = 17f
            setTextColor(if (primary) theme.buttonText else theme.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(CONTROL_HEIGHT_DP); minimumWidth = 0
            setPaddingRelative(dp(BLOCK_GAP_DP), 0, dp(BLOCK_GAP_DP), 0)
            stateListAnimator = null
            fun style(height: Int) {
                background = RippleDrawable(ColorStateList.valueOf(theme.ripple), shape(if (primary) theme.accent else palette.button, height), null)
                foreground = focusRing(height)
            }
            style(dp(CONTROL_HEIGHT_DP))
            doOnLayout { style(height) }
            setOnClickListener { click() }
        }
        private fun checkIcon(tint: Int) =
            context.getDrawable(R.drawable.ic_dp_check)?.mutate()?.apply { setTint(tint) }
        /** One row of a choice list: compact, two text lines still fit, [selected] marks the current choice. */
        fun listChip(title: String, tag: String, selected: Boolean = false, heightDp: Int = COMPACT_CONTROL_HEIGHT_DP,
            click: () -> Unit) = Button(context).apply {
            text = title; this.tag = tag; isAllCaps = false; textSize = 14f
            setTextColor(if (selected) theme.buttonText else theme.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(heightDp); minimumWidth = 0
            setPaddingRelative(dp(CONTROL_GAP_DP), 0, dp(CONTROL_GAP_DP), 0)
            stateListAnimator = null
            isSelected = selected
            fun style(height: Int) {
                background = RippleDrawable(ColorStateList.valueOf(theme.ripple),
                    shape(if (selected) theme.accent else palette.surface, height), null)
                foreground = focusRing(height)
                compoundDrawablePadding = dp(CONTROL_GAP_DP) / 2
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (selected) checkIcon(theme.buttonText) else null, null, null, null)
            }
            style(dp(heightDp))
            doOnLayout { style(height) }
            setOnClickListener { click() }
        }
        fun listChip(resource: Int, tag: String, selected: Boolean = false, heightDp: Int = COMPACT_CONTROL_HEIGHT_DP,
            click: () -> Unit) = listChip(activity.getString(resource), tag, selected, heightDp, click)
        /** Accent-outlined compact button for the step tools row, kept apart from the step buttons. */
        fun tool(resource: Int, tag: String, click: () -> Unit) = Button(context).apply {
            text = activity.getString(resource); this.tag = tag; isAllCaps = false; textSize = 14f
            setTextColor(theme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(COMPACT_CONTROL_HEIGHT_DP); minimumWidth = 0
            setPaddingRelative(dp(CONTROL_GAP_DP), 0, dp(CONTROL_GAP_DP), 0)
            stateListAnimator = null
            fun style(height: Int) {
                background = RippleDrawable(ColorStateList.valueOf(theme.ripple),
                    shape(palette.surface, height, theme.accent, dp(TOOL_OUTLINE_DP)), null)
                foreground = focusRing(height)
            }
            style(dp(COMPACT_CONTROL_HEIGHT_DP))
            doOnLayout { style(height) }
            setOnClickListener { click() }
        }
        /** Drag handle of one step row; touching it picks the step up at once. */
        fun dragHandle() = TextView(context).apply {
            tag = "shortcut_step_handle"
            contentDescription = activity.getString(R.string.shortcut_drag_handle)
            isClickable = true; isFocusable = true
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(theme.ripple), null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = SettingsControlMetrics.radius(dp(CONTROL_HEIGHT_DP), context.resources.displayMetrics.density)
                })
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                context.getDrawable(R.drawable.ic_dp_drag)?.mutate()?.apply { setTint(theme.textSecondary) }, null, null, null)
            compoundDrawablePadding = 0
        }
        fun input(value: String, resource: Int, tag: String, numeric: Boolean = false, max: Int = NAME_MAX_LENGTH) = EditText(context).apply {
            this.tag = tag; hint = activity.getString(resource); contentDescription = hint
            textSize = 18f; setTextColor(theme.textPrimary); setHintTextColor(theme.textSecondary)
            backgroundTintList = ColorStateList.valueOf(theme.accent)
            minHeight = dp(CONTROL_HEIGHT_DP)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(max)); setText(value)
        }
    }

    /** Wraps its pages up to [maxHeight]: only the scroll area shrinks, so the footer stays on screen. */
    private class BoundedPage(context: android.content.Context, private val maxHeight: () -> Int) : LinearLayout(context) {
        var scroll: BoundedScroll? = null
        /** The limit the last measure used; a different [maxHeight] now means the page must measure again. */
        var measuredLimit = -1
            private set
        init { orientation = VERTICAL }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = maxHeight().coerceAtLeast(0)
            measuredLimit = limit
            val cap = if (mode == MeasureSpec.UNSPECIFIED) limit else minOf(size, limit)
            val scroll = scroll
            if (scroll != null) {
                var fixed = paddingTop + paddingBottom
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    val params = child.layoutParams as MarginLayoutParams
                    if (child !== scroll && child.visibility != GONE) {
                        measureChildWithMargins(child, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
                        fixed += child.measuredHeight
                    }
                    fixed += params.topMargin + params.bottomMargin
                }
                scroll.maxHeight = (cap - fixed).coerceAtLeast(0)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, MeasureSpec.makeMeasureSpec(cap,
                if (mode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)))
        }
    }

    private class BoundedScroll(context: android.content.Context) : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE
            set(value) {
                if (field != value) { field = value; forceLayout() }
            }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val bounded = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.AT_MOST))
        }
    }

    private class Editor(val activity: Activity, initial: ShortcutRule, val onSaved: () -> Unit) {
        private val ui = Ui(activity)
        private var draft = initial.copy(steps = initial.steps.toList())
        private val existing = CarPlayShortcuts.load(activity).any { it.id == initial.id }
        private val root = BoundedPage(ui.context, ::availableHeight).apply {
            tag = "shortcut_editor"
            layoutDirection = activity.resources.configuration.layoutDirection
            setPadding(ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP))
            setBackgroundColor(ui.palette.background)
        }
        private val title = ui.text(activity.getString(R.string.shortcut_edit), heading = true)
        private val body = ui.column()
        private val scroll = BoundedScroll(ui.context).apply { isFillViewport = true; addView(body) }
        private val footer = LinearLayout(ui.context).apply {
            tag = "shortcut_footer"; orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
        }
        private val dialog = activity.appDialogBuilder().setView(root).create()
        private var back: () -> Unit = { dialog.dismiss() }
        private var captureActive = false

        init {
            ui.add(root, title, BLOCK_GAP_DP)
            root.scroll = scroll
            root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
            root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(CONTROL_GAP_DP) })
            dialog.setOnDismissListener {
                root.removeCallbacks(heightWatch)
                if (captureActive) cancelCapture()
            }
            dialog.setOnKeyListener { _, code, event ->
                if (OriginalKeyReplayGuard.matches(event)) false
                else if (CarPlayShortcuts.consumeOwnedKey(event)) true
                else if (CarPlayShortcuts.onLearningKey(activity, event)) true
                else if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) back()
                    true
                } else false
            }
            dialog.setCanceledOnTouchOutside(false)
        }
        fun show() {
            editor()
            dialog.show()
            val decor = activity.window.decorView
            val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            dialog.window?.apply {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                setLayout(minOf(ui.dp(PAGE_MAX_WIDTH_DP), width), WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
            // A keyboard change reaches the page as new insets, often without a new measure.
            root.setOnApplyWindowInsetsListener { view, insets ->
                view.post(checkHeight)
                view.onApplyWindowInsets(insets)
            }
            root.removeCallbacks(heightWatch)
            root.postDelayed(heightWatch, HEIGHT_WATCH_MILLIS)
        }

        /**
         * The keyboard shrinks the dialog by resizing its window, which measures it again. Closing the
         * keyboard does not: the shorter wrap-content window still fits, so it is never resized. This
         * measures the page again whenever the room it was measured for has changed.
         */
        private val checkHeight = Runnable {
            if (dialog.isShowing && root.measuredLimit >= 0 && availableHeight() != root.measuredLimit) {
                root.requestLayout()
            }
        }
        /**
         * Some head units report neither insets nor a restored frame when the keyboard closes, so the
         * shrunken wrap-content window never grows again on its own. The watch then keeps measuring the
         * page against the host's full height: the window manager keeps clamping the dialog while the
         * keyboard is up and grants the height as soon as the keyboard is really gone.
         */
        private val heightWatch = object : Runnable {
            private var lastRoom = -1
            private var stableTicks = 0
            override fun run() {
                if (!dialog.isShowing) return
                checkHeight.run()
                val room = frameRoom()
                if (room != lastRoom) { lastRoom = room; stableTicks = 0; probeFullRoom = false }
                else if (++stableTicks >= 2 && room < fullRoom() - ui.dp(BLOCK_GAP_DP)) {
                    probeFullRoom = true
                    root.requestLayout()
                }
                root.postDelayed(this, HEIGHT_WATCH_MILLIS)
            }
        }
        /** While set, the page measures against the host height instead of a frame that may be stale. */
        private var probeFullRoom = false
        /**
         * Use the focused dialog's visible frame for IME changes, but the host's full height:
         * the dialog's own wrap-content height must never become the next page's height limit.
         */
        private fun availableHeight(): Int {
            testAvailableHeight?.let { return it() }
            return if (probeFullRoom) fullRoom() else frameRoom()
        }
        private fun frameRoom(): Int {
            val decor = activity.window.decorView
            val visibleDecor = if (dialog.isShowing) dialog.window?.decorView ?: decor else decor
            val visible = android.graphics.Rect().also(visibleDecor::getWindowVisibleDisplayFrame).height()
            val height = listOf(visible, decor.height).filter { it > 0 }.minOrNull()
                ?: activity.resources.displayMetrics.heightPixels
            return height - 2 * ui.dp(BLOCK_GAP_DP)
        }
        /** The room without any keyboard: the host window, which never resizes for this dialog's IME. */
        private fun fullRoom(): Int {
            val decor = activity.window.decorView
            val height = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            return height - 2 * ui.dp(BLOCK_GAP_DP)
        }
        private fun page(resource: Int, onBack: () -> Unit = { editor() }) {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
            title.setText(resource); body.removeAllViews(); footer.removeAllViews()
            back = onBack; scroll.scrollTo(0, 0)
            root.isFocusableInTouchMode = true; root.requestFocus()
        }
        private fun card(resource: Int): LinearLayout = ui.column().also {
            it.background = ui.shape(ui.palette.surface)
            it.setPadding(ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(BLOCK_GAP_DP), ui.dp(CONTROL_GAP_DP))
            ui.add(it, ui.text(activity.getString(resource), heading = true), BLOCK_GAP_DP)
            ui.add(body, it, BLOCK_GAP_DP)
        }
        /** Footer actions share one row and one height, so a page never needs more than one row of them. */
        private fun addFooter(button: View, index: Int = footer.childCount) {
            footer.addView(button, index, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            for (at in 0 until footer.childCount) {
                (footer.getChildAt(at).layoutParams as LinearLayout.LayoutParams).marginStart =
                    if (at == 0) 0 else ui.dp(CONTROL_GAP_DP)
            }
        }
        private fun cancelButton(action: () -> Unit = { editor() }) = addFooter(ui.button(android.R.string.cancel, "shortcut_cancel", click = action))
        private fun editor() {
            if (!live(activity)) return
            page(R.string.shortcut_edit) { dialog.dismiss() }
            val basic = card(R.string.shortcut_basic)
            val name = ui.input(draft.name, R.string.shortcut_name, "shortcut_name")
            name.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft = draft.copy(name = s.toString())
                    body.findViewWithTag<Button>("shortcut_test")?.apply {
                        isEnabled = draft.steps.none { it.action == ShortcutAction.ORIGINAL_CLICK } && ShortcutCodec.executable(draft)
                        alpha = if (isEnabled) 1f else 0.5f
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            ui.add(basic, name)
            val assigned = draft.steps.none { it.action == ShortcutAction.KEY_PRESS || it.action.legacyWindow }
            val enabled = SettingsWidgets.createSwitchRow(ui.context, activity.getString(R.string.shortcut_enabled),
                activity.getString(if (assigned) R.string.shortcut_enabled_hint else R.string.shortcut_key_unmapped),
                draft.enabled, ui.theme, enabled = assigned) { draft = draft.copy(enabled = it) }
            enabled.switch.tag = "shortcut_enabled"
            ui.add(basic, enabled.rowView)
            ui.add(basic, ui.text(activity.getString(R.string.shortcut_draft_hint), secondary = true))
            val event = card(R.string.shortcut_event)
            ui.add(event, ui.text(trigger(activity, draft)).apply { tag = "shortcut_trigger" })
            val triggers = ui.row()
            ui.addWeighted(triggers, ui.listChip(R.string.shortcut_connected, "shortcut_connected",
                draft.event == ShortcutEvent.CONNECTED, TRIGGER_CHIP_HEIGHT_DP) {
                draft = draft.copy(event = ShortcutEvent.CONNECTED, keyCode = 0, scanCode = 0, clickCount = 1, vehicleEvent = null); editor()
            }, TRIGGER_CHIP_HEIGHT_DP)
            ui.addWeighted(triggers, ui.listChip(R.string.shortcut_learn, "shortcut_learn",
                draft.event == ShortcutEvent.KEY, TRIGGER_CHIP_HEIGHT_DP) { learn() }, TRIGGER_CHIP_HEIGHT_DP)
            ui.addWeighted(triggers, ui.listChip(R.string.shortcut_vehicle_event, "shortcut_vehicle_event",
                draft.event == ShortcutEvent.VEHICLE, TRIGGER_CHIP_HEIGHT_DP) { chooseVehicleEvent() }, TRIGGER_CHIP_HEIGHT_DP)
            ui.add(event, triggers)
            if (draft.event == ShortcutEvent.VEHICLE) {
                ui.add(event, ui.text(activity.getString(R.string.shortcut_vehicle_hint), secondary = true))
            }
            if (draft.event == ShortcutEvent.KEY) {
                ui.add(event, ui.button("${activity.getString(R.string.shortcut_click_count)} · ${clickLabel(activity, draft.clickCount)}",
                    "shortcut_click_count") { chooseClickCount() })
                ui.add(event, ui.text(activity.getString(R.string.shortcut_click_hint), secondary = true))
            }
            val conditions = card(R.string.shortcut_conditions)
            ui.add(conditions, ui.text(activity.getString(R.string.shortcut_conditions_hint), secondary = true))
            if (draft.conditions.isEmpty()) ui.add(conditions, ui.text(activity.getString(R.string.shortcut_condition_none), secondary = true))
            draft.conditions.forEachIndexed { index, condition ->
                ui.add(conditions, ui.button(conditionSummary(activity, condition), "shortcut_condition_$index") {
                    chooseConditionValues(index, condition.key, condition.values)
                })
            }
            if (draft.conditions.size < ShortcutConditions.MAX_PER_RULE &&
                draft.conditions.size < ShortcutConditionKey.entries.size) {
                ui.add(conditions, ui.button(R.string.shortcut_add_condition, "shortcut_add_condition") { chooseConditionKey() })
            }
            val steps = card(R.string.shortcut_steps)
            if (draft.steps.isEmpty()) ui.add(steps, ui.text(activity.getString(R.string.shortcut_no_steps), secondary = true))
            else ui.add(steps, ui.text(activity.getString(R.string.shortcut_steps_hint), secondary = true))
            val stepRows = ArrayList<View>(draft.steps.size)
            draft.steps.forEachIndexed { index, step ->
                val row = ui.row().apply { tag = "shortcut_step_row_$index" }
                val handle = ui.dragHandle()
                val button = ui.button("${index + 1}. ${summary(activity, step)}", "shortcut_step_$index") { editStep(index) }
                row.addView(handle, LinearLayout.LayoutParams(ui.dp(HANDLE_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT))
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = ui.dp(CONTROL_GAP_DP)
                })
                stepRows.add(row)
                ui.add(steps, row)
                makeStepDraggable(row, handle, stepRows)
            }
            if (draft.steps.any { it.action in ShortcutAction.PLAY..ShortcutAction.PREVIOUS } &&
                !AirPlayPersistence.loadCarPlayOwnsMainAudio(activity)) {
                ui.add(steps, ui.text(activity.getString(R.string.shortcut_media_main_audio_hint,
                    activity.getString(R.string.settings_carplay_owns_main_audio)), secondary = true)
                    .apply { tag = "shortcut_media_hint" })
            }
            val tools = ui.row()
            ui.addWeighted(tools, ui.tool(R.string.shortcut_add_action, "shortcut_add_action") {
                if (draft.steps.size >= ShortcutCodec.MAX_STEPS || draft.steps.lastOrNull()?.action == ShortcutAction.SIRI)
                    message(activity, R.string.shortcut_invalid)
                else chooseAction(null)
            }, COMPACT_CONTROL_HEIGHT_DP)
            ui.addWeighted(tools, ui.tool(R.string.shortcut_record_taps, "shortcut_record_taps") {
                if (CarPlayShortcuts.touchSession() == null || CarPlayShortcuts.hasCapture() || hasTouchTransfer()) {
                    message(activity, R.string.shortcut_unavailable)
                } else confirm(R.string.shortcut_record_taps_hint) {
                    val token = prepareTouchRecording(draft)
                    if (token == null) message(activity, R.string.shortcut_unavailable)
                    else if (runCatching {
                        activity.startActivity(android.content.Intent(activity, CarPlayHostActivity::class.java)
                            .putExtra(RECORD_TOKEN, token).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }.isSuccess) {
                        dialog.dismiss()
                        if (ProjectionWindow.isPartial(activity)) activity.finish()
                    }
                    else { transfer = null; message(activity, R.string.shortcut_unavailable); editor() }
                }
            }, COMPACT_CONTROL_HEIGHT_DP)
            ui.addWeighted(tools, ui.tool(R.string.shortcut_record_keys, "shortcut_record") { record() }, COMPACT_CONTROL_HEIGHT_DP)
            ui.add(steps, tools)
            val replay = draft.steps.any { it.action == ShortcutAction.ORIGINAL_CLICK }
            val testing = card(R.string.shortcut_test)
            ui.add(testing, ui.text(activity.getString(if (replay) R.string.shortcut_original_click_hint else R.string.shortcut_confirm_run), secondary = true))
            val testRow = ui.row()
            ui.addWeighted(testRow, ui.button(R.string.shortcut_test, "shortcut_test") {
                if (!ShortcutCodec.executable(draft)) message(activity, R.string.shortcut_invalid)
                else confirm(R.string.shortcut_confirm_run) {
                    message(activity, if (CarPlayShortcuts.test(draft)) R.string.shortcut_queued else R.string.shortcut_unavailable); editor()
                }
            }.apply { isEnabled = !replay && ShortcutCodec.executable(draft); alpha = if (isEnabled) 1f else 0.5f }, CONTROL_HEIGHT_DP)
            ui.addWeighted(testRow, ui.button(R.string.shortcut_stop, "shortcut_stop") { CarPlayShortcuts.stop() }, CONTROL_HEIGHT_DP)
            ui.add(testing, testRow)
            if (existing) {
                val manage = ui.row()
                ui.addWeighted(manage, ui.button(R.string.shortcut_duplicate, "shortcut_duplicate") {
                    val copy = draft.copy(id = java.util.UUID.randomUUID().toString(),
                        name = "${draft.name.trim()} ${activity.getString(R.string.shortcut_duplicate_suffix)}")
                    val saved = CarPlayShortcuts.load(activity).toMutableList().apply { add(copy) }
                    if (CarPlayShortcuts.save(activity, saved)) {
                        message(activity, R.string.shortcut_duplicated)
                        finish()
                    } else message(activity, R.string.shortcut_invalid)
                }, CONTROL_HEIGHT_DP)
                ui.addWeighted(manage, ui.button(R.string.shortcut_delete, "shortcut_delete_rule") {
                    confirm(R.string.shortcut_delete_confirm) {
                        if (CarPlayShortcuts.save(activity, CarPlayShortcuts.load(activity).filterNot { it.id == draft.id })) finish()
                        else message(activity, R.string.shortcut_invalid)
                    }
                }.apply { setTextColor(ui.palette.danger) }, CONTROL_HEIGHT_DP)
                ui.add(body, manage)
            }
            addFooter(ui.button(R.string.shortcut_save, "shortcut_save", primary = true) { save() })
            cancelButton { dialog.dismiss() }
        }
        private fun chooseVehicleEvent() {
            page(R.string.shortcut_vehicle_event)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_vehicle_hint), secondary = true))
            BydVehicleEvent.entries.forEach { event ->
                val selected = draft.event == ShortcutEvent.VEHICLE && draft.vehicleEvent == event
                ui.add(body, ui.listChip(vehicleLabels.getValue(event), "shortcut_vehicle_${event.name}", selected) {
                    draft = draft.copy(event = ShortcutEvent.VEHICLE, vehicleEvent = event, keyCode = 0, scanCode = 0,
                        clickCount = 1, steps = draft.steps.filterNot { it.action == ShortcutAction.ORIGINAL_CLICK })
                    editor()
                })
            }
            cancelButton()
        }
        private fun chooseConditionKey() {
            page(R.string.shortcut_add_condition)
            val used = draft.conditions.map { it.key }.toSet()
            ShortcutConditionKey.entries.filter { it !in used }.forEach { key ->
                ui.add(body, ui.listChip(conditionLabels.getValue(key), "shortcut_condition_key_${key.name}") {
                    chooseConditionValues(null, key, emptySet())
                })
            }
            cancelButton()
        }
        /** Values of [key] the condition accepts; any one of them holding is enough. */
        private fun chooseConditionValues(index: Int?, key: ShortcutConditionKey, initial: Set<String>) {
            val chosen = LinkedHashSet(initial)
            fun render() {
                page(conditionLabels.getValue(key))
                ui.add(body, ui.text(activity.getString(R.string.shortcut_condition_values_hint), secondary = true))
                key.values.forEach { value ->
                    val selected = value in chosen
                    ui.add(body, ui.listChip(valueLabel(activity, key, value), "shortcut_condition_value_$value", selected) {
                        if (!chosen.remove(value)) chosen.add(value)
                        render()
                    })
                }
                if (index != null) ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_condition") {
                    draft = draft.copy(conditions = draft.conditions.filterIndexed { at, _ -> at != index }); editor()
                })
                addFooter(ui.button(android.R.string.ok, "shortcut_accept_condition", primary = true) {
                    if (chosen.isEmpty()) message(activity, R.string.shortcut_invalid)
                    else {
                        val condition = ShortcutCondition(key, chosen.toSet())
                        draft = draft.copy(conditions = draft.conditions.toMutableList().apply {
                            if (index == null) add(condition) else set(index, condition)
                        })
                        editor()
                    }
                })
                cancelButton()
            }
            render()
        }
        private fun chooseClickCount() {
            page(R.string.shortcut_click_count)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_click_hint), secondary = true))
            for (count in 1..4) {
                ui.add(body, ui.listChip(clickLabel(activity, count), "shortcut_click_$count", count == draft.clickCount) {
                    draft = draft.copy(clickCount = count); editor()
                })
            }
            cancelButton()
        }
        private fun finish() { dialog.dismiss(); if (live(activity)) onSaved() }
        private fun save() {
            draft = draft.copy(name = draft.name.trim())
            if (!ShortcutCodec.valid(draft)) { message(activity, R.string.shortcut_invalid); return }
            val saved = CarPlayShortcuts.load(activity).toMutableList()
            val index = saved.indexOfFirst { it.id == draft.id }
            if (index >= 0) saved[index] = draft else saved.add(draft)
            val accept = {
                if (CarPlayShortcuts.save(activity, saved)) finish() else message(activity, R.string.shortcut_invalid)
            }
            if (draft.enabled) confirm(R.string.shortcut_confirm_run, accept) else accept()
        }
        private fun confirm(resource: Int, accept: () -> Unit) {
            page(R.string.shortcut_title)
            ui.add(body, ui.text(activity.getString(resource)).apply { tag = "shortcut_confirmation" })
            addFooter(ui.button(android.R.string.ok, "shortcut_confirm", primary = true, click = accept))
            cancelButton()
        }
        /** Opens the editor for the action itself: every action edits in place, without a detour page. */
        private fun editStep(index: Int) {
            when (val action = draft.steps[index].action) {
                ShortcutAction.TAP -> tapCoordinates(index)
                ShortcutAction.PINCH_IN, ShortcutAction.PINCH_OUT -> pinchEditor(index, action)
                ShortcutAction.WAIT -> waitUriEditor(index, ShortcutAction.WAIT)
                ShortcutAction.URI -> waitUriEditor(index, ShortcutAction.URI)
                ShortcutAction.KEY_PRESS -> keyPressEditor(index)
                else -> chooseAction(index)
            }
        }
        /** Re-learns the key a KEY_PRESS step presses, without going through the action chooser. */
        private fun keyPressEditor(index: Int) {
            val step = draft.steps[index]
            val status = capturePage(R.string.shortcut_assign_action)
            if (step.keyCode >= 0 && step.scanCode >= 0) status.apply {
                textDirection = View.TEXT_DIRECTION_LTR
                text = activity.getString(R.string.shortcut_key_codes, step.keyCode, step.scanCode)
            }
            ui.add(body, ui.button(R.string.shortcut_edit_action, "shortcut_change_action") { cancelCapture(); chooseAction(index) })
            ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_step") { cancelCapture(); deleteStep(index) })
            captureActive = true
            CarPlayShortcuts.startLearning({ code, scan ->
                if (captureActive) {
                    captureActive = false
                    val steps = draft.steps.toMutableList()
                    steps[index] = step.copy(keyCode = code, scanCode = scan)
                    draft = draft.copy(steps = steps); editor()
                }
            }, { message(activity, R.string.shortcut_unsupported) }, { captureEnded() }, { captureEnded() })
            WheelKeyService.restoreIfNeeded(activity)
        }
        private fun moveStep(index: Int, offset: Int) {
            val steps = draft.steps.toMutableList()
            steps.add(index + offset, steps.removeAt(index)); draft = draft.copy(steps = steps); editor()
        }
        private fun deleteStep(index: Int) {
            draft = draft.copy(steps = draft.steps.filterIndexed { at, _ -> at != index }); editor()
        }

        // ---- drag a step by its handle, or long-press the row, to reorder ------------------------
        private var stepDragArmed = false
        /** The dragged row's layout top, tracked across swaps so the finger stays on the same spot. */
        private var stepDragLayoutTop = 0
        private fun armStepDrag(row: View) {
            if (stepDragArmed) return
            stepDragArmed = true
            stepDragLayoutTop = row.top
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            row.isPressed = false
            row.translationZ = ui.dp(4).toFloat()
            row.alpha = 0.92f
            (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        }
        private fun makeStepDraggable(row: View, handle: View, rows: MutableList<View>) {
            val slop = ViewConfiguration.get(activity).scaledTouchSlop
            val longPress = Runnable { armStepDrag(row) }
            var downY = 0f
            row.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        false
                    }
                    MotionEvent.ACTION_MOVE -> if (stepDragArmed) {
                        moveDraggedStep(view, rows, event.rawY - downY); true
                    } else {
                        if (Math.abs(event.rawY - downY) > slop) view.removeCallbacks(longPress)
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(longPress)
                        if (stepDragArmed) { endStepDrag(view, rows); true } else false
                    }
                    else -> stepDragArmed
                }
            }
            // The handle picks the step up at once, without the long-press wait.
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = event.rawY; armStepDrag(row); true }
                    MotionEvent.ACTION_MOVE -> { moveDraggedStep(row, rows, event.rawY - downY); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (stepDragArmed) { endStepDrag(row, rows); true } else false
                    }
                    else -> true
                }
            }
        }
        private fun moveDraggedStep(view: View, rows: MutableList<View>, dy: Float) {
            view.translationY = dy
            val from = rows.indexOf(view)
            if (from < 0) return
            val center = stepDragLayoutTop + dy + view.height / 2f
            if (from > 0) {
                val above = rows[from - 1]
                if (center < above.top + above.height / 2f) { swapStepRows(view, rows, from, from - 1); return }
            }
            if (from < rows.size - 1) {
                val below = rows[from + 1]
                if (center > below.top + below.height / 2f) swapStepRows(view, rows, from, from + 1)
            }
        }
        /** Swaps two neighbouring rows in the list and the layout, keeping the dragged row under the finger. */
        private fun swapStepRows(view: View, rows: MutableList<View>, from: Int, to: Int) {
            val parent = view.parent as? ViewGroup ?: return
            val other = rows[to]
            val visualTop = stepDragLayoutTop + view.translationY
            stepDragLayoutTop = if (to > from) stepDragLayoutTop + other.height +
                (other.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin else other.top
            rows[from] = other; rows[to] = view
            val position = parent.indexOfChild(view)
            parent.removeView(view)
            parent.addView(view, position + (to - from))
            view.translationY = visualTop - stepDragLayoutTop
        }
        /** The step a drag row stands for, from the row wrapper or the button inside it. */
        private fun stepRowNumber(row: View): Int? {
            (row.tag as? String)?.removePrefix("shortcut_step_row_")?.toIntOrNull()?.let { return it }
            if (row is ViewGroup) for (index in 0 until row.childCount) {
                (row.getChildAt(index).tag as? String)?.removePrefix("shortcut_step_")?.toIntOrNull()?.let { return it }
            }
            return (row.tag as? String)?.removePrefix("shortcut_step_")?.toIntOrNull()
        }
        /** Commits the dragged order to the draft; an untouched order only drops the pickup visuals. */
        private fun endStepDrag(view: View, rows: MutableList<View>) {
            stepDragArmed = false
            view.translationZ = 0f
            view.alpha = 1f
            view.translationY = 0f
            view.isPressed = false
            (view.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
            val order = rows.mapNotNull(::stepRowNumber)
            if (order.size == draft.steps.size && order != List(order.size) { it }) {
                draft = draft.copy(steps = order.map { draft.steps[it] })
                editor()
            }
        }
        private fun tapCoordinates(index: Int) {
            val step = draft.steps[index]
            page(R.string.shortcut_tap_coordinates) { editor() }
            ui.add(body, ui.text(activity.getString(R.string.shortcut_tap_coordinates_hint), secondary = true))
            val x = ui.input(Math.round(step.tapX.coerceIn(0.0, 1.0) * 100).toString(),
                R.string.shortcut_tap_x, "shortcut_tap_x", numeric = true, max = 3)
            val y = ui.input(Math.round(step.tapY.coerceIn(0.0, 1.0) * 100).toString(),
                R.string.shortcut_tap_y, "shortcut_tap_y", numeric = true, max = 3)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_tap_x)))
            ui.add(body, x)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_tap_y)))
            ui.add(body, y)
            ui.add(body, ui.button(R.string.shortcut_edit_action, "shortcut_change_action") { chooseAction(index) })
            ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_step") { deleteStep(index) })
            addFooter(ui.button(android.R.string.ok, "shortcut_accept_tap", primary = true) {
                val cx = x.text.toString().toIntOrNull()?.takeIf { it in 0..100 }
                val cy = y.text.toString().toIntOrNull()?.takeIf { it in 0..100 }
                if (cx == null || cy == null) message(activity, R.string.shortcut_invalid)
                else {
                    val steps = draft.steps.toMutableList()
                    steps[index] = step.copy(tapX = cx / 100.0, tapY = cy / 100.0)
                    draft = draft.copy(steps = steps)
                    editor()
                }
            })
            cancelButton { editor() }
        }
        private fun pinchEditor(index: Int?, action: ShortcutAction) {
            val previous = index?.let { draft.steps[it] }
            page(labels.getValue(action)) { if (index == null) chooseAction(null) else editor() }
            ui.add(body, ui.text(activity.getString(R.string.settings_shortcut_pinch_hint), secondary = true))
            val x = ui.input(Math.round((previous?.zoomCenterX ?: 0.5) * 100).toString(),
                R.string.settings_shortcut_pinch_x, "shortcut_pinch_x", numeric = true, max = 2)
            val y = ui.input(Math.round((previous?.zoomCenterY ?: 0.5) * 100).toString(),
                R.string.settings_shortcut_pinch_y, "shortcut_pinch_y", numeric = true, max = 2)
            ui.add(body, ui.text(activity.getString(R.string.settings_shortcut_pinch_x)))
            ui.add(body, x)
            ui.add(body, ui.text(activity.getString(R.string.settings_shortcut_pinch_y)))
            ui.add(body, y)
            val scaleLabel = ui.text("").apply { tag = "shortcut_pinch_scale_value" }
            val scale = SeekBar(ui.context).apply {
                tag = "shortcut_pinch_scale"
                max = 69
                progress = (Math.round((previous?.pinchScale ?: com.shilapi.xcertplay.orchestration.CarPlayController.DEFAULT_SHORTCUT_PINCH_SCALE) * 10).toInt() - 11).coerceIn(0, max)
                minimumHeight = ui.dp(CONTROL_HEIGHT_DP)
                progressTintList = ColorStateList.valueOf(ui.theme.accent)
                thumbTintList = ColorStateList.valueOf(ui.theme.accent)
                fun update(value: Int) {
                    scaleLabel.text = activity.getString(R.string.settings_shortcut_pinch_scale_value, (value + 11) / 10.0)
                    contentDescription = scaleLabel.text
                }
                update(progress)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { update(value) }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            ui.add(body, scaleLabel)
            ui.add(body, scale)
            ui.add(body, ui.text(activity.getString(R.string.settings_shortcut_pinch_scale_hint), secondary = true))
            if (index != null) {
                ui.add(body, ui.button(R.string.shortcut_edit_action, "shortcut_change_action") { chooseAction(index) })
                ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_step") { deleteStep(index) })
            }
            addFooter(ui.button(android.R.string.ok, "shortcut_accept_action", primary = true) {
                val cx = x.text.toString().toIntOrNull()?.takeIf { it in 5..95 }
                val cy = y.text.toString().toIntOrNull()?.takeIf { it in 5..95 }
                if (cx == null || cy == null) message(activity, R.string.shortcut_invalid)
                else {
                    val step = ShortcutStep(action, zoomCenterX = cx / 100.0, zoomCenterY = cy / 100.0,
                        pinchScale = (scale.progress + 11) / 10.0).copy(keyCode = previous?.keyCode ?: -1, scanCode = previous?.scanCode ?: -1)
                    draft = draft.copy(steps = draft.steps.toMutableList().apply { if (index == null) add(step) else set(index, step) })
                    editor()
                }
            })
            cancelButton { if (index == null) chooseAction(null) else editor() }
        }
        private fun waitUriEditor(index: Int?, action: ShortcutAction) {
            val previous = index?.let { draft.steps[it] }
            val numeric = action == ShortcutAction.WAIT
            page(labels.getValue(action)) { if (index == null) chooseAction(null) else editor() }
            val input = ui.input(if (numeric) previous?.waitMs?.takeIf { it > 0 }?.toString() ?: "1000" else previous?.url.orEmpty(),
                labels.getValue(action), "shortcut_action_value", numeric, if (numeric) 5 else 2048)
            ui.add(body, input)
            if (index != null) {
                ui.add(body, ui.button(R.string.shortcut_edit_action, "shortcut_change_action") { chooseAction(index) })
                ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_step") { deleteStep(index) })
            }
            addFooter(ui.button(android.R.string.ok, "shortcut_accept_action", primary = true) {
                val value = input.text.toString()
                val step = (if (numeric) value.toLongOrNull()?.takeIf { it in 0..30_000 }?.let { ShortcutStep(action, waitMs = it) }
                    else ShortcutCodec.safeUrl(value)?.let { ShortcutStep(action, url = it) })
                    ?.copy(keyCode = previous?.keyCode ?: -1, scanCode = previous?.scanCode ?: -1)
                if (step == null) message(activity, R.string.shortcut_invalid)
                else {
                    draft = draft.copy(steps = draft.steps.toMutableList().apply { if (index == null) add(step) else set(index, step) })
                    editor()
                }
            })
            cancelButton { if (index == null) chooseAction(null) else editor() }
        }
        /** The action list: adding appends a step; editing replaces it, with the current action marked. */
        private fun chooseAction(index: Int?) {
            page(if (index == null) R.string.shortcut_add_action else R.string.shortcut_edit_action)
            val current = index?.let { draft.steps.getOrNull(it)?.action }
            selectableActions.filter { action ->
                (action != ShortcutAction.ORIGINAL_CLICK || draft.event == ShortcutEvent.KEY) &&
                    (action != ShortcutAction.TAP || current == ShortcutAction.TAP)
            }.forEach { action ->
                ui.add(body, ui.listChip(labels.getValue(action), "shortcut_action_${action.name}", current == action) {
                    val previous = index?.let { draft.steps[it] }
                    fun accept(step: ShortcutStep) {
                        val withKey = step.copy(keyCode = previous?.keyCode ?: -1, scanCode = previous?.scanCode ?: -1)
                        draft = draft.copy(steps = draft.steps.toMutableList().apply { if (index == null) add(withKey) else set(index, withKey) })
                        editor()
                    }
                    when (action) {
                        ShortcutAction.PINCH_IN, ShortcutAction.PINCH_OUT -> pinchEditor(index, action)
                        ShortcutAction.WAIT, ShortcutAction.URI -> waitUriEditor(index, action)
                        ShortcutAction.ORIGINAL_CLICK -> {
                            if (!com.shilapi.xcertplay.input.ShellKeyReplay.safeCode(draft.keyCode)) message(activity, R.string.shortcut_invalid)
                            else accept(ShortcutStep(action))
                        }
                        else -> accept(previous?.takeIf { it.action == action } ?: ShortcutStep(action))
                    }
                })
            }
            if (index != null) {
                val reorder = ui.row()
                ui.addWeighted(reorder, ui.button(R.string.shortcut_move_up, "shortcut_move_up") { moveStep(index, -1) }
                    .apply { isEnabled = index > 0; alpha = if (isEnabled) 1f else 0.5f }, CONTROL_HEIGHT_DP)
                ui.addWeighted(reorder, ui.button(R.string.shortcut_move_down, "shortcut_move_down") { moveStep(index, 1) }
                    .apply { isEnabled = index < draft.steps.lastIndex; alpha = if (isEnabled) 1f else 0.5f }, CONTROL_HEIGHT_DP)
                ui.addWeighted(reorder, ui.button(R.string.shortcut_delete, "shortcut_delete_step") { deleteStep(index) }
                    .apply { setTextColor(ui.palette.danger) }, CONTROL_HEIGHT_DP)
                ui.add(body, reorder)
            }
            cancelButton()
        }
        private fun cancelCapture() { captureActive = false; CarPlayShortcuts.cancelCapture() }
        private fun capturePage(resource: Int): TextView {
            page(resource) { cancelCapture(); editor() }
            ui.add(body, ui.text(activity.getString(if (resource == R.string.shortcut_recording) R.string.shortcut_record_hint else R.string.shortcut_capture_hint), secondary = true))
            val status = ui.text(activity.getString(R.string.shortcut_waiting_key)).apply {
                tag = "shortcut_capture_key"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            ui.add(body, status)
            cancelButton { cancelCapture(); editor() }
            return status
        }
        private fun captureEnded() { if (captureActive) { captureActive = false; if (dialog.isShowing) editor() } }
        private fun learn() {
            val status = capturePage(R.string.shortcut_learn)
            captureActive = true
            CarPlayShortcuts.startLearning({ code, scan ->
                if (captureActive) {
                    captureActive = false
                    draft = draft.copy(event = ShortcutEvent.KEY, keyCode = code, scanCode = scan, vehicleEvent = null); editor()
                }
            }, { message(activity, R.string.shortcut_unsupported) }, { captureEnded() }, { captureEnded() }, onChanged = { update ->
                if (captureActive && update.code != null && update.scan != null)
                    status.apply {
                        textDirection = View.TEXT_DIRECTION_LTR
                        text = activity.getString(R.string.shortcut_key_codes, update.code, update.scan)
                    }
            })
            WheelKeyService.restoreIfNeeded(activity)
        }
        fun reviewRecording(steps: List<ShortcutStep>) {
            page(R.string.shortcut_record_accept)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_record_confirm, steps.size)))
            steps.forEachIndexed { index, step -> ui.add(body, ui.text("${index + 1}. ${summary(activity, step)}")) }
            addFooter(ui.button(android.R.string.ok, "shortcut_confirm", primary = true) {
                draft = draft.copy(steps = steps.toList(), enabled = false); editor()
            })
            cancelButton()
        }
        private fun record() {
            val status = capturePage(R.string.shortcut_recording)
            val count = ui.text(activity.getString(R.string.shortcut_record_count, 0)).apply { tag = "shortcut_record_count" }
            ui.add(body, count)
            val recorded = ui.column().apply { tag = "shortcut_record_steps" }
            ui.add(body, recorded, BLOCK_GAP_DP)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_add_action), heading = true))
            selectableActions.filter { it != ShortcutAction.WAIT && it != ShortcutAction.URI }.forEach { action ->
                ui.add(body, ui.listChip(labels.getValue(action), "shortcut_record_${action.name}") { CarPlayShortcuts.recordAction(action) })
            }
            addFooter(ui.button(R.string.shortcut_record_accept, "shortcut_record_accept", primary = true) { CarPlayShortcuts.stopRecording() }, 0)
            captureActive = true
            CarPlayShortcuts.startRecording({ steps ->
                if (captureActive) {
                    captureActive = false
                    reviewRecording(steps)
                }
            }, { message(activity, R.string.shortcut_unsupported) }, { captureEnded() }, onChanged = { update ->
                if (captureActive) {
                    if (update.code != null && update.scan != null) status.apply {
                        textDirection = View.TEXT_DIRECTION_LTR
                        text = activity.getString(R.string.shortcut_key_codes, update.code, update.scan)
                    }
                    count.text = activity.getString(R.string.shortcut_record_count, update.steps.size)
                    recorded.removeAllViews()
                    update.steps.forEachIndexed { index, step -> ui.add(recorded, ui.text("${index + 1}. ${summary(activity, step)}")) }
                }
            })
            WheelKeyService.restoreIfNeeded(activity)
        }
    }
}

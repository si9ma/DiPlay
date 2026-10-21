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
import android.view.KeyEvent
import android.view.View
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
import com.shilapi.xcertplay.settings.SettingsControlMetrics
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** A local draft and one themed window: subpages never stack dialogs or persist partial edits. */
internal object ShortcutEditor {
    private const val BLOCK_GAP_DP = SettingsControlMetrics.BLOCK_GAP_DP
    private const val CONTROL_GAP_DP = SettingsControlMetrics.GAP_DP
    private const val CONTROL_HEIGHT_DP = SettingsControlMetrics.HEIGHT_DP
    private const val OUTLINE_DP = SettingsControlMetrics.OUTLINE_DP
    private const val FOCUS_OUTLINE_DP = SettingsControlMetrics.FOCUS_OUTLINE_DP
    private const val PAGE_MAX_WIDTH_DP = 760
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
    )
    private val selectableActions get() = ShortcutAction.entries.filterNot {
        it.legacyWindow || it == ShortcutAction.KEY_PRESS || it == ShortcutAction.TAP
    }
    private fun live(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
    private fun message(activity: Activity, resource: Int) {
        if (live(activity)) Toast.makeText(activity, resource, Toast.LENGTH_LONG).show()
    }
    private fun keyCodes(activity: Activity, code: Int, scan: Int): String = BidiFormatter.getInstance(
        activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        .unicodeWrap(activity.getString(R.string.shortcut_key_codes, code, scan), TextDirectionHeuristicsCompat.LTR).orEmpty()

    private fun clickLabel(activity: Activity, count: Int) = activity.getString(when (count) {
        2 -> R.string.shortcut_click_double
        3 -> R.string.shortcut_click_triple
        4 -> R.string.shortcut_click_quadruple
        else -> R.string.shortcut_click_single
    })

    private fun trigger(activity: Activity, rule: ShortcutRule) = if (rule.event == ShortcutEvent.CONNECTED)
        activity.getString(R.string.shortcut_connected)
    else "${activity.getString(R.string.shortcut_key)} · ${keyCodes(activity, rule.keyCode, rule.scanCode)} · ${clickLabel(activity, rule.clickCount)}"

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
                ui.add(row, ui.text("${activity.getString(if (rule.enabled) R.string.shortcut_enabled else R.string.shortcut_disabled)} · ${activity.getString(R.string.shortcut_steps_count, rule.steps.size)}", secondary = true))
                ui.add(list, row, BLOCK_GAP_DP)
            }
            ui.add(list, ui.button(R.string.shortcut_new, "shortcut_new", primary = true) {
                if (CarPlayShortcuts.load(activity).size >= ShortcutCodec.MAX_RULES) message(activity, R.string.shortcut_limit)
                else show(activity, ShortcutRule(), ::refresh)
            })
            ui.add(list, ui.button(R.string.shortcut_stop, "shortcut_stop_all") {
                CarPlayShortcuts.stop(); CarPlayShortcuts.cancelCapture()
            })
        }
        refresh()
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
        fun add(parent: LinearLayout, child: View, gap: Int = CONTROL_GAP_DP) {
            parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
        }
        fun shape(color: Int, height: Int = dp(CONTROL_HEIGHT_DP)) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = SettingsControlMetrics.radius(height, context.resources.displayMetrics.density)
            setStroke(dp(OUTLINE_DP), palette.outline)
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
            setPaddingRelative(dp(BLOCK_GAP_DP), dp(CONTROL_GAP_DP), dp(BLOCK_GAP_DP), dp(CONTROL_GAP_DP))
            stateListAnimator = null
            fun style(height: Int) {
                background = RippleDrawable(ColorStateList.valueOf(theme.ripple), shape(if (primary) theme.accent else palette.button, height), null)
                foreground = focusRing(height)
            }
            style(dp(CONTROL_HEIGHT_DP))
            doOnLayout { style(height) }
            setOnClickListener { click() }
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
        init { orientation = VERTICAL }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = maxHeight().coerceAtLeast(0)
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
            dialog.setOnDismissListener { if (captureActive) cancelCapture() }
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
        }
        /**
         * Use the focused dialog's visible frame for IME changes, but the host's full height:
         * the dialog's own wrap-content height must never become the next page's height limit.
         */
        private fun availableHeight(): Int {
            val decor = activity.window.decorView
            val visibleDecor = if (dialog.isShowing) dialog.window?.decorView ?: decor else decor
            val visible = android.graphics.Rect().also(visibleDecor::getWindowVisibleDisplayFrame).height()
            val height = listOf(visible, decor.height).filter { it > 0 }.minOrNull()
                ?: activity.resources.displayMetrics.heightPixels
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
            ui.add(event, ui.button(R.string.shortcut_connected, "shortcut_connected") {
                draft = draft.copy(event = ShortcutEvent.CONNECTED, keyCode = 0, scanCode = 0, clickCount = 1); editor()
            })
            ui.add(event, ui.button(R.string.shortcut_learn, "shortcut_learn") { learn() })
            if (draft.event == ShortcutEvent.KEY) {
                ui.add(event, ui.button("${activity.getString(R.string.shortcut_click_count)} · ${clickLabel(activity, draft.clickCount)}",
                    "shortcut_click_count") { chooseClickCount() })
                ui.add(event, ui.text(activity.getString(R.string.shortcut_click_hint), secondary = true))
            }
            val steps = card(R.string.shortcut_steps)
            if (draft.steps.isEmpty()) ui.add(steps, ui.text(activity.getString(R.string.shortcut_no_steps), secondary = true))
            draft.steps.forEachIndexed { index, step ->
                ui.add(steps, ui.button("${index + 1}. ${summary(activity, step)}", "shortcut_step_$index") { editStep(index) })
            }
            if (draft.steps.any { it.action in ShortcutAction.PLAY..ShortcutAction.PREVIOUS } &&
                !AirPlayPersistence.loadCarPlayOwnsMainAudio(activity)) {
                ui.add(steps, ui.text(activity.getString(R.string.shortcut_media_main_audio_hint,
                    activity.getString(R.string.settings_carplay_owns_main_audio)), secondary = true)
                    .apply { tag = "shortcut_media_hint" })
            }
            ui.add(steps, ui.button(R.string.shortcut_add_action, "shortcut_add_action") {
                if (draft.steps.size >= ShortcutCodec.MAX_STEPS || draft.steps.lastOrNull()?.action == ShortcutAction.SIRI)
                    message(activity, R.string.shortcut_invalid)
                else chooseAction(null)
            })
            ui.add(steps, ui.button(R.string.shortcut_record_taps, "shortcut_record_taps") {
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
            })
            ui.add(steps, ui.button(R.string.shortcut_record_keys, "shortcut_record") { record() })
            val replay = draft.steps.any { it.action == ShortcutAction.ORIGINAL_CLICK }
            val testing = card(R.string.shortcut_test)
            ui.add(testing, ui.text(activity.getString(if (replay) R.string.shortcut_original_click_hint else R.string.shortcut_confirm_run), secondary = true))
            ui.add(testing, ui.button(R.string.shortcut_test, "shortcut_test") {
                if (!ShortcutCodec.executable(draft)) message(activity, R.string.shortcut_invalid)
                else confirm(R.string.shortcut_confirm_run) {
                    message(activity, if (CarPlayShortcuts.test(draft)) R.string.shortcut_queued else R.string.shortcut_unavailable); editor()
                }
            }.apply { isEnabled = !replay && ShortcutCodec.executable(draft); alpha = if (isEnabled) 1f else 0.5f })
            ui.add(testing, ui.button(R.string.shortcut_stop, "shortcut_stop") { CarPlayShortcuts.stop() })
            if (existing) ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_rule") {
                confirm(R.string.shortcut_delete_confirm) {
                    if (CarPlayShortcuts.save(activity, CarPlayShortcuts.load(activity).filterNot { it.id == draft.id })) finish()
                    else message(activity, R.string.shortcut_invalid)
                }
            })
            addFooter(ui.button(R.string.shortcut_save, "shortcut_save", primary = true) { save() })
            cancelButton { dialog.dismiss() }
        }
        private fun chooseClickCount() {
            page(R.string.shortcut_click_count)
            ui.add(body, ui.text(activity.getString(R.string.shortcut_click_hint), secondary = true))
            for (count in 1..4) {
                ui.add(body, ui.button(clickLabel(activity, count), "shortcut_click_$count", primary = count == draft.clickCount) {
                    draft = draft.copy(clickCount = count); editor()
                }.apply { isSelected = count == draft.clickCount })
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
            if (ShortcutCodec.conflicts(saved)) { message(activity, R.string.shortcut_conflict); return }
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
        private fun editStep(index: Int) {
            val step = draft.steps[index]
            page(R.string.shortcut_edit_action)
            ui.add(body, ui.text(summary(activity, step), heading = true))
            ui.add(body, ui.button(if (step.action == ShortcutAction.KEY_PRESS) R.string.shortcut_assign_action else R.string.shortcut_edit_action,
                "shortcut_change_action") { chooseAction(index) })
            fun move(offset: Int) {
                val steps = draft.steps.toMutableList()
                steps.add(index + offset, steps.removeAt(index)); draft = draft.copy(steps = steps); editor()
            }
            if (index > 0) ui.add(body, ui.button(R.string.shortcut_move_up, "shortcut_move_up") { move(-1) })
            if (index < draft.steps.lastIndex) ui.add(body, ui.button(R.string.shortcut_move_down, "shortcut_move_down") { move(1) })
            ui.add(body, ui.button(R.string.shortcut_delete, "shortcut_delete_step") {
                draft = draft.copy(steps = draft.steps.filterIndexed { at, _ -> at != index }); editor()
            })
            cancelButton()
        }
        private fun chooseAction(index: Int?) {
            page(R.string.shortcut_add_action)
            selectableActions.filter { it != ShortcutAction.ORIGINAL_CLICK || draft.event == ShortcutEvent.KEY }.forEach { action ->
                ui.add(body, ui.button(labels.getValue(action), "shortcut_action_${action.name}") {
                    val previous = index?.let { draft.steps[it] }
                    fun accept(step: ShortcutStep) {
                        val withKey = step.copy(keyCode = previous?.keyCode ?: -1, scanCode = previous?.scanCode ?: -1)
                        draft = draft.copy(steps = draft.steps.toMutableList().apply { if (index == null) add(withKey) else set(index, withKey) })
                        editor()
                    }
                    when (action) {
                        ShortcutAction.PINCH_IN, ShortcutAction.PINCH_OUT -> {
                            page(labels.getValue(action)) { chooseAction(index) }
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
                            addFooter(ui.button(android.R.string.ok, "shortcut_accept_action", primary = true) {
                                val cx = x.text.toString().toIntOrNull()?.takeIf { it in 5..95 }
                                val cy = y.text.toString().toIntOrNull()?.takeIf { it in 5..95 }
                                if (cx == null || cy == null) message(activity, R.string.shortcut_invalid)
                                else accept(ShortcutStep(action, zoomCenterX = cx / 100.0, zoomCenterY = cy / 100.0,
                                    pinchScale = (scale.progress + 11) / 10.0))
                            })
                            cancelButton { chooseAction(index) }
                        }
                        ShortcutAction.WAIT, ShortcutAction.URI -> {
                            page(labels.getValue(action)) { chooseAction(index) }
                            val numeric = action == ShortcutAction.WAIT
                            val input = ui.input(if (numeric) previous?.waitMs?.takeIf { it > 0 }?.toString() ?: "1000" else previous?.url.orEmpty(),
                                labels.getValue(action), "shortcut_action_value", numeric, if (numeric) 5 else 2048)
                            ui.add(body, input)
                            addFooter(ui.button(android.R.string.ok, "shortcut_accept_action", primary = true) {
                                val value = input.text.toString()
                                val step = if (numeric) value.toLongOrNull()?.takeIf { it in 0..30_000 }?.let { ShortcutStep(action, waitMs = it) }
                                    else ShortcutCodec.safeUrl(value)?.let { ShortcutStep(action, url = it) }
                                if (step == null) message(activity, R.string.shortcut_invalid) else accept(step)
                            })
                            cancelButton { chooseAction(index) }
                        }
                        ShortcutAction.ORIGINAL_CLICK -> {
                            if (!com.shilapi.xcertplay.input.ShellKeyReplay.safeCode(draft.keyCode)) message(activity, R.string.shortcut_invalid)
                            else accept(ShortcutStep(action))
                        }
                        else -> accept(ShortcutStep(action))
                    }
                })
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
                    draft = draft.copy(event = ShortcutEvent.KEY, keyCode = code, scanCode = scan); editor()
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
                ui.add(body, ui.button(labels.getValue(action), "shortcut_record_${action.name}") { CarPlayShortcuts.recordAction(action) })
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

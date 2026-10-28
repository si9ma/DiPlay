package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import com.shilapi.xcertplay.host.R
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** UI flow of the shortcut editor: steps edit in place, drag to reorder, compact choice chips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutEditorUiTest {
    private lateinit var activity: Activity

    @After fun tearDown() {
        ShortcutEditor.testAvailableHeight = null
        val dialog = ShadowAlertDialog.getLatestAlertDialog() ?: return
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun openEditor(steps: List<ShortcutStep>, block: ShortcutRule.() -> ShortcutRule = { this }) {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_NoActionBar)
        }
        ShortcutEditor.show(activity, ShortcutRule(name = "Flow", steps = steps).block()) {}
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun decor() = ShadowAlertDialog.getLatestAlertDialog()!!.window!!.decorView

    private fun find(tag: String): View? {
        val root = decor() ?: return null
        fun scan(view: View): View? {
            if (view.tag == tag) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) scan(view.getChildAt(index))?.let { return it }
            return null
        }
        return scan(root)
    }

    private fun click(tag: String) {
        val view = find(tag) ?: throw AssertionError("no view tagged $tag")
        view.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun label(tag: String): String = (find(tag) as? TextView)?.text?.toString().orEmpty()

    private fun stepCount(): Int = allViews(decor()).count {
        (it.tag as? String)?.matches(Regex("shortcut_step_[0-9]+")) == true
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(allViews(view.getChildAt(index)))
    }

    private fun layoutEditor() {
        val decor = decor()
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun tappingAStepOpensThatActionsOwnEditorWithoutADetour() {
        openEditor(listOf(
            ShortcutStep(ShortcutAction.WAIT, waitMs = 1000),
            ShortcutStep(ShortcutAction.NEXT),
            ShortcutStep(ShortcutAction.TAP, tapX = 0.3, tapY = 0.7)))
        // A WAIT step edits its milliseconds at once.
        click("shortcut_step_0")
        assertEquals("1000", (find("shortcut_action_value") as EditText).text.toString())
        click("shortcut_cancel")
        // A parameterless step opens the action list itself, with the current action marked.
        click("shortcut_step_1")
        assertTrue(find("shortcut_action_NEXT")!!.isSelected)
        assertNotNull(find("shortcut_move_up"))
        assertNotNull(find("shortcut_move_down"))
        assertNotNull(find("shortcut_delete_step"))
        click("shortcut_cancel")
        // A TAP step edits its coordinates at once.
        click("shortcut_step_2")
        assertEquals("30", (find("shortcut_tap_x") as EditText).text.toString())
        assertEquals("70", (find("shortcut_tap_y") as EditText).text.toString())
    }

    @Test fun theActionListReplacesTheStepInPlaceAndKeepsTheStepCount() {
        openEditor(listOf(
            ShortcutStep(ShortcutAction.WAIT, waitMs = 1000),
            ShortcutStep(ShortcutAction.NEXT),
            ShortcutStep(ShortcutAction.TAP, tapX = 0.3, tapY = 0.7)))
        click("shortcut_step_1")
        click("shortcut_action_PAUSE")
        assertEquals(3, stepCount())
        assertTrue(label("shortcut_step_1").contains(activity.getString(R.string.shortcut_pause)))
        // Picking the current action again keeps the step, including a tap's coordinates.
        click("shortcut_step_2")
        click("shortcut_change_action")
        assertTrue(find("shortcut_action_TAP")!!.isSelected)
        click("shortcut_action_TAP")
        click("shortcut_step_2")
        assertEquals("30", (find("shortcut_tap_x") as EditText).text.toString())
        assertEquals("70", (find("shortcut_tap_y") as EditText).text.toString())
    }

    @Test fun theActionListMovesAndDeletesSteps() {
        openEditor(listOf(
            ShortcutStep(ShortcutAction.WAIT, waitMs = 1000),
            ShortcutStep(ShortcutAction.NEXT),
            ShortcutStep(ShortcutAction.TAP)))
        click("shortcut_step_1")
        click("shortcut_move_down")
        assertTrue(label("shortcut_step_1").contains(activity.getString(R.string.shortcut_tap)))
        assertTrue(label("shortcut_step_2").contains(activity.getString(R.string.shortcut_next)))
        click("shortcut_step_2")
        click("shortcut_delete_step")
        assertEquals(2, stepCount())
        assertTrue(label("shortcut_step_1").contains(activity.getString(R.string.shortcut_tap)))
    }

    @Test fun theDragHandleReordersSteps() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT), ShortcutStep(ShortcutAction.PAUSE)))
        layoutEditor()
        val handle = find("shortcut_step_handle")!!
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action,
                handle.width / 2f, handle.height / 2f + y, 0)
            try { assertTrue(handle.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        // The handle picks the step up at once; dragging past the row below swaps them; releasing commits.
        touch(MotionEvent.ACTION_DOWN, 0f)
        touch(MotionEvent.ACTION_MOVE, 2000f)
        touch(MotionEvent.ACTION_UP, 2000f)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(label("shortcut_step_0").contains(activity.getString(R.string.shortcut_pause)))
        assertTrue(label("shortcut_step_1").contains(activity.getString(R.string.shortcut_next)))
    }

    @Test fun triggerChipsAreCompactAndMarkTheChosenEvent() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT))) {
            copy(event = ShortcutEvent.KEY, keyCode = 87, scanCode = 163, clickCount = 2)
        }
        layoutEditor()
        val learn = find("shortcut_learn") as Button
        val connected = find("shortcut_connected") as Button
        val vehicle = find("shortcut_vehicle_event") as Button
        assertTrue(learn.isSelected)
        assertFalse(connected.isSelected)
        // One compact height for the whole row, and the check mark is a drawable, not label text.
        val heights = listOf(learn, connected, vehicle).map { it.measuredHeight }.toSet()
        assertEquals(1, heights.size)
        assertTrue("chip height ${heights.single()}", heights.single() <= 40 * activity.resources.displayMetrics.density)
        listOf(learn, connected, vehicle).forEach { assertFalse(it.text.toString().contains("✓")) }
        assertNotNull(find("shortcut_click_count"))
        // Choosing another event updates the chips and clears the key-only details.
        click("shortcut_connected")
        assertTrue(find("shortcut_connected")!!.isSelected)
        assertNull(find("shortcut_click_count"))
        assertTrue(label("shortcut_trigger").contains(activity.getString(R.string.shortcut_connected)))
    }

    @Test fun vehicleEventsArePickedFromCompactChips() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT))) {
            copy(event = ShortcutEvent.VEHICLE, vehicleEvent = com.shilapi.xcertplay.hud.BydVehicleEvent.RIGHT_CARD_SHOWN)
        }
        layoutEditor()
        click("shortcut_vehicle_event")
        layoutEditor()
        val selected = find("shortcut_vehicle_RIGHT_CARD_SHOWN") as Button
        val other = find("shortcut_vehicle_GEAR_P") as Button
        assertTrue(selected.isSelected)
        assertFalse(other.isSelected)
        assertFalse(selected.text.toString().contains("✓"))
        assertEquals(selected.measuredHeight, other.measuredHeight)
        click("shortcut_vehicle_GEAR_P")
        assertTrue(label("shortcut_trigger").contains(activity.getString(R.string.shortcut_vehicle_gear_p)))
    }

    @Test fun stepRowsCarryEqualHeightDragHandles() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT), ShortcutStep(ShortcutAction.PAUSE)))
        layoutEditor()
        for (index in 0..1) {
            val row = find("shortcut_step_row_$index") as ViewGroup
            val handle = row.getChildAt(0)
            val button = row.getChildAt(1)
            assertEquals("shortcut_step_handle", handle.tag)
            assertEquals("shortcut_step_$index", button.tag)
            assertEquals(button.measuredHeight, handle.measuredHeight)
            assertEquals(activity.getString(R.string.shortcut_drag_handle), handle.contentDescription)
        }
        // The step list explains the tap and drag gestures.
        assertTrue(allViews(decor()).filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.shortcut_steps_hint) })
    }

    @Test fun theStepToolsRowHoldsThreeMatchingButtonsApartFromTheStepButtons() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT)))
        layoutEditor()
        val tools = listOf("shortcut_add_action", "shortcut_record_taps", "shortcut_record").map { find(it) as Button }
        assertEquals(1, tools.map { it.measuredHeight }.toSet().size)
        assertEquals(1, tools.map { it.currentTextColor }.toSet().size)
        val step = find("shortcut_step_0") as Button
        assertTrue(tools.all { it.currentTextColor != step.currentTextColor })
    }

    @Test fun addingAnActionAppendsToTheSequence() {
        openEditor(listOf(ShortcutStep(ShortcutAction.NEXT)))
        click("shortcut_add_action")
        click("shortcut_action_PAUSE")
        assertEquals(2, stepCount())
        assertTrue(label("shortcut_step_1").contains(activity.getString(R.string.shortcut_pause)))
    }
}

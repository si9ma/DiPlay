package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import com.shilapi.xcertplay.host.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.hud.BydVehicleEvent
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Compact/full and RTL screenshots of the shortcuts list and editor at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutEditorScreenshotsTest {
    @Test fun shortcutsListAndEditorRenderAtLargeFontsInThreeLayouts() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_NoActionBar)
        }
        val rules = listOf(
            ShortcutRule(id = "commute", name = "Commute music", enabled = true, event = ShortcutEvent.KEY,
                keyCode = 87, scanCode = 163, clickCount = 2,
                steps = listOf(ShortcutStep(ShortcutAction.PLAY), ShortcutStep(ShortcutAction.WAIT, waitMs = 500),
                    ShortcutStep(ShortcutAction.TAP, tapX = 0.3, tapY = 0.7), ShortcutStep(ShortcutAction.PINCH_OUT))),
            ShortcutRule(id = "gear", name = "Gear P cluster", enabled = false, event = ShortcutEvent.VEHICLE,
                vehicleEvent = BydVehicleEvent.GEAR_P, steps = listOf(ShortcutStep(ShortcutAction.SHOW_CLUSTER_MAP))))
        assertTrue(CarPlayShortcuts.save(activity, rules))
        ShortcutEditor.testAvailableHeight = { 100_000 }
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-ja", 320, "ja-JP"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()

                val list = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = activity.resources.configuration.layoutDirection
                }
                ShortcutEditor.renderList(activity, list)
                assertTrue(allViews(list).filterIsInstance<TextView>()
                    .any { it.text.toString() == "Commute music" })
                snapshot(list, width, "shortcut-list-$filename")

                ShortcutEditor.show(activity, rules[0]) {}
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
                val editor = find(dialog.window!!.decorView, "shortcut_editor")!!
                snapshot(editor, width, "shortcut-editor-$filename")
                assertTrue(allViews(editor).filterIsInstance<TextView>()
                    .any { it.text.toString() == activity.getString(R.string.shortcut_steps_hint) })
                assertEquals(4, allViews(editor).count {
                    (it.tag as? String)?.matches(Regex("shortcut_step_[0-9]+")) == true
                })

                find(dialog.window!!.decorView, "shortcut_add_action")!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                snapshot(editor, width, "shortcut-actions-$filename")
                assertTrue(find(dialog.window!!.decorView, "shortcut_action_PAUSE") != null)
                dialog.dismiss()
                shadowOf(Looper.getMainLooper()).idle()
            }
        } finally {
            ShortcutEditor.testAvailableHeight = null
            CarPlayShortcuts.save(activity, emptyList())
            activity.finish()
        }
    }

    private fun snapshot(view: View, width: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/shortcut-ui").apply { mkdirs() }
            FileOutputStream(File(directory, "$name.png")).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }

    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), tag)?.let { return it }
        return null
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(allViews(view.getChildAt(index)))
    }
}

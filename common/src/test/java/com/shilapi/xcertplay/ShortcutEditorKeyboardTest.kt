package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutEditorKeyboardTest {
    @After fun reset() { ShortcutEditor.testAvailableHeight = null }

    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }

    @Test fun theEditorGrowsBackWhenTheKeyboardCloses() {
        var room = 200
        ShortcutEditor.testAvailableHeight = { room }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_NoActionBar)
        }
        ShortcutEditor.show(activity)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val root = find(dialog.window!!.decorView, "shortcut_editor")!!
        // The keyboard is up: the page keeps to the room above it.
        assertTrue("height ${root.height}", root.height in 1..200)
        // The keyboard closes. Nothing resizes the shorter window; the editor must measure itself again.
        room = 5_000
        looper.idleFor(Duration.ofMillis(600))
        assertTrue("height ${root.height}", root.height > 200)
        dialog.dismiss()
    }
}

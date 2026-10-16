package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.AutoPlaybackOptions
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class AutoPlaybackSettingsTest {
    private fun activity(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
        setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    private fun controls(activity: DiPlayActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "autoPlaybackControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, this))
    }

    @Test fun defaultOffAndRenderingDoesNotWritePreferences() {
        val activity = activity()
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().clear().commit()
        val before = prefs.all
        val parent = controls(activity)
        val toggle = descendants(parent).filterIsInstance<Switch>().single()
        assertFalse(toggle.isChecked)
        assertEquals(before, prefs.all)
        toggle.isChecked = true
        assertEquals(before + ("auto_playback" to true), prefs.all)
        toggle.isChecked = false
        assertFalse(AirPlayPersistence.loadAutoPlayback(activity).enabled)
    }

    @Test fun urlDialogValidatesWithoutEnablingAndCancelPreservesSavedValue() {
        val activity = activity()
        val parent = controls(activity)
        parent.getChildAt(1).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("bad URL")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(input.error)
        assertEquals("", AirPlayPersistence.loadAutoPlayback(activity).appUrl)
        input.setText(" testmusic://play?id=1 ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(AutoPlaybackOptions(false, "testmusic://play?id=1"), AirPlayPersistence.loadAutoPlayback(activity))
        val recreated = controls(activity())
        recreated.getChildAt(1).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()
        val edit = descendants(reopened.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals("testmusic://play?id=1", edit.text.toString())
        edit.setText("othermusic://")
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("testmusic://play?id=1", AirPlayPersistence.loadAutoPlayback(activity).appUrl)
    }

    @Test fun disconnectedManualTestDoesNotEnableAutomaticPlayback() {
        val activity = activity()
        controls(activity).getChildAt(2).performClick()
        assertFalse(AirPlayPersistence.loadAutoPlayback(activity).enabled)
        assertEquals(activity.getString(R.string.settings_auto_playback_unavailable), ShadowToast.getTextOfLatestToast())
    }

    @Test fun connectedManualTestDelegatesWithoutEnablingOrReconnecting() {
        val activity = activity()
        val controller = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val sink = org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java)
        org.mockito.Mockito.doReturn(true).`when`(controller).tryAutoPlayback()
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480)) {}
        try {
            controls(activity).getChildAt(2).performClick()
            org.mockito.Mockito.verify(controller).tryAutoPlayback()
            assertFalse(AirPlayPersistence.loadAutoPlayback(activity).enabled)
            assertFalse(PendingReconnect.isPending(controller))
            assertEquals(activity.getString(R.string.settings_auto_playback_scheduled), ShadowToast.getTextOfLatestToast())
        } finally {
            CarPlayBackgroundSession.clear(controller)
        }
    }

    @Test fun settingsRoundTripThroughBackupAndMalformedImportsFailClosed() {
        val activity = activity()
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        AirPlayPersistence.saveAutoPlayback(activity, true)
        AirPlayPersistence.saveAutoPlaybackUrl(activity, "testmusic://play?id=1")
        val backup = SettingsBackupCodec.export(activity, "test")
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(activity, backup).invalid)
        assertEquals(AutoPlaybackOptions(true, "testmusic://play?id=1"), AirPlayPersistence.loadAutoPlayback(activity))
        prefs.edit().putInt("auto_playback_url", 123).putString("auto_playback", "true").commit()
        assertFalse(AirPlayPersistence.loadAutoPlayback(activity).enabled)
        assertNull(AutoPlaybackOptions.validatedUrl(AirPlayPersistence.loadAutoPlayback(activity).appUrl))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h900dp-xhdpi")
    fun compactLargeFontScreenshot() = screenshot("compact-zh", 320)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w800dp-h900dp-xhdpi")
    fun fullLargeFontScreenshot() = screenshot("full-en", 800)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h900dp-xhdpi")
    fun arabicLargeFontScreenshot() = screenshot("compact-ar", 320)

    private fun screenshot(name: String, widthDp: Int) {
        val activity = activity()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val config = Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        val parent = controls(activity).apply { layoutDirection = config.layoutDirection }
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, width, parent.measuredHeight)
        assertTrue(parent.height > 0)
        val toggle = descendants(parent).filterIsInstance<Switch>().single()
        assertTrue(toggle.left >= 0 && toggle.right <= (toggle.parent as View).width)
        for (index in 1..2) {
            val row = parent.getChildAt(index)
            assertTrue(row.height > 0 && row.left >= 0 && row.right <= width && row.bottom <= parent.height)
            descendants(row).filterIsInstance<android.widget.TextView>().forEach { text ->
                val layout = text.layout ?: return@forEach
                assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            }
        }
        saveImage(parent, "$name-controls")
        parent.getChildAt(1).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val decor = dialog.window!!.decorView
        val maxHeight = (900 * activity.resources.displayMetrics.density).toInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, width, decor.measuredHeight)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
        saveImage(decor, "$name-dialog")
        dialog.dismiss()
    }

    private fun saveImage(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
        view.draw(canvas)
        val directory = File("build/reports/auto-playback-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}

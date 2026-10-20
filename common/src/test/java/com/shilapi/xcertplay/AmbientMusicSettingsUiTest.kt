package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.media.AmbientSupportDetails
import com.shilapi.xcertplay.media.AmbientSupportResult
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AmbientColorSource
import com.shilapi.xcertplay.media.AmbientDiagnostics
import com.shilapi.xcertplay.media.AmbientMusicSettings
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", manifest = Config.NONE)
class AmbientMusicSettingsUiTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ambient_music", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun cancelDoesNotApplyAnEnabledDraftOrCheckAccess() {
        activity.ambientSupportCheck = { fail("Cancel must not start an access check"); CompletableFuture.completedFuture(false) }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    @Test fun albumSourceUsesExistingSaveAndCancelDraft() {
        var dialog = open()
        albumSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AmbientColorSource.SELECTED, AmbientMusicSettings.load(activity).colorSource)
        dialog = open()
        albumSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AmbientColorSource.ALBUM, AmbientMusicSettings.load(activity).colorSource)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    private fun albumSwitch(dialog: AlertDialog): Switch = descendants(dialog.window!!.decorView)
        .filterIsInstance<Switch>().single { it.contentDescription == activity.getString(R.string.settings_ambient_album) }

    @Test fun failedReadOnlyCheckLeavesControlDisabledAndExplainsWhy() {
        var checks = 0
        activity.ambientSupportCheck = { checks++; CompletableFuture.completedFuture(false) }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.settings_ambient_unavailable), ShadowToast.getTextOfLatestToast())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun canceledDialogIgnoresACompletedSupportCheck() {
        val pending = CompletableFuture<Boolean>()
        activity.ambientSupportCheck = { pending }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pending.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        val report = AmbientDiagnostics.report(activity)
        assertTrue(report.contains("stage=save-request result=start enabled=true"))
        assertTrue(report.contains("stage=cancel result=skipped"))
        assertTrue(report.contains("stage=check-complete-dialog-closed result=ok"))
        assertTrue(report.contains("stage=save-result result=skipped"))
    }

    @Test fun disabledSaveRecordsTheDirectCommitWithoutStartingACheck() {
        activity.ambientSupportCheck = { fail("Disabled save must not check access"); CompletableFuture.completedFuture(false) }
        val dialog = open()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val report = AmbientDiagnostics.report(activity)
        assertTrue(report.contains("saved enabled=false"))
        assertTrue(report.contains("stage=configuration-open result=ok"))
        assertTrue(report.contains("stage=save-request result=start enabled=false"))
        assertTrue(report.contains("stage=check-skipped result=skipped enabled=false"))
        assertTrue(report.contains("stage=save-result result=ok enabled=false"))
    }

    @Test fun synchronousCheckFailureRestoresTheSaveButtonAndRecordsOnlyItsType() {
        activity.ambientSupportCheck = { throw IllegalStateException("private-device-message") }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(dialog.isShowing)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        val report = AmbientDiagnostics.report(activity)
        assertTrue(report.contains("stage=enable-change result=changed enabled=true"))
        assertTrue(report.contains("stage=check-complete result=failed error=IllegalStateException"))
        assertFalse(report.contains("private-device-message"))
    }

    @Test fun backCancellationRecordsAnEventWithoutSaving() {
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        assertTrue(AmbientDiagnostics.report(activity).contains("stage=cancel result=skipped enabled=true"))
    }

    @Test fun unverifiedOriginalColorsStayVisibleAndNeverSaveTheDraft() {
        val details = AmbientSupportDetails("color-unverified", "lamp-state", "57,57,6,1,2", rawColorConfig = 64)
        val result = AmbientSupportResult("test", details).apply { complete(false) }
        activity.ambientSupportCheck = { result }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val expected = activity.getString(R.string.settings_ambient_color_unverified, 57, 57)
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
        val visible = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }
        assertTrue(visible.any { it.text.contains(expected) && it.text.contains("rawState=57,57,6,1,2") && it.text.contains("rawColorConfig=64") })
        assertTrue(dialog.isShowing)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    @Test fun identifying127ColorsSwitchesTheGridAndAsksBeforeSaving() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ambient_profile", 0).edit().clear().commit()
        var checks = 0
        activity.ambientSupportCheck = {
            checks++
            com.shilapi.xcertplay.media.AmbientColorProfile.record(activity, android.os.Build.FINGERPRINT, 6)
            AmbientSupportResult("test", AmbientSupportDetails("none", "check", "32,32,6,6,3",
                rawColorConfig = 6, observedProfile = "oem127")).apply { complete(true) }
        }
        val dialog = open()
        fun grid() = descendants(dialog.window!!.decorView).filterIsInstance<AmbientSwatchGrid>().single()
        assertEquals(31, grid().childCount)
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertEquals(127, grid().childCount)
        assertEquals(com.shilapi.xcertplay.media.AmbientPalette.OEM127, grid().palette)
        assertEquals(activity.getString(R.string.settings_ambient_palette_changed, 127), ShadowToast.getTextOfLatestToast())
        assertTrue(dialog.isShowing)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        grid().getChildAt(56).performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, checks)
        val saved = AmbientMusicSettings.load(activity)
        assertTrue(saved.enabled)
        assertTrue(57 in saved.selectedColors)
    }

    @Test fun detailedReadFailureShowsTheSpecificStageWithoutChangingTheSavedSetting() {
        val details = AmbientSupportDetails("check-failed", "method-getInt", error = "NoSuchMethodException")
        activity.ambientSupportCheck = { AmbientSupportResult("test", details).apply { complete(false) } }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val texts = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.any { it.contains("failureStage=method-getInt") && it.contains("error=NoSuchMethodException") })
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    private fun open(): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showAmbientConfiguration")
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun enableSwitch(dialog: AlertDialog): Switch = descendants(dialog.window!!.decorView)
        .filterIsInstance<Switch>().single { it.contentDescription == activity.getString(R.string.settings_ambient_enable) }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}

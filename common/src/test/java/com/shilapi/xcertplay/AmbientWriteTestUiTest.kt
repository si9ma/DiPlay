package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydAmbientWriteTest
import com.shilapi.xcertplay.media.AmbientDiagnostics
import com.shilapi.xcertplay.media.AmbientMusicSettings
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN", manifest = Config.NONE)
class AmbientWriteTestUiTest {
    private fun setUp(confirm: (BydAmbientWriteTest.Outcome) -> CompletableFuture<Boolean> = { CompletableFuture.completedFuture(false) },
        run: ((String) -> Unit) -> CompletableFuture<BydAmbientWriteTest.Outcome>): Pair<Activity, LinearLayout> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.getSharedPreferences("ambient_music", 0).edit().clear().commit()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(root)
        AmbientWriteTestUi.render(activity, root,
            { text, click -> Button(activity).apply { this.text = text; setOnClickListener { click() } } },
            { text -> TextView(activity).apply { this.text = text } }, run, confirm)
        return activity to root
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun cancelRunsNothingAndPassRecordsWhatTheDriverSaw() {
        var calls = 0
        val pending = CompletableFuture<BydAmbientWriteTest.Outcome>()
        val (activity, root) = setUp(confirm = { CompletableFuture.completedFuture(true) }) { calls++; pending }
        val start = root.findViewWithTag<View>("ambient_write_test")
        start.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals(0, calls)
        start.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(1, calls)
        assertFalse(start.isEnabled)
        pending.complete(BydAmbientWriteTest.Outcome(BydAmbientWriteTest.Result.PASSED, 57,
            "127,127,6,6,3", "57,127,6,6,1", restored = "127,127,6,6,3")); idle()
        assertTrue(start.isEnabled)
        val question = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(question).message.contains(activity.getString(R.string.settings_ambient_verify_color_code, 57)))
        question.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val status = root.findViewWithTag<TextView>("ambient_write_test_status").text.toString()
        assertTrue(status.contains(activity.getString(R.string.settings_ambient_verify_authorised)))
        assertTrue(AmbientDiagnostics.report(activity).contains("stage=write-test-confirm result=ok seen=yes testColor=57"))
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    @Test fun noOrUnsureDoesNotCommitAndFailedCommitNeverEnablesLighting() {
        for (button in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            var commits = 0
            val (activity, root) = setUp(confirm = { commits++; CompletableFuture.completedFuture(false) }) {
                CompletableFuture.completedFuture(BydAmbientWriteTest.Outcome(BydAmbientWriteTest.Result.PASSED, 57,
                    "127,127,6,6,3", "57,57,6,6,3", restored = "127,127,6,6,3", confirmation = "a".repeat(32)))
            }
            root.findViewWithTag<View>("ambient_write_test").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(button).performClick(); idle()
            assertEquals(if (button == AlertDialog.BUTTON_POSITIVE) 1 else 0, commits)
            if (button == AlertDialog.BUTTON_POSITIVE) assertEquals(activity.getString(R.string.settings_ambient_verify_commit_failed),
                root.findViewWithTag<TextView>("ambient_write_test_status").text.toString())
            assertFalse(AmbientMusicSettings.load(activity).enabled)
        }
    }

    @Test fun unconfirmedRestoreTellsTheDriverToRestoreByHandWithoutAskingAboutColor() {
        val (activity, root) = setUp { CompletableFuture.completedFuture(BydAmbientWriteTest.Outcome(
            BydAmbientWriteTest.Result.RESTORE_FAILED, original = "127,127,6,6,3")) }
        root.findViewWithTag<View>("ambient_write_test").performClick()
        val confirm = ShadowAlertDialog.getLatestAlertDialog()
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val status = root.findViewWithTag<TextView>("ambient_write_test_status").text.toString()
        assertTrue(status.contains(activity.getString(R.string.settings_ambient_write_test_restore_failed, "127,127,6,6,3")))
        assertSame(confirm, ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }
}

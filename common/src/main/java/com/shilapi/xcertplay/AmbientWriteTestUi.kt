package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydAmbientWriteTest
import com.shilapi.xcertplay.media.AmbientDiagnostics
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Parked, confirmed, one-shot lamp write test. Its result is diagnostic only and never enables control. */
internal object AmbientWriteTestUi {
    fun render(activity: Activity, parent: LinearLayout,
        button: (String, () -> Unit) -> View, label: (String) -> TextView,
        run: ((String) -> Unit) -> CompletableFuture<BydAmbientWriteTest.Outcome>) {
        parent.addView(label(activity.getString(R.string.settings_ambient_write_test_hint)))
        val status = label("").apply { tag = "ambient_write_test_status"; visibility = View.GONE; setTextIsSelectable(true) }
        lateinit var start: View
        start = button(activity.getString(R.string.settings_ambient_write_test)) {
            activity.appDialogBuilder().setTitle(R.string.settings_ambient_write_test)
                .setMessage(R.string.settings_ambient_write_test_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_ambient_write_test_start) { _, _ ->
                    if (!start.isEnabled) return@setPositiveButton
                    start.isEnabled = false
                    status.visibility = View.VISIBLE
                    status.setText(R.string.settings_ambient_write_test_running)
                    val app = activity.applicationContext
                    val id = UUID.randomUUID().toString()
                    AmbientDiagnostics.record(app, "stage=write-test-confirm result=ok", id)
                    val future = try { run { AmbientDiagnostics.record(app, it, id) } } catch (_: Exception) {
                        CompletableFuture.completedFuture(BydAmbientWriteTest.Outcome(BydAmbientWriteTest.Result.WORKER_FAILED))
                    }
                    future.whenComplete { outcome, _ -> activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        start.isEnabled = true
                        val result = outcome ?: BydAmbientWriteTest.Outcome(BydAmbientWriteTest.Result.WORKER_FAILED)
                        status.text = summary(activity, result)
                        if (result.result == BydAmbientWriteTest.Result.PASSED) askWhatWasSeen(activity, result, status) { seen ->
                            AmbientDiagnostics.record(app, "stage=write-test-confirm result=ok seen=$seen testColor=${result.color}", id)
                        }
                    } }
                }.show()
        }.apply { tag = "ambient_write_test" }
        parent.addView(start, LinearLayout.LayoutParams(-1, -2))
        parent.addView(status)
    }

    private fun askWhatWasSeen(activity: Activity, outcome: BydAmbientWriteTest.Outcome, status: TextView, record: (String) -> Unit) {
        val colorName = activity.getString(if (outcome.color == 20) R.string.settings_ambient_write_test_blue
            else R.string.settings_ambient_write_test_green)
        fun answer(seen: String, text: Int) {
            record(seen)
            status.text = summary(activity, outcome) + "\n" + activity.getString(text)
        }
        activity.appDialogBuilder().setTitle(R.string.settings_ambient_write_test)
            .setMessage(activity.getString(R.string.settings_ambient_write_test_question, colorName))
            .setPositiveButton(R.string.settings_ambient_write_test_yes) { _, _ -> answer("yes", R.string.settings_ambient_write_test_seen_yes) }
            .setNegativeButton(R.string.settings_ambient_write_test_no) { _, _ -> answer("no", R.string.settings_ambient_write_test_seen_no) }
            .setNeutralButton(R.string.settings_ambient_write_test_unsure) { _, _ -> answer("unsure", R.string.settings_ambient_write_test_seen_unsure) }
            .setCancelable(false)
            .show()
    }

    internal fun summary(activity: Activity, outcome: BydAmbientWriteTest.Outcome): String {
        val states = listOfNotNull(outcome.original?.let { "original=$it" }, outcome.applied?.let { "applied=$it" },
            outcome.lastRead?.let { "lastRead=$it" }, outcome.restored?.let { "restored=$it" }).joinToString(" ")
        val text = when (outcome.result) {
            BydAmbientWriteTest.Result.PASSED -> activity.getString(R.string.settings_ambient_write_test_passed)
            BydAmbientWriteTest.Result.RESTORE_FAILED ->
                activity.getString(R.string.settings_ambient_write_test_restore_failed, outcome.original ?: "?")
            BydAmbientWriteTest.Result.BUSY -> activity.getString(R.string.settings_ambient_write_test_busy)
            BydAmbientWriteTest.Result.NOT_127_PROFILE -> activity.getString(R.string.settings_ambient_write_test_not_127)
            else -> activity.getString(R.string.settings_ambient_write_test_failed, outcome.result.name)
        }
        return if (states.isEmpty()) text else "$text\n$states"
    }
}

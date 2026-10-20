package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydAmbientSdkProbe
import com.shilapi.xcertplay.media.Oem127Palette
import java.util.concurrent.CompletableFuture

internal object AmbientSdkDiagnosticsUi {
    fun render(activity: Activity, parent: LinearLayout,
        button: (String, () -> Unit) -> View, label: (String) -> TextView,
        check: () -> CompletableFuture<BydAmbientSdkProbe.Outcome>) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
        parent.addView(label(activity.getString(R.string.settings_ambient_sdk_hint)))
        val details = label(BydAmbientSdkProbe.report(activity)).apply {
            tag = "ambient_sdk_details"; visibility = View.GONE; setTextIsSelectable(true)
        }
        lateinit var start: View
        start = button(activity.getString(R.string.settings_ambient_sdk_check)) {
            activity.appDialogBuilder().setTitle(R.string.settings_ambient_sdk_check)
                .setMessage(R.string.settings_ambient_sdk_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!start.isEnabled) return@setPositiveButton
                    start.isEnabled = false
                    details.visibility = View.VISIBLE
                    details.setText(R.string.settings_ambient_sdk_running)
                    val future = try { check() } catch (_: Exception) {
                        start.isEnabled = true; details.setText(R.string.settings_ambient_sdk_failed); return@setPositiveButton
                    }
                    future.whenComplete { result, error -> activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        start.isEnabled = true
                        val message = when {
                            error != null || result == null -> R.string.settings_ambient_sdk_failed
                            result.busy -> R.string.settings_ambient_sdk_busy
                            result.complete -> R.string.settings_ambient_sdk_complete
                            else -> R.string.settings_ambient_sdk_partial
                        }
                        details.text = activity.getString(message) + (result?.details?.let { "\n$it" } ?: "")
                    } }
                }.show()
        }.apply { tag = "ambient_sdk_check" }
        parent.addView(start, LinearLayout.LayoutParams(-1, -2))
        parent.addView(button(activity.getString(R.string.settings_ambient_sdk_results)) {
            details.text = BydAmbientSdkProbe.report(activity)
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply { tag = "ambient_sdk_results" }, LinearLayout.LayoutParams(-1, -2))
        parent.addView(details)
        val palette = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "ambient_127_reference"
        }
        parent.addView(button(activity.getString(R.string.settings_ambient_127_palette)) {
            if (palette.childCount == 0) {
                palette.addView(label(activity.getString(R.string.settings_ambient_127_hint)))
                for (first in 1..127 step 4) {
                    val row = LinearLayout(activity)
                    for (raw in first..minOf(first + 3, 127)) {
                        val rgb = Oem127Palette.previewRgb(raw)
                        val hex = String.format(java.util.Locale.ROOT, "#%06X", rgb and 0xffffff)
                        row.addView(TextView(activity).apply {
                            text = "$raw\n$hex"; textSize = 12f; gravity = Gravity.CENTER
                            minHeight = dp(56); setPadding(dp(2), dp(4), dp(2), dp(4))
                            setBackgroundColor(rgb)
                            setTextColor(if (Color.red(rgb) * 299 + Color.green(rgb) * 587 + Color.blue(rgb) * 114 > 140000) Color.BLACK else Color.WHITE)
                            contentDescription = activity.getString(R.string.settings_ambient_127_color, raw, raw - 1, hex)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    palette.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            }
            palette.visibility = if (palette.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply { tag = "ambient_127_palette" }, LinearLayout.LayoutParams(-1, -2))
        parent.addView(palette)
    }
}

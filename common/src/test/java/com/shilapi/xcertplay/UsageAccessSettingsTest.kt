package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class UsageAccessSettingsTest {
    private var controller: ActivityController<DiPlayActivity>? = null

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    private fun activity() = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
        setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    private fun controls(activity: DiPlayActivity, method: String = "usageAccessControls") = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        ReflectionHelpers.callInstanceMethod<Unit>(activity, method,
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, this))
    }

    private fun setAccess(activity: DiPlayActivity, mode: Int) {
        shadowOf(activity.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), activity.packageName, mode)
    }

    @Test fun statusChecksAppOpsAndCanBeRefreshedWithoutChangingSettings() {
        val activity = activity()
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        AirPlayPersistence.saveCarPlayOnlyOn(activity, true)
        AirPlayPersistence.saveCarPlayRequireSplit(activity, true)
        val saved = prefs.all.toMap()
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        val parent = controls(activity)
        val check = parent.getChildAt(0) as TextView
        assertTrue(check.text.endsWith(activity.getString(R.string.permission_not_enabled)))
        setAccess(activity, AppOpsManager.MODE_ALLOWED)
        check.performClick()
        assertTrue(check.text.endsWith(activity.getString(R.string.permission_enabled_ready)))
        assertEquals(activity.getString(R.string.permission_enabled_ready), ShadowToast.getTextOfLatestToast())
        setAccess(activity, AppOpsManager.MODE_DEFAULT)
        check.performClick()
        assertTrue(check.text.endsWith(activity.getString(R.string.permission_not_enabled)))
        assertEquals(saved, prefs.all)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test fun grantEntryTargetsThisAppWithoutChangingDisplayPreferences() {
        val activity = activity()
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        val saved = prefs.all.toMap()
        controls(activity).getChildAt(2).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        assertEquals("package:${activity.packageName}", intent.dataString)
        assertEquals(saved, prefs.all)
    }

    @Test fun unavailablePerAppScreenFallsBackToUsageAccessList() {
        val activity = spy(activity())
        val attempts = mutableListOf<Intent>()
        doAnswer { invocation ->
            val intent = invocation.getArgument<Intent>(0)
            attempts.add(intent)
            if (intent.data != null) throw ActivityNotFoundException()
            null
        }.`when`(activity).startActivity(any(Intent::class.java))
        controls(activity).getChildAt(2).performClick()
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.action == Settings.ACTION_USAGE_ACCESS_SETTINGS })
        assertNotNull(attempts.first().data)
        assertNull(attempts.last().data)
    }

    @Test fun missingSystemScreensOfferCopyableUserAwareAdbCommand() {
        val activity = spy(activity())
        activity.applicationInfo.uid = 1_012_345
        val attempts = mutableListOf<Intent>()
        doAnswer { invocation ->
            attempts.add(invocation.getArgument(0))
            throw ActivityNotFoundException()
        }.`when`(activity).startActivity(any(Intent::class.java))
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        val saved = prefs.all.toMap()
        activity.usbPermissionOperationFactory = { error("Opening or copying the fallback must not connect ADB") }
        controls(activity).getChildAt(2).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, attempts.size)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val command = "adb shell appops set --user 10 ${activity.packageName} GET_USAGE_STATS allow"
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains(command) })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        assertEquals(command, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(saved, prefs.all)
    }

    @Test fun networkAdbButtonGrantsOnlyUsageAndRefreshesStatus() {
        val activity = activity()
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        val saved = prefs.all.toMap()
        val before = UsbPermissionSetup.snapshot(activity)
        val client = UsageClient { setAccess(activity, AppOpsManager.MODE_ALLOWED) }
        activity.usbPermissionOperationFactory = { UsbPermissionSetup.Operation(it, { client }) }
        val dialog = usageFallback(activity)
        assertNull(client.worker)
        grantButton(activity, dialog).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val progress = ShadowAlertDialog.getLatestAlertDialog()
        client.awaitCompletion()
        assertFalse(progress.isShowing)
        assertEquals(1, client.commands.size)
        assertTrue(client.commands.single().contains("GET_USAGE_STATS allow"))
        assertFalse(client.commands.single().contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(client.commands.single().contains("accessibility"))
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text.startsWith(activity.getString(R.string.settings_usage_access) + " · ") &&
                it.text.endsWith(activity.getString(R.string.permission_enabled_ready))
        })
        assertEquals(activity.getString(R.string.permission_enabled_ready), ShadowToast.getTextOfLatestToast())
        assertEquals(before + (UsbPermissionSetup.Permission.USAGE to true), UsbPermissionSetup.snapshot(activity))
        assertEquals(saved, prefs.all)
        assertFalse(PendingReconnect.isPending(null))
        assertNull(ReflectionHelpers.getField<UsbPermissionSetup.Operation?>(activity, "usbPermissionOperation"))
    }

    @Test fun unverifiedUsageAndAdbFailuresRetainRetryAndManualCommand() {
        val activity = activity()
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        for ((access, message) in listOf(
            com.shilapi.xcertplay.adb.LocalAdb.Access.READY to R.string.settings_usage_adb_failed,
            com.shilapi.xcertplay.adb.LocalAdb.Access.NOT_APPROVED to R.string.adb_not_approved,
            com.shilapi.xcertplay.adb.LocalAdb.Access.UNREACHABLE to R.string.adb_off,
            com.shilapi.xcertplay.adb.LocalAdb.Access.UNSUPPORTED to R.string.adb_pairing_only,
        )) {
            val client = UsageClient().apply { this.access = access }
            activity.usbPermissionOperationFactory = { UsbPermissionSetup.Operation(it, { client }) }
            grantButton(activity, usageFallback(activity)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            client.awaitCompletion()
            val failure = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(failure.isShowing)
            assertTrue(descendants(failure.window!!.decorView).filterIsInstance<TextView>().any {
                it.text == activity.getString(message)
            })
            assertTrue(grantButton(activity, failure).isEnabled)
            assertTrue(failure.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertFalse(UsbPermissionSetup.Permission.USAGE.granted(activity))
            if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) assertTrue(client.commands.isEmpty())
            failure.dismiss()
        }
    }

    @Test fun busyOrAlreadyGrantedUsageDoesNotStartAnotherAdbOperation() {
        val activity = activity()
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        activity.usbPermissionOperationFactory = { error("No ADB operation expected") }
        for (field in listOf("adbCheckInProgress", "adbSwitchChangePending", "automaticVehicleValidationInProgress")) {
            ReflectionHelpers.setField(activity, field, true)
            grantButton(activity, usageFallback(activity)).performClick()
            assertEquals(activity.getString(R.string.settings_usage_adb_busy), ShadowToast.getTextOfLatestToast())
            ReflectionHelpers.setField(activity, field, false)
        }
        setAccess(activity, AppOpsManager.MODE_ALLOWED)
        grantButton(activity, usageFallback(activity)).performClick()
        assertEquals(activity.getString(R.string.permission_enabled_ready), ShadowToast.getTextOfLatestToast())
    }

    private fun usageFallback(activity: DiPlayActivity): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPageUsageAccessSetup",
            ReflectionHelpers.ClassParameter.from(String::class.java, null))
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun grantButton(activity: DiPlayActivity, dialog: AlertDialog) =
        descendants(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().single {
            it.text == activity.getString(R.string.settings_usage_adb_grant)
        }

    private class UsageClient(private val onGrant: () -> Unit = {}) : UsbPermissionSetup.Client {
        var access = com.shilapi.xcertplay.adb.LocalAdb.Access.READY
        @Volatile var worker: Thread? = null
        private val finished = java.util.concurrent.CountDownLatch(1)
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun connect(): com.shilapi.xcertplay.adb.LocalAdb.Access {
            worker = Thread.currentThread()
            return access
        }
        override fun shell(command: String): String {
            commands += command
            onGrant()
            return "DIPLAY_PERMISSION_EXIT:0"
        }
        override fun cancel() {}
        override fun close() { finished.countDown() }
        fun awaitCompletion() {
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            worker!!.join(5_000)
            assertFalse(worker!!.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun returningFromSystemSettingsReflectsGrantAndRevocationWithoutReconnect() {
        val app = RuntimeEnvironment.getApplication()
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller!!.get()
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        val saved = prefs.all.toMap()
        fun status() = descendants(activity.window.decorView).filterIsInstance<TextView>().single {
            it.text.startsWith(activity.getString(R.string.settings_usage_access) + " · ")
        }.text
        assertTrue(status().endsWith(activity.getString(R.string.permission_not_enabled)))
        for (mode in listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED)) {
            controller!!.pause()
            setAccess(activity, mode)
            controller!!.resume()
            shadowOf(Looper.getMainLooper()).idle()
            val expected = if (mode == AppOpsManager.MODE_ALLOWED) R.string.permission_enabled_ready else R.string.permission_not_enabled
            assertTrue(status().endsWith(activity.getString(expected)))
        }
        assertEquals(saved, prefs.all)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h900dp-xhdpi")
    fun compactLargeFontAccessScreenshots() = screenshots("compact-zh", 320)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w800dp-h900dp-xhdpi")
    fun fullLargeFontAccessScreenshots() = screenshots("full-en", 800)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h900dp-xhdpi")
    fun arabicLargeFontAccessScreenshots() = screenshots("compact-ar", 320)

    private fun screenshots(name: String, widthDp: Int) {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = activity()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        ReflectionHelpers.setField(activity, "page", "settings")
        val overview = controls(activity, "settingsOverview")
        val check = descendants(overview).filterIsInstance<TextView>().single {
            it.text == activity.getString(R.string.check_adb_access)
        }
        val adbCard = check.parent as ViewGroup
        val readiness = ReflectionHelpers.getField<View>(activity, "readinessCard")
        assertEquals(overview.indexOfChild(adbCard) + 1, overview.indexOfChild(readiness))
        renderImage(adbCard, "$name-adb", widthDp)
        setAccess(activity, AppOpsManager.MODE_IGNORED)
        val split = controls(activity, "onlyOnPagesControl")
        split.removeViews(5, split.childCount - 5)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "normalizeSpacing",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, split),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        renderImage(split, "$name-split", widthDp)
        activity.usbPermissionOperationFactory = { error("Screenshots must not start ADB") }
        val dialog = usageFallback(activity)
        renderImage(dialog.window!!.decorView as ViewGroup, "$name-network-adb", widthDp, 900)
        assertTrue(grantButton(activity, dialog).isShown)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
        dialog.dismiss()
    }

    private fun renderImage(view: ViewGroup, name: String, widthDp: Int, maxHeightDp: Int = 0) {
        view.layoutDirection = view.resources.configuration.layoutDirection
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        val maxHeight = (maxHeightDp * view.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, if (maxHeightDp > 0) View.MeasureSpec.AT_MOST else View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        assertTrue(view.height > 0)
        for (text in descendants(view).filterIsInstance<TextView>()) {
            val layout = text.layout ?: continue
            assertTrue(text.toString(), layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
        view.draw(canvas)
        val dir = File("build/reports/settings-access-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}

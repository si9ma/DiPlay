package com.shilapi.xcertplay

import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyPermissionSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ours get() = ComponentName(context, WheelKeyService::class.java).flattenToString()
    private val other = "car.package/.Accessibility"
    private val commands = mutableListOf<String>()
    private var reading = other
    private var reply: String? = "\nDIPLAY_WHEEL_EXIT:0"
    private var allow = true

    @Before fun setup() {
        WheelKeyService.resetDiagnosticsForTest()
        context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        WheelZoomSettings.setEnabled(context, false)
        WheelZoomSettings.setJoystick(context, false)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    @Test fun nullEmptyOrFailedReadDoesNotWriteTheServiceList() {
        for (output in listOf(null, "", "$other\nDIPLAY_WHEEL_EXIT:1")) {
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, { commands += it; output }))
            assertEquals(1, commands.size)
            assertEquals(other, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        }
    }

    @Test fun malformedOrShellActiveValuesAreRejectedBeforeAnyWrite() {
        for (value in listOf("Permission denied", "$other:'; touch /tmp/unsafe; '", "$other::bad", "$other\nbad")) {
            reading = value
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
            assertEquals(1, commands.size)
        }
    }

    @Test fun stoppedServiceIsReboundWhileOtherServicesKeepTheirOrder() {
        reading = "$other:$ours:another.package/.Service"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
        assertEquals(2, writes.size)
        assertTrue(writes[0].contains("'$other:another.package/.Service'"))
        // Android clears its crashed mark only if its observer sees the list without the service (#606).
        assertTrue(writes[0].contains("&& sleep 1"))
        assertFalse(writes[1].contains("sleep"))
        assertEquals("$other:another.package/.Service:$ours",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun healthyServiceIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            reading = "$other:$ours"
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun serviceBindingAfterTheReadIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        reading = "$other:$ours"
        var continuationChecks = 0
        try {
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell) {
                if (++continuationChecks == 2) {
                    service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
                }
                true
            })
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun shellFailureStopsFurtherWritesAndDoesNotClaimSuccess() {
        reply = "DIPLAY_WHEEL_EXIT:1"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.contains("settings put secure accessibility_enabled") })
    }

    @Test fun successfulShellWithoutActualPermissionIsNotSuccess() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command ->
            if (command.contains("settings get")) "$other\nDIPLAY_WHEEL_EXIT:0" else "DIPLAY_WHEEL_EXIT:0"
        }))
    }

    @Test fun disablingTheFeaturesDuringTheRestoreGraceStopsTheRequest() {
        WheelZoomSettings.setEnabled(context, true)
        assertTrue(WheelKeyService.needsRestore(context))
        WheelZoomSettings.setEnabled(context, false)
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    @Test fun disablingAfterTheReadStopsAllWrites() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command -> shell(command).also { allow = false } }) { allow })
        assertEquals(1, commands.size)
    }

    @Test fun navigationOnlyRestorationRequiresRecordedAuthorizationAndStopsWhenDisabled() {
        val prefs = context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE)
        assertFalse(NavigationWheelSettings.enabled(context))
        prefs.edit().putBoolean("enabled", true).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        NavigationWheelSettings.recordAuthorization(context)
        assertTrue(WheelKeyService.needsRestore(context))
        prefs.edit().putBoolean("enabled", false).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    /**
     * BYD runs DiPlay as user 10. The crash-reset write once lost its --user in a merge, so it cleared
     * user 0's list: the service stayed crashed on the user it runs as, and the keys never came back.
     */
    @Test fun everyWriteOnASecondaryUserNamesThatUser() {
        val info = context.applicationInfo
        val uid = info.uid
        info.uid = 10 * 100_000 + uid % 100_000
        try {
            val seen = mutableListOf<String>()
            WheelKeyService.applyServiceSettings(context, { command ->
                seen += command
                if (command.contains(" get secure ")) "$other:$ours\nDIPLAY_WHEEL_EXIT:0" else "\nDIPLAY_WHEEL_EXIT:0"
            })
            val writes = seen.filter { it.contains(" put secure ") }
            // The crash reset, the list and accessibility_enabled, plus the retry this fake provokes by
            // not storing anything.
            assertTrue(seen.toString(), writes.size >= 3)
            writes.forEach { assertTrue(it, it.contains("settings --user 10 put secure ")) }
            seen.filter { it.contains("appops ") }.forEach { assertTrue(it, it.contains(" --user 10 ")) }
        } finally {
            info.uid = uid
        }
    }

    /**
     * A Di150VCP accepted both writes and the list no longer held DiPlay straight after. The
     * investigation must record what the car's shell sees, the restricted-settings appop and the
     * car's own log, and allow the appop before writing the list once more.
     */
    @Test fun aWriteTheCarRevertsIsInvestigatedAndRetriedWithRestrictedSettingsAllowed() {
        val seen = mutableListOf<String>()
        var opAllowed = false
        val ok = WheelKeyService.applyServiceSettings(context, { command ->
            seen += command
            when {
                command.contains(" get secure ") -> "$other\nDIPLAY_WHEEL_EXIT:0"
                command.contains("appops get") -> "ACCESS_RESTRICTED_SETTINGS: ignore\nDIPLAY_WHEEL_EXIT:0"
                command.contains("appops set") -> { opAllowed = true; "\nDIPLAY_WHEEL_EXIT:0" }
                command.contains("logcat") -> "W/AccessibilityManagerService( 900): removing restricted service\nDIPLAY_WHEEL_EXIT:0"
                // The car keeps DiPlay only once the restricted setting is allowed.
                command.contains("put secure enabled_accessibility_services") && opAllowed -> {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$other:$ours")
                    "\nDIPLAY_WHEEL_EXIT:0"
                }
                command.contains("put secure accessibility_enabled") -> {
                    Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    "\nDIPLAY_WHEEL_EXIT:0"
                }
                else -> "\nDIPLAY_WHEEL_EXIT:0"
            }
        })
        assertTrue(WheelKeyService.lastApplyStep, ok)
        val step = WheelKeyService.lastApplyStep
        assertTrue(step, step.contains("listedAfterWrite=false"))
        assertTrue(step, step.contains("shellListed=false"))
        assertTrue(step, step.contains("restricted=ignore"))
        assertTrue(step, step.contains("listedAfterRetry=true"))
        assertTrue(seen.toString(), seen.any { it.contains("ACCESS_RESTRICTED_SETTINGS allow") })
        // The grep's own audit record must not be able to match its pattern.
        val grep = seen.single { it.contains("logcat") }
        assertFalse(grep, Regex(grep.substringAfter("grep -E '").substringBefore("'")).containsMatchIn(grep))
        assertTrue(WheelKeyService.revertReport().toString(), WheelKeyService.revertReport().any { it.contains("car log: W/AccessibilityManagerService") })
    }

    @Test fun theReportSaysWhichStepFailed() {
        reply = "\nDIPLAY_WHEEL_EXIT:1"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
        assertEquals("failed at write of the allowed list", WheelKeyService.lastApplyStep)
        assertTrue(WheelKeyService.report(context).contains("lastManual=\"failed at write of the allowed list\""))
    }

    private fun shell(command: String): String? {
        commands += command
        if (command.contains("settings get")) return "$reading\nDIPLAY_WHEEL_EXIT:0"
        if (reply?.trim() != "DIPLAY_WHEEL_EXIT:0") return reply
        if (command.contains("settings put secure enabled_accessibility_services")) {
            val value = command.substringAfter("enabled_accessibility_services '").substringBefore("'")
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        } else if (command.contains("settings put secure accessibility_enabled 1")) {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return reply
    }
}

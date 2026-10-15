package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupDiagnosticSnapshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
    }

    @Test fun disabledBootIsRecordedWithoutLaunchingOrChangingTheSetting() {
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = true }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(launched)
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchEnabledAtBoot=false launchResult=disabled"))
    }

    @Test fun successfulLaunchMeansAnActivityRequestAndRetainsTheExistingFlags() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        var launch: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launch = intent }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(DiPlayActivity::class.java.name, launch!!.component!!.className)
        assertTrue(launch!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=startActivity-returned"))
    }

    @Test fun launchFailureRecordsOnlyItsClassAndASecondBootReplacesOldEvidence() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw SecurityException("Jane's phone token=private-data") }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val failed = StartupDiagnosticSnapshot.report(app)
        assertTrue(failed.contains("launchResult=failed failureClass=SecurityException"))
        assertFalse(failed.contains("Jane")); assertFalse(failed.contains("private-data"))
        assertNotNull(DiagnosticRedactor.redact(failed))
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=disabled failureClass=none"))
    }

    @Test fun unrelatedBroadcastsDoNotCreateOrOverwriteBootEvidence() {
        BootReceiver().onReceive(app, Intent("custom.intent.token=secret"))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("received=false"))
    }

    /** The install time decides what a missing delivery means, so it has to be in the report. */
    private fun setUpdatedAt(epochMillis: Long) {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.lastUpdateTime = epochMillis
        info.firstInstallTime = epochMillis
        org.robolectric.Shadows.shadowOf(app.packageManager).installPackage(info)
    }

    /**
     * A build installed after the last restart has had no boot to miss. Reporting that as a refusal
     * would send someone chasing their firmware's autostart list over nothing, which is the usual
     * case while beta builds are being replaced several times a day.
     */
    @Test fun aBuildInstalledSinceTheLastRestartCannotHaveMissedABoot() {
        setUpdatedAt(System.currentTimeMillis())
        val verdict = StartupDiagnosticSnapshot.verdict(app)
        assertTrue(verdict, verdict.contains("has NOT restarted since DiPlay was last installed"))
        assertTrue(StartupDiagnosticSnapshot.environment(app).contains("bootedSinceInstall=false"))
    }

    @Test fun aRestartAfterInstallWithNoDeliveryIsTheFirmwareRefusing() {
        setUpdatedAt(0)
        val verdict = StartupDiagnosticSnapshot.verdict(app)
        assertTrue(verdict, verdict.contains("did not hand BOOT_COMPLETED to DiPlay"))
        assertTrue(StartupDiagnosticSnapshot.environment(app).contains("bootedSinceInstall=true"))
    }

    /** Evidence kept from an earlier start-up must not read as the current one having worked. */
    @Test fun aDeliveryOlderThanThisStartUpIsNotCreditedToIt() {
        setUpdatedAt(0)
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(StartupDiagnosticSnapshot.verdict(app).contains("delivered on the current start-up"))

        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit()
            .putLong("receivedAt", 1_000L).commit()
        val stale = StartupDiagnosticSnapshot.verdict(app)
        assertTrue(stale, stale.contains("most recent restart did not hand BOOT_COMPLETED"))
    }

    @Test fun theEnvironmentCarriesNothingThatIdentifiesTheCar() {
        setUpdatedAt(0)
        val environment = StartupDiagnosticSnapshot.environment(app)
        assertTrue(environment, environment.contains("upFor="))
        assertNotNull(DiagnosticRedactor.redact(environment))
        assertNotNull(DiagnosticRedactor.redact(StartupDiagnosticSnapshot.verdict(app)))
    }
}

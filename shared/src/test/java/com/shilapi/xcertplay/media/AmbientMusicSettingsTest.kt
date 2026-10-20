package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.adb.AdbDiagnostics
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun missingPreferencesKeepControlDisabled() {
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(listOf(1), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(3, values.area)
    }

    @Test fun oldSingleColorSettingsSupplyMissingPaletteAndModes() {
        prefs.edit().putInt("color", 12).putInt("brightness", 4).commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(AmbientColorSource.SELECTED, values.colorSource)
        assertEquals(listOf(12), values.selectedColors)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
    }

    @Test fun malformedPreferencesUseSafeDefaultsAndValidBounds() {
        prefs.edit().putString("enabled", "true").putString("music", "false")
            .putInt("color", 90).putInt("brightness", -2).putInt("area", 8)
            .putInt("selectedColors", 8).putString("colorMode", "UNKNOWN")
            .putString("speed", "UNKNOWN").putString("colorSource", "UNKNOWN").commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertTrue(values.music)
        assertEquals(AmbientColorSource.SELECTED, values.colorSource)
        assertEquals(listOf(31), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(3, values.area)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
        assertEquals(AmbientIdleBehavior.STEADY, values.idle)
    }

    @Test fun idleChoiceRoundTripsAndUnknownValuesFallBackToTheSteadyGlow() {
        AmbientMusicSettings.save(context, AmbientMusicSettings.Values(idle = AmbientIdleBehavior.CAR))
        assertEquals(AmbientIdleBehavior.CAR, AmbientMusicSettings.load(context).idle)
        prefs.edit().putString("idle", "UNKNOWN").commit()
        assertEquals(AmbientIdleBehavior.STEADY, AmbientMusicSettings.load(context).idle)
    }

    @Test fun ambientDiagnosticsSurviveARecreatedStoreAndAdbEviction() {
        val target = diagnosticFile()
        val writer = AmbientDiagnosticStore { target }
        val id = UUID.randomUUID().toString()
        writer.record("stage=check result=start mayAsk=false readOnly=true", id, startsCheck = true)
        writer.record("worker stage=read-SET_IAL_BACK_COLOR result=invalid value=0", id)
        writer.record("stage=check result=failed", id)
        AdbDiagnostics.sink = null
        repeat(400) { AdbDiagnostics.record("unrelated traffic $it") }
        val report = AmbientDiagnosticStore { target }.reportAsync().get(5, TimeUnit.SECONDS)
        assertTrue(report.contains("lastCheck time="))
        assertTrue(report.contains("checkId=$id stage=check result=failed"))
        assertTrue(report.contains("stage=read-SET_IAL_BACK_COLOR result=invalid value=0"))
        assertFalse(report.contains("unrelated traffic"))
        assertFalse(report.contains("storage=degraded"))
    }

    @Test fun eventsStayBoundedWithoutEvictingTheLastCheck() {
        val target = diagnosticFile()
        val store = AmbientDiagnosticStore { target }
        val id = UUID.randomUUID().toString()
        store.record("stage=check result=start", id, startsCheck = true)
        store.record("stage=check result=ok", id)
        repeat(AmbientDiagnosticStore.MAX_EVENTS + 20) { store.record("stage=configuration-open result=ok elapsedMs=$it") }
        val report = AmbientDiagnosticStore { target }.reportAsync().get(10, TimeUnit.SECONDS)
        assertEquals(AmbientDiagnosticStore.MAX_EVENTS, report.lineSequence().count { it.startsWith("AMBIENT_SUPPORT") })
        assertTrue(report.contains("checkId=$id stage=check result=ok"))
        assertTrue(target.length() < 192 * 1024)
    }

    @Test fun detailedFailureSurvivesEventEvictionAndAGenericFinalError() {
        val target = diagnosticFile()
        val store = AmbientDiagnosticStore { target }
        val id = UUID.randomUUID().toString()
        store.record("stage=check result=start", id, startsCheck = true)
        store.record("stage=method-setIntArray result=failed error=NoSuchMethodException", id)
        store.record("stage=read result=failed error=IllegalStateException", id)
        store.record("stage=check result=failed", id)
        repeat(AmbientDiagnosticStore.MAX_EVENTS + 1) { store.record("stage=configuration-open result=ok elapsedMs=$it") }
        val report = AmbientDiagnosticStore { target }.reportAsync().get(10, TimeUnit.SECONDS)
        val failure = report.lineSequence().single { it.startsWith("lastFailure ") }
        assertTrue(failure.contains("stage=method-setIntArray result=failed error=NoSuchMethodException"))
        val next = UUID.randomUUID().toString()
        store.record("stage=check result=start", next, startsCheck = true)
        store.record("stage=check result=ok", next)
        assertTrue(store.reportAsync().get(5, TimeUnit.SECONDS).contains("lastFailure none"))
    }

    @Test fun olderConcurrentCheckCannotReplaceANewerFinalResult() {
        val target = diagnosticFile()
        val first = AmbientDiagnosticStore { target }
        val second = AmbientDiagnosticStore { target }
        val old = UUID.randomUUID().toString()
        val latest = UUID.randomUUID().toString()
        first.record("stage=check result=start", old, startsCheck = true)
        second.record("stage=check result=start", latest, startsCheck = true)
        second.record("stage=check result=failed", latest)
        first.record("stage=check result=ok", old)
        second.record("stage=check-complete result=ok", latest)
        val last = first.reportAsync().get(5, TimeUnit.SECONDS).lineSequence().single { it.startsWith("lastCheck") }
        assertTrue(last.contains("checkId=$latest stage=check result=failed"))
    }

    @Test fun storageFailureIsVisibleSafeAndDoesNotLosePendingEvents() {
        val target = diagnosticFile()
        var unavailable = true
        val store = AmbientDiagnosticStore {
            if (unavailable) throw IOException("secret-token shell-command private-path")
            target
        }
        store.record("stage=save-request result=start enabled=false")
        val failed = store.reportAsync().get(5, TimeUnit.SECONDS)
        assertTrue(failed.contains("storage=degraded"))
        assertTrue(failed.contains("IOException"))
        assertTrue(failed.contains("stage=save-request result=start"))
        assertFalse(failed.contains("secret-token"))
        unavailable = false
        store.record("stage=save-result result=ok enabled=false")
        store.reportAsync().get(5, TimeUnit.SECONDS)
        val recovered = AmbientDiagnosticStore { target }.reportAsync().get(5, TimeUnit.SECONDS)
        assertTrue(recovered.contains("stage=save-request result=start"))
        assertTrue(recovered.contains("stage=save-result result=ok"))
    }

    @Test fun corruptedStorageIsReportedWithoutLeakingItsContents() {
        val target = diagnosticFile()
        target.parentFile!!.mkdirs()
        target.writeText("private SDK output")
        val report = AmbientDiagnosticStore { target }.reportAsync().get(5, TimeUnit.SECONDS)
        assertTrue(report.contains("storage=degraded"))
        assertFalse(report.contains("private SDK output"))
    }

    @Test fun aReportWithNoHistoryCreatesNoFilesAndDoesNotProbe() {
        val target = diagnosticFile()
        val report = AmbientDiagnosticStore { target }.reportAsync().get(5, TimeUnit.SECONDS)
        assertTrue(report.contains("lastCheck none"))
        assertFalse(target.parentFile!!.exists())
    }

    @Test fun diagnosticFieldsExcludeCredentialsCommandsAndArbitraryMessages() {
        val safe = AmbientDiagnostics.sanitize("stage=read result=failed value=0 error=IllegalStateException " +
            "token=secret shell=command message=private-path stdout=SDK-output")
        assertEquals("stage=read result=failed value=0 error=IllegalStateException", safe)
        val first = AmbientDiagnostics.beginCheck(context)
        val second = AmbientDiagnostics.beginCheck(context)
        assertNotEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
        AmbientDiagnostics.report(context)
    }

    @Test fun synchronousReadStopAndCloseFailuresAlwaysCompleteAndAttemptCleanup() {
        for (failure in listOf("read", "stop", "close")) {
            var stops = 0
            var closes = 0
            val result = CompletableFuture<Boolean>()
            val logs = mutableListOf<String>()
            runAmbientSupportCheck(result, { it() }, { false }, {
                object : AmbientSupportClient {
                    override fun read(): CompletableFuture<Unit> {
                        if (failure == "read") throw IOException("private-message")
                        return CompletableFuture.completedFuture(Unit)
                    }
                    override fun stop(): CompletableFuture<Unit> {
                        stops++
                        if (failure == "stop") throw IOException("private-message")
                        return CompletableFuture.completedFuture(Unit)
                    }
                    override fun close() { closes++; if (failure == "close") throw IOException("private-message") }
                }
            }, { logs += it })
            assertFalse(result.get(1, TimeUnit.SECONDS))
            assertEquals(1, stops)
            assertEquals(1, closes)
            assertTrue(logs.last().contains("stage=check result=failed"))
            assertFalse(logs.any { it.contains("private-message") })
        }
    }

    @Test fun dispatchAndConstructionFailuresCompleteAndALogFailureDoesNotBreakSuccess() {
        val rejected = CompletableFuture<Boolean>()
        runAmbientSupportCheck(rejected, { throw IllegalStateException() }, { false }, { error("not reached") }, {})
        assertFalse(rejected.get(1, TimeUnit.SECONDS))
        val construction = CompletableFuture<Boolean>()
        runAmbientSupportCheck(construction, { it() }, { false }, { throw IllegalStateException() }, {})
        assertFalse(construction.get(1, TimeUnit.SECONDS))
        val existing = CompletableFuture<Boolean>()
        runAmbientSupportCheck(existing, { it() }, { true }, { error("ready session must not open another client") }, { throw IOException() })
        assertTrue(existing.get(1, TimeUnit.SECONDS))
    }

    @Test fun asynchronousReadFailureStillClosesAndCompletes() {
        val read = CompletableFuture<Unit>()
        val result = CompletableFuture<Boolean>()
        var closed = false
        runAmbientSupportCheck(result, { it() }, { false }, {
            object : AmbientSupportClient {
                override fun read() = read
                override fun stop() = CompletableFuture.completedFuture(Unit)
                override fun close() { closed = true }
            }
        }, {})
        assertFalse(result.isDone)
        read.completeExceptionally(IOException())
        assertFalse(result.get(1, TimeUnit.SECONDS))
        assertTrue(closed)
    }

    @Test fun persistenceRunsOffTheCallerAndReportWaitsForEarlierWrites() {
        val target = diagnosticFile()
        val caller = Thread.currentThread()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val store = AmbientDiagnosticStore {
            worker.set(Thread.currentThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            target
        }
        try {
            store.record("stage=save-result result=ok enabled=false")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotEquals(caller, worker.get())
            val report = store.reportAsync()
            assertFalse(report.isDone)
            release.countDown()
            assertTrue(report.get(5, TimeUnit.SECONDS).contains("stage=save-result result=ok enabled=false"))
        } finally { release.countDown() }
    }

    @Test fun unknownRawColorsAndConfigsSurviveCleanupPersistenceAndEventEviction() {
        val target = diagnosticFile()
        val store = AmbientDiagnosticStore { target }
        val id = UUID.randomUUID().toString()
        store.record("stage=check result=start", id, startsCheck = true)
        val result = AmbientSupportResult(id)
        val operations = mutableListOf<String>()
        runAmbientSupportCheck(result, { it() }, { false }, { log ->
            object : AmbientSupportClient {
                override fun read(): CompletableFuture<Unit> {
                    operations += "read"
                    log("stage=read-SET_IAL_COLOR_CONFIG result=ok value=64 semantics=unknown")
                    log("stage=read-SET_IAL_BRIGHTNESS_CONFIG result=failed error=SecurityException semantics=unknown")
                    log("stage=read-SET_IAL_AREA_CONFIG result=ok value=-1 semantics=unknown")
                    listOf("SET_IAL_FRONT_COLOR" to 57, "SET_IAL_BACK_COLOR" to 57,
                        "SET_IAL_FRONT_BRIGHTNESS" to 6, "SET_IAL_BACK_BRIGHTNESS" to 1,
                        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA" to 2).forEach { (name, value) ->
                        log("stage=read-$name result=ok value=$value")
                    }
                    return CompletableFuture.completedFuture(Unit)
                }
                override fun stop(): CompletableFuture<Unit> { operations += "stop"; return CompletableFuture.completedFuture(Unit) }
                override fun close() { operations += "close" }
            }
        }, { store.record(it, id) })
        assertFalse(result.get(1, TimeUnit.SECONDS))
        assertEquals(listOf("read", "stop", "close"), operations)
        val details = requireNotNull(AmbientMusicController.supportCheckDetails(result))
        assertEquals("color-unverified", details.reason)
        assertEquals("57,57,6,1,2", details.rawState)
        assertEquals(64, details.rawColorConfig)
        assertNull(details.rawBrightnessConfig)
        assertEquals(-1, details.rawAreaConfig)
        repeat(AmbientDiagnosticStore.MAX_EVENTS + 1) { store.record("stage=configuration-open result=ok") }
        val report = AmbientDiagnosticStore { target }.reportAsync().get(10, TimeUnit.SECONDS)
        val failure = report.lineSequence().single { it.startsWith("lastFailure ") }
        assertTrue(failure.contains("reason=color-unverified"))
        assertTrue(failure.contains("rawState=57,57,6,1,2"))
        assertTrue(failure.contains("rawColorConfig=64"))
        assertTrue(failure.contains("rawAreaConfig=-1"))
        assertTrue(failure.contains("semantics=unknown"))
    }

    @Test fun failedOptionalConfigDoesNotRejectAValidThirtyOneColorSnapshot() {
        val result = AmbientSupportResult(UUID.randomUUID().toString())
        runAmbientSupportCheck(result, { it() }, { false }, { log ->
            object : AmbientSupportClient {
                override fun read(): CompletableFuture<Unit> {
                    log("stage=read-SET_IAL_COLOR_CONFIG result=failed error=SecurityException semantics=unknown")
                    listOf("SET_IAL_FRONT_COLOR" to 31, "SET_IAL_BACK_COLOR" to 31,
                        "SET_IAL_FRONT_BRIGHTNESS" to 6, "SET_IAL_BACK_BRIGHTNESS" to 1,
                        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA" to 2).forEach { (name, value) ->
                        log("stage=read-$name result=ok value=$value")
                    }
                    return CompletableFuture.completedFuture(Unit)
                }
                override fun stop() = CompletableFuture.completedFuture(Unit)
                override fun close() = Unit
            }
        }, {})
        assertTrue(result.get(1, TimeUnit.SECONDS))
        assertEquals("31,31,6,1,2", result.details?.rawState)
        assertEquals("none", result.details?.reason)
    }

    private fun check127(front: Int, configs: MutableList<Int> = mutableListOf(), verified: Boolean = true): AmbientSupportResult {
        val result = AmbientSupportResult(UUID.randomUUID().toString())
        runAmbientSupportCheck(result, { it() }, { false }, { log ->
            object : AmbientSupportClient {
                override fun read(): CompletableFuture<Unit> {
                    log("stage=read-SET_IAL_COLOR_CONFIG result=ok value=6")
                    log("stage=verification result=ok value=${if (verified) 1 else 0}")
                    listOf("SET_IAL_FRONT_COLOR" to front, "SET_IAL_BACK_COLOR" to 32,
                        "SET_IAL_FRONT_BRIGHTNESS" to 6, "SET_IAL_BACK_BRIGHTNESS" to 6,
                        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA" to 3).forEach { (name, value) ->
                        log("stage=read-$name result=ok value=$value")
                    }
                    return CompletableFuture.completedFuture(Unit)
                }
                override fun stop() = CompletableFuture.completedFuture(Unit)
                override fun close() = Unit
            }
        }, {}, "any-firmware") { configs += it }
        return result
    }

    @Test fun observed127FirmwareIsSupportedAcrossItsWholeRangeAndReportsItsConfig() {
        val configs = mutableListOf<Int>()
        val result = check127(127, configs)
        assertTrue(result.get(1, TimeUnit.SECONDS))
        assertEquals("none", result.details?.reason)
        assertEquals(listOf(6), configs)
        val summary = result.details!!.diagnosticSummary()
        assertTrue(summary.contains("observedProfile=oem127"))
        assertTrue(summary.contains("expectedColor=1..127"))
        assertTrue(summary.contains("rawState=127,32,6,6,3"))
    }

    @Test fun readable127CandidateWithoutWorkerVerificationCannotEnableControl() {
        val result = check127(127, verified = false)
        assertFalse(result.get(1, TimeUnit.SECONDS))
        assertEquals("profile-write-unverified", result.details?.reason)
        assertEquals(6, result.details?.rawColorConfig)
    }

    @Test fun observed127FirmwareStillRejectsCodesOutsideItsRange() {
        val result = check127(128)
        assertFalse(result.get(1, TimeUnit.SECONDS))
        assertEquals("color-unverified", result.details?.reason)
    }

    @Test fun detailedFieldsRetainOnlyBoundedRawDataAndKnownReasons() {
        val raw = "failureStage=lamp-state reason=color-unverified rawState=57,57,6,1,2 rawColorConfig=64 semantics=unknown"
        assertEquals(raw, AmbientDiagnostics.sanitize(raw + " token=private path=/data/private"))
        assertEquals("", AmbientDiagnostics.sanitize("reason=private failureStage=secret semantics=64-colors rawColorConfig=99999999999999999999"))
    }

    private fun diagnosticFile() = File(context.cacheDir, "ambient-test-${UUID.randomUUID()}/state.bin")
}

package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControlReadinessTest {
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
    private lateinit var controller: CarPlayController
    private lateinit var listener: AirPlaySessionListener
    private val sessions = mutableListOf<AirPlaySession>()
    private val states = mutableListOf<Any?>()

    @Before fun setup() {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        listener = ReflectionHelpers.getField(controller, "sessionListener")
        controller.mediaControlListener = { states.add(controller.mediaControlSessionToken()) }
    }

    @After fun cleanup() {
        controller.mediaControlListener = null
        sessions.forEach(AirPlaySession::close)
        controller.close()
        controller.awaitClosed(2_000)
    }

    private fun session() = AirPlaySession(Socket(), config, identity, PairingStore(), null, listener,
        object : AirPlayMediaHandler {}).also(sessions::add)

    private fun ready(session: AirPlaySession, value: Boolean) {
        session.javaClass.getDeclaredMethod("updateEventReady", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(session, value)
    }

    @Test fun recordThenEventChannelBecomesReady() {
        val session = session()
        listener.onSessionActive(session)
        assertNull(controller.mediaControlSessionToken())
        ready(session, true)
        assertSame(session, controller.mediaControlSessionToken())
        assertSame(session, states.last())
        ready(session, false)
        assertNull(controller.mediaControlSessionToken())
        assertNull(states.last())
    }

    @Test fun eventChannelBeforeRecordDoesNotLoseReadyNotification() {
        val session = session()
        ready(session, true)
        assertNull(controller.mediaControlSessionToken())
        listener.onSessionActive(session)
        assertSame(session, states.last())
    }

    @Test fun closedSessionCannotBecomeReadyAndOldEndDoesNotRetireNewSession() {
        val old = session()
        listener.onSessionActive(old)
        ready(old, true)
        val current = session()
        ready(current, true)
        listener.onSessionActive(current)
        old.close()
        ready(old, true)
        assertSame(current, controller.mediaControlSessionToken())
        current.close()
        ready(current, true)
        assertNull(controller.mediaControlSessionToken())
        assertNull(states.last())
    }

    @Test fun controllerCloseImmediatelyRevokesControlReadiness() {
        val session = session()
        ready(session, true)
        listener.onSessionActive(session)
        controller.close()
        assertNull(controller.mediaControlSessionToken())
        assertNull(states.last())
    }

    @Test fun diagnosticSeparatesQueueAcceptanceFromUnavailableEventChannel() {
        val session = session()
        listener.onSessionActive(session)
        val recorded = CountDownLatch(1)
        var result = ""
        controller.keyCommandListener = { result = it; recorded.countDown() }
        assertTrue(controller.requestSiri())
        assertTrue(recorded.await(2, TimeUnit.SECONDS))
        assertEquals("command=siri written=false reason=event_not_ready", result)
    }
}

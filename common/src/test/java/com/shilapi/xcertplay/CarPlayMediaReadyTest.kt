package com.shilapi.xcertplay

import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaReadyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    private class Connection(val controller: CarPlayController, var token: Any? = null, var changed: (() -> Unit)? = null)

    @Before fun setup() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        idle()
        WheelKeyLog.reset()
    }

    private fun connection(ready: Boolean = false): Connection {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val connection = Connection(controller, if (ready) Any() else null)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.mediaControlSessionToken()).thenAnswer { connection.token }
        `when`(controller.mediaAttachmentToken()).thenAnswer { connection.token }
        doAnswer { invocation ->
            connection.changed = invocation.getArgument(0)
            null
        }.`when`(controller).mediaControlListener = any()
        CarPlayMediaKeys.attach(app, controller)
        return connection
    }

    @Test fun connectedWithoutAudioPublishesPausedSessionAndDoesNotRequestFocusOrPlay() {
        val connection = connection()
        idle()
        assertNull(session())
        connection.token = Any()
        connection.changed!!.invoke()
        idle()
        val media = session()!!
        assertTrue(media.isActive)
        val playback = ReflectionHelpers.getField<PlaybackState>(media, "mPlaybackState")
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertTrue(playback.actions and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertTrue(playback.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertNoFocus()
        verify(connection.controller, never()).sendMediaButton(anyInt())
        verify(connection.controller, never()).tryAutoPlayback()
        callback(media).onPlay()
        idle()
        verify(connection.controller).sendMediaButton(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY)
    }

    @Test fun lateAttachAndRepeatedReadyAreIdempotent() {
        val connection = connection(ready = true)
        idle()
        val initial = session()!!
        connection.changed!!.invoke()
        connection.changed!!.invoke()
        idle()
        assertSame(initial, session())
        assertNoFocus()
    }

    @Test fun firstAudioAfterEarlyActivationStillRequestsFocus() {
        connection(ready = true)
        idle()
        val initial = session()!!
        assertNoFocus()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertSame(initial, session())
        assertNotNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertTrue(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
    }

    @Test fun focusDisabledRemainsDisabledAfterFirstAudio() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        connection(ready = true)
        idle()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertTrue(session()!!.isActive)
        assertNoFocus()
    }

    @Test fun readyDuringCallDoesNotTakeFocusOrStartPlayback() {
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        val connection = connection(ready = true)
        idle()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertTrue(session()!!.isActive)
        assertNoFocus()
        verify(connection.controller, never()).sendMediaButton(anyInt())
    }

    @Test fun losingChannelReleasesSessionAndLateReadyCannotReviveIt() {
        val connection = connection(ready = true)
        idle()
        val initial = session()!!
        connection.changed!!.invoke()
        connection.token = null
        connection.changed!!.invoke()
        idle()
        assertNull(session())
        assertFalse(initial.isActive)
        assertNoFocus()
    }

    @Test fun oldControllerCallbacksAndQueuedAudioCannotAffectReplacement() {
        val old = connection(ready = true)
        idle()
        val changed = old.changed!!
        CarPlayMediaKeys.onMediaAudioChanged(true)
        connection(ready = true)
        changed()
        idle()
        assertTrue(session()!!.isActive)
        assertNoFocus()
    }

    @Test fun audioBeforeReadyIsRetainedButDoesNotTakeFocusEarly() {
        val connection = connection()
        val attachment = Any()
        `when`(connection.controller.mediaAttachmentToken()).thenReturn(attachment)
        idle()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertNull(session())
        assertNoFocus()
        connection.token = attachment
        connection.changed!!.invoke()
        idle()
        assertTrue(session()!!.isActive)
        assertTrue(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
    }

    @Test fun queuedAudioBeforeReadyIsDroppedWhenAttachmentEnds() {
        val connection = connection()
        `when`(connection.controller.mediaAttachmentToken()).thenReturn(Any())
        idle()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        `when`(connection.controller.mediaAttachmentToken()).thenReturn(null)
        connection.changed!!.invoke()
        idle()
        assertNull(session())
        assertNoFocus()
    }

    @Test fun newAttachmentAudioBeforeReadyNotificationDoesNotLoseFocus() {
        val connection = connection(ready = true)
        idle()
        val old = session()!!
        val oldCallback = callback(old)
        connection.token = Any()
        CarPlayMediaKeys.onMediaAudioChanged(true)
        connection.changed!!.invoke()
        idle()
        assertNotSame(old, session())
        assertTrue(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
        oldCallback.onPlay()
        verify(connection.controller, never()).sendMediaButton(anyInt())
        callback(session()!!).onPlay()
        verify(connection.controller).sendMediaButton(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY)
    }

    private fun callback(media: MediaSession): MediaSession.Callback {
        val handler = ReflectionHelpers.getField<Any>(media, "mCallback")
        return ReflectionHelpers.getField(handler, "mCallback")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun session(): MediaSession? = ReflectionHelpers.getField(CarPlayMediaKeys, "session")
    private fun assertNoFocus() {
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertFalse(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
    }
}

package com.shilapi.xcertplay.adb

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A followed command, such as `logcat` without `-d`: output arrives in pieces and never ends by itself. */
class LocalAdbStreamTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** adbd without auth; [serve] writes the shell's output for the one OPEN it receives. */
    private fun adbd(serve: (send: (ByteArray) -> Unit, input: InputStream) -> Unit): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val out: OutputStream = socket.getOutputStream()
                AdbPacket.read(input) // CNXN
                out.write(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()).encode())
                val open = AdbPacket.read(input)
                out.write(AdbPacket(AdbPacket.OKAY, 77, open.arg0, ByteArray(0)).encode())
                runCatching {
                    serve({ bytes ->
                        out.write(AdbPacket(AdbPacket.WRTE, 77, open.arg0, bytes).encode())
                        out.flush()
                        AdbPacket.read(input) // the client's OKAY
                    }, input)
                }
            }
            server.close()
        }
        return server.localPort
    }

    @Test fun chunksArriveAsTheyAreWrittenWithoutWaitingForTheCommandToEnd() {
        val second = CountDownLatch(1)
        val firstSeen = CountDownLatch(1)
        val port = adbd { send, _ ->
            send("10-12 19:00:11.855 E/NaviController( 1): onNaviStop\n".toByteArray())
            assertTrue(second.await(5, TimeUnit.SECONDS))
            send("second\n".toByteArray())
            Thread.sleep(5_000)
        }
        val received = StringBuilder()
        var stop = false
        val reader = thread {
            LocalAdb(key, port = port, trace = {}).use { adb ->
                adb.stream("logcat -v time", 50, { !stop }) { bytes ->
                    synchronized(received) { received.append(String(bytes)) }
                    firstSeen.countDown()
                    if (received.contains("second")) stop = true
                }
            }
        }
        // The first line is handed over while the command is still running: that is the whole point.
        assertTrue(firstSeen.await(2, TimeUnit.SECONDS))
        assertTrue(synchronized(received) { received.contains("onNaviStop") })
        second.countDown()
        reader.join(3_000)
        assertFalse("keepGoing=false must end the stream within an idle check", reader.isAlive)
        assertTrue(received.contains("second"))
    }

    /** A UTF-8 name split over two packets must not be decoded half at a time. */
    @Test fun aCharacterSplitAcrossPacketsStaysWhole() {
        val name = "目的地".toByteArray(Charsets.UTF_8)
        val port = adbd { send, _ ->
            send(name.copyOfRange(0, 4))
            send(name.copyOfRange(4, name.size) + "\n".toByteArray())
            Thread.sleep(5_000)
        }
        val bytes = java.io.ByteArrayOutputStream()
        LocalAdb(key, port = port, trace = {}).use { adb ->
            adb.stream("logcat", 50, { !String(bytes.toByteArray(), Charsets.UTF_8).contains('\n') }) { bytes.write(it) }
        }
        assertEquals("目的地\n", String(bytes.toByteArray(), Charsets.UTF_8))
    }

    @Test fun cancellationFromAnotherThreadEndsAQuietStreamAtOnce() {
        val port = adbd { _, input -> while (input.read() != -1) { } }
        val adb = LocalAdb(key, port = port, trace = {})
        var result: Boolean? = null
        val reader = thread { result = adb.stream("logcat", 10_000, { true }) { } }
        Thread.sleep(300)
        adb.cancelPendingOperations()
        reader.join(1_000)
        assertFalse("must not wait for the idle check", reader.isAlive)
        assertEquals(false, result)
    }
}

package com.shilapi.xcertplay.external

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CarMeterVideoTest {
    @Test fun commandsMatchTheFirmwareProtocol() {
        assertEquals("{\"video\":1,\"bench\":0,\"dec\":0,\"dither\":1,\"te\":1}", CarMeterVideo.startCommand())
        assertEquals("{\"video\":1,\"bench\":0,\"dec\":0,\"dither\":0,\"te\":0}",
            CarMeterVideo.startCommand(dither = false, teSync = false))
        assertEquals("{\"video\":0}", CarMeterVideo.STOP_COMMAND)
        assertEquals("{\"hello\":1}", CarMeterVideo.HELLO)
    }

    @Test fun tearFreeSyncOnlyUpTo30Fps() {
        assertEquals(true, CarMeterVideo.teSyncFor(30))
        assertEquals(false, CarMeterVideo.teSyncFor(45))
    }

    @Test fun framesAreSplitWithLittleEndianHeaders() {
        val jpeg = ByteArray(3000) { it.toByte() }
        val packets = CarMeterVideo.packets(0x1_0000_0007L, jpeg)
        assertEquals(3, packets.size)
        val sizes = packets.map { it.size - CarMeterVideo.HEADER_SIZE }
        assertEquals(listOf(1448, 1448, 104), sizes)
        packets.forEachIndexed { i, p ->
            val h = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0xC4D2, h.short.toInt() and 0xFFFF)
            assertEquals(if (i == 0) 1 else 0, h.short.toInt())
            assertEquals(7, h.int)                 // 帧号取低 32 位
            assertEquals(3000, h.int)
            assertEquals(i * 1448, h.int)
            assertEquals(3, h.short.toInt())
            assertEquals(sizes[i], h.short.toInt())
        }
        val joined = packets.flatMap { it.drop(CarMeterVideo.HEADER_SIZE) }.toByteArray()
        assertArrayEquals(jpeg, joined)
    }

    @Test fun packetsFitOneEthernetFrame() {
        val packets = CarMeterVideo.packets(1, ByteArray(CarMeterVideo.MAX_FRAME_BYTES))
        assertEquals(true, packets.all { it.size + 8 + 20 <= 1500 })
    }

    @Test fun preRotationIsDeclaredInTheFlags() {
        val packets = CarMeterVideo.packets(1, ByteArray(2000), rotation = 270)
        val flags = packets.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() and 0xFFFF }
        assertEquals(listOf(0x2 or (270 shl 4) or 1, 0x2 or (270 shl 4)), flags)
        val neg = CarMeterVideo.packets(1, ByteArray(10), rotation = -90)
        assertEquals(270, (ByteBuffer.wrap(neg[0]).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() shr 4) and 0x1FF)
    }

    @Test fun rotationIsParsedFromDeviceReplies() {
        assertEquals(135, CarMeterVideo.parseRotation("{\"carmeter\":1,\"rot\":135}"))
        assertEquals(0, CarMeterVideo.parseRotation("{\"carmeter\":1,\"rot\":360}"))
        assertEquals(null, CarMeterVideo.parseRotation("{\"carmeter\":1}"))     // 老固件
        assertEquals(null, CarMeterVideo.parseRotation("{\"rot\":90}"))         // 不是 CarMeter 上行
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFramesAreRejected() {
        CarMeterVideo.packets(1, ByteArray(CarMeterVideo.MAX_FRAME_BYTES + 1))
    }

    @Test fun centredSquareUsesTheFullMapHeight() {
        val crop = CarMeterVideo.squareCrop(8.0 / 3, 50, 50, 100)
        assertEquals(0.3125f, crop.x, 1e-5f)
        assertEquals(0f, crop.y, 1e-5f)
        assertEquals(0.375f, crop.width, 1e-5f)
        assertEquals(1f, crop.height, 1e-5f)
    }

    @Test fun cropStaysInsideTheMap() {
        val left = CarMeterVideo.squareCrop(8.0 / 3, 0, 0, 50)
        assertEquals(0f, left.x, 1e-5f)
        assertEquals(0f, left.y, 1e-5f)
        val right = CarMeterVideo.squareCrop(8.0 / 3, 100, 100, 50)
        assertEquals(1f, right.x + right.width, 1e-5f)
        assertEquals(1f, right.y + right.height, 1e-5f)
    }

    @Test fun portraitMapsUseTheWidth() {
        val crop = CarMeterVideo.squareCrop(0.5, 50, 50, 100)
        assertEquals(1f, crop.width, 1e-5f)
        assertEquals(0.5f, crop.height, 1e-5f)
        assertEquals(0.25f, crop.y, 1e-5f)
    }
}

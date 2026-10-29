package com.shilapi.xcertplay.external

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CarMeter 视频推流协议(与固件 esp32/main/video_test.c、tools/videotx.py 一致)。纯函数，便于单测。
 *
 * - 启停：JSON 发到 UDP 8899(与导航帧同一通道)。推流期间固件会卸载 BLE，
 *   所以启停和保活都必须走 UDP，不能走 [CarMeterOutput] 的 BLE 分支。
 * - 帧：一帧 JPEG 切成 ≤1448B 的分片发到 UDP 8900，每片前加 20B 小端头：
 *   u16 magic(0xC4D2) | u16 flags(bit0=帧首包) | u32 frame | u32 total | u32 off | u16 npkt | u16 plen
 *   (20+1448+8+20=1496 ≤ 1500，不分 IP 片)。
 * - 固件单帧上限 128KB，超过整帧丢弃。推荐 464×464(16 整倍 MCU，设备居中直推不缩放)、
 *   baseline JPEG、4:2:0；圆屏外涂黑可省码率和解码时间。
 */
object CarMeterVideo {
    const val VIDEO_PORT = 8900
    const val DEFAULT_HOST = "192.168.4.1"   // CarMeter SoftAP 的网关地址
    const val FRAME_SIZE = 464
    const val MAX_FRAME_BYTES = 131072
    const val PAYLOAD_MAX = 1448
    const val HEADER_SIZE = 20
    private const val MAGIC = 0xC4D2

    /** dec=0 esp_new_jpeg；dither=1 设备端 RGB888 解码 + 有序抖动(消色带，默认) */
    fun startCommand(dither: Boolean = true): String =
        "{\"video\":1,\"bench\":0,\"dec\":0,\"dither\":${if (dither) 1 else 0}}"

    const val STOP_COMMAND = "{\"video\":0}"

    /** 把一帧切成数据报(含头)。frameId 取低 32 位。 */
    fun packets(frameId: Long, jpeg: ByteArray, length: Int = jpeg.size): List<ByteArray> {
        require(length in 1..MAX_FRAME_BYTES) { "frame size $length" }
        val count = (length + PAYLOAD_MAX - 1) / PAYLOAD_MAX
        return (0 until count).map { i ->
            val off = i * PAYLOAD_MAX
            val plen = minOf(PAYLOAD_MAX, length - off)
            ByteBuffer.allocate(HEADER_SIZE + plen).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(MAGIC.toShort())
                putShort((if (i == 0) 1 else 0).toShort())
                putInt(frameId.toInt())
                putInt(length)
                putInt(off)
                putShort(count.toShort())
                putShort(plen.toShort())
                put(jpeg, off, plen)
            }.array()
        }
    }

    /** 归一化取景框(相对整幅地图画面，左上为原点)。 */
    data class Crop(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * 从宽高比 [aspect](宽/高) 的地图画面里取一个正方形：边长 = 画面高度 × [sizePercent]%，
     * 中心在 ([centerXPercent], [centerYPercent])%，超出画面时平移回界内(与仪表窗口取景一致)。
     */
    fun squareCrop(aspect: Double, centerXPercent: Int, centerYPercent: Int, sizePercent: Int): Crop {
        val a = if (aspect.isFinite() && aspect > 0) aspect else 1.0
        // 以画面高度为 1：画面宽 = a。正方形边长不超过短边
        val side = (sizePercent.coerceIn(1, 100) / 100.0) * minOf(1.0, a)
        val cx = (centerXPercent.coerceIn(0, 100) / 100.0 * a).coerceIn(side / 2, a - side / 2)
        val cy = (centerYPercent.coerceIn(0, 100) / 100.0).coerceIn(side / 2, 1 - side / 2)
        return Crop(
            x = ((cx - side / 2) / a).toFloat(),
            y = (cy - side / 2).toFloat(),
            width = (side / a).toFloat(),
            height = side.toFloat(),
        )
    }
}

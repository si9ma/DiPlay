package com.shilapi.xcertplay.external

/**
 * CarMeter 线上协议帧（紧凑 JSON，与固件 cm_protocol.c 完全一致）：
 *
 *   对时     {"unix":1761400000123}
 *   导航     {"nav":1,"eta":1761401200000,"rem_m":8400}
 *
 * eta 为 Unix 毫秒，rem_m 为剩余米数。纯 Kotlin，便于单测。
 */
object CarMeterFrames {
    /** 对时帧 */
    fun time(nowMs: Long): String = "{\"unix\":$nowMs}"

    /**
     * 由 CarPlay 导航状态生成导航帧。
     * active=false 时输出收尾帧（eta/rem 置 0，仪表回表盘模式）。
     * 字段缺失时返回 null（不下发无意义的帧）。
     */
    fun nav(
        active: Boolean,
        arrivalEpochSeconds: Long?,
        remainingSeconds: Long?,
        remainingMeters: Long?,
        nowMs: Long,
    ): String? {
        if (!active) return "{\"nav\":0,\"eta\":0,\"rem_m\":0}"
        val eta = arrivalEpochSeconds?.let { it * 1000L + 500L }
            ?: remainingSeconds?.let { nowMs + it * 1000L }
            ?: return null
        val rem = remainingMeters ?: return null
        if (rem < 0 || eta <= 0) return null
        return "{\"nav\":1,\"eta\":$eta,\"rem_m\":$rem}"
    }

    /** 测试帧：演示值（设置页「测试发送」用） */
    fun testTime(nowMs: Long): String = time(nowMs)

    fun testDistance(nowMs: Long, remainingMeters: Long = 8_400L): String =
        "{\"nav\":1,\"eta\":${nowMs + 12 * 60_000L},\"rem_m\":$remainingMeters}"

    fun testDuration(nowMs: Long, remainingSeconds: Long = 42 * 60L): String =
        "{\"nav\":1,\"eta\":${nowMs + remainingSeconds * 1000L},\"rem_m\":${remainingSeconds * 12L}}"

    fun testArrival(nowMs: Long, etaMs: Long): String =
        "{\"nav\":1,\"eta\":$etaMs,\"rem_m\":6000}"
}

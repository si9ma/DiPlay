package com.shilapi.xcertplay.external

import android.content.Context

/** 「外接设备」的持久化设置（SharedPreferences）。 */
object CarMeterSettings {
    private const val PREFS = "diplay_carmeter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_SEND_NAVIGATION = "send_navigation"
    private const val KEY_SEND_TIME = "send_time"
    private const val KEY_UDP_HOST = "udp_host"
    private const val KEY_LAST_DEVICE = "last_device"
    private const val KEY_LAST_DEVICE_NAME = "last_device_name"

    /** 总开关：关闭时不扫描、不连接、不发送 */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun autoReconnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_RECONNECT, true)
    fun setAutoReconnect(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    fun sendNavigation(context: Context): Boolean = prefs(context).getBoolean(KEY_SEND_NAVIGATION, true)
    fun setSendNavigation(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SEND_NAVIGATION, value).apply()

    fun sendTime(context: Context): Boolean = prefs(context).getBoolean(KEY_SEND_TIME, true)
    fun setSendTime(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SEND_TIME, value).apply()

    /** Wi-Fi 备用链路对端（CarMeter 的 IP）。为空 = 不走 UDP。端口固定 8899。 */
    fun udpHost(context: Context): String = prefs(context).getString(KEY_UDP_HOST, null)?.trim() ?: ""
    fun setUdpHost(context: Context, host: String) = prefs(context).edit().putString(KEY_UDP_HOST, host.trim()).apply()
    const val UDP_PORT = 8899

    /** 上次连接的 CarMeter 蓝牙地址（自动回连目标） */
    fun lastDevice(context: Context): String? = prefs(context).getString(KEY_LAST_DEVICE, null)?.takeIf { it.isNotBlank() }
    fun lastDeviceName(context: Context): String? = prefs(context).getString(KEY_LAST_DEVICE_NAME, null)
    fun setLastDevice(context: Context, address: String, name: String?) {
        prefs(context).edit()
            .putString(KEY_LAST_DEVICE, address)
            .putString(KEY_LAST_DEVICE_NAME, name ?: address)
            .apply()
    }

    fun clearLastDevice(context: Context) {
        prefs(context).edit().remove(KEY_LAST_DEVICE).remove(KEY_LAST_DEVICE_NAME).apply()
    }

    // ---- 推送导航地图(见 CarMeterMapStream / CarMeterVideo) ----
    private const val KEY_MAP_CENTER_X = "map_stream_center_x"
    private const val KEY_MAP_CENTER_Y = "map_stream_center_y"
    private const val KEY_MAP_SIZE = "map_stream_size"
    private const val KEY_MAP_QUALITY = "map_stream_quality"
    private const val KEY_MAP_FPS = "map_stream_fps"

    /** 取景中心(%，从左/上算起)。默认地图正中。 */
    fun mapCenterX(context: Context): Int = prefs(context).getInt(KEY_MAP_CENTER_X, 50).coerceIn(0, 100)
    fun mapCenterY(context: Context): Int = prefs(context).getInt(KEY_MAP_CENTER_Y, 50).coerceIn(0, 100)
    fun setMapCenterX(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAP_CENTER_X, value.coerceIn(0, 100)).apply()
    fun setMapCenterY(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAP_CENTER_Y, value.coerceIn(0, 100)).apply()

    /** 正方形取景边长占地图画面高度的百分比(越小 = 放得越大)。 */
    fun mapSize(context: Context): Int = prefs(context).getInt(KEY_MAP_SIZE, 100).coerceIn(MAP_SIZE_MIN, 100)
    fun setMapSize(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAP_SIZE, value.coerceIn(MAP_SIZE_MIN, 100)).apply()
    const val MAP_SIZE_MIN = 30

    /** JPEG 质量(50..95)。85 在 464² 上约 15~40KB/帧。 */
    fun mapQuality(context: Context): Int = prefs(context).getInt(KEY_MAP_QUALITY, 85).coerceIn(50, 95)
    fun setMapQuality(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAP_QUALITY, value.coerceIn(50, 95)).apply()

    /** 推流帧率上限(10..60)。CarMeter 464² 抖动模式满载约 45~50fps；超过 30 时关掉 TE 同步(见 CarMeterVideo)。 */
    fun mapFps(context: Context): Int = prefs(context).getInt(KEY_MAP_FPS, 30).coerceIn(MAP_FPS_MIN, MAP_FPS_MAX)
    fun setMapFps(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAP_FPS, value.coerceIn(MAP_FPS_MIN, MAP_FPS_MAX)).apply()
    const val MAP_FPS_MIN = 10
    const val MAP_FPS_MAX = 60

    /** 镜像哪一路 CarPlay 画面推给 CarMeter */
    enum class MapSource { CLUSTER, MAIN }
    private const val KEY_MAP_SOURCE = "map_stream_source"
    fun mapSource(context: Context): MapSource =
        MapSource.entries.getOrNull(prefs(context).getInt(KEY_MAP_SOURCE, 0)) ?: MapSource.CLUSTER
    fun setMapSource(context: Context, value: MapSource) = prefs(context).edit().putInt(KEY_MAP_SOURCE, value.ordinal).apply()

    /**
     * CarMeter 导航期间显示什么(快捷指令「切换外接设备内容」按此顺序循环)：
     * MAP 推送导航地图视频；SIMPLE 只发导航简洁信息(剩余距离/时间/到达)；NONE 不显示导航(手表保持表盘)。
     * 非导航时三者都显示表盘。
     */
    enum class Content { MAP, SIMPLE, NONE }
    private const val KEY_CONTENT = "content_mode"
    fun content(context: Context): Content =
        Content.entries.getOrNull(prefs(context).getInt(KEY_CONTENT, Content.SIMPLE.ordinal)) ?: Content.SIMPLE
    fun setContent(context: Context, value: Content) = prefs(context).edit().putInt(KEY_CONTENT, value.ordinal).apply()

    /**
     * 导航开始时「显示内容」回到哪：KEEP 记住上次的选择(默认，即当前行为)；
     * MAP/SIMPLE/NONE 每次导航开始都强制回到该模式，忽略快捷指令上次切到哪。
     */
    enum class NavStart {
        KEEP, MAP, SIMPLE, NONE;
        fun content(): Content? = when (this) { KEEP -> null; MAP -> Content.MAP; SIMPLE -> Content.SIMPLE; NONE -> Content.NONE }
    }
    private const val KEY_NAV_START = "nav_start_content"
    fun navStart(context: Context): NavStart =
        NavStart.entries.getOrNull(prefs(context).getInt(KEY_NAV_START, NavStart.KEEP.ordinal)) ?: NavStart.KEEP
    fun setNavStart(context: Context, value: NavStart) = prefs(context).edit().putInt(KEY_NAV_START, value.ordinal).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

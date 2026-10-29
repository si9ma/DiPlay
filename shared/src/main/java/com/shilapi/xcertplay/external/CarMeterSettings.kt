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

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

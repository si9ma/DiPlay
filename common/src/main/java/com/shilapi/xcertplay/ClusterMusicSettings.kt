package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences

internal object ClusterMusicSettings {
    private const val PREFIX = "cluster_music_"
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREFIX + "enabled", false)
    fun permanent(context: Context) = prefs(context).getBoolean(PREFIX + "permanent", false)
    fun x(context: Context) = prefs(context).getInt(PREFIX + "x", 50).coerceIn(5, 95)
    fun y(context: Context) = prefs(context).getInt(PREFIX + "y", 75).coerceIn(5, 95)
    fun size(context: Context) = prefs(context).getInt(PREFIX + "size", 100).coerceIn(50, 150)
    fun saveEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(PREFIX + "enabled", value).apply()
        ClusterMusicState.note("settings enabled=$value user=${android.os.Process.myUid() / 100_000}")
    }
    fun savePermanent(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(PREFIX + "permanent", value).apply()
        ClusterMusicState.note("settings permanent=$value user=${android.os.Process.myUid() / 100_000}")
    }
    fun diagnosticReport(context: Context): String {
        val values = prefs(context).all
        fun saved(key: String) = values[key]?.toString() ?: "unset"
        return "clusterMusicPrefs package=${context.packageName} user=${android.os.Process.myUid() / 100_000} " +
            "deviceProtected=${context.isDeviceProtectedStorage} " +
            "enabled=${saved(PREFIX + "enabled")} permanent=${saved(PREFIX + "permanent")} " +
            "mapEnabled=${saved("cluster_map_enabled")} " +
            "oemSongText=${com.shilapi.xcertplay.hud.BydOutputSettings.clusterSong(context)} " +
            "x=${x(context)} y=${y(context)} size=${size(context)}"
    }
    fun save(context: Context, key: String, value: Int) {
        require(key in setOf("x", "y", "size"))
        prefs(context).edit().putInt(PREFIX + key, value.coerceIn(if (key == "size") 50..150 else 5..95)).apply()
    }
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(PREFIX)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

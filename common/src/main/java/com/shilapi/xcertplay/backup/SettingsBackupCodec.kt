package com.shilapi.xcertplay.backup

import android.content.Context
import android.content.SharedPreferences.Editor
import org.json.JSONArray
import org.json.JSONObject

data class SettingsImportOutcome(val applied: Int, val skipped: Int, val invalid: Boolean)

/** Reads and writes the settings documents exchanged through the backup web page. */
object SettingsBackupCodec {
    const val FORMAT = "diplay-settings"
    const val VERSION = 1

    /**
     * The settings of the main file that travel, by name. That file also holds the iPhones' pairing keys
     * (`pairing.<id>`), lockdown records, the authentication target and the car's hotspot name and
     * passphrase. An imported file must never set those: a pairing key from someone else's file would make
     * DiPlay trust their device. So a key travels only once it is listed here; car-specific values (the
     * detected screen size, the car's hotspot) and consents (sharing the map with other apps) stay out too.
     */
    private val AIRPLAY_KEYS = setOf(
        "display_scale_tenths", "ui_scale_percent", "hevc_enabled", "hevc_software_decoder",
        "advanced_audio_channel_mapping", "audio_focus_enabled", "audio_focus_auto_yield",
        "disable_media_key_audio_focus", "media_audio_channel", "navigation_audio_channel",
        "navigation_stream_type", "wireless_enabled", "wireless_hotspot_mode", "wifi_p2p_preferred_channel", "wifi_direct_scan_pause",
        "manual_hotspot_band", "manual_hotspot_channel", "manual_hotspot_security", "app_appearance",
        "ambient_lux_threshold", "display_fps", "media_buffer_ms", "wired_media_buffer_ms", "main_buffered_audio",
        "speech_without_8_16_khz", "car_bluetooth_audio", "call_echo_cancellation", "call_voice_filter",
        "smooth_video", "direct_video_output", "low_latency_decoder", "mtk_decoder_tuning", "fps_counter",
        "adb_cluster_activity_enabled", "center_map_overlay", "center_map_auto_hide",
        "center_map_follows_dashboard", "settings_gesture_fingers", "display_width_physical_mm",
        "display_physical_size_basis", "right_hand_drive", "hide_top_bar", "hide_bottom_bar",
        "harman_fullscreen", "safe_area_draw_outside", "main_display_disable_safe_area",
        "adapt_pip_resolution", "auto_start_on_boot", "bt_suspend_during_carplay", "hide_byd_call_popup",
        "bt_suspend_delay_seconds", "location_reporting_enabled", "external_controller", "usb_charging_current",
        // The floating CarPlay window: its width, place and corners, and the pages it shows on or hides from.
        "projection_width_percent", "projection_alignment", "overlay_corner_radius_dp", "overlay_corner_colored",
        "overlay_corner_color", "carplay_only_on_pages", "carplay_only_on_packages", "carplay_only_on_require_split",
        "carplay_never_on_packages",
        // The head-unit map's destination, and the recorded taps that start and end the iPhone's guidance.
        "head_unit_map_destination_listen", "head_unit_map_destination_adb", "head_unit_map_destination_follow",
        "head_unit_map_destination_follow_stop", "head_unit_map_destination_link", "head_unit_map_destination_via",
        "head_unit_map_destination_probe", "navigation_debug_overlay",
        "carplay_stop_auto", "carplay_stop_taps", "carplay_stop_tap_count", "carplay_stop_tap_gap_ms",
        "carplay_stop_parked_only", "carplay_stop_foreground_first", "carplay_stop_foreground_wait_millis",
        "carplay_start_auto", "carplay_start_taps", "carplay_start_tap_count", "carplay_start_tap_gap_ms",
        "carplay_start_delay_millis",
        // Not here, on purpose: report_raw_car_log, the consent to put real destinations in a report.
    )

    /** Families of layout settings with many keys and nothing sensitive in them. */
    private val AIRPLAY_PREFIXES = listOf("cluster_", "carplay_night_")
    private val DIPLAY_KEYS = setOf(
        "auto_connect",
        "connect_on_phone_bluetooth",
        "default_connection_mode",
        "settings_force_full_layout",
        "app_appearance_button",
        "byd_vehicle_advanced",
        "picture_controls",
        "settings_gesture_fingers",
    )
    private val FILES = mapOf(
        "xcertplay_airplay" to Policy.Allow(AIRPLAY_KEYS, AIRPLAY_PREFIXES),
        "diplay" to Policy.Allow(DIPLAY_KEYS),
        "carplay_picture" to Policy.All,
        "diplay_car_hotspot" to Policy.All,
        // Choices only, nothing that identifies the car or a phone.
        "diplay_byd_outputs" to Policy.All,
        "diplay_cluster_layout" to Policy.All,
        // Where the floating map card and CarPlay's handle sit. These are positions on this car's
        // screen, which is what a backup taken before reinstalling on the same car is for.
        "diplay_center_map" to Policy.All,
        "diplay_projection_handle" to Policy.All,
    )

    /**
     * Keys that are not what their JSON value looks like. An import onto a fresh install has no stored
     * value to take the type from, and guessing makes `2000` an Int and `1.0` an Int too; the app then
     * reads a Long or a Float and throws, on every start. Every listed key read with getLong or getFloat
     * is named here, by file.
     */
    private val TYPES: Map<String, Map<String, Char>> = mapOf(
        "xcertplay_airplay" to mapOf(
            "carplay_start_delay_millis" to 'l', "carplay_stop_foreground_wait_millis" to 'l',
            "carplay_start_tap_gap_ms" to 'l', "carplay_stop_tap_gap_ms" to 'l',
        ),
        // The card's aspect ratio is a double's raw bits.
        "diplay_center_map" to mapOf("aspect" to 'l'),
        "diplay_projection_handle" to mapOf("y_fraction" to 'f'),
    )

    private sealed interface Policy {
        fun includes(key: String): Boolean

        data object All : Policy {
            override fun includes(key: String) = true
        }

        data class Allow(val keys: Set<String>, val prefixes: List<String> = emptyList()) : Policy {
            override fun includes(key: String) = key in keys || prefixes.any(key::startsWith)
        }
    }

    fun export(context: Context, appVersion: String): ByteArray {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app", appVersion)
        root.put("exportedAt", System.currentTimeMillis())
        val files = JSONObject()
        for ((name, policy) in FILES) {
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterKeys { policy.includes(it) }
                .filterValues { it !is ByteArray }
                .mapValues { it.value!! }
            if (values.isEmpty()) continue
            val file = JSONObject()
            for ((key, value) in values) file.put(key, jsonValue(value))
            files.put(name, file)
        }
        root.put("settings", files)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun import(context: Context, document: ByteArray): SettingsImportOutcome {
        val root = runCatching { JSONObject(document.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        if (root.optString("format") != FORMAT || root.optInt("version", Int.MIN_VALUE) !in 1..VERSION) {
            return SettingsImportOutcome(0, 0, invalid = true)
        }
        // The earlier head-unit file says each value's type itself, which beats any guess.
        val legacyTypes = HashMap<String, MutableMap<String, Char>>()
        val files = root.optJSONObject("settings") ?: legacyFiles(root.optJSONObject("prefs"), legacyTypes)
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        var applied = 0
        var skipped = 0
        for (name in files.keys()) {
            val policy = FILES[name]
            val file = files.optJSONObject(name)
            if (policy == null || file == null) {
                skipped += files.optJSONObject(name)?.length() ?: 1
                continue
            }
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val current = prefs.all
            val editor = prefs.edit()
            for (key in file.keys()) {
                val value = file.opt(key)
                if (!policy.includes(key) || value === JSONObject.NULL) {
                    skipped++
                    continue
                }
                val stored = when (val target = current[key]) {
                    is Boolean -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
                    is Int -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { editor.putInt(key, it.toInt()); true } ?: false
                    is Long -> integral(value)?.let { editor.putLong(key, it); true } ?: false
                    is Float -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
                    is String -> if (value is String) { editor.putString(key, value); true } else false
                    is Set<*> -> stringSet(value)?.let { editor.putStringSet(key, it); true } ?: false
                    null -> when (legacyTypes[name]?.get(key) ?: TYPES[name]?.get(key)) {
                        'l' -> integral(value)?.let { editor.putLong(key, it); true } ?: false
                        'f' -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
                        's' -> if (value is String) { editor.putString(key, value); true } else false
                        'S' -> stringSet(value)?.let { editor.putStringSet(key, it); true } ?: false
                        else -> inferred(editor, key, value)
                    }
                    else -> false
                }
                if (stored) applied++ else skipped++
            }
            editor.apply()
        }
        return SettingsImportOutcome(applied, skipped, invalid = false)
    }

    /**
     * A file saved by the head-unit-only backup this branch had before it used this codec: the same
     * format name and version, with each value under "prefs" wrapped as {"t": type, "v": value}. Unwrapped
     * here, it goes through the same allow list as any other file, so it can set nothing this codec would
     * not — that older backup let `pairing.<id>` through, which this list never does.
     */
    private fun legacyFiles(prefs: JSONObject?, types: MutableMap<String, MutableMap<String, Char>>): JSONObject? {
        prefs ?: return null
        val files = JSONObject()
        for (name in prefs.keys()) {
            val wrapped = prefs.optJSONObject(name) ?: continue
            val file = JSONObject()
            for (key in wrapped.keys()) {
                val entry = wrapped.optJSONObject(key) ?: continue
                if (!entry.has("v")) continue
                file.put(key, entry.get("v"))
                when (entry.optString("t")) {
                    "l" -> 'l'; "f" -> 'f'; "s" -> 's'; "ss" -> 'S'; else -> null
                }?.let { types.getOrPut(name) { HashMap() }[key] = it }
            }
            files.put(name, file)
        }
        return files
    }

    private fun inferred(editor: Editor, key: String, value: Any?): Boolean = when (value) {
        is Boolean -> { editor.putBoolean(key, value); true }
        is String -> { editor.putString(key, value); true }
        else -> {
            val integral = integral(value)
            when {
                integral != null && integral in Int.MIN_VALUE..Int.MAX_VALUE -> { editor.putInt(key, integral.toInt()); true }
                integral != null -> { editor.putLong(key, integral); true }
                else -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
            }
        }
    }

    private fun integral(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE || double > Long.MAX_VALUE) return null
        return double.toLong()
    }

    private fun decimal(value: Any?): Float? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite()) return null
        val float = double.toFloat()
        return float.takeIf { it.isFinite() }
    }

    private fun stringSet(value: Any?): Set<String>? {
        val array = value as? JSONArray ?: return null
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index) as? String ?: return null
            result.add(element)
        }
        return result
    }

    private fun jsonValue(value: Any): Any = when (value) {
        is Set<*> -> JSONArray().apply { value.forEach { put(it.toString()) } }
        is Float -> value.toDouble()
        else -> value
    }
}

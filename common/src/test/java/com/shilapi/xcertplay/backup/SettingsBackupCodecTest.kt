package com.shilapi.xcertplay.backup

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsBackupCodecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun exportOmitsSensitiveAndNavigationKeys() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putString("identity_private", "secret")
            .putString("manual_hotspot_passphrase", "wifi-pass")
            .putStringSet("pairing_ids", setOf("pair-1"))
            .putString("pairing.0F7A", "a1b2")
            .putString("manual_hotspot_ssid", "car-ap")
            .putString("mfi_target", "REMOTE")
            .putBoolean("cluster_map_enabled", true)
            .apply()
        prefs("diplay").edit()
            .putBoolean("auto_connect", true)
            .putString("page", "settings")
            .putString("phone_name", "iphone-of-owner")
            .apply()
        prefs("carplay_picture").edit().putInt("brightness", 80).apply()
        val document = JSONObject(String(SettingsBackupCodec.export(context, "0.2.17")))
        assertEquals(SettingsBackupCodec.FORMAT, document.getString("format"))
        assertEquals(SettingsBackupCodec.VERSION, document.getInt("version"))
        val airplay = document.getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps"))
        assertTrue(airplay.getBoolean("cluster_map_enabled"))
        assertFalse(airplay.has("manual_hotspot_ssid"))
        assertFalse(airplay.has("pairing.0F7A"))
        assertFalse(airplay.has("mfi_target"))
        assertFalse(airplay.has("identity_private"))
        assertFalse(airplay.has("manual_hotspot_passphrase"))
        assertFalse(airplay.has("pairing_ids"))
        val diplay = document.getJSONObject("settings").getJSONObject("diplay")
        assertTrue(diplay.getBoolean("auto_connect"))
        assertFalse(diplay.has("page"))
        assertFalse(diplay.has("phone_name"))
        assertEquals(80, document.getJSONObject("settings").getJSONObject("carplay_picture").getInt("brightness"))
        assertFalse(document.getJSONObject("settings").has("diplay_car_hotspot"))
    }

    @Test
    fun importAppliesKnownKeysAndSkipsUnknownOrForbidden() {
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": 48, "brand_new_key": 7, "manual_hotspot_passphrase": "x",
                "pairing.attacker": "00ff"},
              "diplay": {"auto_connect": true, "page": "about"},
              "carplay_picture": {"brightness": 66}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(false, outcome.invalid)
        assertEquals(3, outcome.applied)
        assertEquals(4, outcome.skipped)
        assertEquals(48, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertFalse(prefs("xcertplay_airplay").contains("manual_hotspot_passphrase"))
        // Only listed settings travel: an imported file cannot add an iPhone pairing key or any unknown key.
        assertFalse(prefs("xcertplay_airplay").contains("pairing.attacker"))
        assertFalse(prefs("xcertplay_airplay").contains("brand_new_key"))
        assertEquals(true, prefs("diplay").getBoolean("auto_connect", false))
        assertFalse(prefs("diplay").contains("page"))
        assertEquals(66, prefs("carplay_picture").getInt("brightness", 0))
    }

    @Test
    fun importRejectsForeignOrNewerDocuments() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"other\",\"version\":1,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":99,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "not json".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{}".toByteArray()).invalid)
    }

    @Test
    fun importMatchesTypesAgainstStoredValues() {
        prefs("xcertplay_airplay").edit().putInt("display_fps", 60).putBoolean("hevc_enabled", false).apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": "48", "hevc_enabled": true, "display_scale_tenths": 85}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(2, outcome.applied)
        assertEquals(1, outcome.skipped)
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertEquals(true, prefs("xcertplay_airplay").getBoolean("hevc_enabled", true))
        assertEquals(85, prefs("xcertplay_airplay").getInt("display_scale_tenths", 0))
    }

    @Test
    fun importRejectsMissingOrUnsupportedVersions() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":0,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":-1,\"settings\":{}}".toByteArray()).invalid)
    }

    @Test
    fun importRejectsOutOfRangeOrFractionalNumbers() {
        prefs("xcertplay_airplay").edit()
            .putLong("cluster_test_counter", 5L)
            .putFloat("ui_scale_percent", 1f)
            .putInt("display_fps", 60)
            .apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"cluster_test_counter": 1.5, "ui_scale_percent": 1e300, "display_fps": 3000000000}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(0, outcome.applied)
        assertEquals(3, outcome.skipped)
        assertEquals(5L, prefs("xcertplay_airplay").getLong("cluster_test_counter", 0L))
        assertEquals(1f, prefs("xcertplay_airplay").getFloat("ui_scale_percent", 0f))
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
    }

    /** The settings this branch added, which the phone page's list did not have, travel too. */
    @Test
    fun theFloatingWindowAndGuidanceSettingsTravelAndTheRawLogConsentDoesNot() {
        prefs("xcertplay_airplay").edit()
            .putInt("projection_width_percent", 66)
            .putString("carplay_only_on_packages", "com.byd.sr")
            .putString("carplay_stop_taps", "[{\"x\":0.27,\"y\":0.93,\"d\":250}]")
            .putLong("carplay_stop_foreground_wait_millis", 500L)
            .putBoolean("head_unit_map_destination_follow", true)
            .putBoolean("report_raw_car_log", true)
            .apply()
        prefs("diplay_byd_outputs").edit().putBoolean("carplay_calls", true).apply()
        prefs("diplay_projection_handle").edit().putInt("bubble_y", 300).apply()
        val document = JSONObject(String(SettingsBackupCodec.export(context, "8.0.33"))).getJSONObject("settings")
        val airplay = document.getJSONObject("xcertplay_airplay")
        assertEquals(66, airplay.getInt("projection_width_percent"))
        assertEquals("com.byd.sr", airplay.getString("carplay_only_on_packages"))
        assertTrue(airplay.has("carplay_stop_taps"))
        assertEquals(500L, airplay.getLong("carplay_stop_foreground_wait_millis"))
        assertTrue(airplay.getBoolean("head_unit_map_destination_follow"))
        // A consent stays with the person who gave it.
        assertFalse(airplay.has("report_raw_car_log"))
        assertTrue(document.getJSONObject("diplay_byd_outputs").getBoolean("carplay_calls"))
        assertEquals(300, document.getJSONObject("diplay_projection_handle").getInt("bubble_y"))

        val imported = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"report_raw_car_log": true, "projection_width_percent": 50}
            }}
        """.trimIndent()
        prefs("xcertplay_airplay").edit().putBoolean("report_raw_car_log", false).apply()
        SettingsBackupCodec.import(context, imported.toByteArray())
        assertEquals(false, prefs("xcertplay_airplay").getBoolean("report_raw_car_log", true))
        assertEquals(50, prefs("xcertplay_airplay").getInt("projection_width_percent", 0))
    }

    /**
     * A file saved by this branch's earlier head-unit backup: the same format name, values wrapped as
     * {"t", "v"} under "prefs". It restores, and through the same allow list — that backup let
     * `pairing.<id>` through, which must not become a way in now.
     */
    @Test
    fun aFileFromTheEarlierHeadUnitBackupRestoresThroughTheSameAllowList() {
        val legacy = """
            {"format": "diplay-settings", "version": 1, "app": "8.0.31", "exported": "2026-10-12 10:00:00",
             "prefs": {
               "xcertplay_airplay": {
                 "display_fps": {"t": "i", "v": 48},
                 "projection_alignment": {"t": "s", "v": "LEFT"},
                 "carplay_stop_tap_gap_ms": {"t": "l", "v": 500},
                 "pairing.attacker": {"t": "s", "v": "00ff"}
               },
               "diplay_center_map": {"x": {"t": "i", "v": 120}}
             }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, legacy.toByteArray())
        assertEquals(false, outcome.invalid)
        assertEquals(4, outcome.applied)
        assertEquals(1, outcome.skipped)
        val airplay = prefs("xcertplay_airplay")
        assertEquals(48, airplay.getInt("display_fps", 0))
        assertEquals("LEFT", airplay.getString("projection_alignment", null))
        // The file said Long, so it is stored as one although 500 fits an Int.
        assertEquals(500L, airplay.getLong("carplay_stop_tap_gap_ms", 0L))
        assertFalse(airplay.contains("pairing.attacker"))
        assertEquals(120, prefs("diplay_center_map").getInt("x", 0))
    }

    /**
     * On a fresh install nothing is stored to take the type from, and a guess makes 2000 an Int. The
     * app reads these with getLong and getFloat, which would then throw on every start.
     */
    @Test
    fun valuesReadAsLongOrFloatKeepThatTypeOnAFreshInstall() {
        listOf("xcertplay_airplay", "diplay_center_map", "diplay_projection_handle").forEach { prefs(it).edit().clear().commit() }
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"carplay_start_delay_millis": 2000, "carplay_stop_foreground_wait_millis": 0,
                "carplay_start_tap_gap_ms": 750, "carplay_stop_tap_gap_ms": 500},
              "diplay_center_map": {"aspect": 4607182418800017408},
              "diplay_projection_handle": {"y_fraction": 1}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(6, outcome.applied)
        val airplay = prefs("xcertplay_airplay")
        assertEquals(2000L, airplay.getLong("carplay_start_delay_millis", -1))
        assertEquals(0L, airplay.getLong("carplay_stop_foreground_wait_millis", -1))
        assertEquals(750L, airplay.getLong("carplay_start_tap_gap_ms", -1))
        assertEquals(500L, airplay.getLong("carplay_stop_tap_gap_ms", -1))
        assertEquals(1.0, java.lang.Double.longBitsToDouble(prefs("diplay_center_map").getLong("aspect", 0)), 0.0)
        assertEquals(1f, prefs("diplay_projection_handle").getFloat("y_fraction", 0f))
    }

    @Test
    fun exportImportRoundTripRestoresValues() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putBoolean("hevc_enabled", true)
            .putString("manual_hotspot_ssid", "car-ap")
            .putFloat("ui_scale_percent", 100f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", true).apply()
        val document = SettingsBackupCodec.export(context, "0.2.17")
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 30)
            .putBoolean("hevc_enabled", false)
            .putString("manual_hotspot_ssid", "changed")
            .putFloat("ui_scale_percent", 120f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", false).apply()
        val outcome = SettingsBackupCodec.import(context, document)
        assertEquals(false, outcome.invalid)
        val airplay = prefs("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps", 0))
        assertEquals(true, airplay.getBoolean("hevc_enabled", false))
        // The car's hotspot name is not part of a backup.
        assertEquals("changed", airplay.getString("manual_hotspot_ssid", ""))
        assertEquals(100f, airplay.getFloat("ui_scale_percent", 0f))
        assertEquals(true, prefs("diplay_car_hotspot").getBoolean("auto_enable", false))
        assertFalse(airplay.contains("identity_private"))
    }
}

package com.shilapi.xcertplay.media

import android.os.Build
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AmbientColorProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("ambient_profile", 0).edit().clear().commit()
        context.getSharedPreferences("ambient_music", 0).edit().clear().commit()
    }

    @Test fun paletteIsTiedToTheExactFirmwareAndDefaultsTo31() {
        assertEquals(AmbientPalette.LEGACY31, AmbientColorProfile.current(context))
        assertEquals(AmbientPalette.OEM127, AmbientColorProfile.resolve("fp", "OEM127", "fp"))
        assertEquals(AmbientPalette.LEGACY31, AmbientColorProfile.resolve("fp", "OEM127", "fp-updated"))
        assertEquals(AmbientPalette.LEGACY31, AmbientColorProfile.resolve(null, "OEM127", "fp"))
        assertEquals(AmbientPalette.LEGACY31, AmbientColorProfile.resolve("fp", "UNKNOWN", "fp"))
        // Discovery controls software numbering, never the worker's permission to write.
        assertTrue(AmbientColorProfile.record(context, Build.FINGERPRINT, 6))
        assertEquals(AmbientPalette.OEM127, AmbientColorProfile.current(context))
        assertFalse(BydAmbientLightPolicy.sessionColors(Build.FINGERPRINT, 6).contains(57))
        assertFalse(AmbientColorProfile.record(context, Build.FINGERPRINT, 6))
    }

    @Test fun settingsKeepCodesWithinTheCurrentPalette() {
        context.getSharedPreferences("ambient_music", 0).edit().putInt("color", 57)
            .putStringSet("selectedColors", setOf("20", "57", "127", "128")).commit()
        val legacy = AmbientMusicSettings.load(context, AmbientPalette.LEGACY31)
        assertEquals(31, legacy.color)
        assertEquals(listOf(20), legacy.selectedColors)
        val wide = AmbientMusicSettings.load(context, AmbientPalette.OEM127)
        assertEquals(57, wide.color)
        assertEquals(listOf(20, 57, 127), wide.selectedColors)
        assertEquals(listOf(20, 57, 127), normalizeAmbientPalette(listOf(127, 57, 20, 128, 0), range = 1..127))
        assertEquals(listOf(127), normalizeAmbientPalette(emptyList(), 200, 1..127))
    }

    @Test fun oem127AlbumColorsUseThe127TableAndItsWhites() {
        val palette = AmbientPalette.OEM127
        assertEquals(BydAmbientLightPolicy.OEM127_COLORS, palette.colors)
        // Pure lookup colors map to themselves: green 57, blue 20, red 104 (255,0,72) by hue.
        assertEquals(57, palette.albumCode(Oem127Palette.previewRgb(57)))
        assertEquals(20, palette.albumCode(Oem127Palette.previewRgb(20)))
        assertEquals(104, palette.albumCode(Oem127Palette.previewRgb(104)))
        val white = IntArray(576) { 0xfff5f5f5.toInt() }
        val coldWhite = IntArray(576) { 0xffdde8ff.toInt() }
        assertEquals(116, AmbientAlbumPalette.albumColor(white, palette))
        assertEquals(119, AmbientAlbumPalette.albumColor(coldWhite, palette))
        assertEquals(29, AmbientAlbumPalette.albumColor(white))
        val red = IntArray(576) { 0xffe01020.toInt() }
        val code = AmbientAlbumPalette.albumColor(red, palette)!!
        assertTrue(code in 98..107)
    }

    @Test fun oem127NeighborsStayInHueAndAlbumFallbackUsesTheWholePalette() {
        val palette = AmbientPalette.OEM127
        val near = palette.neighbors(57)
        assertTrue(near.size in 1..5)
        assertTrue(57 in near)
        assertTrue(near.all { it in 50..65 })
        assertEquals(listOf(116, 119), palette.neighbors(116))
        assertEquals((1..127).toList(), ambientColorPalette(AmbientColorSource.ALBUM, listOf(1), null, palette))
        assertEquals((1..127).toList(), ambientColorPalette(AmbientColorSource.ALBUM, listOf(1), 200, palette))
        assertEquals(near, ambientColorPalette(AmbientColorSource.ALBUM, listOf(1), 57, palette))
        assertEquals(listOf(40, 90), ambientColorPalette(AmbientColorSource.SELECTED, listOf(90, 40, 128), null, palette))
        assertEquals((1..31).toList(), ambientColorPalette(AmbientColorSource.ALBUM, listOf(1), 57))
    }

    @Test fun detectorKeepsCodesAbove31InA127Palette() {
        val detector = AmbientMusicColorDetector(57, listOf(57, 90, 120), range = 1..127)
        val seen = (0 until 400).map { detector.color(AmbientColorMode.ENERGY, it * 600L, if (it % 2 == 0) 0.9 else 0.05) }.toSet()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it in setOf(57, 90, 120) })
    }
}

package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import org.junit.Assert.*
import org.junit.Test

class Oem127PaletteTest {
    @Test fun all38SampledIndicesMatchOemLogEvidence() {
        // Distinct indices from 20261018-185530 and 20261018-213103 color-evidence.csv.
        val samples = listOf(
            0 to 0xdf85ff, 6 to 0x8b4fe3, 8 to 0x7544d8, 12 to 0x193aff, 19 to 0x02a7ff,
            23 to 0x0ab7f3, 27 to 0x02dbfe, 30 to 0x02f0fc, 34 to 0x01fcff, 39 to 0xa0fff5,
            44 to 0xaafff0, 47 to 0xa8fad0, 56 to 0x53f204, 60 to 0xc0f987, 65 to 0x18ff74,
            68 to 0xfdff96, 72 to 0xfee687, 74 to 0xfcf588, 79 to 0xfcfe4f, 83 to 0xfff654,
            85 to 0xfae800, 86 to 0xf2d144, 87 to 0xf1c91e, 89 to 0xf1b934, 92 to 0xfba04b,
            97 to 0xfc7b4f, 99 to 0xff724f, 100 to 0xff6c4e, 106 to 0xfa3871, 109 to 0xff587d,
            112 to 0xff8599, 113 to 0xff9faf, 116 to 0xfbffff, 117 to 0xf3feff,
            120 to 0xd9faff, 122 to 0xb3faff, 124 to 0x97f5ff, 126 to 0x65f0ff,
        )
        assertEquals(38, samples.size)
        for ((index, rgb) in samples) {
            assertEquals("UI index $index", rgb or (0xff shl 24), Oem127Palette.previewRgb(Oem127Palette.rawCode(index)))
        }
    }

    @Test fun completeTableRoundTripsAndKeepsLookupRatherThanBodyPreviewValues() {
        for (code in 1..127) {
            assertEquals(code, Oem127Palette.rawCode(Oem127Palette.uiIndex(code)))
            val rgb = Oem127Palette.previewRgb(code)
            assertEquals(255, rgb ushr 24)
            assertEquals(rgb, Oem127Palette.previewRgb(Oem127Palette.nearestRawCode(rgb)))
        }
        assertEquals(0x94ff43, Oem127Palette.previewRgb(59) and 0xffffff)
        assertEquals(0x18ff74, Oem127Palette.previewRgb(66) and 0xffffff)
        assertEquals(0xfffc4f, Oem127Palette.previewRgb(81) and 0xffffff)
        assertEquals(0xffffff, Oem127Palette.previewRgb(116) and 0xffffff)
        for (code in listOf(0, 128, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { Oem127Palette.previewRgb(code) }
        }
        assertThrows(IllegalArgumentException::class.java) { Oem127Palette.rawCode(-1) }
        assertThrows(IllegalArgumentException::class.java) { Oem127Palette.rawCode(127) }
    }

    @Test fun paletteNeverAuthorizesLegacyWritesOrChangesOldPalette() {
        assertFalse(BydAmbientLightPolicy.validApply(1, 57, 1))
        assertFalse(BydAmbientLightPolicy.canRestore(BydAmbientLightPolicy.Snapshot(57, 57, 6, 1, 2)))
        assertEquals("oem127", BydAmbientLightPolicy.observedColorProfile("any-compatible-firmware", 6))
        assertEquals(0xffa84cc1.toInt(), AmbientAlbumPalette.previewRgb(1))
        assertThrows(IllegalArgumentException::class.java) { AmbientAlbumPalette.previewRgb(127) }
    }
}

package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomClusterWindowsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val ids = mutableListOf<Int>()
    @Before fun setup() { app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit() }
    @After fun cleanup() { ids.forEach(ShadowDisplayManager::removeDisplay); ClusterMusicState.publish(CarPlayNowPlaying(), null); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun display(): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5).also(ids::add)
        return app.getSystemService(DisplayManager::class.java).getDisplay(id).also { shadowOf(it).setName("bosdisplay0"); shadowOf(it).setFlags(Display.FLAG_PRESENTATION) }
    }
    @Test fun settingsDefaultOffBoundsClampAndDoNotEnableOldProjection() {
        assertFalse(ClusterWindowSettings.load(app).enabled)
        ClusterWindowSettings.save(app, "music", true)
        assertTrue(ClusterWindowSettings.load(app).enabled)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        ClusterWindowSettings.save(app, "music_width", 999)
        ClusterWindowSettings.save(app, "music_y", -12)
        assertEquals(95, ClusterWindowSettings.load(app).musicRect.width)
        assertEquals(0, ClusterWindowSettings.load(app).musicRect.y)
        ClusterWindowSettings.disable(app)
        assertFalse(ClusterWindowSettings.load(app).enabled)
    }
    @Test fun musicModesKeepIndependentRectanglesAndInvalidValuesFallBackToMini() {
        val initial = ClusterWindowSettings.load(app)
        assertEquals(ClusterMusicMode.MINI, initial.musicMode)
        assertEquals(initial.musicRect, initial.activeMusicRect)
        assertEquals("music", initial.musicRectKey)
        assertEquals(ClusterWindowRect(30, 50, 50, 50), initial.musicCardRect)
        assertTrue(initial.musicCardRect.height > initial.musicRect.height)
        ClusterWindowSettings.save(app, "music_width", 37)
        ClusterWindowSettings.save(app, "music_y", 73)
        val mini = ClusterWindowSettings.load(app).musicRect
        ClusterWindowSettings.save(app, "music_mode", "CARD")
        for ((key, value) in listOf("x" to -1, "y" to 101, "width" to 150, "height" to 1)) {
            ClusterWindowSettings.save(app, "music_card_$key", value)
        }
        val card = ClusterWindowSettings.load(app)
        assertEquals(ClusterWindowRect(0, 100, 95, 10), card.activeMusicRect)
        assertEquals("music_card", card.musicRectKey)
        assertEquals(mini, card.musicRect)
        assertEquals(initial.mapRect, card.mapRect)
        assertFalse(card.enabled)
        repeat(3) {
            ClusterWindowSettings.save(app, "music_mode", "MINI")
            assertEquals(mini, ClusterWindowSettings.load(app).activeMusicRect)
            assertEquals(card.musicCardRect, ClusterWindowSettings.load(app).musicCardRect)
            ClusterWindowSettings.save(app, "music_mode", "CARD")
            assertEquals(card, ClusterWindowSettings.load(app))
        }
        try { ClusterWindowSettings.save(app, "music_mode", "invalid"); fail("Unknown music mode") }
        catch (_: IllegalArgumentException) { }
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putString("cluster_windows_music_mode", "invalid")
            .putInt("cluster_windows_music_card_width", 1000).putInt("cluster_windows_music_card_height", -20).commit()
        assertEquals(card.copy(musicMode = ClusterMusicMode.MINI), ClusterWindowSettings.load(app))
    }

    @Test fun artworkAndIndependentFontsPersistClampAndInheritLegacyFont() {
        val initial = ClusterWindowSettings.load(app)
        assertEquals(100, initial.musicCardCoverSize)
        assertEquals(100, initial.titleFont); assertEquals(100, initial.artistFont)
        ClusterWindowSettings.save(app, "font", 135)
        val legacy = ClusterWindowSettings.load(app)
        assertEquals(135, legacy.titleFont); assertEquals(135, legacy.artistFont)
        ClusterWindowSettings.save(app, "music_card_cover_size", 75)
        ClusterWindowSettings.save(app, "title_font", 150)
        ClusterWindowSettings.save(app, "artist_font", 85)
        assertEquals(legacy.copy(musicCardCoverSize = 75, titleFont = 150, artistFont = 85), ClusterWindowSettings.load(app))
        ClusterWindowSettings.save(app, "music_card_cover_size", 999)
        ClusterWindowSettings.save(app, "title_font", -10)
        ClusterWindowSettings.save(app, "artist_font", 999)
        val clamped = ClusterWindowSettings.load(app)
        assertEquals(140, clamped.musicCardCoverSize)
        assertEquals(60, clamped.titleFont); assertEquals(180, clamped.artistFont)
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putInt("cluster_windows_music_card_cover_size", -100)
            .putInt("cluster_windows_title_font", 999).putInt("cluster_windows_artist_font", -100).commit()
        val loaded = ClusterWindowSettings.load(app)
        assertEquals(40, loaded.musicCardCoverSize)
        assertEquals(180, loaded.titleFont); assertEquals(60, loaded.artistFont)
        assertEquals(initial.musicRect, loaded.musicRect); assertEquals(initial.musicCardRect, loaded.musicCardRect)
        assertEquals(initial.mapRect, loaded.mapRect)
    }

    @Test fun cardAlignmentsPersistIndependentlyAndInvalidStoredValuesFallBack() {
        val base = ClusterWindowSettings.load(app)
        assertEquals(ClusterMusicAlignment.CENTER, base.cardCoverAlignment)
        assertEquals(ClusterMusicAlignment.LEFT, base.cardLyricAlignment)
        assertEquals(ClusterMusicAlignment.LEFT, base.cardInfoAlignment)
        assertEquals(ClusterMusicInfoLines.ORIGINAL, base.cardInfoLines)
        ClusterWindowSettings.save(app, "card_cover_alignment", "RIGHT")
        ClusterWindowSettings.save(app, "card_lyric_alignment", "CENTER")
        ClusterWindowSettings.save(app, "card_info_alignment", "LEFT")
        ClusterWindowSettings.save(app, "card_info_lines", "DASH")
        assertEquals(base.copy(cardCoverAlignment = ClusterMusicAlignment.RIGHT,
            cardLyricAlignment = ClusterMusicAlignment.CENTER, cardInfoLines = ClusterMusicInfoLines.DASH), ClusterWindowSettings.load(app))
        for (key in listOf("card_cover_alignment", "card_lyric_alignment", "card_info_alignment", "card_info_lines")) {
            try { ClusterWindowSettings.save(app, key, "invalid"); fail(key) } catch (_: IllegalArgumentException) { }
            app.getSharedPreferences("xcertplay_airplay", 0).edit().putString("cluster_windows_$key", "invalid").commit()
        }
        assertEquals(base, ClusterWindowSettings.load(app))
    }

    @Test fun trimInPlacePersistsIndependentlyAndDefaultsToLegacyFit() {
        val initial = ClusterWindowSettings.load(app)
        assertFalse(initial.trimInPlace)
        ClusterWindowSettings.save(app, "trim_left", 20)
        val trimmed = ClusterWindowSettings.load(app)
        ClusterWindowSettings.save(app, "trim_in_place", true)
        assertEquals(trimmed.copy(trimInPlace = true), ClusterWindowSettings.load(app))
        ClusterWindowSettings.resetTrim(app)
        assertEquals(initial.copy(trimInPlace = true), ClusterWindowSettings.load(app))
        ClusterWindowSettings.save(app, "trim_in_place", false)
        assertEquals(initial, ClusterWindowSettings.load(app))
    }

    @Test fun previewDragsTheActiveMusicRectAndKeepsTheGestureModeWhenSelectionChanges() {
        val preview = ClusterWindowLayoutView(app, 2f).apply { layout(0, 0, 1000, 500) }
        fun touch(action: Int, x: Float, y: Float) {
            val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
            try { assertTrue(preview.onTouchEvent(event)) } finally { event.recycle() }
        }
        for (mode in ClusterMusicMode.entries) {
            ClusterWindowSettings.save(app, "music_mode", mode.name)
            val before = ClusterWindowSettings.load(app)
            val rect = before.activeMusicRect.pixels(1000, 500)
            val x = rect.centerX().toFloat(); val y = rect.centerY().toFloat()
            touch(android.view.MotionEvent.ACTION_DOWN, x, y)
            val otherMode = if (mode == ClusterMusicMode.CARD) ClusterMusicMode.MINI else ClusterMusicMode.CARD
            ClusterWindowSettings.save(app, "music_mode", otherMode.name)
            touch(android.view.MotionEvent.ACTION_MOVE, x + 100, y + 50)
            touch(android.view.MotionEvent.ACTION_UP, x + 100, y + 50)
            val after = ClusterWindowSettings.load(app)
            val moved = before.activeMusicRect.copy(x = before.activeMusicRect.x + 10, y = before.activeMusicRect.y + 10)
            assertEquals(if (mode == ClusterMusicMode.CARD) moved else before.musicCardRect, after.musicCardRect)
            assertEquals(if (mode == ClusterMusicMode.MINI) moved else before.musicRect, after.musicRect)
            assertEquals(before.mapRect, after.mapRect)
            assertEquals(otherMode, after.musicMode)
        }
    }

    @Test fun mapBlendDefaultsOffAndItsIndependentParametersClampOnSaveAndLoad() {
        val initial = ClusterWindowSettings.load(app)
        assertFalse(initial.mapBlend)
        assertEquals(ClusterWindowSettings.MAP_FEATHER_DEFAULT, initial.mapFeatherPercent)
        assertEquals(4, initial.mapCornerPercent)
        ClusterWindowSettings.save(app, "map_blend", true)
        for (value in listOf(-100, 0, 5, 15, 25, 100)) {
            ClusterWindowSettings.save(app, "map_feather_percent", value)
            ClusterWindowSettings.save(app, "map_corner_percent", value)
            val config = ClusterWindowSettings.load(app)
            assertTrue(config.mapBlend)
            assertEquals(value.coerceIn(0, 30), config.mapFeatherPercent)
            assertEquals(value.coerceIn(0, 25), config.mapCornerPercent)
            assertEquals(initial.opacity, config.opacity)
            assertEquals(initial.mapRect, config.mapRect)
            assertFalse(config.enabled)
        }
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putInt("cluster_windows_map_feather_percent", -100)
            .putInt("cluster_windows_map_corner_percent", 100).commit()
        val loaded = ClusterWindowSettings.load(app)
        assertEquals(0, loaded.mapFeatherPercent)
        assertEquals(25, loaded.mapCornerPercent)
        ClusterWindowSettings.save(app, "map_blend", false)
        assertEquals(loaded.copy(mapBlend = false), ClusterWindowSettings.load(app))
    }

    private fun drawMapPanel(panel: android.widget.FrameLayout, width: Int = 300, height: Int = 200): Bitmap {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { panel.draw(Canvas(it)) }
    }

    @Test fun mapBlendWaitsForNonzeroBoundsAndRecoversAfterZeroSize() {
        val panel = CustomClusterWindows.MapPanel(app).apply {
            setBackgroundColor(Color.RED)
            applyBlend(ClusterWindowSettings.load(app).copy(mapBlend = true))
        }
        val destination = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(destination)
            panel.draw(canvas)
            assertNull(ReflectionHelpers.getField<Bitmap?>(panel, "mask"))
            for ((width, height) in listOf(0 to 200, 300 to 0, 0 to 0)) {
                drawMapPanel(panel).recycle()
                panel.layout(0, 0, width, height)
                destination.eraseColor(Color.GREEN)
                val saveCount = canvas.saveCount
                panel.draw(canvas)
                assertEquals(saveCount, canvas.saveCount)
                assertNull(ReflectionHelpers.getField<Bitmap?>(panel, "mask"))
                assertEquals(Color.GREEN, destination.getPixel(150, 100))
                val restored = drawMapPanel(panel)
                try {
                    assertEquals(Color.RED, restored.getPixel(150, 100))
                    assertEquals(0, Color.alpha(restored.getPixel(0, 0)))
                } finally { restored.recycle() }
            }
        } finally { destination.recycle() }
    }

    @Test fun mapBlendFeathersAllFourEdgesToAlphaWithoutTintingTheClearCenter() {
        val config = ClusterWindowSettings.load(app).copy(mapBlend = true, mapFeatherPercent = 10, mapCornerPercent = 20)
        val panel = CustomClusterWindows.MapPanel(app).apply { setBackgroundColor(Color.RED); applyBlend(config) }
        val image = drawMapPanel(panel)
        try {
            assertFalse(panel.isOpaque)
            assertEquals(Color.RED, image.getPixel(150, 100))
            for ((x, y) in listOf(0 to 0, 299 to 0, 0 to 199, 299 to 199, 5 to 5)) {
                assertEquals("Corner ($x,$y)", 0, Color.alpha(image.getPixel(x, y)))
            }
            val edges = listOf<(Int) -> Int>(
                { distance -> image.getPixel(distance, 100) },
                { distance -> image.getPixel(299 - distance, 100) },
                { distance -> image.getPixel(150, distance) },
                { distance -> image.getPixel(150, 199 - distance) })
            for (pixel in edges) {
                assertTrue(Color.alpha(pixel(0)) in 0..10)
                assertTrue(Color.alpha(pixel(5)) < Color.alpha(pixel(10)))
                assertTrue(Color.alpha(pixel(10)) < Color.alpha(pixel(15)))
                assertTrue(Color.alpha(pixel(10)) in 115..145)
                assertEquals(255, Color.alpha(pixel(20)))
                assertEquals(255, Color.red(pixel(10)))
                assertEquals(0, Color.green(pixel(10)))
                assertEquals(0, Color.blue(pixel(10)))
            }
            val mask = ReflectionHelpers.getField<Bitmap>(panel, "mask")
            repeat(3) { drawMapPanel(panel).recycle() }
            panel.applyBlend(config.copy(opacity = 50))
            assertSame(mask, ReflectionHelpers.getField<Bitmap>(panel, "mask"))
            val resized = drawMapPanel(panel, 600, 400)
            try {
                assertNotSame(mask, ReflectionHelpers.getField<Bitmap>(panel, "mask"))
                assertTrue(Color.alpha(resized.getPixel(20, 200)) in 115..145)
                assertEquals(Color.RED, resized.getPixel(300, 200))
            } finally { resized.recycle() }
        } finally { image.recycle() }
    }

    @Test fun mapBlendMasksBackgroundAndChildrenTogetherAndPreservesTheSceneBehindIt() {
        val panel = CustomClusterWindows.MapPanel(app).apply {
            setBackgroundColor(Color.RED)
            applyBlend(ClusterWindowSettings.load(app).copy(mapBlend = true, mapFeatherPercent = 10, mapCornerPercent = 20))
            addView(View(app).apply { setBackgroundColor(Color.argb(128, 0, 0, 255)) }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val image = drawMapPanel(panel)
        val scene = Bitmap.createBitmap(320, 220, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            val center = image.getPixel(150, 100)
            val edge = image.getPixel(10, 100)
            assertEquals(255, Color.alpha(center))
            assertTrue(Color.red(center) in 126..128)
            assertTrue(Color.blue(center) in 127..129)
            assertTrue(Color.alpha(edge) in 115..145)
            assertEquals(Color.red(center).toFloat(), Color.red(edge).toFloat(), 2f)
            assertEquals(Color.blue(center).toFloat(), Color.blue(edge).toFloat(), 2f)
            assertEquals(0, Color.alpha(image.getPixel(0, 0)))
            val canvas = Canvas(scene)
            canvas.translate(10f, 10f)
            panel.draw(canvas)
            assertEquals(Color.GREEN, scene.getPixel(0, 0))
            assertEquals(Color.GREEN, scene.getPixel(10, 10))
            assertEquals(center, scene.getPixel(160, 110))
            assertTrue(Color.green(scene.getPixel(20, 110)) in 110..145)
            assertEquals(255, Color.alpha(scene.getPixel(20, 110)))
        } finally { image.recycle(); scene.recycle() }
    }

    @Test fun mapBlendDisabledOrZeroWidthMatchesLegacyAndRadiusWorksWithoutFeather() {
        val config = ClusterWindowSettings.load(app)
        val panel = CustomClusterWindows.MapPanel(app).apply { setBackgroundColor(Color.RED) }
        val legacy = android.widget.FrameLayout(app).apply { setBackgroundColor(Color.RED) }
        val expected = drawMapPanel(legacy)
        try {
            for (next in listOf(config, config.copy(mapBlend = true, mapFeatherPercent = 0, mapCornerPercent = 0),
                config.copy(mapBlend = true, mapFeatherPercent = -100, mapCornerPercent = -100))) {
                panel.applyBlend(next)
                val actual = drawMapPanel(panel)
                try { assertTrue(actual.sameAs(expected)) } finally { actual.recycle() }
            }
            panel.applyBlend(config.copy(mapBlend = true, mapFeatherPercent = 0, mapCornerPercent = 100))
            val rounded = drawMapPanel(panel)
            try {
                assertEquals(0, Color.alpha(rounded.getPixel(0, 0)))
                assertEquals(Color.RED, rounded.getPixel(0, 100))
                assertEquals(Color.RED, rounded.getPixel(150, 0))
                assertEquals(25, ReflectionHelpers.getField<Int>(panel, "cornerPercent").toInt())
            } finally { rounded.recycle() }
            panel.applyBlend(config.copy(mapBlend = true, mapFeatherPercent = 100, mapCornerPercent = 0))
            assertEquals(30, ReflectionHelpers.getField<Int>(panel, "featherPercent").toInt())
            panel.applyBlend(config)
            val disabled = drawMapPanel(panel)
            try { assertTrue(disabled.sameAs(expected)) } finally { disabled.recycle() }
        } finally { expected.recycle() }
    }

    @Test fun safeAreaTrimAndCoverDefaultsClampPersistAndResetIndependently() {
        val initial = ClusterWindowSettings.load(app)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(8, 8, 8, 8), initial.safeArea)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(), initial.trim)
        assertEquals(ClusterCoverSide.LEFT, initial.coverSide)
        for (value in listOf(-200, 0, 8, 40, 200)) {
            for (side in listOf("top", "bottom", "left", "right")) ClusterWindowSettings.save(app, "safe_$side", value)
            val area = ClusterWindowSettings.load(app).safeArea
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(value.coerceIn(0, 40), value.coerceIn(0, 40),
                value.coerceIn(0, 40), value.coerceIn(0, 40)), area)
            assertTrue(area.left + area.right < 100 && area.top + area.bottom < 100)
        }
        ClusterWindowSettings.save(app, "trim_top", 3); ClusterWindowSettings.save(app, "trim_bottom", 99)
        ClusterWindowSettings.save(app, "trim_left", -99); ClusterWindowSettings.save(app, "trim_right", 7)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(3, 40, 0, 7), ClusterWindowSettings.load(app).trim)
        ClusterWindowSettings.save(app, "cover_side", "RIGHT")
        assertEquals(ClusterCoverSide.RIGHT, ClusterWindowSettings.load(app).coverSide)
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putInt("cluster_windows_safe_left", -100).putInt("cluster_windows_safe_right", 100)
            .remove("cluster_windows_trim_left").putInt("cluster_windows_left_trim", 6)
            .putInt("cluster_windows_trim_top", 100).putString("cluster_windows_cover_side", "invalid").commit()
        val clamped = ClusterWindowSettings.load(app)
        assertEquals(0, clamped.safeArea.left); assertEquals(40, clamped.safeArea.right)
        assertEquals("Legacy left-only crop carries over", 6, clamped.trim.left)
        assertEquals(40, clamped.trim.top); assertEquals(ClusterCoverSide.LEFT, clamped.coverSide)
        ClusterWindowSettings.save(app, "trim_left", 2)
        assertEquals(2, ClusterWindowSettings.load(app).trim.left)
        ClusterWindowSettings.resetSafeArea(app)
        assertEquals(initial.safeArea, ClusterWindowSettings.load(app).safeArea)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(40, 40, 2, 7), ClusterWindowSettings.load(app).trim)
        val beforeReset = ClusterWindowSettings.load(app)
        ClusterWindowSettings.resetTrim(app)
        assertEquals(beforeReset.copy(trim = AirPlayInsets()), ClusterWindowSettings.load(app))
        assertFalse(ClusterWindowSettings.load(app).enabled)
    }

    @Test fun customSafeAreaIsPassedToHostWithoutChangingLegacyProjectionDefaults() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        display(); ClusterWindowSettings.save(app, "display", "bosdisplay0"); ClusterWindowSettings.save(app, "map", true)
        fun requested(): AirPlayDisplayConfig = ReflectionHelpers.callInstanceMethod(activity, "clusterDisplayConfig")
        val initial = requested()
        val virtual = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.config(initial.widthPixels, initial.heightPixels, 100,
            baseSafeArea = com.shilapi.xcertplay.airplay.AirPlayInsets(8, 8, 8, 8))
        assertEquals(virtual.safeArea, initial.safeArea)
        ClusterWindowSettings.save(app, "safe_top", 0); ClusterWindowSettings.save(app, "safe_bottom", 40)
        ClusterWindowSettings.save(app, "safe_left", 7); ClusterWindowSettings.save(app, "safe_right", 19)
        val changed = requested()
        val expected = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.config(changed.widthPixels, changed.heightPixels, 100,
            baseSafeArea = com.shilapi.xcertplay.airplay.AirPlayInsets(0, 40, 7, 19))
        assertEquals(expected.safeArea, changed.safeArea)
        val legacy = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.config(1000, 600, 100)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(96, 150, 350, 360), legacy.safeArea)
        assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(16, 25, 35, 36),
            com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.SAFE_AREA_PERCENT)
    }

    @Test fun canvasPreferenceRoundTripsWithoutEnablingWindowsOrChangingOtherSettings() {
        val initial = ClusterWindowSettings.load(app)
        assertEquals(ClusterMapCanvas.WINDOW, initial.mapCanvas)
        for (preset in ClusterMapCanvas.entries) {
            ClusterWindowSettings.save(app, "map_canvas", preset.name)
            assertEquals(initial.copy(mapCanvas = preset), ClusterWindowSettings.load(app))
        }
        try {
            ClusterWindowSettings.save(app, "map_canvas", "unknown")
            fail("Invalid canvas must not be saved")
        } catch (_: IllegalArgumentException) { }
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putString("cluster_windows_map_canvas", "unknown").commit()
        assertEquals(initial, ClusterWindowSettings.load(app))
    }

    @Test fun highResolutionSourceKeepsTheSameCropGeometryAcrossWindowSizes() {
        for ((sw, sh) in listOf(1280 to 720, 1920 to 1080, 1280 to 1280, 1920 to 1920)) {
            for ((w, h) in listOf(320 to 600, 616 to 552, 900 to 300)) {
                for (crop in listOf(false, true)) for (trim in listOf(AirPlayInsets(), AirPlayInsets(20, 40, 30, 10), AirPlayInsets(40, 40, 40, 40))) {
                    val result = CustomClusterWindows.mapTransform(sw, sh, w, h, crop, trim)
                    val sourceWidth = sw * (100 - trim.left - trim.right) / 100f
                    val sourceHeight = sh * (100 - trim.top - trim.bottom) / 100f
                    val kept = android.graphics.RectF(w * trim.left / 100f, h * trim.top / 100f,
                        w * (100 - trim.right) / 100f, h * (100 - trim.bottom) / 100f)
                    result.matrix.mapRect(kept)
                    assertEquals(kept.width() / sourceWidth, kept.height() / sourceHeight, .0001f)
                    assertEquals(w / 2f, kept.centerX(), .001f)
                    assertEquals(h / 2f, kept.centerY(), .001f)
                    assertTrue(result.clip.width() > 0 && result.clip.height() > 0)
                    assertTrue(result.content.width() > 0 && result.content.height() > 0)
                    val doubled = CustomClusterWindows.mapTransform(sw * 2, sh * 2, w, h, crop, trim)
                    val values = FloatArray(9); val twiceValues = FloatArray(9)
                    result.matrix.getValues(values); doubled.matrix.getValues(twiceValues)
                    assertArrayEquals(values, twiceValues, .0001f)
                    assertEquals(result.clip, doubled.clip)
                }
            }
        }
    }

    @Test fun alignedStreamsCoverTinyGapsButLargeAspectDifferencesStillFitUniformly() {
        fun projected(sw: Int, sh: Int, w: Int, h: Int, crop: Boolean): android.graphics.RectF {
            val result = CustomClusterWindows.mapTransform(sw, sh, w, h, crop, AirPlayInsets())
            val bounds = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
            result.matrix.mapRect(bounds)
            assertEquals(bounds.width() / sw, bounds.height() / sh, .0001f)
            assertEquals(android.graphics.Rect(0, 0, w, h), result.clip)
            return bounds
        }
        for ((sw, sh, w, h) in listOf(listOf(864, 328, 864, 324), listOf(864, 328, 861, 326),
            listOf(960, 544, 960, 540), listOf(480, 632, 480, 626), listOf(1000, 1000, 1008, 1000))) {
            val bounds = projected(sw, sh, w, h, false)
            val difference = maxOf(w.toFloat() / sw, h.toFloat() / sh) / minOf(w.toFloat() / sw, h.toFloat() / sh)
            if (difference <= 1.01f) assertTrue(bounds.left <= .001f && bounds.top <= .001f && bounds.right >= w - .001f && bounds.bottom >= h - .001f)
            else assertTrue(bounds.left > 0 || bounds.top > 0)
        }
        val wide = projected(1600, 900, 400, 400, false)
        assertEquals(0f, wide.left, .001f); assertTrue(wide.top > 80f)
        val cropped = projected(1600, 900, 400, 400, true)
        assertTrue(cropped.left < 0f); assertEquals(0f, cropped.top, .001f)
    }

    @Test fun edgeTrimCropsEachSourceEdgeKeepsAspectAndClipsDiscardedPixels() {
        val window = android.graphics.RectF(0f, 0f, 1000f, 500f)
        for (crop in listOf(false, true)) for (trim in listOf(AirPlayInsets(), AirPlayInsets(left = 1), AirPlayInsets(left = 10),
            AirPlayInsets(right = 10), AirPlayInsets(top = 10), AirPlayInsets(bottom = 20), AirPlayInsets(5, 10, 15, 20))) {
            val result = CustomClusterWindows.mapTransform(1000, 500, 1000, 500, crop, trim)
            val retained = android.graphics.RectF(trim.left * 10f, trim.top * 5f, 1000f - trim.right * 10f, 500f - trim.bottom * 5f)
            val sourceWidth = retained.width(); val sourceHeight = retained.height()
            result.matrix.mapRect(retained)
            assertEquals("$trim", retained.width() / sourceWidth, retained.height() / sourceHeight, .0001f)
            assertEquals(500f, retained.centerX(), .001f)
            assertEquals(250f, retained.centerY(), .001f)
            val visible = android.graphics.RectF(retained).apply { assertTrue(intersect(window)) }
            for ((expected, actual) in listOf(visible.left to result.content.left, visible.top to result.content.top,
                visible.right to result.content.right, visible.bottom to result.content.bottom)) assertEquals("$trim", expected, actual, .01f)
            if (crop) assertTrue(retained.left <= .001f && retained.top <= .001f && retained.right >= 999.999f && retained.bottom >= 499.999f)
            fun mapped(x: Float, y: Float) = floatArrayOf(x, y).also(result.matrix::mapPoints)
            if (trim.left > 0) assertTrue("$trim", mapped(trim.left * 10f - 1, 250f)[0] < result.clip.left)
            if (trim.right > 0) assertTrue("$trim", mapped(1000f - trim.right * 10f + 1, 250f)[0] > result.clip.right)
            if (trim.top > 0) assertTrue("$trim", mapped(500f, trim.top * 5f - 1)[1] < result.clip.top)
            if (trim.bottom > 0) assertTrue("$trim", mapped(500f, 500f - trim.bottom * 5f + 1)[1] > result.clip.bottom)
            if (trim.left > 0) assertTrue("$trim", result.clip.left >= visible.left - .001f && result.clip.left <= visible.left + 1)
            if (trim.right > 0) assertTrue("$trim", result.clip.right <= visible.right + .001f && result.clip.right >= visible.right - 1)
            if (trim == AirPlayInsets()) assertEquals(android.graphics.Rect(0, 0, 1000, 500), result.clip)
        }
    }

    @Test fun inPlaceTrimPreservesTheFullSourceMatrixAndThreeUntrimmedEdges() {
        for ((sw, sh) in listOf(1920 to 1080, 1280 to 1280, 864 to 328)) {
            for ((w, h) in listOf(300 to 200, 320 to 600, 900 to 300)) for (crop in listOf(false, true)) {
                val baseline = CustomClusterWindows.mapTransform(sw, sh, w, h, crop, AirPlayInsets())
                val baselineValues = FloatArray(9).also(baseline.matrix::getValues)
                for (trim in listOf(AirPlayInsets(), AirPlayInsets(left = 40), AirPlayInsets(right = 30),
                    AirPlayInsets(top = 25), AirPlayInsets(bottom = 40), AirPlayInsets(40, 30, 20, 10))) {
                    val actual = CustomClusterWindows.mapTransform(sw, sh, w, h, crop, trim, true)
                    assertArrayEquals(baselineValues, FloatArray(9).also(actual.matrix::getValues), 0f)
                    val projected = android.graphics.RectF(w * trim.left / 100f, h * trim.top / 100f,
                        w * (100 - trim.right) / 100f, h * (100 - trim.bottom) / 100f)
                    baseline.matrix.mapRect(projected)
                    if (!projected.intersect(0f, 0f, w.toFloat(), h.toFloat())) projected.setEmpty()
                    assertEquals(projected, actual.content)
                    val edges = listOf(Triple(trim.left, baseline.content.left, actual.content.left),
                        Triple(trim.top, baseline.content.top, actual.content.top),
                        Triple(trim.right, baseline.content.right, actual.content.right),
                        Triple(trim.bottom, baseline.content.bottom, actual.content.bottom))
                    for ((amount, original, retained) in edges) if (amount == 0) assertEquals(original, retained, .001f)
                    assertEquals(kotlin.math.ceil(projected.left).toInt(), actual.clip.left)
                    assertEquals(kotlin.math.floor(projected.right).toInt(), actual.clip.right)
                    assertEquals(kotlin.math.ceil(projected.top).toInt(), actual.clip.top)
                    assertEquals(kotlin.math.floor(projected.bottom).toInt(), actual.clip.bottom)
                }
            }
        }
        val left = CustomClusterWindows.mapTransform(1000, 500, 1000, 500, false, AirPlayInsets(left = 20), true)
        assertEquals(android.graphics.RectF(200f, 0f, 1000f, 500f), left.content)
        assertEquals(android.graphics.Rect(200, 0, 1000, 500), left.clip)
    }

    @Test fun inPlaceTrimClipsBackgroundAndChildrenEvenWithoutBlendAndKeepsOppositeFades() {
        for (blend in listOf(false, true)) {
            var trim = AirPlayInsets()
            val panel = CustomClusterWindows.MapPanel(app).apply {
                setBackgroundColor(Color.RED)
                addView(View(app).apply { setBackgroundColor(Color.argb(128, 0, 0, 255)) }, android.widget.FrameLayout.LayoutParams(-1, -1))
                contentBounds = { w, h -> CustomClusterWindows.mapTransform(300, 200, w, h, false, trim, true).content }
                untrimmedContentBounds = { w, h -> CustomClusterWindows.mapTransform(300, 200, w, h, false, AirPlayInsets()).content }
                applyBlend(ClusterWindowSettings.load(app).copy(trimInPlace = true, mapBlend = blend,
                    mapFeatherPercent = 20, mapCornerPercent = 0))
            }
            val original = drawMapPanel(panel)
            trim = AirPlayInsets(left = 40)
            panel.refreshContent()
            val image = drawMapPanel(panel)
            val scene = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try {
                assertFalse(panel.isOpaque)
                assertEquals(0, Color.alpha(image.getPixel(100, 100)))
                assertEquals(original.getPixel(210, 100), image.getPixel(210, 100))
                for ((x, y) in listOf(290 to 100, 210 to 10, 210 to 190)) {
                    assertEquals("Opposite fade must keep its original width", original.getPixel(x, y), image.getPixel(x, y))
                }
                if (blend) assertTrue(Color.alpha(image.getPixel(130, 100)) in 1..100)
                else assertEquals(original.getPixel(210, 100), image.getPixel(120, 100))
                panel.draw(Canvas(scene))
                assertEquals(Color.GREEN, scene.getPixel(100, 100))
                assertEquals(image.getPixel(210, 100), scene.getPixel(210, 100))
            } finally { original.recycle(); image.recycle(); scene.recycle() }
        }
    }

    @Test fun inPlaceTrimEmptyContentNeverFallsBackToDrawingTheFullPanel() {
        for (blend in listOf(false, true)) for (feather in listOf(0, 20)) {
            val panel = CustomClusterWindows.MapPanel(app).apply {
                setBackgroundColor(Color.RED)
                addView(View(app).apply { setBackgroundColor(Color.BLUE) }, android.widget.FrameLayout.LayoutParams(-1, -1))
                applyBlend(ClusterWindowSettings.load(app).copy(trimInPlace = true, mapBlend = blend,
                    mapFeatherPercent = feather, mapCornerPercent = 0))
            }
            for (empty in listOf(android.graphics.RectF(), android.graphics.RectF(400f, 300f, 500f, 400f))) {
                panel.contentBounds = { _, _ -> empty }
                val image = drawMapPanel(panel)
                try {
                    assertEquals(0, Color.alpha(image.getPixel(150, 100)))
                    assertEquals(0, Color.alpha(image.getPixel(0, 0)))
                    val canvas = Canvas(image)
                    val saves = canvas.saveCount
                    panel.draw(canvas)
                    assertEquals(saves, canvas.saveCount)
                } finally { image.recycle() }
            }
            panel.contentBounds = null
            val restored = drawMapPanel(panel)
            try { assertEquals(Color.BLUE, restored.getPixel(150, 100)) } finally { restored.recycle() }
        }
    }

    @Test fun mapBlendFadesTheCroppedPictureWithSmoothEnds() {
        val trim = AirPlayInsets(top = 10, bottom = 10)
        val config = ClusterWindowSettings.load(app).copy(mapBlend = true, mapFeatherPercent = 20, mapCornerPercent = 0, trim = trim)
        val panel = CustomClusterWindows.MapPanel(app).apply {
            setBackgroundColor(Color.RED)
            contentBounds = { w, h -> CustomClusterWindows.mapTransform(300, 200, w, h, false, trim).content }
            applyBlend(config)
        }
        val image = drawMapPanel(panel)
        try {
            // Cropping 10% top and bottom letterboxes the picture to y = 20..180; the fade spans 20% of 160 px.
            assertEquals(0, Color.alpha(image.getPixel(150, 10)))
            assertEquals(0, Color.alpha(image.getPixel(150, 190)))
            assertTrue(Color.alpha(image.getPixel(150, 20)) <= 2)
            assertEquals(Color.RED, image.getPixel(150, 52))
            assertEquals(Color.RED, image.getPixel(150, 100))
            val column = (20..52).map { Color.alpha(image.getPixel(150, it)) }
            val steps = column.zipWithNext { a, b -> b - a }
            assertTrue("Fade must not reverse: $column", steps.all { it >= 0 })
            assertTrue("Fade must start and end without a visible line: $column", steps.first() <= 2 && steps.last() <= 2)
            assertTrue("Fade must stay gradual: $column", steps.all { it <= 16 })
            for (y in 20..180) assertEquals(Color.alpha(image.getPixel(150, y)).toFloat(), Color.alpha(image.getPixel(150, 199 - y)).toFloat(), 1f)
            panel.contentBounds = null
            val full = drawMapPanel(panel)
            try { assertTrue(Color.alpha(full.getPixel(150, 20)) > 100) } finally { full.recycle() }
        } finally { image.recycle() }
    }

    @Test fun cardShowsAdditionalLyricLinesAndAlbumWhileMiniRenderingStaysUnchanged() {
        val info = CarPlayNowPlaying(title = "First line\nSecond line\nThird line\nFourth line", artist = "Artist", album = "Album")
        val config = ClusterWindowSettings.load(app).copy(music = true, cover = false)
        fun render(mode: ClusterMusicMode, playing: CarPlayNowPlaying, text: Boolean = true): Bitmap {
            val panel = CustomClusterWindows.MusicPanel(app, config.copy(musicMode = mode, text = text))
            ReflectionHelpers.setField(panel, "snapshot", ClusterMusicState.Snapshot(playing, null))
            panel.layout(0, 0, 640, 320)
            return Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).also { panel.draw(Canvas(it)) }
        }
        for (mode in ClusterMusicMode.entries) {
            val original = render(mode, info)
            val lyric = render(mode, info.copy(title = "First line\nSecond line\nChanged lyric\nFourth line"))
            val album = render(mode, info.copy(album = "Different album"))
            val disabled = render(mode, info, text = false)
            val changedDisabled = render(mode, info.copy(title = "Different", album = "Different"), text = false)
            try {
                assertEquals(mode == ClusterMusicMode.MINI, original.sameAs(lyric))
                assertEquals(mode == ClusterMusicMode.MINI, original.sameAs(album))
                assertTrue(disabled.sameAs(changedDisabled))
            } finally { original.recycle(); lyric.recycle(); album.recycle(); disabled.recycle(); changedDisabled.recycle() }
        }
    }

    @Test fun musicLayoutUsesPhysicalCoverSidesAndFullWidthWithoutArtwork() {
        for (mode in ClusterMusicMode.entries) for ((width, height) in listOf(300 to 180, 900 to 300)) {
            val base = ClusterWindowSettings.load(app).copy(musicMode = mode)
            val left = CustomClusterWindows.musicLayout(width, height, base.copy(coverSide = ClusterCoverSide.LEFT), true)
            val right = CustomClusterWindows.musicLayout(width, height, base.copy(coverSide = ClusterCoverSide.RIGHT), true)
            if (mode == ClusterMusicMode.CARD) {
                assertTrue(left.cover!!.bottom < left.text.top)
                assertEquals(width / 2f, left.cover.centerX(), .001f)
                assertEquals(left.cover, right.cover)
                assertEquals(left.text, right.text)
            } else {
                assertTrue(left.cover!!.right < left.text.left)
                assertTrue(right.cover!!.left > right.text.right)
                assertEquals(width.toFloat(), left.cover.left + right.cover.right, .001f)
            }
            assertEquals(left.text.width(), right.text.width(), .001f)
            for (side in ClusterCoverSide.entries) {
                val coverOnly = CustomClusterWindows.musicLayout(width, height, base.copy(text = false, coverSide = side), true)
                assertEquals(width / 2f, coverOnly.cover!!.centerX(), .001f)
                assertEquals(height / 2f, coverOnly.cover.centerY(), .001f)
                val absent = CustomClusterWindows.musicLayout(width, height, base.copy(coverSide = side), false)
                val disabled = CustomClusterWindows.musicLayout(width, height, base.copy(cover = false, coverSide = side), true)
                assertNull(absent.cover); assertNull(disabled.cover)
                assertEquals(absent.text, disabled.text)
                if (mode == ClusterMusicMode.MINI) assertTrue(absent.text.width() > left.text.width())
                else assertTrue(absent.text.height() > left.text.height())
            }
        }
    }

    @Test fun verticalCardKeepsSquareArtworkAndPositiveNonOverlappingTextAtExtremeSizes() {
        val base = ClusterWindowSettings.load(app).copy(musicMode = ClusterMusicMode.CARD)
        for ((width, height) in listOf(90 to 720, 1920 to 72, 640 to 640, 1 to 1)) for (size in listOf(40, 100, 140)) {
            val layout = CustomClusterWindows.musicLayout(width, height, base.copy(musicCardCoverSize = size), true)
            val cover = layout.cover!!
            assertEquals(cover.width(), cover.height(), .001f)
            assertEquals(width / 2f, cover.centerX(), .001f)
            assertTrue(cover.bottom < layout.text.top)
            assertTrue(layout.text.width() > 0 && layout.text.height() > 0)
            assertTrue(cover.left >= 0 && cover.right <= width && layout.text.bottom <= height)
        }
    }

    @Test fun cardArtworkScalesWithoutChangingWindowOrMiniLayout() {
        val config = ClusterWindowSettings.load(app).copy(musicMode = ClusterMusicMode.CARD)
        val normal = CustomClusterWindows.musicLayout(640, 640, config, true)
        val small = CustomClusterWindows.musicLayout(640, 640, config.copy(musicCardCoverSize = 60), true)
        val large = CustomClusterWindows.musicLayout(640, 640, config.copy(musicCardCoverSize = 140), true)
        assertEquals(normal.cover!!.width() * .6f, small.cover!!.width(), .001f)
        assertTrue(large.cover!!.width() > normal.cover.width())
        assertTrue(large.cover.bottom < large.text.top)
        assertTrue(large.text.height() > 0)
        assertEquals(320f, small.cover.centerX(), .001f)
        val only = CustomClusterWindows.musicLayout(640, 640, config.copy(text = false, musicCardCoverSize = 60), true)
        assertEquals(320f, only.cover!!.centerX(), .001f)
        assertEquals(320f, only.cover.centerY(), .001f)
        val mini = config.copy(musicMode = ClusterMusicMode.MINI)
        assertEquals(CustomClusterWindows.musicLayout(640, 640, mini, true),
            CustomClusterWindows.musicLayout(640, 640, mini.copy(musicCardCoverSize = 40), true))
        assertNull(CustomClusterWindows.musicLayout(640, 640, config.copy(musicCardCoverSize = 140), false).cover)
    }

    @Test fun titleAndArtistSizesAreIndependentInBothModes() {
        for (mode in ClusterMusicMode.entries) {
            val config = ClusterWindowSettings.load(app).copy(musicMode = mode)
            val base = CustomClusterWindows.musicTextSizes(640, config, 1f)
            val title = CustomClusterWindows.musicTextSizes(640, config.copy(titleFont = 150), 1f)
            val artist = CustomClusterWindows.musicTextSizes(640, config.copy(artistFont = 80), 1f)
            assertEquals(base.first * 1.5f, title.first, .001f)
            assertEquals(base.second, title.second, .001f)
            assertEquals(base.first, artist.first, .001f)
            assertEquals(base.second * .8f, artist.second, .001f)
        }
    }

    @Test fun cardCoverAlignmentDoesNotChangeTextBoundsAndMiniIgnoresIt() {
        val base = ClusterWindowSettings.load(app).copy(musicMode = ClusterMusicMode.CARD, musicCardCoverSize = 60)
        for (text in listOf(false, true)) {
            val layouts = ClusterMusicAlignment.entries.map {
                CustomClusterWindows.musicLayout(640, 480, base.copy(text = text, cardCoverAlignment = it), true)
            }
            assertTrue(layouts[0].cover!!.left < layouts[1].cover!!.left)
            assertTrue(layouts[1].cover!!.left < layouts[2].cover!!.left)
            assertEquals(320f, layouts[1].cover!!.centerX(), .001f)
            assertEquals(layouts[0].text, layouts[2].text)
            assertEquals(layouts[0].cover!!.width(), layouts[2].cover!!.width(), .001f)
            assertTrue(layouts.all { it.cover!!.left >= 0 && it.cover.right <= 640 })
        }
        val mini = base.copy(musicMode = ClusterMusicMode.MINI)
        assertEquals(CustomClusterWindows.musicLayout(640, 480, mini, true),
            CustomClusterWindows.musicLayout(640, 480, mini.copy(cardCoverAlignment = ClusterMusicAlignment.RIGHT), true))
    }

    @Test fun cardInformationUsesExplicitSeparatorAndNeverGuessesTrackNames() {
        assertEquals(listOf("Song - Author", "Album"), CustomClusterWindows.cardInformation("Song - Author", "Album", ClusterMusicInfoLines.ORIGINAL))
        for (mode in ClusterMusicInfoLines.entries.filter { it.separator != null }) {
            assertEquals(listOf("Song", "Author", "Album"), CustomClusterWindows.cardInformation("Song${mode.separator}Author", "Album", mode))
        }
        assertEquals(listOf("AC/DC", "Artist-name"), CustomClusterWindows.cardInformation("AC/DC", "Artist-name", ClusterMusicInfoLines.SLASH))
        assertEquals(listOf("Artist"), CustomClusterWindows.cardInformation("Artist", "Artist", ClusterMusicInfoLines.DASH))
        assertTrue(CustomClusterWindows.cardInformation(null, " ", ClusterMusicInfoLines.DASH).isEmpty())
    }

    @Test fun cardTextWrapsUnbrokenChineseEnglishAndRtlAndAlignsEveryLinePhysically() {
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
        for (rtl in listOf(false, true)) for (text in listOf("超宽歌词和曲名作者自动换行".repeat(4),
            "An extremely long song title and artist without manual newlines ".repeat(3),
            "Supercalifragilisticexpialidocious".repeat(3), "كلمات الأغنية واسم الفنان الطويل ".repeat(3),
            "English first line\n中文第二行\nسطر عربي")) {
            val layout = CustomClusterWindows.cardTextLayout(text, paint, 200, 600f, rtl)
            assertTrue("Must wrap without explicit newlines", layout.lineCount > 1)
            for (line in 0 until layout.lineCount) for (alignment in ClusterMusicAlignment.entries) {
                val offset = CustomClusterWindows.lineAlignmentOffset(layout, line, alignment)
                val left = layout.getLineLeft(line) + offset
                val right = layout.getLineRight(line) + offset
                when (alignment) {
                    ClusterMusicAlignment.LEFT -> assertEquals(0f, left, .01f)
                    ClusterMusicAlignment.CENTER -> assertEquals(200f, left + right, .01f)
                    ClusterMusicAlignment.RIGHT -> assertEquals(200f, right, .01f)
                }
            }
        }
        assertEquals(24f, paint.textSize, .001f)
    }

    @Test fun cardTextKeepsPrimaryAndEachInformationFieldInSeparateNonOverlappingBlocks() {
        val config = ClusterWindowSettings.load(app).copy(musicMode = ClusterMusicMode.CARD,
            cardLyricAlignment = ClusterMusicAlignment.CENTER, cardInfoAlignment = ClusterMusicAlignment.RIGHT,
            cardInfoLines = ClusterMusicInfoLines.DASH)
        val titlePaint = android.text.TextPaint().apply { textSize = 32f }
        val detailPaint = android.text.TextPaint().apply { textSize = 24f }
        for (height in listOf(1f, 72f, 600f)) for (width in listOf(1, 120, 640)) {
            val blocks = CustomClusterWindows.cardTextBlocks("Lyrics ".repeat(20), "Song - Author", "Album", config,
                titlePaint, detailPaint, width, height, false)
            assertEquals(listOf("Lyrics ".repeat(20), "Song", "Author", "Album").map { it.length }, blocks.map { it.layout.text.length })
            if (height == 600f && width == 640) {
                assertEquals(listOf("Lyrics ".repeat(20), "Song", "Author", "Album"), blocks.map { it.layout.text.toString() })
            }
            assertEquals(ClusterMusicAlignment.CENTER, blocks.first().alignment)
            assertTrue(blocks.drop(1).all { it.alignment == ClusterMusicAlignment.RIGHT })
            assertTrue(blocks.zipWithNext().all { (a, b) -> a.top + a.height <= b.top + .001f })
            assertTrue(blocks.all { it.top >= 0 && it.height > 0 && it.top + it.height <= height + .001f })
        }
        val onlyTitle = CustomClusterWindows.cardTextBlocks("Song", null, null, config, titlePaint, detailPaint, 640, 300f, false)
        assertEquals(1, onlyTitle.size); assertEquals(300f, onlyTitle.single().height, .001f)
    }

    @Test fun rectanglesAlwaysFitAndPixelSizeUsesTargetDisplay() {
        for (x in listOf(-100, 0, 50, 100, 999)) for (y in listOf(-50, 0, 100, 999)) {
            val r = ClusterWindowRect(x, y, 30, 25).pixels(1920, 720)
            assertEquals(576, r.width()); assertEquals(180, r.height())
            assertTrue(r.left >= 0 && r.top >= 0 && r.right <= 1920 && r.bottom <= 720)
        }
    }
    @Test fun musicWindowDoesNotNeedMapAndLyricsRemainAfterEightSeconds() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = false)
        var surfaceCalls = 0
        val window = CustomClusterWindows(host.get(), display(), false, 960, 540, config) { surfaceCalls++ }
        try {
            window.show(); idle()
            val panel: CustomClusterWindows.MusicPanel = ReflectionHelpers.getField(window, "music")
            val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            ClusterMusicState.publish(CarPlayNowPlaying(title = "lyric one", artist = "song - artist"), image); idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            assertEquals(View.VISIBLE, panel.visibility)
            val state: ClusterMusicState.Snapshot = ReflectionHelpers.getField(panel, "snapshot")
            assertSame(image, state.artwork)
            ClusterMusicState.publish(state.info.copy(title = "lyric two"), image); idle()
            assertEquals("lyric two", ReflectionHelpers.getField<ClusterMusicState.Snapshot>(panel, "snapshot").info.title)
            assertEquals(0, surfaceCalls)
            assertNull(ReflectionHelpers.getField<View?>(window, "video"))
        } finally { window.dismiss(); host.pause().stop().destroy() }
    }
    @Test fun hostRequestsNoMapForMusicOnlyAndIndependentMapWhenEnabled() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ClusterWindowSettings.save(app, "music", true)
        fun config(): AirPlayDisplayConfig? = ReflectionHelpers.callInstanceMethod(activity, "clusterDisplayConfig")
        assertNull(config())
        display(); ClusterWindowSettings.save(app, "display", "bosdisplay0")
        ClusterWindowSettings.save(app, "map", true)
        val map = config()!!
        assertTrue(map.widthPixels in 320..1280 && map.heightPixels in 180..720)
        assertEquals("maps:/car/instrumentcluster/map", map.initialUrl)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
    }
    @Test fun rendersIndependentCardAlignmentsAndWrappedInfoWithoutChangingMini() {
        val folder = java.io.File("build/reports/custom-cluster-ui").apply { mkdirs() }
        for ((locale, text) in listOf("zh-rCN" to "这是一句超宽的歌词会在卡片内自动换行并独立对齐", "en" to "A long lyric line wraps automatically inside this music card",
            "ar-ldrtl" to "كلمات أغنية طويلة تلتف تلقائيًا داخل بطاقة الموسيقى")) {
            RuntimeEnvironment.setQualifiers("$locale-w360dp-h900dp-mdpi")
            val config = ClusterWindowSettings.load(app).copy(musicMode = ClusterMusicMode.CARD, musicCardCoverSize = 70,
                cardInfoLines = ClusterMusicInfoLines.DASH)
            val art = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val info = CarPlayNowPlaying(title = text, artist = if (locale.startsWith("zh")) "这是一首很长的歌曲名称 - 作者名字" else "Song name - Artist name", album = "Album")
            fun draw(settings: ClusterWindowConfig): Bitmap {
                val panel = CustomClusterWindows.MusicPanel(app, settings).apply {
                    layoutDirection = if (locale.startsWith("ar")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                ReflectionHelpers.setField(panel, "snapshot", ClusterMusicState.Snapshot(info, art))
                panel.layout(0, 0, 360, 600)
                return Bitmap.createBitmap(360, 600, Bitmap.Config.ARGB_8888).also { panel.draw(Canvas(it)) }
            }
            val baseImage = draw(config)
            try {
                for (alignment in ClusterMusicAlignment.entries) {
                    val image = draw(config.copy(cardCoverAlignment = alignment, cardLyricAlignment = alignment, cardInfoAlignment = alignment))
                    try { java.io.File(folder, "$locale-card-alignment-${alignment.name.lowercase()}.png").outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
                }
                for (changed in listOf(config.copy(cardCoverAlignment = ClusterMusicAlignment.RIGHT),
                    config.copy(cardLyricAlignment = ClusterMusicAlignment.CENTER), config.copy(cardInfoAlignment = ClusterMusicAlignment.RIGHT))) {
                    val image = draw(changed)
                    try { assertFalse(baseImage.sameAs(image)) } finally { image.recycle() }
                }
                val mini = draw(config.copy(musicMode = ClusterMusicMode.MINI))
                val changedMini = draw(config.copy(musicMode = ClusterMusicMode.MINI, cardCoverAlignment = ClusterMusicAlignment.RIGHT,
                    cardLyricAlignment = ClusterMusicAlignment.CENTER, cardInfoAlignment = ClusterMusicAlignment.RIGHT, cardInfoLines = ClusterMusicInfoLines.SLASH))
                try { assertTrue(mini.sameAs(changedMini)) } finally { mini.recycle(); changedMini.recycle() }
            } finally { baseImage.recycle(); art.recycle() }
        }
    }

    @Test fun rendersControlsAndPanelsAtCompactFullAndRtlSizes() {
        for ((locale, width) in listOf("zh-rCN" to 360, "en" to 960, "ar-ldrtl" to 360)) {
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h900dp-mdpi")
            RuntimeEnvironment.setFontScale(1.3f)
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
            val container = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "customClusterControls", ReflectionHelpers.ClassParameter.from(android.widget.LinearLayout::class.java, container))
            container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            container.layout(0, 0, width, container.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, container.height, Bitmap.Config.ARGB_8888)
            container.draw(Canvas(bitmap))
            val folder = java.io.File("build/reports/custom-cluster-ui").apply { mkdirs() }
            java.io.File(folder, "$locale-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue(container.height > 0)
            val art = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val info = if (locale.startsWith("ar")) CarPlayNowPlaying(title = "الأغنية المفضلة في الطريق", artist = "الفنان والألبوم")
                else CarPlayNowPlaying(title = "A long song title for the next journey", artist = "Artist · Album")
            for (night in listOf(false, true)) for (side in ClusterCoverSide.entries) for (mode in ClusterMusicMode.entries)
                for (font in if (mode == ClusterMusicMode.CARD) listOf(100, 180) else listOf(100)) {
                val config = ClusterWindowSettings.load(app).copy(music = true, coverSide = side, musicMode = mode,
                    font = font, titleFont = font, artistFont = font)
                val panel = CustomClusterWindows.MusicPanel(activity, config).apply {
                    this.night = night
                    layoutDirection = if (locale.startsWith("ar")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                val playing = if (mode == ClusterMusicMode.MINI) info else if (locale.startsWith("ar"))
                    CarPlayNowPlaying(title = "الأغنية المفضلة\nفي الطريق الطويل\nنحو رحلة جديدة\nمع الموسيقى", artist = "الفنان", album = "الألبوم المفضل")
                    else CarPlayNowPlaying(title = "First lyric line\nThe road ahead\nAnother verse\nA new journey", artist = "Artist", album = "Journey album")
                ReflectionHelpers.setField(panel, "snapshot", ClusterMusicState.Snapshot(playing, art))
                val height = width * (if (mode == ClusterMusicMode.CARD) 3 else 2) / 5
                panel.layout(0, 0, width, height)
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(image))
                val cover = CustomClusterWindows.musicLayout(width, height, config, true).cover!!
                assertEquals(Color.BLUE, image.getPixel(cover.centerX().toInt(), cover.centerY().toInt()))
                if (mode == ClusterMusicMode.CARD) assertEquals(width / 2f, cover.centerX(), .001f)
                else if (side == ClusterCoverSide.LEFT) assertTrue(cover.centerX() < width / 2f)
                else assertTrue(cover.centerX() > width / 2f)
                val modeSuffix = if (mode == ClusterMusicMode.CARD) "-card-font$font" else ""
                java.io.File(folder, "$locale-$width-${if (night) "night" else "day"}-${side.name.lowercase()}$modeSuffix.png")
                    .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            art.recycle()
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun hostMusicWindowWaitsForConnectionAndDismissesAfterDisconnect() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val connection = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        display(); ClusterWindowSettings.save(app, "display", "bosdisplay0"); ClusterWindowSettings.save(app, "music", true)
        ReflectionHelpers.setField(activity, "controller", connection)
        fun ensure() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "ensureClusterPresentation")
        fun shown(): CustomClusterWindows? = ReflectionHelpers.getField(activity, "customWindows")
        try {
            ensure(); assertNull(shown())
            org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(Any())
            ensure(); assertTrue(shown()!!.isShowing)
            val same = shown()
            ClusterWindowSettings.save(app, "music_x", 80); idle()
            assertSame(same, shown())
            repeat(5) {
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onClusterActivityState",
                    ReflectionHelpers.ClassParameter.from(ClusterActivityState.Snapshot::class.java,
                        ClusterActivityState.Snapshot(null, false)))
                assertSame(same, shown()); assertTrue(same!!.isShowing)
            }
            org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(Any())
            ensure()
            val reconnected = shown()
            assertNotSame(same, reconnected); assertFalse(same!!.isShowing); assertTrue(reconnected!!.isShowing)
            org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(null)
            ensure(); assertNull(shown()); assertFalse(reconnected.isShowing)
        } finally {
            ReflectionHelpers.getField<(() -> Unit)?>(activity, "stopCustomWindowSettings")?.invoke()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "dismissCustomWindows")
        }
    }

    @Test fun safeAreaSlidersMarkReconnectWithoutStoppingAnExistingSession() {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.clear(); PendingReconnect.clear()
        CarPlayBackgroundSession.store(session, org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        try {
            val container = android.widget.LinearLayout(screen)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls",
                ReflectionHelpers.ClassParameter.from(android.widget.LinearLayout::class.java, container))
            fun hasTitle(view: View, title: String): Boolean = when (view) {
                is android.widget.TextView -> view.text.toString() == title
                is android.view.ViewGroup -> (0 until view.childCount).any { hasTitle(view.getChildAt(it), title) }
                else -> false
            }
            for (title in listOf(com.shilapi.xcertplay.host.R.string.settings_cluster_safe_top,
                com.shilapi.xcertplay.host.R.string.settings_cluster_safe_bottom,
                com.shilapi.xcertplay.host.R.string.settings_cluster_safe_left,
                com.shilapi.xcertplay.host.R.string.settings_cluster_safe_right)) {
                PendingReconnect.clear()
                val row = (0 until container.childCount).map(container::getChildAt).first { hasTitle(it, screen.getString(title)) }
                ReflectionHelpers.getField<(Int) -> Unit>(row, "onSave").invoke(17)
                assertTrue(PendingReconnect.isPending(session))
                assertSame(session, CarPlayBackgroundSession.snapshot()!!.controller)
                assertEquals(0, stops)
            }
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(17, 17, 17, 17), ClusterWindowSettings.load(app).safeArea)
        } finally { CarPlayBackgroundSession.clear(); PendingReconnect.clear() }
    }

    @Test fun headUnitThemeAndLiveTrimKeepSurfaceRootTransparencyAndOpacity() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = true, opacity = 60)
        val surfaces = mutableListOf<android.view.Surface?>()
        val window = CustomClusterWindows(activity, display(), false, 1000, 500, config) { surfaces.add(it) }
        val texture = android.graphics.SurfaceTexture(0)
        val previousCallback = HeadUnitNightMode.onChanged
        val callback: (String) -> Unit = {}
        val oldByd = android.provider.Settings.System.getString(app.contentResolver, HeadUnitNightMode.BYD_NIGHT_KEY)
        try {
            HeadUnitNightMode.onChanged = callback
            AirPlayPersistence.saveNightSource(app, HeadUnitNightMode.Source.BYD)
            val nightController: CarPlayNightModeController = ReflectionHelpers.callInstanceMethod(activity, "getNightModeController")
            nightController.configure(CarPlayNightMode.DAY, false)
            window.create()
            ReflectionHelpers.setField(activity, "customWindows", window)
            val video = ReflectionHelpers.getField<android.view.TextureView>(window, "video")
            video.layout(0, 0, 1000, 500)
            video.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 1000, 500)
            val surface = window.outputSurface
            val root = ReflectionHelpers.getField<View>(window, "root")
            val map = ReflectionHelpers.getField<View>(window, "mapFrame")
            val waiting = ReflectionHelpers.getField<android.widget.TextView>(window, "waiting")
            val music = ReflectionHelpers.getField<CustomClusterWindows.MusicPanel>(window, "music")
            for (night in listOf(false, true, false)) {
                android.provider.Settings.System.putString(app.contentResolver, HeadUnitNightMode.BYD_NIGHT_KEY, if (night) "1" else "2")
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshConfiguration",
                    ReflectionHelpers.ClassParameter.from(android.content.res.Configuration::class.java, activity.resources.configuration),
                    ReflectionHelpers.ClassParameter.from(ThemeModeDiagnostics.Source::class.java, ThemeModeDiagnostics.Source.CALLBACK))
                val colors = CustomClusterWindows.panelColors(night, 60)
                assertEquals(night, music.night)
                assertEquals(colors.text, waiting.currentTextColor)
                assertEquals(colors.background, (map.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(153, Color.alpha(colors.background))
                assertEquals(Color.TRANSPARENT, (root.background as android.graphics.drawable.ColorDrawable).color)
                assertFalse(ReflectionHelpers.getField<Boolean>(activity, "darkMode"))
                assertSame(callback, HeadUnitNightMode.onChanged)
            }
            android.provider.Settings.System.putString(app.contentResolver, HeadUnitNightMode.BYD_NIGHT_KEY, "1")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshConfiguration",
                ReflectionHelpers.ClassParameter.from(android.content.res.Configuration::class.java, activity.resources.configuration),
                ReflectionHelpers.ClassParameter.from(ThemeModeDiagnostics.Source::class.java, ThemeModeDiagnostics.Source.CALLBACK))
            assertTrue(music.night)
            android.provider.Settings.System.putString(app.contentResolver, HeadUnitNightMode.BYD_NIGHT_KEY, "unknown")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshConfiguration",
                ReflectionHelpers.ClassParameter.from(android.content.res.Configuration::class.java, activity.resources.configuration),
                ReflectionHelpers.ClassParameter.from(ThemeModeDiagnostics.Source::class.java, ThemeModeDiagnostics.Source.CALLBACK))
            assertTrue("Unknown head-unit mode must retain the last panel theme", music.night)
            window.apply(config.copy(trim = AirPlayInsets(top = 5, left = 10, right = 3), coverSide = ClusterCoverSide.RIGHT))
            val expected = CustomClusterWindows.mapTransform(1000, 500, 1000, 500, false, AirPlayInsets(top = 5, left = 10, right = 3))
            val actualValues = FloatArray(9); val expectedValues = FloatArray(9)
            video.getTransform(android.graphics.Matrix()).getValues(actualValues); expected.matrix.getValues(expectedValues)
            assertArrayEquals(expectedValues, actualValues, .0001f)
            assertEquals(expected.clip, video.clipBounds)
            root.layout(0, 0, 1920, 720)
            val panel = map as CustomClusterWindows.MapPanel
            for (x in listOf(50, 70, 30)) {
                val next = config.copy(mapDisplayMode = ClusterMapDisplayMode.PANEL_CROP, mapCenterX = x, mapCenterY = 45)
                window.apply(next)
                val reference = CustomClusterWindows.panelCropTransform(1000, 500, video.width, video.height,
                    root.width, root.height, x, 45, AirPlayInsets())
                video.getTransform(android.graphics.Matrix()).getValues(actualValues)
                reference.matrix.getValues(expectedValues)
                assertArrayEquals(expectedValues, actualValues, .0001f)
                val bounds = panel.contentBounds!!.invoke(video.width, video.height)
                assertEquals(reference.content, bounds)
                assertEquals(reference.clip, video.clipBounds)
                assertSame(surface, window.outputSurface)
                assertSame(video, ReflectionHelpers.getField<View>(window, "video"))
                assertEquals(1, surfaces.size)
            }
            window.apply(config.copy(safeArea = com.shilapi.xcertplay.airplay.AirPlayInsets(40, 40, 40, 40)))
            assertSame(video, ReflectionHelpers.getField<View>(window, "video"))
            assertSame(surface, window.outputSurface); assertEquals(1, surfaces.size)
        } finally {
            HeadUnitNightMode.onChanged = previousCallback
            android.provider.Settings.System.putString(app.contentResolver, HeadUnitNightMode.BYD_NIGHT_KEY, oldByd)
            ReflectionHelpers.getField<android.view.TextureView?>(window, "video")?.surfaceTextureListener?.onSurfaceTextureDestroyed(texture)
            ReflectionHelpers.setField(activity, "customWindows", null)
            texture.release(); window.dismiss()
        }
    }

    @Test fun rawMapSourceRequiresAVisibleReceivedFrameAndNeverChangesDisplayTransforms() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val config = ClusterWindowSettings.load(app).copy(map = true, trim = AirPlayInsets(left = 40), mapBlend = true)
        val surfaces = mutableListOf<android.view.Surface?>()
        val window = CustomClusterWindows(host.get(), display(), false, 1920, 1080, config) { surfaces.add(it) }
        val texture = android.graphics.SurfaceTexture(0)
        val session = Any()
        try {
            window.show()
            ClusterWindowVisibility.attach(session, window)
            val video = ReflectionHelpers.getField<android.view.TextureView>(window, "video")
            video.layout(0, 0, 400, 300)
            val listener = video.surfaceTextureListener!!
            listener.onSurfaceTextureAvailable(texture, 400, 300)
            window.outputSurface!!.release()
            val validSurface = org.mockito.Mockito.mock(android.view.Surface::class.java)
            org.mockito.Mockito.`when`(validSurface.isValid).thenReturn(true)
            ReflectionHelpers.setField(window, "outputSurface", validSurface)
            window.setStreamActive(true)
            assertNull(window.rawMapSurface())
            listener.onSurfaceTextureUpdated(texture)
            val matrix = video.getTransform(android.graphics.Matrix())
            val clip = video.clipBounds
            assertSame(window.outputSurface, window.rawMapSurface())
            assertSame(window, ClusterWindowVisibility.rawMapWindow(session))
            assertNull(ClusterWindowVisibility.rawMapWindow(Any()))
            window.setPanelVisibility(true, false)
            assertNull(window.rawMapSurface())
            window.setPanelVisibility(true, true)
            assertSame(window.outputSurface, window.rawMapSurface())
            assertEquals(matrix, video.getTransform(android.graphics.Matrix()))
            assertEquals(clip, video.clipBounds)
            assertEquals(1, surfaces.size)
            window.setStreamActive(false); window.setStreamActive(true)
            assertNull(window.rawMapSurface())
            listener.onSurfaceTextureUpdated(texture)
            assertNotNull(window.rawMapSurface())
            listener.onSurfaceTextureDestroyed(texture)
            assertNull(window.rawMapSurface())
        } finally {
            window.dismiss(); texture.release(); host.pause().stop().destroy()
        }
        assertNull(ClusterWindowVisibility.rawMapWindow(session))
    }

    @Test fun mapBlendAppliesToWaitingAndVideoContainerWithoutReplacingSurfaceOrVisibility() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = true, opacity = 100)
        val surfaces = mutableListOf<android.view.Surface?>()
        val window = CustomClusterWindows(host.get(), display(), false, 960, 540, config) { surfaces.add(it) }
        val texture = android.graphics.SurfaceTexture(0)
        try {
            window.create()
            val video = ReflectionHelpers.getField<android.view.TextureView>(window, "video")
            val listener = video.surfaceTextureListener!!
            listener.onSurfaceTextureAvailable(texture, 960, 540)
            val surface = window.outputSurface
            assertNotNull(surface)
            val map = ReflectionHelpers.getField<CustomClusterWindows.MapPanel>(window, "mapFrame")
            val waiting = ReflectionHelpers.getField<android.widget.TextView>(window, "waiting")
            val music = ReflectionHelpers.getField<CustomClusterWindows.MusicPanel>(window, "music")
            val root = ReflectionHelpers.getField<View>(window, "root")
            for (next in listOf(config.copy(mapBlend = true, mapFeatherPercent = 10, mapCornerPercent = 20),
                config.copy(mapBlend = true, mapFeatherPercent = 15, mapCornerPercent = 25),
                config.copy(trimInPlace = true, trim = AirPlayInsets(left = 30, top = 20), musicMode = ClusterMusicMode.CARD),
                config.copy(trimInPlace = true, trim = AirPlayInsets(left = 30, top = 20), mapBlend = true,
                    mapFeatherPercent = 20, musicMode = ClusterMusicMode.CARD), config)) {
                window.setPanelVisibility(true, false)
                window.apply(next)
                assertEquals(0f, map.alpha, 0f)
                window.setPanelVisibility(false, true)
                for (active in listOf(false, true)) {
                    window.setStreamActive(active)
                    val image = drawMapPanel(map)
                    try {
                        assertEquals(if (next.mapBlend || next.trimInPlace) 0 else 255, Color.alpha(image.getPixel(0, 0)))
                        // The 960x540 stream letterboxes inside 300x200, so the fade follows the picture, not the panel.
                        val pictureTop = CustomClusterWindows.mapTransform(960, 540, 300, 200, next.crop, next.trim, next.trimInPlace).content.top
                        assertEquals(if (next.mapBlend || next.trimInPlace) 0 else 255, Color.alpha(image.getPixel(150, (pictureTop - 2).toInt())))
                        val edgeAlpha = Color.alpha(image.getPixel(150, (pictureTop + 10).toInt()))
                        if (next.mapBlend) assertTrue(edgeAlpha in 1..200) else assertEquals(255, edgeAlpha)
                        if (next.trimInPlace) assertEquals(0, Color.alpha(image.getPixel(50, 100)))
                        assertEquals(next.musicMode, music.config.musicMode)
                    } finally { image.recycle() }
                    assertEquals(if (active) View.GONE else View.VISIBLE, waiting.visibility)
                    assertEquals(1f, map.alpha, 0f)
                    assertEquals(0f, music.alpha, 0f)
                    assertSame(map, video.parent)
                    assertSame(map, waiting.parent)
                    assertSame(video, ReflectionHelpers.getField<View>(window, "video"))
                    assertSame(listener, video.surfaceTextureListener)
                    assertSame(surface, window.outputSurface)
                    assertEquals(1, surfaces.size)
                }
            }
            assertNotEquals(View.LAYER_TYPE_SOFTWARE, map.layerType)
            assertNotEquals(View.LAYER_TYPE_SOFTWARE, video.layerType)
            assertFalse(video.isOpaque)
            assertEquals(Color.TRANSPARENT, (root.background as android.graphics.drawable.ColorDrawable).color)
        } finally {
            ReflectionHelpers.getField<android.view.TextureView>(window, "video").surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
            texture.release(); window.dismiss(); host.pause().stop().destroy()
        }
    }

    @Test fun switchingMusicModesUsesSeparateSizesWithoutRecreatingPanelsOrTheMapSurface() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = true,
            musicRect = ClusterWindowRect(20, 70, 30, 20), musicCardRect = ClusterWindowRect(40, 50, 55, 60))
        val surfaces = mutableListOf<android.view.Surface?>()
        val window = CustomClusterWindows(host.get(), display(), false, 960, 540, config) { surfaces.add(it) }
        val texture = android.graphics.SurfaceTexture(0)
        try {
            window.create()
            val video = ReflectionHelpers.getField<android.view.TextureView>(window, "video")
            val music = ReflectionHelpers.getField<CustomClusterWindows.MusicPanel>(window, "music")
            val map = ReflectionHelpers.getField<View>(window, "mapFrame")
            val root = ReflectionHelpers.getField<android.widget.FrameLayout>(window, "root")
            video.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 960, 540)
            val surface = window.outputSurface
            drawMapPanel(root, 1000, 500).recycle()
            window.setPanelVisibility(false, false)
            for (mode in listOf(ClusterMusicMode.MINI, ClusterMusicMode.CARD, ClusterMusicMode.MINI)) {
                val next = config.copy(musicMode = mode, trimInPlace = mode == ClusterMusicMode.CARD, trim = AirPlayInsets(left = 25))
                window.apply(next)
                val expected = next.activeMusicRect.pixels(1000, 500)
                val params = music.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(expected.width(), params.width); assertEquals(expected.height(), params.height)
                assertEquals(expected.left, params.leftMargin); assertEquals(expected.top, params.topMargin)
                assertEquals(config.musicRect, music.config.musicRect)
                assertEquals(config.musicCardRect, music.config.musicCardRect)
                assertEquals(0f, music.alpha, 0f); assertEquals(0f, map.alpha, 0f)
                assertSame(music, ReflectionHelpers.getField<View>(window, "music"))
                assertSame(video, ReflectionHelpers.getField<View>(window, "video"))
                assertSame(surface, window.outputSurface); assertEquals(1, surfaces.size)
            }
        } finally {
            ReflectionHelpers.getField<android.view.TextureView>(window, "video").surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
            texture.release(); window.dismiss(); host.pause().stop().destroy()
        }
    }

    @Test fun hiddenMapRetainsItsSurfaceAndBothPanelsKeepTheirOwnVisibility() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = true)
        val surfaces = mutableListOf<android.view.Surface?>()
        val window = CustomClusterWindows(host.get(), display(), false, 960, 540, config) { surfaces.add(it) }
        val texture = android.graphics.SurfaceTexture(0)
        try {
            window.create()
            val video = ReflectionHelpers.getField<android.view.TextureView>(window, "video")
            video.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 960, 540)
            val surface = window.outputSurface
            assertNotNull(surface)
            val map = ReflectionHelpers.getField<View>(window, "mapFrame")
            val music = ReflectionHelpers.getField<View>(window, "music")
            window.setPanelVisibility(true, false)
            window.setStreamActive(false)
            window.apply(config.copy(crop = true, font = 120))
            window.setStreamActive(true)
            assertEquals(0f, map.alpha, 0f)
            assertEquals(1f, music.alpha, 0f)
            assertEquals(View.VISIBLE, map.visibility)
            assertSame(video, ReflectionHelpers.getField<View>(window, "video"))
            assertSame(surface, window.outputSurface)
            assertEquals(1, surfaces.size)
            window.setPanelVisibility(false, true)
            assertEquals(1f, map.alpha, 0f)
            assertEquals(0f, music.alpha, 0f)
            assertEquals(1, surfaces.size)
        } finally {
            ReflectionHelpers.getField<android.view.TextureView>(window, "video").surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
            texture.release(); window.dismiss(); host.pause().stop().destroy()
        }
    }

    @Test fun visibilityBeforeCreationSurvivesSameSessionRebuildButNotANewConnection() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val target = display()
        ClusterWindowSettings.save(app, "display", "bosdisplay0")
        ClusterWindowSettings.save(app, "music", true)
        ClusterWindowSettings.save(app, "map", true)
        val connection = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val session = Any()
        org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(session)
        org.mockito.Mockito.`when`(connection.configuredClusterSize()).thenReturn(960 to 540)
        val windows = mutableListOf<CustomClusterWindows>()
        fun open(token: Any): CustomClusterWindows = CustomClusterWindows(host.get(), target, false, 960, 540,
            ClusterWindowSettings.load(app)) {}.also { windows.add(it); ClusterWindowVisibility.attach(token, it); it.show(); idle() }
        try {
            assertTrue(ClusterWindowVisibility.setVisible(app, connection, ClusterWindowPanel.MUSIC, false))
            val first = open(session)
            assertFalse(first.musicVisible); assertTrue(first.mapVisible)
            ClusterMusicState.publish(CarPlayNowPlaying(title = "latest hidden lyric"), null); idle()
            assertEquals("latest hidden lyric", ReflectionHelpers.getField<ClusterMusicState.Snapshot>(
                ReflectionHelpers.getField<CustomClusterWindows.MusicPanel>(first, "music"), "snapshot").info.title)
            first.dismiss(); idle()
            val restored = open(session)
            assertFalse(restored.musicVisible)
            ClusterWindowVisibility.detach(first)
            assertTrue(ClusterWindowVisibility.setVisible(app, connection, ClusterWindowPanel.MUSIC, true))
            assertTrue(restored.musicVisible)
            assertTrue(ClusterWindowVisibility.setVisible(app, connection, ClusterWindowPanel.MAP, false))
            assertFalse(restored.mapVisible)
            restored.dismiss()
            val fresh = open(Any())
            assertTrue(fresh.musicVisible); assertTrue(fresh.mapVisible)
        } finally { windows.forEach { it.dismiss() }; host.pause().stop().destroy() }
    }

    @Test fun hostReportsHiddenMapAsInvisibleWithoutRebuildingOrDetachingSurface() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val connection = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        display(); ClusterWindowSettings.save(app, "display", "bosdisplay0")
        ClusterWindowSettings.save(app, "music", true); ClusterWindowSettings.save(app, "map", true)
        org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(Any())
        org.mockito.Mockito.`when`(connection.configuredClusterSize()).thenReturn(960 to 540)
        ReflectionHelpers.setField(host, "controller", connection)
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        org.mockito.Mockito.`when`(surface.isValid).thenReturn(true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(host, "ensureClusterPresentation")
            val window = ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows")
            ReflectionHelpers.setField(window, "outputSurface", surface)
            ReflectionHelpers.setField(host, "clusterSurface", surface)
            ReflectionHelpers.callInstanceMethod<Unit>(host, "updateClusterMapShown")
            org.mockito.Mockito.verify(connection).setDashboardMapOutputVisible(true)
            org.mockito.Mockito.clearInvocations(connection)
            assertTrue(ClusterWindowVisibility.setVisible(app, connection, ClusterWindowPanel.MAP, false))
            org.mockito.Mockito.verify(connection).setDashboardMapOutputVisible(false)
            assertTrue(window.musicVisible)
            assertSame(surface, window.outputSurface)
            assertSame(surface, ReflectionHelpers.getField<android.view.Surface>(host, "clusterSurface"))
            ClusterWindowSettings.save(app, "music_x", 80); idle()
            val music = ReflectionHelpers.getField<CustomClusterWindows.MusicPanel>(window, "music")
            for (mode in listOf(ClusterMusicMode.CARD, ClusterMusicMode.MINI)) {
                ClusterWindowSettings.save(app, "music_mode", mode.name)
                ClusterWindowSettings.save(app, "music_card_width", 65)
                ClusterWindowSettings.save(app, "trim_in_place", mode == ClusterMusicMode.CARD)
                ClusterWindowSettings.save(app, "trim_left", 25)
                idle()
                assertSame(window, ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows"))
                assertEquals(mode, music.config.musicMode)
                assertEquals(mode == ClusterMusicMode.CARD, music.config.trimInPlace)
                assertEquals(65, music.config.musicCardRect.width)
                assertEquals(80, music.config.musicRect.x)
                assertFalse(window.mapVisible); assertTrue(window.musicVisible)
                assertSame(surface, window.outputSurface)
                assertSame(surface, ReflectionHelpers.getField<android.view.Surface>(host, "clusterSurface"))
            }
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            ReflectionHelpers.getField<(() -> Unit)?>(host, "stopCustomWindowSettings")?.invoke()
            ReflectionHelpers.callInstanceMethod<Unit>(host, "dismissCustomWindows")
            surface.release()
        }
    }

    @Test @Config(sdk = [34])
    fun elevatedWindowUsesMatchingOverlayContextAndAttachedTypeOnTargetDisplay() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val target = display()
        val config = ClusterWindowSettings.load(app).copy(music = true, elevated = true)
        val window = CustomClusterWindows(app, target, true, 960, 540, config) {}
        try {
            val provider = generateSequence(window.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .first { it.javaClass.name == "android.window.WindowContext" }
            assertEquals(2038, ReflectionHelpers.callInstanceMethod<Int>(provider, "getWindowType"))
            assertEquals(target.displayId, provider.display.displayId)
            window.show(); idle()
            assertTrue(window.isShowing)
            assertEquals("overlay_dialog", window.backend)
            assertEquals(2038, window.window!!.attributes.type)
            assertEquals(2038, (window.window!!.decorView.layoutParams as android.view.WindowManager.LayoutParams).type)
            assertTrue(ClusterMusicState.diagnosticEvents().any {
                it.contains("custom windows shown backend=overlay_dialog requested=2038 contextType=2038 actual=2038 display=${target.displayId}")
            })
            window.dismiss("test_cleanup")
            assertFalse(window.isShowing)
            assertFalse(ReflectionHelpers.getField<Boolean>(window, "observingDisplay"))
        } finally { window.dismiss() }
    }

    @Test @Config(sdk = [34])
    fun normalAndPrivatePresentationsKeepTheirOriginalActualTypes() {
        val target = display()
        val config = ClusterWindowSettings.load(app).copy(music = true)
        for ((flags, expected) in listOf(Display.FLAG_PRESENTATION to 2037,
            (Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE) to 2030)) {
            shadowOf(target).setFlags(flags)
            val window = CustomClusterWindows(app, target, false, 960, 540, config) {}
            try {
                window.create()
                assertTrue(ReflectionHelpers.getField<android.app.Dialog>(window, "host") is android.app.Presentation)
                assertEquals("presentation", window.backend)
                assertEquals(expected, window.window!!.attributes.type)
                assertEquals(expected, window.contextType)
            } finally { window.dismiss() }
        }
    }

    @Test fun repeatedLegacyMonitorUpdatesKeepCustomPanelsAndSurfaceAliveWhenOldMapIsOff() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val connection = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        display(); ClusterWindowSettings.save(app, "display", "bosdisplay0")
        ClusterWindowSettings.save(app, "music", true); ClusterWindowSettings.save(app, "map", true)
        AirPlayPersistence.saveClusterMapEnabled(app, false)
        org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(Any())
        org.mockito.Mockito.`when`(connection.configuredClusterSize()).thenReturn(960 to 540)
        ReflectionHelpers.setField(host, "controller", connection)
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        org.mockito.Mockito.`when`(surface.isValid).thenReturn(true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(host, "ensureClusterPresentation")
            val window = ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows")
            ReflectionHelpers.setField(window, "outputSurface", surface)
            ReflectionHelpers.setField(host, "clusterSurface", surface)
            assertTrue(ClusterWindowVisibility.setVisible(app, connection, ClusterWindowPanel.MAP, false))
            repeat(12) { i ->
                val state = ClusterActivityState.Snapshot(
                    if (i % 2 == 0) DiLink51ClusterLayout.Theme.MAP else DiLink51ClusterLayout.Theme.SIMPLE,
                    mapVisible = i % 3 == 0, smallWindow = i % 2 == 0)
                ReflectionHelpers.callInstanceMethod<Unit>(host, "onClusterActivityState",
                    ReflectionHelpers.ClassParameter.from(ClusterActivityState.Snapshot::class.java, state))
                assertSame(window, ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows"))
                assertTrue(window.isShowing); assertTrue(window.musicVisible); assertFalse(window.mapVisible)
                assertSame(surface, window.outputSurface)
                assertSame(surface, ReflectionHelpers.getField<android.view.Surface>(host, "clusterSurface"))
            }
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
            ClusterWindowSettings.disable(app); idle()
            assertNull(ReflectionHelpers.getField<CustomClusterWindows?>(host, "customWindows"))
            assertFalse(window.isShowing)
            assertNull(window.outputSurface)
            org.mockito.Mockito.verify(surface).release()
            assertTrue(ClusterMusicState.diagnosticEvents().any { it.contains("reason=settings_disabled") })
        } finally {
            ReflectionHelpers.getField<(() -> Unit)?>(host, "stopCustomWindowSettings")?.invoke()
            ReflectionHelpers.callInstanceMethod<Unit>(host, "dismissCustomWindows")
        }
    }

    @Test @Config(sdk = [34])
    fun overlayConstructorFailureFallsBackOnceAndReusesOrdinaryPresentation() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val target = display()
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val host = org.mockito.Mockito.spy(activity)
        val displayContext = org.mockito.Mockito.spy(activity.createDisplayContext(target))
        org.mockito.Mockito.doThrow(IllegalArgumentException("Window type mismatch private-token"))
            .`when`(displayContext).createWindowContext(org.mockito.ArgumentMatchers.eq(2038),
                org.mockito.ArgumentMatchers.isNull<android.os.Bundle>())
        org.mockito.Mockito.doReturn(displayContext).`when`(host).createDisplayContext(org.mockito.ArgumentMatchers.any(Display::class.java))
        val connection = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        org.mockito.Mockito.`when`(connection.mediaAttachmentToken()).thenReturn(Any())
        ReflectionHelpers.setField(host, "controller", connection)
        ClusterWindowSettings.save(app, "music", true); ClusterWindowSettings.save(app, "display", "bosdisplay0")
        ClusterWindowSettings.save(app, "elevated", true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(host, "ensureClusterPresentation")
            val window = ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows")
            assertTrue(window.isShowing); assertFalse(window.elevated)
            assertEquals(2038, window.requestedType)
            assertEquals(2037, window.window!!.attributes.type)
            repeat(5) { ReflectionHelpers.callInstanceMethod<Unit>(host, "ensureClusterPresentation") }
            assertSame(window, ReflectionHelpers.getField<CustomClusterWindows>(host, "customWindows"))
            org.mockito.Mockito.verify(displayContext, org.mockito.Mockito.times(1)).createWindowContext(
                org.mockito.ArgumentMatchers.eq(2038), org.mockito.ArgumentMatchers.isNull<android.os.Bundle>())
            val events = ClusterMusicState.diagnosticEvents()
            assertTrue(events.any { it.contains("custom window failed backend=overlay_dialog") && it.contains("phase=construct reason=window_type_mismatch") })
            assertTrue(events.any { it.contains("custom window fallback backend=presentation") && it.contains("reason=overlay_failed") })
            assertTrue(events.any { it.contains("backend=presentation requested=2038 contextType=2037 actual=2037") })
            assertFalse(events.any { it.contains("private-token") })
        } finally {
            ReflectionHelpers.getField<(() -> Unit)?>(host, "stopCustomWindowSettings")?.invoke()
            ReflectionHelpers.callInstanceMethod<Unit>(host, "dismissCustomWindows")
        }
    }

    @Test fun failedAddViewDoesNotReportShownAndReleasesSurfaceAndDisplayObserver() {
        val config = ClusterWindowSettings.load(app).copy(music = true, elevated = true)
        val window = CustomClusterWindows(app, display(), true, 960, 540, config) {}
        val dialog = ReflectionHelpers.getField<android.app.Dialog>(window, "host")
        val manager = org.mockito.Mockito.mock(android.view.WindowManager::class.java)
        org.mockito.Mockito.doThrow(android.view.WindowManager.BadTokenException("private-token"))
            .`when`(manager).addView(org.mockito.ArgumentMatchers.any(View::class.java),
                org.mockito.ArgumentMatchers.any(android.view.ViewGroup.LayoutParams::class.java))
        ReflectionHelpers.setField(dialog, "mWindowManager", manager)
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        ReflectionHelpers.setField(window, "outputSurface", surface)
        val before = ClusterMusicState.diagnosticEvents().count { it.contains("custom windows shown") }
        try {
            try { window.show(); fail("addView must fail") } catch (_: android.view.WindowManager.BadTokenException) { }
            assertFalse(window.isShowing)
            assertNull(window.outputSurface)
            assertFalse(ReflectionHelpers.getField<Boolean>(window, "observingDisplay"))
            assertEquals(before, ClusterMusicState.diagnosticEvents().count { it.contains("custom windows shown") })
            window.dismiss(); window.dismiss()
            org.mockito.Mockito.verify(surface).release()
        } finally { window.dismiss() }
    }

    @Test fun removingOverlayDisplayDismissesItAndClearsSessionVisibilityAttachment() {
        val target = display()
        val config = ClusterWindowSettings.load(app).copy(music = true, elevated = true)
        val window = CustomClusterWindows(app, target, true, 960, 540, config) {}
        ClusterWindowVisibility.attach(Any(), window)
        try {
            window.show(); idle()
            ShadowDisplayManager.removeDisplay(target.displayId); ids.remove(target.displayId); idle()
            assertFalse(window.isShowing)
            assertFalse(ReflectionHelpers.getField<Boolean>(window, "observingDisplay"))
            assertTrue(ClusterMusicState.diagnosticEvents().any { it.contains("reason=display_removed") })
        } finally { window.dismiss() }
    }

    @Test fun diagnosticsAreBoundedAndDoNotIncludeExceptionMessages() {
        val error = IllegalArgumentException("Window type mismatch private-token /private/path")
        error.stackTrace = Array(100) { StackTraceElement("android.view.WindowManagerImpl", "addView", "WindowManagerImpl.java", it) }
        val summary = CustomClusterWindows.failureSummary(error)
        assertTrue(summary.contains("reason=window_type_mismatch"))
        assertTrue(summary.contains("IllegalArgumentException"))
        assertTrue(summary.contains("android.view.WindowManagerImpl.addView:0"))
        assertFalse(summary.contains("private-token")); assertFalse(summary.contains("/private/path"))
        assertTrue(summary.length <= 1500)
        repeat(100) { ClusterMusicState.note("custom window test diagnostic=$it $summary") }
        assertTrue(ClusterMusicState.diagnosticEvents().size <= 80)
    }

    @Test fun noGuessedDisplayAndMapWaitingCannotHideMusic() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val target = display()
        val config = ClusterWindowSettings.load(app).copy(music = true, map = true, displayName = "missing")
        assertNull(CustomClusterWindows.findDisplay(app, config))
        val window = CustomClusterWindows(host.get(), target, false, 960, 540, config) {}
        try {
            window.create()
            window.setStreamActive(false)
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(window, "music").visibility)
            window.setStreamActive(true)
            assertEquals(View.GONE, ReflectionHelpers.getField<View>(window, "waiting").visibility)
        } finally { window.dismiss(); host.pause().stop().destroy() }
    }
}

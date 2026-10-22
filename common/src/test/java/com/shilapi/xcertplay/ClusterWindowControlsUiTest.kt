package com.shilapi.xcertplay

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterWindowControlsUiTest {
    private fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }

    @Test fun controlsStayInSharedMusicAndMapGroupsAcrossLayouts() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear(); PendingReconnect.clear()
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val headings = listOf(R.string.settings_cluster_group_common, R.string.settings_cluster_group_music, R.string.settings_cluster_group_map)
        try {
            for (musicMode in ClusterMusicMode.entries) for (mapMode in ClusterMapDisplayMode.entries)
                for (mapEnabled in listOf(false, true)) for (turnCard in listOf(false, true)) {
                ClusterWindowSettings.save(app, "music_mode", musicMode.name)
                ClusterWindowSettings.save(app, "map_display_mode", mapMode.name)
                ClusterWindowSettings.save(app, "map", mapEnabled)
                ClusterWindowSettings.save(app, "content", if (turnCard) "TURN_CARD" else "MAP")
                val body = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls",
                    ReflectionHelpers.ClassParameter(LinearLayout::class.java, body))
                val children = (0 until body.childCount).map(body::getChildAt)
                val starts = headings.map { resource ->
                    children.indexOf(children.filterIsInstance<TextView>().single { it.text == screen.getString(resource) })
                }
                assertTrue("Groups must be shared, music, then map", starts.zipWithNext().all { (a, b) -> a < b })
                val groups = starts.mapIndexed { index, start -> children.subList(start + 1, starts.getOrElse(index + 1) { children.size }) }
                fun matches(row: View, title: String) = views(row).filterIsInstance<TextView>().any {
                    it.text == title || it.text.startsWith("$title · ") || it.contentDescription == title
                }
                fun members(group: Int, resources: List<Int>) {
                    val positions = resources.map { resource ->
                        val title = screen.getString(resource)
                        val row = children.single { matches(it, title) }
                        assertSame("Controls must remain direct card children", body, row.parent)
                        assertTrue("$title must stay in group ${headings[group]}", row in groups[group])
                        groups[group].indexOf(row)
                    }
                    assertTrue("Members must retain their group order", positions.zipWithNext().all { (a, b) -> a < b })
                }
                members(0, listOf(R.string.cluster_display, R.string.settings_cluster_window_elevated,
                    R.string.settings_cluster_windows_stop, R.string.settings_cluster_window_drag))
                val preview = views(body).filterIsInstance<ClusterWindowLayoutView>().single()
                assertSame(body, preview.parent)
                assertSame("Only the shared group owns the preview", preview, groups[0].last())
                members(1, buildList {
                    add(R.string.settings_cluster_window_music); add(R.string.settings_cluster_music_layout)
                    add(R.string.settings_cluster_window_cover)
                    if (musicMode == ClusterMusicMode.MINI) add(R.string.settings_cluster_cover_side)
                    add(R.string.settings_cluster_window_text)
                    if (musicMode == ClusterMusicMode.CARD) {
                        add(R.string.settings_cluster_card_cover_size)
                        add(R.string.settings_cluster_card_cover_alignment)
                        add(R.string.settings_cluster_card_lyric_alignment)
                        add(R.string.settings_cluster_card_info_alignment)
                        add(R.string.settings_cluster_card_info_lines)
                    }
                    add(R.string.settings_cluster_title_font); add(R.string.settings_cluster_artist_font)
                    add(R.string.settings_cluster_window_opacity); add(R.string.settings_cluster_night_background)
                    add(R.string.settings_cluster_window_alpha_hint)
                })
                members(2, buildList {
                    add(R.string.settings_cluster_window_map); add(R.string.settings_cluster_canvas)
                    add(R.string.settings_cluster_display_mode)
                    if (mapMode == ClusterMapDisplayMode.PANEL_CROP) {
                        add(R.string.settings_cluster_viewport_x); add(R.string.settings_cluster_viewport_y)
                    }
                    add(R.string.settings_cluster_safe_area)
                    add(R.string.settings_cluster_safe_top); add(R.string.settings_cluster_safe_bottom)
                    add(R.string.settings_cluster_safe_left); add(R.string.settings_cluster_safe_right)
                    add(R.string.settings_cluster_safe_reset)
                    if (mapMode == ClusterMapDisplayMode.FIT_WINDOW) add(R.string.settings_cluster_window_crop)
                    add(R.string.settings_cluster_trim)
                    if (mapMode == ClusterMapDisplayMode.FIT_WINDOW) add(R.string.settings_cluster_trim_in_place)
                    add(R.string.settings_cluster_trim_top); add(R.string.settings_cluster_trim_bottom)
                    add(R.string.settings_cluster_trim_left); add(R.string.settings_cluster_trim_right)
                    add(R.string.settings_cluster_trim_reset); add(R.string.settings_cluster_map_blend)
                    add(R.string.settings_cluster_map_feather); add(R.string.settings_cluster_map_corner)
                    add(R.string.dashboard_shows)
                    if (mapEnabled && !turnCard) add(R.string.settings_cluster_map_auto_zoom)
                    add(R.string.settings_cluster_window_camera_limit)
                })
                for (title in listOf("X", "Y", screen.getString(R.string.settings_cluster_window_width), screen.getString(R.string.settings_cluster_window_height))) {
                    assertFalse(groups[0].any { matches(it, title) })
                    assertEquals(1, groups[1].count { matches(it, title) })
                    assertEquals(1, groups[2].count { matches(it, title) })
                }
                for ((resource, present) in listOf(
                    R.string.settings_cluster_cover_side to (musicMode == ClusterMusicMode.MINI),
                    R.string.settings_cluster_card_cover_size to (musicMode == ClusterMusicMode.CARD),
                    R.string.settings_cluster_card_cover_alignment to (musicMode == ClusterMusicMode.CARD),
                    R.string.settings_cluster_card_lyric_alignment to (musicMode == ClusterMusicMode.CARD),
                    R.string.settings_cluster_card_info_alignment to (musicMode == ClusterMusicMode.CARD),
                    R.string.settings_cluster_card_info_lines to (musicMode == ClusterMusicMode.CARD),
                    R.string.settings_cluster_window_crop to (mapMode == ClusterMapDisplayMode.FIT_WINDOW),
                    R.string.settings_cluster_trim_in_place to (mapMode == ClusterMapDisplayMode.FIT_WINDOW),
                    R.string.settings_cluster_viewport_x to (mapMode == ClusterMapDisplayMode.PANEL_CROP),
                    R.string.settings_cluster_viewport_y to (mapMode == ClusterMapDisplayMode.PANEL_CROP),
                    R.string.settings_cluster_map_auto_zoom to (mapEnabled && !turnCard),
                )) assertEquals(present, children.any { matches(it, screen.getString(resource)) })
            }
        } finally {
            CarPlayBackgroundSession.clear(); PendingReconnect.clear()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-xhdpi")
    fun compactLargeFont() = capture("compact-zh")

    @Test @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun fullLargeFont() = capture("full-en")

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h600dp-xhdpi")
    fun rtlLargeFont() = capture("compact-ar")

    private fun capture(name: String) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        CarPlayBackgroundSession.clear()
        RuntimeEnvironment.setFontScale(1.6f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val activity = controller.get()
        val displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        try {
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
            shadowOf(display).setName("bosdisplay2")
            ClusterWindowSettings.save(activity, "map", true)
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(DiPlayPalette.of(activity.currentAppNight).background)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "customClusterControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, body))
            val title = activity.getString(R.string.settings_cluster_map_auto_zoom)
            val zoom = views(body).filterIsInstance<Button>().single { it.text.startsWith(title) }
            zoom.performClick()
            val choice = ShadowAlertDialog.getLatestAlertDialog()
            choice.listView.performItemClick(choice.listView.getChildAt(2), 2, choice.listView.adapter.getItemId(2))
            choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, ClusterWindowSettings.load(activity).autoZoomSteps)
            assertFalse(AirPlayPersistence.loadClusterMapEnabled(activity))
            val scroll = ScrollView(activity).apply {
                layoutDirection = activity.resources.configuration.layoutDirection
                addView(body)
            }
            layout(scroll, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            val bounds = android.graphics.Rect()
            for ((resource, suffix) in listOf(R.string.settings_cluster_group_common to "shared-controls",
                R.string.settings_cluster_group_music to "music-group", R.string.settings_cluster_group_map to "map-group")) {
                val heading = views(body).filterIsInstance<TextView>().single { it.text == activity.getString(resource) }
                heading.getDrawingRect(bounds); body.offsetDescendantRectToMyCoords(heading, bounds)
                scroll.scrollTo(0, bounds.top)
                snapshot(scroll, "$name-$suffix")
            }
            zoom.getDrawingRect(bounds)
            body.offsetDescendantRectToMyCoords(zoom, bounds)
            scroll.scrollTo(0, bounds.top)
            assertTrue("Zoom row must be in the captured viewport", bounds.top >= scroll.scrollY && bounds.top < scroll.scrollY + scroll.height)
            snapshot(scroll, "$name-map-window")
            val canvasRow = views(body).filterIsInstance<Button>().single { it.text.startsWith(activity.getString(R.string.settings_cluster_canvas)) }
            canvasRow.getDrawingRect(bounds)
            body.offsetDescendantRectToMyCoords(canvasRow, bounds)
            scroll.scrollTo(0, bounds.top)
            assertTrue(bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + scroll.height)
            snapshot(scroll, "$name-canvas")
            canvasRow.performClick()
            val canvasDialog = ShadowAlertDialog.getLatestAlertDialog()
            layout(canvasDialog.window!!.decorView, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            assertEquals(ClusterMapCanvas.entries.size, canvasDialog.listView.adapter.count)
            snapshot(canvasDialog.window!!.decorView, "$name-canvas-choice")
            canvasDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(ClusterMapCanvas.WINDOW, ClusterWindowSettings.load(activity).mapCanvas)
            for ((resource, suffix) in listOf(R.string.settings_cluster_music_layout to "music-layout",
                R.string.settings_cluster_trim_modes_hint to "crop-modes")) {
                val row = views(body).filterIsInstance<TextView>().first { it.text.startsWith(activity.getString(resource)) }
                row.getDrawingRect(bounds); body.offsetDescendantRectToMyCoords(row, bounds)
                scroll.scrollTo(0, bounds.top)
                snapshot(scroll, "$name-$suffix")
            }
            ClusterWindowSettings.save(activity, "map_canvas", "PANEL_125")
            ClusterWindowSettings.save(activity, "map_display_mode", "PANEL_CROP")
            val panelBody = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(DiPlayPalette.of(activity.currentAppNight).background)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "customClusterControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, panelBody))
            val panelScroll = ScrollView(activity).apply { addView(panelBody) }
            layout(panelScroll, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            for ((resource, suffix) in listOf(R.string.settings_cluster_display_mode to "panel-mode",
                R.string.settings_cluster_viewport_x to "viewport-centre")) {
                val row = views(panelBody).filterIsInstance<TextView>().first { it.text.startsWith(activity.getString(resource)) }
                row.getDrawingRect(bounds); panelBody.offsetDescendantRectToMyCoords(row, bounds)
                panelScroll.scrollTo(0, bounds.top)
                snapshot(panelScroll, "$name-$suffix")
            }
            assertFalse(views(panelBody).filterIsInstance<android.widget.Switch>().any {
                it.contentDescription == activity.getString(R.string.settings_cluster_trim_in_place) ||
                    it.contentDescription == activity.getString(R.string.settings_cluster_window_crop)
            })
            ClusterWindowSettings.save(activity, "content", "TURN_CARD")
            val cards = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "customClusterControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, cards))
            assertFalse(views(cards).filterIsInstance<Button>().any { it.text.startsWith(title) })

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showProbeControls",
                ReflectionHelpers.ClassParameter(Display::class.java, display))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(Looper.getMainLooper()).idle()
            val decor = dialog.window!!.decorView
            val confirmation = views(decor).filterIsInstance<CheckBox>().single()
            confirmation.isChecked = true
            val backend = decor.findViewWithTag<Button>("cluster_probe_backend")
            backend.performClick()
            assertTrue(backend.text.endsWith("Overlay"))
            assertFalse(confirmation.isChecked)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            layout(decor, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            snapshot(decor, "$name-window-probe")
            dialog.dismiss()
            ShortcutEditor.show(activity, ShortcutRule(name = "Pinch", steps = listOf(ShortcutStep(ShortcutAction.PINCH_IN))))
            shadowOf(Looper.getMainLooper()).idle()
            val editor = ShadowAlertDialog.getLatestAlertDialog()
            fun click(tag: String) {
                editor.window!!.decorView.findViewWithTag<View>(tag).performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            click("shortcut_step_0"); click("shortcut_change_action"); click("shortcut_action_PINCH_IN")
            val editorDecor = editor.window!!.decorView
            layout(editorDecor, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            snapshot(editorDecor, "$name-pinch-centre")
            assertNotNull(editorDecor.findViewWithTag<View>("shortcut_pinch_x"))
            assertNotNull(editorDecor.findViewWithTag<View>("shortcut_pinch_y"))
            assertNotNull(editorDecor.findViewWithTag<View>("shortcut_pinch_scale"))
            editor.dismiss()
        } finally {
            ClusterTransparencyProbe.stop()
            ShadowDisplayManager.removeDisplay(displayId)
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun canvasSaveNeedsConsentToReconnectWhileFixedWindowResizeAndCropStayLive() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.clear(); PendingReconnect.clear()
        CarPlayBackgroundSession.store(session, org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        fun controls() = LinearLayout(screen).also {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, it))
        }
        fun slider(body: LinearLayout, title: Int, value: Int) {
            val row = (0 until body.childCount).map(body::getChildAt).last { child ->
                views(child).filterIsInstance<TextView>().any { it.text == screen.getString(title) }
            }
            ReflectionHelpers.getField<(Int) -> Unit>(row, "onSave").invoke(value)
        }
        try {
            var body = controls()
            slider(body, R.string.settings_cluster_window_width, 50)
            assertTrue(PendingReconnect.isPending(session))
            PendingReconnect.clear()
            val row = views(body).filterIsInstance<Button>().single { it.text.startsWith(screen.getString(R.string.settings_cluster_canvas)) }
            row.performClick()
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.setItemChecked(ClusterMapCanvas.FULL_HD.ordinal, true)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ClusterMapCanvas.WINDOW, ClusterWindowSettings.load(app).mapCanvas)
            assertFalse(PendingReconnect.isPending(session))
            row.performClick()
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.setItemChecked(ClusterMapCanvas.FULL_HD.ordinal, true)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ClusterMapCanvas.FULL_HD, ClusterWindowSettings.load(app).mapCanvas)
            assertTrue(PendingReconnect.isPending(session))
            PendingReconnect.clear()
            body = controls()
            slider(body, R.string.settings_cluster_window_width, 30)
            slider(body, R.string.settings_cluster_window_height, 70)
            for (title in listOf(R.string.settings_cluster_trim_top, R.string.settings_cluster_trim_bottom,
                R.string.settings_cluster_trim_left, R.string.settings_cluster_trim_right)) slider(body, title, 40)
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(40, 40, 40, 40), ClusterWindowSettings.load(app).trim)
            views(body).filterIsInstance<Button>().single { it.text == screen.getString(R.string.settings_cluster_trim_reset) }.performClick()
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(), ClusterWindowSettings.load(app).trim)
            assertFalse(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertSame(session, CarPlayBackgroundSession.snapshot()!!.controller)
        } finally {
            CarPlayBackgroundSession.clear(); PendingReconnect.clear()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    @Test fun musicModesKeepTheirOwnSliderValuesAndInPlaceCropDoesNotReconnect() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        CarPlayBackgroundSession.clear(); PendingReconnect.clear()
        CarPlayBackgroundSession.store(session, org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { fail("Must not stop session") }
        fun controls() = LinearLayout(screen).also {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, it))
        }
        fun choose(body: LinearLayout, mode: ClusterMusicMode) {
            views(body).filterIsInstance<Button>().single { it.text.startsWith(screen.getString(R.string.settings_cluster_music_layout)) }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(dialog.listView.getChildAt(mode.ordinal), mode.ordinal, mode.ordinal.toLong())
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun width(body: LinearLayout, value: Int) {
            val row = (0 until body.childCount).map(body::getChildAt).first { child ->
                views(child).filterIsInstance<TextView>().any { it.text == screen.getString(R.string.settings_cluster_window_width) }
            }
            ReflectionHelpers.getField<(Int) -> Unit>(row, "onSave").invoke(value)
        }
        fun size(body: LinearLayout, title: Int, value: Int) {
            val row = (0 until body.childCount).map(body::getChildAt).single { child ->
                views(child).filterIsInstance<TextView>().any { it.text == screen.getString(title) }
            }
            ReflectionHelpers.getField<(Int) -> Unit>(row, "onSave").invoke(value)
        }
        try {
            var body = controls()
            width(body, 27)
            choose(body, ClusterMusicMode.CARD)
            body = controls(); width(body, 55)
            assertFalse(views(body).filterIsInstance<Button>().any { it.text.startsWith(screen.getString(R.string.settings_cluster_cover_side)) })
            size(body, R.string.settings_cluster_card_cover_size, 70)
            size(body, R.string.settings_cluster_title_font, 48)
            size(body, R.string.settings_cluster_artist_font, 36)
            val card = ClusterWindowSettings.load(app)
            assertEquals(70, card.musicCardCoverSize)
            assertEquals(48, card.titleFont); assertEquals(36, card.artistFont)
            assertEquals(27, card.musicRect.width); assertEquals(55, card.activeMusicRect.width)
            views(body).filterIsInstance<android.widget.Switch>().single {
                it.contentDescription == screen.getString(R.string.settings_cluster_trim_in_place)
            }.performClick()
            assertTrue(ClusterWindowSettings.load(app).trimInPlace)
            choose(body, ClusterMusicMode.MINI)
            val mini = ClusterWindowSettings.load(app)
            assertEquals(27, mini.activeMusicRect.width); assertEquals(55, mini.musicCardRect.width)
            assertEquals(70, mini.musicCardCoverSize)
            assertEquals(48, mini.titleFont); assertEquals(36, mini.artistFont)
            assertFalse(views(controls()).filterIsInstance<TextView>().any { it.text == screen.getString(R.string.settings_cluster_card_cover_size) })
            assertFalse(PendingReconnect.isPending(session))
        } finally {
            CarPlayBackgroundSession.clear(); PendingReconnect.clear()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    @Test fun cardAlignmentChoicesSaveSeparatelyAndCancelOrMiniDoNotChangeThem() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterWindowSettings.save(app, "music_mode", "CARD")
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        fun controls() = LinearLayout(screen).also {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls", ReflectionHelpers.ClassParameter(LinearLayout::class.java, it))
        }
        fun choose(title: Int, index: Int?) {
            views(controls()).filterIsInstance<Button>().single { it.text.startsWith(screen.getString(title) + " · ") }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            if (index == null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            else dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            val base = ClusterWindowSettings.load(app)
            choose(R.string.settings_cluster_card_cover_alignment, null)
            assertEquals(base, ClusterWindowSettings.load(app))
            choose(R.string.settings_cluster_card_cover_alignment, ClusterMusicAlignment.RIGHT.ordinal)
            choose(R.string.settings_cluster_card_lyric_alignment, ClusterMusicAlignment.CENTER.ordinal)
            choose(R.string.settings_cluster_card_info_alignment, ClusterMusicAlignment.LEFT.ordinal)
            choose(R.string.settings_cluster_card_info_lines, ClusterMusicInfoLines.DASH.ordinal)
            val saved = ClusterWindowSettings.load(app)
            assertEquals(base.copy(cardCoverAlignment = ClusterMusicAlignment.RIGHT, cardLyricAlignment = ClusterMusicAlignment.CENTER,
                cardInfoLines = ClusterMusicInfoLines.DASH), saved)
            ClusterWindowSettings.save(app, "music_mode", "MINI")
            for (resource in listOf(R.string.settings_cluster_card_cover_alignment, R.string.settings_cluster_card_lyric_alignment,
                R.string.settings_cluster_card_info_alignment, R.string.settings_cluster_card_info_lines)) {
                assertFalse(views(controls()).filterIsInstance<Button>().any { it.text.startsWith(screen.getString(resource) + " · ") })
            }
            ClusterWindowSettings.save(app, "music_mode", "CARD")
            assertEquals(saved, ClusterWindowSettings.load(app))
        } finally { app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit() }
    }

    @Test fun viewportModeSavesWithConsentAndAutomaticOrFallbackCanvasNeedsReconnect() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.clear(); PendingReconnect.clear()
        CarPlayBackgroundSession.store(session, org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        fun controls() = LinearLayout(screen).also {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "customClusterControls", ReflectionHelpers.ClassParameter(LinearLayout::class.java, it))
        }
        fun choose(body: View, mode: ClusterMapDisplayMode, save: Boolean) {
            views(body).filterIsInstance<Button>().single { it.text.startsWith(screen.getString(R.string.settings_cluster_display_mode)) }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.setItemChecked(mode.ordinal, true)
            dialog.getButton(if (save) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun slider(body: LinearLayout, title: Int, value: Int) {
            val row = (0 until body.childCount).map(body::getChildAt).last { child ->
                views(child).filterIsInstance<TextView>().any { it.text == screen.getString(title) }
            }
            ReflectionHelpers.getField<(Int) -> Unit>(row, "onSave").invoke(value)
        }
        try {
            var body = controls()
            choose(body, ClusterMapDisplayMode.PANEL_CROP, false)
            assertEquals(ClusterMapDisplayMode.FIT_WINDOW, ClusterWindowSettings.load(app).mapDisplayMode)
            assertFalse(PendingReconnect.isPending(session))
            choose(body, ClusterMapDisplayMode.PANEL_CROP, true)
            assertTrue(PendingReconnect.isPending(session)); PendingReconnect.clear()
            body = controls()
            slider(body, R.string.settings_cluster_viewport_x, 65)
            slider(body, R.string.settings_cluster_viewport_y, 40)
            slider(body, R.string.settings_cluster_window_width, 35)
            assertFalse(PendingReconnect.isPending(session))
            assertEquals(65, ClusterWindowSettings.load(app).mapCenterX)
            assertEquals(40, ClusterWindowSettings.load(app).mapCenterY)
            ClusterWindowSettings.save(app, "map_canvas", "PANEL_125")
            org.mockito.Mockito.`when`(session.configuredClusterSize()).thenReturn(2408 to 904)
            body = controls(); choose(body, ClusterMapDisplayMode.FIT_WINDOW, true)
            assertFalse(PendingReconnect.isPending(session))
            org.mockito.Mockito.`when`(session.configuredClusterSize()).thenReturn(1280 to 480)
            body = controls(); choose(body, ClusterMapDisplayMode.PANEL_CROP, true)
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertEquals(ClusterMapCanvas.PANEL_125, ClusterWindowSettings.load(app).mapCanvas)
        } finally { CarPlayBackgroundSession.clear(); PendingReconnect.clear() }
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun snapshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            if (view is ScrollView) {
                canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
                view.getChildAt(0).draw(canvas)
            } else view.draw(canvas)
            val file = File("build/reports/cluster-window-ui/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

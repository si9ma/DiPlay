package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Di150VCP: 1920 px wide, CarPlay taking 66% on the left, leaving a strip on the right. */
class NavigationDebugPlacementTest {
    private val screen = 1920
    private val density = 2f

    @Test fun theStripBesideALeftProjectionIsOnTheRight() {
        val strip = NavigationDebugPlacement.freeStrip(screen, 66, ProjectionWindow.Alignment.LEFT)!!
        assertEquals(1267, strip.first)
        assertEquals(1919, strip.last)
    }

    @Test fun theStripBesideARightProjectionIsOnTheLeft() {
        val strip = NavigationDebugPlacement.freeStrip(screen, 66, ProjectionWindow.Alignment.RIGHT)!!
        assertEquals(0, strip.first)
        assertEquals(652, strip.last)
    }

    /** A centred projection leaves two strips of the same size; the right-hand one is taken. */
    @Test fun aCentredProjectionGivesTheRightHandStrip() {
        val strip = NavigationDebugPlacement.freeStrip(screen, 50, ProjectionWindow.Alignment.CENTER)!!
        assertEquals(1440, strip.first)
        assertEquals(1919, strip.last)
    }

    @Test fun aFullWidthProjectionLeavesNoStrip() {
        assertNull(NavigationDebugPlacement.freeStrip(screen, 100, ProjectionWindow.Alignment.LEFT))
    }

    /**
     * The bug this fixes: bounds taken from the host's own window confined the drag to the projection,
     * which is the one place the window is in the way. With the display's width the strip is reachable.
     */
    @Test fun theWindowCanBeDraggedOntoTheStripBesideCarPlay() {
        val width = 800
        val strip = NavigationDebugPlacement.freeStrip(screen, 66, ProjectionWindow.Alignment.LEFT)!!
        assertEquals(strip.first, NavigationDebugPlacement.clampX(strip.first, width, screen, density))
    }

    /** It may hang off an edge, so it can be pushed aside rather than only moved about. */
    @Test fun itMayHangOffEitherEdge() {
        val width = 800
        val visible = (72 * density).toInt()
        assertEquals(visible - width, NavigationDebugPlacement.clampX(-5000, width, screen, density))
        assertEquals(screen - visible, NavigationDebugPlacement.clampX(5000, width, screen, density))
    }

    /** However far it is pushed, enough stays on screen to grab it by. */
    @Test fun enoughAlwaysStaysOnScreenToGrabItBy() {
        val width = 800
        val visible = (72 * density).toInt()
        listOf(-5000, -1, 0, 900, screen, 5000).forEach { wanted ->
            val x = NavigationDebugPlacement.clampX(wanted, width, screen, density)
            assertTrue("$wanted -> $x", x + width >= visible && x <= screen - visible)
        }
    }

    /** Never above the top edge: the title bar is the only way to move the window back. */
    @Test fun itIsNeverDraggedAboveTheTopEdge() {
        assertEquals(0, NavigationDebugPlacement.clampY(-500, 400, 1080, density))
        assertTrue(NavigationDebugPlacement.clampY(5000, 400, 1080, density) <= 1080 - (72 * density).toInt())
    }

    /** A window narrower than the margin must still be placeable rather than clamped to nothing. */
    @Test fun aWindowSmallerThanTheMarginIsStillPlaceable() {
        val width = 40
        val x = NavigationDebugPlacement.clampX(1000, width, screen, density)
        assertTrue(x.toString(), x in 0..screen)
    }
}

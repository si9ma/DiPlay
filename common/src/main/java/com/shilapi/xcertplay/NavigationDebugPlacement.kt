package com.shilapi.xcertplay

/**
 * Where [NavigationDebugOverlay]'s window may sit. Geometry only, so it can be reasoned about and
 * tested without a Looper or a WindowManager.
 */
internal object NavigationDebugPlacement {
    /** How much of the window has to stay on screen, so one can never be dragged out of reach. */
    const val MIN_VISIBLE_DP = 72

    /**
     * Where the CarPlay projection is not, which is where the debug window wants to be while the
     * projection is being watched. Null when CarPlay has the whole screen; a centred projection
     * leaves two strips and the right-hand one is taken.
     */
    fun freeStrip(displayWidth: Int, percent: Int, alignment: ProjectionWindow.Alignment): IntRange? {
        val projected = (displayWidth * percent / 100f).toInt().coerceIn(0, displayWidth)
        val free = displayWidth - projected
        if (free <= 0) return null
        return when (alignment) {
            ProjectionWindow.Alignment.LEFT -> projected until displayWidth
            ProjectionWindow.Alignment.RIGHT -> 0 until free
            ProjectionWindow.Alignment.CENTER -> (projected + free / 2) until displayWidth
        }.takeIf { !it.isEmpty() }
    }

    /**
     * Lets the window hang off a side edge while keeping a grip on screen. A window confined whole
     * cannot be pushed aside on a screen this size; one allowed to leave entirely could not be got
     * back, since its title bar is the only way to move it.
     */
    fun clampX(x: Int, width: Int, screenWidth: Int, density: Float): Int {
        val visible = margin(density, width)
        val lowest = visible - width
        val highest = (screenWidth - visible).coerceAtLeast(lowest)
        return x.coerceIn(lowest, highest)
    }

    /** Never above the top edge, which would put the title bar out of reach. */
    fun clampY(y: Int, height: Int, screenHeight: Int, density: Float): Int {
        val highest = (screenHeight - margin(density, height)).coerceAtLeast(0)
        return y.coerceIn(0, highest)
    }

    private fun margin(density: Float, extent: Int): Int =
        (MIN_VISIBLE_DP * density).toInt().coerceAtMost(extent).coerceAtLeast(1)
}

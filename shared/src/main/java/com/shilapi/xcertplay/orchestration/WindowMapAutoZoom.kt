package com.shilapi.xcertplay.orchestration

/** Relative zoom, once per alt stream; content/visibility changes cancel rather than accumulate. */
internal class WindowMapAutoZoom {
    data class Step(val generation: Long, val route: Any, val zoomIn: Boolean)
    private var configured = 0
    private var visible = false
    private var phone: Any? = null
    private var sessionSteps = 0
    private var stream = 0
    private var attempted = false
    private var route: Any? = null
    private var remaining = 0
    private var due = 0L
    private var generation = 0L

    @Synchronized fun configure(steps: Int, visible: Boolean) {
        val next = steps.coerceIn(-5, 5)
        if (configured != next || this.visible != visible) cancel()
        configured = next
        this.visible = visible
    }

    private fun observe(owner: Any?, stream: Int) {
        if (phone !== owner) {
            cancel()
            phone = owner
            sessionSteps = configured
            this.stream = 0
        }
        if (this.stream != stream) {
            cancel()
            this.stream = stream
            attempted = false
        }
    }

    @Synchronized fun manual(owner: Any, stream: Int) {
        observe(owner, stream)
        attempted = true
        cancel()
    }

    @Synchronized fun cancel() {
        generation++
        remaining = 0
        route = null
    }

    @Synchronized fun next(owner: Any?, stream: Int, currentRoute: Any?, now: Long): Step? {
        observe(owner, stream)
        if (owner == null || stream <= 0 || !visible || currentRoute == null) {
            cancel()
            return null
        }
        if (remaining > 0 && route != currentRoute) {
            cancel()
            return null
        }
        if (!attempted) {
            attempted = true
            remaining = kotlin.math.abs(sessionSteps)
            route = currentRoute
            due = now + 1_500L
        }
        if (remaining == 0 || now < due) return null
        remaining--
        due = now + 400L
        return Step(generation, currentRoute, sessionSteps > 0)
    }

    @Synchronized fun current(step: Step): Boolean =
        visible && step.generation == generation && step.route == route
}

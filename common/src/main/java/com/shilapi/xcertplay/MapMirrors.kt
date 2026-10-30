package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The surfaces that show a copy of the dashboard map (CarPlay stream 111) outside the dashboard,
 * such as the centre card ([CARD]) and maps embedded by launchers ("launcher:<n>"). The CarPlay screen hands them to its media sink, now and after
 * every reconnect. Main thread.
 */
internal object MapMirrors {
    const val CARD = "card"

    /** The map pushed to the CarMeter watch ([CarMeterMapStream]); not on any head-unit screen. */
    const val CARMETER = "carmeter"

    /**
     * Same, but a copy of the main CarPlay screen (stream 110) instead of the dashboard stream;
     * the host maps this key to the main stream (see CarPlayHostActivity.mirrorSink).
     */
    const val CARMETER_MAIN = "carmeter_main"

    /** Which CarPlay stream a mirror [key] copies: true = main screen (110), false = dashboard (111). */
    fun mirrorsMainScreen(key: String): Boolean = key == CARMETER_MAIN

    /** The negotiated main screen's shape (width/height), set by the CarPlay screen when it starts. */
    @Volatile var mainAspect: Double = 16.0 / 9

    /** Whether the iPhone streams the main CarPlay screen right now. */
    var mainStreamActive = false
        private set
    private val mainStreamListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun setMainStreamActive(active: Boolean) {
        main.post {
            if (mainStreamActive == active) return@post
            mainStreamActive = active
            mainStreamListeners.forEach { it(active) }
        }
    }

    fun addMainStreamListener(listener: (Boolean) -> Unit) { mainStreamListeners.add(listener) }

    fun removeMainStreamListener(listener: (Boolean) -> Unit) { mainStreamListeners.remove(listener) }

    /** The physical dashboard stream's shape (1920x720, sent scaled to 1600x600 by default). */
    const val PHYSICAL_STREAM_ASPECT = 8.0 / 3

    /** The virtual dashboard stream's shape (1280x720, 16:9 for center screen cards/launchers). */
    const val VIRTUAL_STREAM_ASPECT = 16.0 / 9

    /** The current active aspect ratio for mirror surfaces and launcher embedding. */
    var streamAspect: Double = PHYSICAL_STREAM_ASPECT
        set(value) {
            require(value.isFinite() && value > 0)
            if (field == value) return
            field = value
            main.post { aspectListeners.forEach { it() } }
        }

    /** The active dashboard stream's shape (defaults to PHYSICAL_STREAM_ASPECT or VIRTUAL_STREAM_ASPECT). */
    val STREAM_ASPECT: Double get() = streamAspect

    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, Surface>()
    private val aspectListeners = CopyOnWriteArraySet<() -> Unit>()
    private val streamListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    /** Set by the CarPlay screen: applies one mirror to its current media sink. */
    var sink: ((String, Surface?) -> Unit)? = null

    /** The CarPlay screen has a new media sink: give it every mirror. */
    fun reapply() {
        val apply = sink ?: return
        surfaces.forEach { (key, surface) -> apply(key, surface) }
    }

    /** Called when the set of mirrors changes, so the dashboard map pause can stand aside. */
    var onChanged: (() -> Unit)? = null

    /** Whether the iPhone streams the dashboard map right now. */
    var streamActive = false
        private set

    fun set(key: String, surface: Surface?) {
        if (surface == null) {
            if (surfaces.remove(key) == null) return
        } else {
            surfaces[key] = surface
        }
        sink?.invoke(key, surface)
        onChanged?.invoke()
    }

    /** A copy of the dashboard map exists, so the dashboard map pause must not stop stream 111. */
    val any: Boolean get() = surfaces.keys.any { !mirrorsMainScreen(it) }

    /** Whether a launcher shows the map (see [MapEmbedService]), so the centre card is not needed. */
    val launcherShowsMap: Boolean get() = surfaces.keys.any { it != CARD && it != CARMETER && it != CARMETER_MAIN }

    fun setStreamActive(active: Boolean) {
        main.post {
            if (streamActive == active) return@post
            streamActive = active
            streamListeners.forEach { it(active) }
        }
    }

    fun addAspectListener(listener: () -> Unit) { aspectListeners.add(listener) }

    fun removeAspectListener(listener: () -> Unit) { aspectListeners.remove(listener) }

    fun addStreamListener(listener: (Boolean) -> Unit) { streamListeners.add(listener) }

    fun removeStreamListener(listener: (Boolean) -> Unit) { streamListeners.remove(listener) }
}

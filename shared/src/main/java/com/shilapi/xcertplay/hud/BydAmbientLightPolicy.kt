package com.shilapi.xcertplay.hud

/** Pure validation and restore-order rules for the narrowly scoped interior-lamp worker. */
internal object BydAmbientLightPolicy {
    const val TYPE = 1023
    const val LEASE_MILLIS = 15_000L
    const val MIN_APPLY_INTERVAL_MILLIS = 200L
    const val READBACK_POLL_MILLIS = 50L
    /** Software palette identification only; a candidate must complete the separate parked verification. */
    fun observedColorProfile(fingerprint: String?, colorConfig: Int?): String? =
        if (!fingerprint.isNullOrBlank() && colorConfig == 6) OEM127_PROFILE else null

    const val OEM127_PROFILE = "oem127"

    /** The color range a lamp session on this head unit may write and restore. */
    fun sessionColors(fingerprint: String?, colorConfig: Int?, verified: Boolean = false): IntRange =
        if (verified && observedColorProfile(fingerprint, colorConfig) != null) OEM127_COLORS else LEGACY_COLORS

    val getNames = setOf(
        "SET_HAS_INTERIOR_ATMOSPHERE_LAMP", "SET_IAL_COLOR_CONFIG", "SET_IAL_BRIGHTNESS_CONFIG",
        "SET_IAL_AREA_CONFIG", "SET_INTERIOR_ATMOSPHERE_LAMP_AREA", "SET_IAL_FRONT_COLOR",
        "SET_IAL_BACK_COLOR", "SET_IAL_FRONT_BRIGHTNESS", "SET_IAL_BACK_BRIGHTNESS",
    )
    val setNames = setOf(
        "SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET", "SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET",
        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET",
    )

    val configNames = setOf("SET_IAL_COLOR_CONFIG", "SET_IAL_BRIGHTNESS_CONFIG", "SET_IAL_AREA_CONFIG")

    /** Raw readback, not a promise that these values can safely be written or restored. */
    data class Snapshot(val frontColor: Int, val backColor: Int, val frontBrightness: Int, val backBrightness: Int, val area: Int)
    data class Values(val color: Int, val rawBrightness: Int, val area: Int)
    data class RestoreStep(val values: Values? = null, val areaOnly: Int? = null)

    fun parseSnapshot(value: String): Snapshot {
        val fields = value.trim().split(',').map { it.toInt() }
        require(fields.size == 5)
        return Snapshot(fields[0], fields[1], fields[2], fields[3], fields[4])
    }

    fun restorationFailure(s: Snapshot, colors: IntRange = LEGACY_COLORS): String? = when {
        s.frontColor !in colors || s.backColor !in colors -> "color-unverified"
        s.frontBrightness !in 1..6 || s.backBrightness !in 1..6 -> "brightness-unverified"
        s.area !in 1..3 -> "area-unverified"
        else -> null
    }

    /** The verified 31-color range, and the range of the observed OEM 127-color firmware. */
    val LEGACY_COLORS = 1..31
    val OEM127_COLORS = 1..127

    /** Every verification write must exercise a code above 31, including an already-green original. */
    fun writeTestColor(s: Snapshot): Int = listOf(57, 104, 90).first { it != s.frontColor && it != s.backColor }

    /** The caller supplies its independently authorised range; CONFIG alone never authorises writes. */
    fun canRestore(s: Snapshot, colors: IntRange = LEGACY_COLORS): Boolean = restorationFailure(s, colors) == null
    fun validSnapshot(s: Snapshot): Boolean = canRestore(s)

    fun validApply(area: Int, color: Int, rawBrightness: Int, colors: IntRange = LEGACY_COLORS): Boolean =
        area in 1..3 && color in colors && rawBrightness in 1..6

    /** SDK display brightness 0 is raw 1: minimum output, not verified physical power-off. */
    fun minimumTarget(current: Snapshot, colors: IntRange = LEGACY_COLORS): Snapshot {
        require(canRestore(current, colors))
        return current.copy(frontBrightness = 1, backBrightness = 1)
    }

    /** Preserve the current zone colors, then restore the current area selection. */
    fun minimumSteps(current: Snapshot, colors: IntRange = LEGACY_COLORS): List<RestoreStep> {
        val target = minimumTarget(current, colors)
        return listOf(
            RestoreStep(values = Values(target.frontColor, 1, 1)),
            RestoreStep(values = Values(target.backColor, 1, 2)),
            RestoreStep(areaOnly = target.area),
        )
    }

    /** Equal original zones can be restored atomically with the known-good ALL-area batch. */
    fun restoreSteps(s: Snapshot, colors: IntRange = LEGACY_COLORS): List<RestoreStep> {
        require(canRestore(s, colors)) { "unverified-restore-state" }
        if (s.frontColor == s.backColor && s.frontBrightness == s.backBrightness) {
            val steps = mutableListOf(RestoreStep(values = Values(s.frontColor, s.frontBrightness, 3)))
            if (s.area != 3) steps += RestoreStep(areaOnly = s.area)
            return steps
        }
        val steps = mutableListOf<RestoreStep>()
        steps += RestoreStep(values = Values(s.frontColor, s.frontBrightness, 1))
        steps += RestoreStep(values = Values(s.backColor, s.backBrightness, 2))
        steps += RestoreStep(areaOnly = s.area)
        return steps
    }
}

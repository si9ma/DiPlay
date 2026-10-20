package com.shilapi.xcertplay.hud

import android.util.SparseIntArray
import java.lang.reflect.InvocationTargetException

/** Fixed getter list. No arbitrary class, method, area, or setter arrives from the caller. */
internal object BydAmbientSdkProtocol {
    const val PREFIX = "ambient-sdk "
    const val SERVICE = "com.byd.car.feature.light.ICarAmbientLightService"
    const val AUTHORITY = "com.byd.car.server.provider.CarServiceProvider"
    const val TIMEOUT_MILLIS = 7_000L
    const val MAX_OUTPUT = 3_600
    enum class Shape { INTEGER, BOOLEAN, COLOR, BRIGHTNESS }
    data class Read(val key: String, val method: String, val shape: Shape, val area: Int? = null)
    val reads = listOf(
        Read("area", "getLightAreaSelected", Shape.INTEGER),
        Read("brightness", "getLightBrightness", Shape.BRIGHTNESS),
    ) + listOf(1, 2, 4, 8, 16).map { Read("color_$it", "getLightColor", Shape.COLOR, it) } + listOf(
        Read("enabled", "isLightEnable", Shape.BOOLEAN),
        Read("theme", "getAmbientLightTheme", Shape.INTEGER),
        Read("dynamic", "isDynamicMultiColorEnabled", Shape.BOOLEAN),
        Read("dynamic_mode", "getDynamicMultiColorMode", Shape.INTEGER),
        Read("rhythm", "isMusicRhythmEnabled", Shape.BOOLEAN),
        Read("rhythm_mode", "getMusicRhythmMode", Shape.INTEGER),
        Read("night_dimming", "isNightDrivingDimmingEnabled", Shape.BOOLEAN),
        Read("linkage", "isLampLinkageEnabled", Shape.BOOLEAN),
    )
    private val keys = reads.map { it.key }.toSet() + setOf("lock", "context", "loader", "package", "version",
        "provider", "user", "service", "complete", "transport")
    private val statuses = setOf("ok", "partial", "busy", "timeout", "permission_denied", "class_missing",
        "dependency_missing", "method_missing", "provider_missing", "service_missing", "sdk_error",
        "invalid_data", "failed", "not_approved", "unreachable", "unsupported", "incomplete")
    private val word = Regex("[A-Za-z0-9_.:;,=+-]{1,180}")
    data class Entry(val key: String, val status: String, val data: String = "none") {
        fun line() = "${PREFIX}key=$key status=$status data=$data"
    }
    fun parse(output: String?): List<Entry> = output.orEmpty().take(16_384).lineSequence().mapNotNull { line ->
        val parts = Regex("ambient-sdk key=([a-z0-9_]+) status=([a-z_]+) data=([^ ]+)").matchEntire(line.trim())?.groupValues
            ?: return@mapNotNull null
        Entry(parts[1], parts[2], parts[3]).takeIf { it.key in keys && it.status in statuses && validData(it) }
    }.take(32).distinctBy { it.key }.toList()

    private fun validData(entry: Entry): Boolean {
        val value = entry.data
        if (!word.matches(value)) return false
        if (entry.status != "ok") return value == "none" ||
            (value.startsWith("code=") && value.removePrefix("code=").toIntOrNull() != null)
        return when (entry.key) {
            "loader" -> value in setOf("default", "installed_system_apk")
            "package" -> value in setOf("com.byd.carsettings", "com.byd.carsettings.fse")
            "version", "provider" -> value.matches(Regex("[A-Za-z0-9_.+-]{1,100}"))
            "context" -> value == "shell"
            "user" -> value.matches(Regex("multi=[01];uid=[0-9]{1,10}"))
            "lock", "service", "complete" -> value == "none"
            else -> when (reads.firstOrNull { it.key == entry.key }?.shape) {
                Shape.INTEGER -> value.toIntOrNull() != null
                Shape.BOOLEAN -> value == "true" || value == "false"
                Shape.COLOR, Shape.BRIGHTNESS -> value.matches(Regex("mode=-?[0-9]{1,11};zones=-?[0-9]{1,11}:-?[0-9]{1,11}(,-?[0-9]{1,11}:-?[0-9]{1,11}){0,7}"))
                else -> false
            }
        }
    }

    fun complete(entries: List<Entry>): Boolean = entries.all { it.status == "ok" } &&
        entries.any { it.key == "complete" && it.status == "ok" } &&
        reads.all { read -> entries.any { it.key == read.key && it.status == "ok" } }

    fun failure(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException else error
        return when (cause) {
            is SecurityException -> "permission_denied"
            is ClassNotFoundException -> "class_missing"
            is NoClassDefFoundError -> "dependency_missing"
            is NoSuchMethodException, is NoSuchFieldException -> "method_missing"
            is IllegalArgumentException, is ClassCastException -> "invalid_data"
            else -> "failed"
        }
    }

    /** Result.message/toString are deliberately never read: they may contain private identifiers. */
    fun decode(read: Read, result: Any?): Entry {
        if (result == null) return Entry(read.key, "invalid_data")
        val cls = result.javaClass
        val code = cls.getField("code").getInt(result)
        if (cls.getMethod("isSuccess").invoke(result) != true) return Entry(read.key, "sdk_error", "code=$code")
        val data = cls.getField("data").get(result) ?: return Entry(read.key, "invalid_data", "code=$code")
        val value = when (read.shape) {
            Shape.INTEGER -> (data as Int).toString()
            Shape.BOOLEAN -> (data as Boolean).toString()
            Shape.COLOR, Shape.BRIGHTNESS -> {
                val color = read.shape == Shape.COLOR
                val mode = data.javaClass.getField(if (color) "mColorMode" else "mBrightnessMode").getInt(data)
                val values = data.javaClass.getField(if (color) "mColors" else "mBrightness").get(data) as SparseIntArray
                require(values.size() in 1..8)
                val pairs = (0 until values.size()).joinToString(",") { "${values.keyAt(it)}:${values.valueAt(it)}" }
                "mode=$mode;zones=$pairs"
            }
        }
        return Entry(read.key, "ok", value).takeIf(::validData) ?: Entry(read.key, "invalid_data")
    }

    fun readOnce(call: (Read) -> Any?, emit: (Entry) -> Unit): Boolean {
        var success = true
        for (read in reads) {
            val entry = try { decode(read, call(read)) } catch (error: Throwable) { Entry(read.key, failure(error)) }
            emit(entry)
            if (entry.status != "ok") success = false
            if (entry.status == "permission_denied") break
        }
        return success
    }
}

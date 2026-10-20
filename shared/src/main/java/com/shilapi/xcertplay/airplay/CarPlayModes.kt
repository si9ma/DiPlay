package com.shilapi.xcertplay.airplay

/**
 * CarPlay's resource arbitration as the iPhone last reported it in modesChanged: who owns the car's
 * main audio, and whether the iPhone has a call. This is separate from Android audio focus.
 *
 * DiPlay's /info says the car owns main audio at connection, so the iPhone keeps the car's own source
 * playing. While the car owns it the iPhone ignores media HID presses: the wheel and auto playback did
 * nothing until the driver started music on the CarPlay screen, which makes the iPhone take it.
 */
class CarPlayModes {
    enum class Entity { NONE, CONTROLLER, ACCESSORY }

    @Volatile var mainAudio: Entity? = null
        private set

    /** Null until the iPhone reports its phone-call app state. */
    @Volatile var phoneCall: Boolean? = null
        private set

    /** Returns true when a value changed. Unknown or malformed entries leave the previous value. */
    @Synchronized
    fun accept(params: Map<String, Any?>): Boolean {
        val before = mainAudio to phoneCall
        (params["resources"] as? List<*>)?.forEach { item ->
            val entry = item as? Map<*, *> ?: return@forEach
            if (number(entry["resourceID"]) == RESOURCE_MAIN_AUDIO) entity(entry["entity"])?.let { mainAudio = it }
        }
        (params["appStates"] as? List<*>)?.forEach { item ->
            val entry = item as? Map<*, *> ?: return@forEach
            if (number(entry["appStateID"]) != APP_STATE_PHONE_CALL) return@forEach
            val owner = entity(entry["entity"])
            val state = entry["state"] as? Boolean
            when {
                owner != null -> phoneCall = owner == Entity.CONTROLLER
                state != null -> phoneCall = state
            }
        }
        return before != (mainAudio to phoneCall)
    }

    @Synchronized
    fun clear() {
        mainAudio = null
        phoneCall = null
    }

    /** The iPhone has to take main audio before it acts on a media press. */
    fun iphoneOwnsMainAudio(): Boolean = mainAudio == Entity.CONTROLLER

    /** Compact summary of what the iPhone sent, for the report. Keys and numbers only. */
    fun describe(params: Map<String, Any?>): String {
        fun list(key: String, id: String) = (params[key] as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            "${number(entry[id]) ?: "?"}:" + entry.entries.filter { it.key != id }
                .joinToString("/") { "${it.key}=${it.value}" }
        }.joinToString(",")
        return "resources=[${list("resources", "resourceID")}] appStates=[${list("appStates", "appStateID")}]"
    }

    private fun number(value: Any?): Int? = (value as? Number)?.toInt()

    private fun entity(value: Any?): Entity? = when (number(value)) {
        0 -> Entity.NONE
        1 -> Entity.CONTROLLER
        2 -> Entity.ACCESSORY
        else -> null
    }

    companion object {
        const val RESOURCE_MAIN_AUDIO = 2
        const val APP_STATE_PHONE_CALL = 2
        // AirPlayCommon.h AirPlayTransferType: take=1, untake=2, borrow=3, unborrow=4.
        const val TRANSFER_TAKE = 1
        const val TRANSFER_UNTAKE = 2

        /** The car gives up main audio, so the iPhone may play; sent when the driver asks CarPlay to. */
        fun releaseMainAudioCommand(): Map<String, Any?> = linkedMapOf(
            "type" to "changeModes",
            "params" to linkedMapOf(
                "resources" to listOf(
                    linkedMapOf("resourceID" to RESOURCE_MAIN_AUDIO, "transferType" to TRANSFER_UNTAKE),
                ),
                "reasonStr" to "userInitiated",
            ),
        )
    }
}

/** A changeModes reply can carry the new modes even without a separate modesChanged event. */
internal class CarPlayModeResponses(
    private val publish: (Map<String, Any?>) -> Unit,
    private val log: (String) -> Unit,
) {
    private var revision = 0L
    private var pending: Pair<Int, Long>? = null

    @Synchronized fun sent(cseq: Int) { pending = cseq to revision }
    @Synchronized fun clear() { pending = null; revision++ }

    @Synchronized fun changed(params: Map<String, Any?>) {
        revision++
        publish(params)
    }

    @Synchronized fun accept(response: RtspMessage.Request) {
        val cseq = response.headers["cseq"]?.toIntOrNull() ?: return
        val request = pending?.takeIf { it.first == cseq } ?: return
        pending = null
        val body = runCatching { BplistCodec.decode(response.body) as? Map<*, *> }.getOrNull()
        val status = body?.get("status")
        val statusOk = status == null || (status is Number && status.toLong() == 0L)
        @Suppress("UNCHECKED_CAST")
        val modes = body?.get("params") as? Map<String, Any?>
        val accepted = response.path.toIntOrNull() == 200 && body != null && statusOk
        val apply = accepted && modes != null && request.second == revision
        log("changeModes response cseq=$cseq rtsp=${response.path.toIntOrNull()} " +
            "status=${(status as? Number)?.toLong() ?: if (status == null) "absent" else "invalid"} " +
            "accepted=$accepted applied=$apply superseded=${request.second != revision}")
        if (apply) changed(modes!!)
    }
}

package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayHidInputSemanticsTest {
    private fun slot(report: ByteArray, index: Int): Triple<Int, Int, Int> {
        val offset = index * 6
        fun u16(at: Int) = (report[at].toInt() and 0xff) or ((report[at + 1].toInt() and 0xff) shl 8)
        return Triple(report[offset + 1].toInt() and 0xff, u16(offset + 2), u16(offset + 4))
    }

    /**
     * A lift has to report where the finger was. The report carries a position in every frame, so a
     * release built without one lands at (0, 0) — the screen's top-left corner — and iOS reads the
     * contact as having been dragged off the button before lifting: it highlights on the way down and
     * the action never fires. Replaying a recorded tap did exactly that.
     */
    @Test fun aLiftKeepsThePositionItWasPressedAt() {
        val down = AirPlayHid.touchReport(listOf(AirPlayContact(0, 640.0, 480.0, down = true)))
        val up = AirPlayHid.touchReport(listOf(AirPlayContact(0, 640.0, 480.0, down = false)))
        assertEquals(Triple(1, 640, 480), slot(down, 0))
        assertEquals(Triple(0, 640, 480), slot(up, 0))
    }

    /** Why an empty report is not a lift: it is the "abandon everything" frame, and reads as (0, 0). */
    @Test fun anEmptyReportReleasesAtTheOriginAndIsThereforeACancel() {
        val empty = AirPlayHid.touchReport(emptyList())
        assertEquals(Triple(0, 0, 0), slot(empty, 0))
        assertEquals(Triple(0, 0, 0), slot(empty, 1))
        // The slot identifiers stay, so the iPhone still sees both contacts reported as up.
        assertEquals(0, empty[0].toInt())
        assertEquals(1, empty[6].toInt())
    }

    /** A real finger's release goes through here, and is what the replay has to look like. */
    @Test fun aSecondContactIsReportedIndependently() {
        val report = AirPlayHid.touchReport(listOf(
            AirPlayContact(0, 10.0, 20.0, down = false),
            AirPlayContact(1, 30.0, 40.0, down = true),
        ))
        assertEquals(Triple(0, 10, 20), slot(report, 0))
        assertEquals(Triple(1, 30, 40), slot(report, 1))
    }

    @Test fun touchscreenCoordinatesRemainAbsoluteSixteenBitValuesForBothContacts() {
        val device = AirPlayHid.touchHidDevice(1920, 1080, "test-display")
        val fields = inputFields(device["hidDescriptor"] as ByteArray).filter {
            it.page == 1 && (0x30 in it.usages || 0x31 in it.usages)
        }
        assertEquals(4, fields.size)
        assertTrue(fields.all { it.size == 16 && it.count == 1 && it.flags == 0x02 })
        assertEquals(listOf(0x30, 0x31, 0x30, 0x31), fields.map { it.usages.single() })
    }

    @Test fun legacyKnobCoordinatesStayAbsoluteWhileWheelMovementRemainsRelative() {
        val device = AirPlayHid.knobHidDevice("test-display")
        val fields = inputFields(device["hidDescriptor"] as ByteArray)
        val coordinates = fields.single { it.page == 1 && it.usages == listOf(0x30, 0x31) }
        val wheel = fields.single { it.page == 1 && it.usages == listOf(0x38) }
        assertEquals(0x02, coordinates.flags)
        assertEquals(8, coordinates.size)
        assertEquals(2, coordinates.count)
        assertEquals(0x06, wheel.flags)
        assertEquals(8, wheel.size)
        assertEquals(1, wheel.count)
    }

    private data class InputField(val page: Int, val usages: List<Int>, val size: Int, val count: Int, val flags: Int)

    /** Decode HID short items so these checks assert field semantics, not byte positions. */
    private fun inputFields(descriptor: ByteArray): List<InputField> {
        val fields = mutableListOf<InputField>()
        val usages = mutableListOf<Int>()
        var page = 0
        var reportSize = 0
        var reportCount = 0
        var offset = 0
        while (offset < descriptor.size) {
            val prefix = descriptor[offset++].toInt() and 0xff
            require(prefix != 0xfe) { "Only HID short items are expected" }
            val size = if ((prefix and 3) == 3) 4 else (prefix and 3)
            var value = 0
            repeat(size) { byte -> value = value or ((descriptor[offset++].toInt() and 0xff) shl (8 * byte)) }
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            when (type) {
                1 -> when (tag) {
                    0 -> page = value
                    7 -> reportSize = value
                    9 -> reportCount = value
                }
                2 -> if (tag == 0) usages += value
                0 -> {
                    if (tag == 8) fields += InputField(page, usages.toList(), reportSize, reportCount, value)
                    usages.clear()
                }
            }
        }
        return fields
    }
}

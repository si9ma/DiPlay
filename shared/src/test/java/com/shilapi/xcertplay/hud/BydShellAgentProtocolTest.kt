package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydShellAgentProtocolTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun commandQuotesTheApkAndRejectsBadTokens() {
        assertEquals("CLASSPATH='/data/app/x'\\''y/base.apk' app_process /system/bin " +
            "com.shilapi.xcertplay.hud.BydShellAgentMain $token 42",
            BydShellAgentProtocol.command("/data/app/x'y/base.apk", token, 42))
        assertTrue(runCatching { BydShellAgentProtocol.command("/a.apk", "nothex", 1) }.isFailure)
        assertTrue(runCatching { BydShellAgentProtocol.command("/a\n.apk", token, 1) }.isFailure)
        assertEquals("dpa01234567", BydShellAgentProtocol.prefix(token))
    }

    @Test fun readsCarryOnlyGetters() {
        assertEquals("1007,1086369832,5 1005,882901008,7", BydShellAgentProtocol.readBody(listOf(
            BydVehicleSignals.LEFT_CARD, BydSignal(1005, 882901008, BydSignal.GET_FLOAT))))
        assertTrue(runCatching { BydShellAgentProtocol.readBody(listOf(BydSignal(1007, 1, 6))) }.isFailure)
        assertTrue(runCatching { BydShellAgentProtocol.readBody(emptyList()) }.isFailure)
        assertEquals(BydSignal(1007, -5, 7), BydShellAgentProtocol.parseRead("1007,-5,7"))
        assertNull(BydShellAgentProtocol.parseRead("1007,5,6"))
        assertNull(BydShellAgentProtocol.parseRead("1007,5"))
        assertEquals(1007 to 1086369832, BydShellAgentProtocol.parseTarget("1007,1086369832"))
        assertNull(BydShellAgentProtocol.parseTarget("1007"))
    }

    @Test fun replyValuesDropErrorCodesAndMismatches() {
        assertEquals(listOf(1, null, null, -40), BydShellAgentProtocol.parseValues(listOf("1", "x", "-10011", "-40"), 4))
        assertNull(BydShellAgentProtocol.parseValues(listOf("1"), 2))
        assertNull(BydShellAgentProtocol.parseValues(listOf("1", "abc"), 2))
        assertFalse(BydShellAgentProtocol.validToken(token.uppercase()))
    }
}

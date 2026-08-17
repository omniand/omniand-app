package dev.omniand.launcher.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingSmsAssemblerTest {
    @Test
    fun emptyBroadcastIsIgnored() {
        assertNull(IncomingSmsAssembler.assemble(emptyList()))
    }

    @Test
    fun multipartBroadcastBecomesOneMessage() {
        val result =
            IncomingSmsAssembler.assemble(
                listOf(
                    IncomingPart("+331", "hello ", 20, 3),
                    IncomingPart("+331", "world", 21, 3),
                )
            )!!
        assertEquals("+331", result.address)
        assertEquals("hello world", result.body)
        assertEquals(20, result.timestamp)
        assertEquals(3, result.subscriptionId)
    }
}

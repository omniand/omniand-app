package dev.omniand.launcher.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMapperTest {
    private val records = listOf(
        SmsRecord("3", "20", "+222", "newest", 300, incoming = false, read = true),
        SmsRecord("2", "10", "+111", "reply", 200, incoming = true, read = false),
        SmsRecord("1", "10", "+111", "hello", 100, incoming = true, read = false),
        SmsRecord("4", "20", "+222", "older", 50, incoming = true, read = false)
    )

    @Test fun groupsThreadsNewestFirstAndCountsIncomingUnread() {
        val threads = SmsMapper.threads(records)
        assertEquals(listOf("20", "10"), threads.map { it.id })
        assertEquals("newest", threads[0].body)
        assertEquals(1, threads[0].unreadCount)
        assertEquals(2, threads[1].unreadCount)
    }

    @Test fun ordersConversationOldestFirstAndPreservesDirection() {
        val messages = SmsMapper.messages(records, "10")
        assertEquals(listOf("1", "2"), messages.map { it.id })
        assertTrue(messages.all { it.incoming })
        assertFalse(records.first().incoming)
    }

    @Test fun rejectsMalformedIdentifiers() {
        assertThrows(SmsService.InvalidId::class.java) { SmsMapper.requireId("oops") }
        assertThrows(SmsService.InvalidId::class.java) { SmsMapper.requireId("-1") }
        assertEquals("42", SmsMapper.requireId("42"))
    }

    @Test fun missingThreadProducesNoRecordsForServiceToMapTo404() {
        assertTrue(SmsMapper.messages(records, "999").isEmpty())
    }
}

package dev.omniand.launcher.services

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMapperTest {
    private val records = listOf(
        SmsRecord("3", "20", "+222", "newest", 300, SmsDelivery.FAILED, read = true),
        SmsRecord("2", "10", "+111", "reply", 200, SmsDelivery.RECEIVED, read = false),
        SmsRecord("1", "10", "+111", "hello", 100, SmsDelivery.RECEIVED, read = false),
        SmsRecord("4", "20", "+222", "older", 50, SmsDelivery.RECEIVED, read = false),
        SmsRecord("5", "30", "+333", "queued", 25, SmsDelivery.PENDING, read = false)
    )

    @Test fun groupsThreadsNewestFirstAndCountsIncomingUnread() {
        val threads = SmsMapper.threads(records)
        assertEquals(listOf("20", "10", "30"), threads.map { it.id })
        assertEquals("newest", threads[0].body)
        assertEquals(1, threads[0].unreadCount)
        assertEquals(2, threads[1].unreadCount)
        assertEquals(0, threads[2].unreadCount)
        assertEquals(SmsDelivery.FAILED, threads[0].lastMessageDelivery)
    }

    @Test fun ordersConversationOldestFirstAndPreservesDirection() {
        val messages = SmsMapper.messages(records, "10")
        assertEquals(listOf("1", "2"), messages.map { it.id })
        assertTrue(messages.all { it.delivery.incoming })
        assertTrue(!records.first().delivery.incoming)
    }

    @Test fun mapsSupportedProviderTypesAndExcludesDraftAndUnknown() {
        assertEquals(SmsDelivery.RECEIVED, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_INBOX))
        assertEquals(SmsDelivery.OUTBOX, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_OUTBOX))
        assertEquals(SmsDelivery.PENDING, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_QUEUED))
        assertEquals(SmsDelivery.SENT, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_SENT))
        assertEquals(SmsDelivery.FAILED, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_FAILED))
        assertEquals(null, SmsMapper.delivery(Telephony.Sms.MESSAGE_TYPE_DRAFT))
        assertEquals(null, SmsMapper.delivery(Int.MAX_VALUE))
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

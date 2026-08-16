package dev.omniand.launcher.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsEventBroadcasterTest {
    @Test fun `subscription starts with retry framing and cleans up`() {
        val stream = SmsEventBroadcaster.subscribe(10)
        assertEquals(1, SmsEventBroadcaster.subscriberCount())
        assertEquals(": connected\nretry: 3000\n\n", readFrame(stream))
        stream.close()
        assertEquals(0, SmsEventBroadcaster.subscriberCount())
    }

    @Test fun `delivery is bounded and conflates to newest invalidation`() {
        val stream = SmsEventBroadcaster.subscribe(10)
        readFrame(stream)
        SmsEventBroadcaster.publish("incoming", "1")
        SmsEventBroadcaster.publish("delivery", "2")
        val frame = readFrame(stream)
        assertTrue(frame.contains("event: sms-change"))
        assertTrue(frame.contains("\"reason\":\"delivery\""))
        assertTrue(frame.contains("\"messageId\":\"2\""))
        assertFalse(frame.contains("\"messageId\":\"1\""))
        stream.close()
    }

    @Test fun `idle subscriptions emit heartbeats`() {
        val stream = SmsEventBroadcaster.subscribe(1)
        readFrame(stream)
        assertEquals(": heartbeat\n\n", readFrame(stream))
        stream.close()
    }

    @Test fun `android web resource bulk reads receive frames immediately`() {
        val stream = SmsEventBroadcaster.subscribe(10, closeAfterEvent = true)
        val buffer = ByteArray(256)
        val initialCount = stream.read(buffer)
        assertEquals(": connected\nretry: 3000\n\n", buffer.decodeToString(0, initialCount))
        SmsEventBroadcaster.publish("incoming", "7")
        val eventCount = stream.read(buffer)
        assertTrue(buffer.decodeToString(0, eventCount).contains("\"messageId\":\"7\""))
        assertEquals(-1, stream.read(buffer))
        assertEquals(0, SmsEventBroadcaster.subscriberCount())
    }

    @Test fun `only final send outcomes publish`() {
        val stream = SmsEventBroadcaster.subscribe(5)
        readFrame(stream)
        SmsSendEventPublisher.publishFinal(SmsSendOutcome.PENDING, "1")
        SmsSendEventPublisher.publishFinal(SmsSendOutcome.DUPLICATE, "1")
        assertEquals(": heartbeat\n\n", readFrame(stream))
        SmsSendEventPublisher.publishFinal(SmsSendOutcome.SENT, "1")
        assertTrue(readFrame(stream).contains("\"reason\":\"delivery\""))
        stream.close()
    }

    @Test fun `persisted incoming messages publish their provider identifier`() {
        val stream = SmsEventBroadcaster.subscribe(5)
        readFrame(stream)
        SmsIncomingEventPublisher.publishPersisted("42")
        val frame = readFrame(stream)
        assertTrue(frame.contains("\"reason\":\"incoming\""))
        assertTrue(frame.contains("\"messageId\":\"42\""))
        stream.close()
    }

    @Test fun `read changes identify their message or thread`() {
        val messageStream = SmsEventBroadcaster.subscribe(5)
        readFrame(messageStream)
        SmsReadEventPublisher.publishMessage("42")
        val messageFrame = readFrame(messageStream)
        assertTrue(messageFrame.contains("\"reason\":\"read\""))
        assertTrue(messageFrame.contains("\"messageId\":\"42\""))
        messageStream.close()

        val threadStream = SmsEventBroadcaster.subscribe(5)
        readFrame(threadStream)
        SmsReadEventPublisher.publishThread("8")
        val threadFrame = readFrame(threadStream)
        assertTrue(threadFrame.contains("\"reason\":\"read\""))
        assertTrue(threadFrame.contains("\"threadId\":\"8\""))
        threadStream.close()
    }

    private fun readFrame(stream: java.io.InputStream): String {
        val bytes = ArrayList<Byte>()
        var trailingNewlines = 0
        while (trailingNewlines < 2) {
            val byte = stream.read()
            if (byte < 0) break
            bytes += byte.toByte()
            trailingNewlines = if (byte == '\n'.code) trailingNewlines + 1 else 0
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}

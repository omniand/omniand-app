package dev.omniand.hub.sms

import com.google.android.mms.pdu.NotificationInd
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduCodecTest {
    @Test
    fun parsesNotificationIdentityAndExpiry() {
        val bytes =
            ByteArrayOutputStream()
                .apply {
                    write(byteArrayOf(0x8c.toByte(), 0x82.toByte()))
                    write(0x98)
                    write("txn-1".toByteArray())
                    write(0)
                    write(byteArrayOf(0x8d.toByte(), 0x91.toByte()))
                    write(byteArrayOf(0x8a.toByte(), 0x80.toByte()))
                    write(byteArrayOf(0x8e.toByte(), 0x01, 0x7f))
                    write(byteArrayOf(0x88.toByte(), 0x03, 0x81.toByte(), 0x01, 0x3c))
                    write(0x83)
                    write("https://mmsc.invalid/abc".toByteArray())
                    write(0)
                }
                .toByteArray()

        val (pdu, envelope) = MmsPduCodec.parse(bytes)

        assertTrue(pdu is NotificationInd)
        assertEquals("notification", envelope.kind)
        assertEquals("txn-1", envelope.transactionId)
        assertEquals("https://mmsc.invalid/abc", envelope.contentLocation)
        assertTrue(envelope.expiryMillis!! > System.currentTimeMillis())
        assertTrue(envelope.expiryMillis < System.currentTimeMillis() + 61_000)
    }
}

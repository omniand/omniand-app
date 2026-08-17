package dev.omniand.launcher.sms

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsJournalTest {
    @Test
    fun deduplicatesAndReducesRetryAndCompletion() {
        var now = 1_000_000L
        val journal = MmsJournal(Files.createTempDirectory("mms-journal").toFile()) { now }
        val first = journal.record(3, notification())
        val duplicate = journal.record(3, notification())
        assertEquals(first.getString("id"), duplicate.getString("id"))

        journal.update(first.getString("id"), MmsJournalState.NOTIFIED, providerId = "41")
        now += 1_000
        val retry = journal.update(first.getString("id"), MmsJournalState.RETRY, "network")
        assertEquals(1, retry.getInt("attempts"))
        assertEquals(now + 30_000, retry.getLong("nextAttempt"))
        assertEquals("41", retry.getString("providerId"))

        journal.update(first.getString("id"), MmsJournalState.COMPLETE, providerId = "42")
        assertFalse(journal.payload(first.getString("id")).exists())
    }

    @Test
    fun quarantinesMalformedPayload() {
        val journal = MmsJournal(Files.createTempDirectory("mms-bad").toFile()) { 5_000 }
        val entry = journal.record(1, byteArrayOf(1, 2, 3))
        assertEquals("QUARANTINED", entry.getString("state"))
        assertTrue(journal.payload(entry.getString("id")).exists())
    }

    @Test
    fun migratesLegacySubscriptionPrefixedPayload() {
        val root = Files.createTempDirectory("mms-legacy").toFile()
        val legacy = root.resolve("123-old.pdu")
        legacy.writeBytes("7\n".toByteArray() + notification())
        val journal = MmsJournal(root) { 10_000 }

        journal.record(8, notification())

        assertFalse(legacy.exists())
        assertTrue(journal.entries().any { it.getInt("subscriptionId") == 7 })
    }

    private fun notification() =
        ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0x8c.toByte(), 0x82.toByte()))
                write(0x98)
                write("txn-journal".toByteArray())
                write(0)
                write(byteArrayOf(0x8d.toByte(), 0x91.toByte()))
                write(byteArrayOf(0x8a.toByte(), 0x80.toByte()))
                write(byteArrayOf(0x8e.toByte(), 0x01, 0x7f))
                write(byteArrayOf(0x88.toByte(), 0x03, 0x81.toByte(), 0x01, 0x3c))
                write(0x83)
                write("https://mmsc.invalid/journal".toByteArray())
                write(0)
            }
            .toByteArray()
}

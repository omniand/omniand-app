package dev.omniand.launcher.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.security.MessageDigest
import java.util.concurrent.Executors

data class IncomingPart(val address: String, val body: String, val timestamp: Long, val subscriptionId: Int)

object IncomingSmsAssembler {
    fun assemble(parts: List<IncomingPart>): IncomingPart? {
        if (parts.isEmpty()) return null
        val first = parts.first()
        return first.copy(body = parts.joinToString("") { it.body }, timestamp = parts.minOf { it.timestamp })
    }
}

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        EXECUTOR.execute {
            try { persist(context.applicationContext, intent) } finally { pending.finish() }
        }
    }

    private fun persist(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val message = IncomingSmsAssembler.assemble(messages.map {
            IncomingPart(it.displayOriginatingAddress.orEmpty(), it.displayMessageBody.orEmpty(), it.timestampMillis, subscriptionId)
        }) ?: return
        val fingerprint = sha256("${message.address}\u0000${message.body}\u0000${message.timestamp}\u0000${message.subscriptionId}")
        if (!DedupLedger.claim(context, fingerprint)) return
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, message.address)
            put(Telephony.Sms.BODY, message.body)
            put(Telephony.Sms.DATE, message.timestamp)
            put(Telephony.Sms.DATE_SENT, message.timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (message.subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, message.subscriptionId)
        }
        try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) ?: error("SMS provider rejected insert")
            val id = uri.lastPathSegment.orEmpty()
            val threadId = context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: id
            SmsNotifications.publish(context, threadId, message.address.ifBlank { "New message" }, message.body, message.timestamp)
        } catch (error: Exception) {
            DedupLedger.release(context, fingerprint)
            throw error
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private val EXECUTOR = Executors.newSingleThreadExecutor() }
}

object DedupLedger {
    private const val PREFS = "sms-pdu-ledger"
    private const val MAX = 256
    @Synchronized fun claim(context: Context, fingerprint: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getString("entries", "").orEmpty().lineSequence().filter(String::isNotBlank).toMutableList()
        if (fingerprint in entries) return false
        entries += fingerprint
        prefs.edit().putString("entries", entries.takeLast(MAX).joinToString("\n")).commit()
        return true
    }
    @Synchronized fun release(context: Context, fingerprint: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("entries", prefs.getString("entries", "").orEmpty().lineSequence()
            .filter { it.isNotBlank() && it != fingerprint }.joinToString("\n")).commit()
    }
}

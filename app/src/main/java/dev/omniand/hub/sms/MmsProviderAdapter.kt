package dev.omniand.hub.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.google.android.mms.pdu.DeliveryInd
import com.google.android.mms.pdu.GenericPdu
import com.google.android.mms.pdu.PduPart
import com.google.android.mms.pdu.ReadOrigInd
import com.google.android.mms.pdu.RetrieveConf
import com.google.android.mms.pdu.SendReq

/** Narrow public-contract adapter for MMS rows, addresses, and binary/text parts. */
class MmsProviderAdapter(private val context: Context) {
    fun persistOutgoing(pdu: SendReq, address: String): String {
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val uri =
            context.contentResolver.insert(
                Telephony.Mms.Outbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, threadId)
                    put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                    put(Telephony.Mms.READ, 1)
                    put(Telephony.Mms.SEEN, 1)
                    put(Telephony.Mms.MESSAGE_TYPE, 128)
                    put(
                        Telephony.Mms.TRANSACTION_ID,
                        pdu.transactionId?.toString(Charsets.ISO_8859_1),
                    )
                    put(Telephony.Mms.SUBJECT, pdu.subject?.string)
                },
            ) ?: error("Unable to insert outgoing MMS")
        val id = uri.lastPathSegment ?: error("MMS provider returned no identifier")
        try {
            insertAddress(id, address, 151)
            for (index in 0 until pdu.body.partsNum) insertPart(id, pdu.body.getPart(index))
            return id
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    fun setOutgoingResult(id: String, sent: Boolean, messageId: String? = null) {
        context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id.toLong()),
            ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, if (sent) 2 else 5)
                put(Telephony.Mms.DATE_SENT, if (sent) System.currentTimeMillis() / 1000 else 0)
                if (messageId != null) put(Telephony.Mms.MESSAGE_ID, messageId)
            },
            null,
            null,
        )
    }

    fun persistNotification(envelope: MmsEnvelope): String {
        envelope.sender ?: throw IllegalArgumentException("MMS notification has no sender")
        val threadId = Telephony.Threads.getOrCreateThreadId(context, envelope.sender)
        val uri =
            context.contentResolver.insert(
                Telephony.Mms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, threadId)
                    put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                    put(Telephony.Mms.READ, 0)
                    put(Telephony.Mms.SEEN, 0)
                    put(Telephony.Mms.MESSAGE_TYPE, 130)
                    put(Telephony.Mms.TRANSACTION_ID, envelope.transactionId)
                    put(Telephony.Mms.CONTENT_LOCATION, envelope.contentLocation)
                    put(Telephony.Mms.SUBJECT, envelope.subject)
                    put(Telephony.Mms.EXPIRY, envelope.expiryMillis?.div(1000))
                },
            ) ?: error("Unable to insert MMS notification")
        val id = uri.lastPathSegment ?: error("MMS provider returned no identifier")
        try {
            insertAddress(id, envelope.sender, 137)
            return id
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    fun persistRetrieved(pdu: RetrieveConf, placeholderId: String?): String {
        val sender = pdu.from?.string ?: "Unknown"
        val threadId = Telephony.Threads.getOrCreateThreadId(context, sender)
        val uri =
            context.contentResolver.insert(
                Telephony.Mms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, threadId)
                    put(
                        Telephony.Mms.DATE,
                        (pdu.date.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000),
                    )
                    put(Telephony.Mms.READ, 0)
                    put(Telephony.Mms.SEEN, 0)
                    put(Telephony.Mms.MESSAGE_TYPE, 132)
                    put(Telephony.Mms.MESSAGE_ID, pdu.messageId?.toString(Charsets.ISO_8859_1))
                    put(
                        Telephony.Mms.TRANSACTION_ID,
                        pdu.transactionId?.toString(Charsets.ISO_8859_1),
                    )
                    put(Telephony.Mms.SUBJECT, pdu.subject?.string)
                },
            ) ?: error("Unable to insert retrieved MMS")
        val id = uri.lastPathSegment ?: error("MMS provider returned no identifier")
        try {
            insertAddress(id, sender, 137)
            pdu.to?.forEach { insertAddress(id, it.string, 151) }
            val body = pdu.body
            for (index in 0 until body.partsNum) insertPart(id, body.getPart(index))
            placeholderId?.let { delete(it) }
            return id
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    fun applyReport(pdu: GenericPdu): Boolean {
        val messageId =
            when (pdu) {
                is DeliveryInd -> pdu.messageId
                is ReadOrigInd -> pdu.messageId
                else -> null
            }?.toString(Charsets.ISO_8859_1) ?: return false
        val values = ContentValues()
        when (pdu) {
            is DeliveryInd -> values.put(Telephony.Mms.STATUS, pdu.status)
            is ReadOrigInd -> values.put(Telephony.Mms.READ_STATUS, pdu.readStatus)
        }
        return context.contentResolver.update(
            Telephony.Mms.CONTENT_URI,
            values,
            "${Telephony.Mms.MESSAGE_ID} = ?",
            arrayOf(messageId),
        ) > 0
    }

    fun delete(id: String) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id.toLong()),
            null,
            null,
        )
    }

    fun threadId(id: String): String =
        context.contentResolver
            .query(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id.toLong()),
                arrayOf(Telephony.Mms.THREAD_ID),
                null,
                null,
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: error("MMS provider row has no thread")

    private fun insertAddress(messageId: String, address: String, type: Int) {
        context.contentResolver.insert(
            Uri.parse("content://mms/$messageId/addr"),
            ContentValues().apply {
                put("address", address)
                put("charset", 106) // IANA MIBenum for UTF-8.
                put("type", type)
            },
        ) ?: error("Unable to insert MMS address")
    }

    private fun insertPart(messageId: String, part: PduPart) {
        val mime = part.contentType?.toString(Charsets.ISO_8859_1) ?: "application/octet-stream"
        val values =
            ContentValues().apply {
                put("ct", mime)
                put("name", part.name?.toString(Charsets.UTF_8))
                put("fn", part.filename?.toString(Charsets.UTF_8))
                put("cid", part.contentId?.toString(Charsets.ISO_8859_1))
                put("cl", part.contentLocation?.toString(Charsets.ISO_8859_1))
                if (mime.startsWith("text/") && part.data != null)
                    put("text", part.data.toString(Charsets.UTF_8))
            }
        val uri =
            context.contentResolver.insert(Uri.parse("content://mms/$messageId/part"), values)
                ?: error("Unable to insert MMS part")
        if (!mime.startsWith("text/") && part.data != null)
            context.contentResolver.openOutputStream(uri)?.use { it.write(part.data) }
                ?: error("Unable to write MMS part")
    }
}

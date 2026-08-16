package dev.omniand.launcher.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

data class SmsRecord(
    val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val incoming: Boolean,
    val read: Boolean
)

data class SmsThread(
    val id: String,
    val participant: String,
    val body: String,
    val timestamp: Long,
    val unreadCount: Int
)

object SmsMapper {
    fun threads(records: List<SmsRecord>): List<SmsThread> = records
        .groupBy { it.threadId }
        .map { (threadId, messages) ->
            val latest = messages.maxWithOrNull(compareBy<SmsRecord> { it.timestamp }.thenBy { it.id })!!
            SmsThread(
                id = threadId,
                participant = latest.address,
                body = latest.body,
                timestamp = latest.timestamp,
                unreadCount = messages.count { it.incoming && !it.read }
            )
        }
        .sortedWith(compareByDescending<SmsThread> { it.timestamp }.thenByDescending { it.id })

    fun messages(records: List<SmsRecord>, threadId: String): List<SmsRecord> = records
        .filter { it.threadId == threadId }
        .sortedWith(compareBy<SmsRecord> { it.timestamp }.thenBy { it.id })

    fun requireId(rawId: String): String {
        if (rawId.toLongOrNull() == null || rawId.toLong() < 0) throw SmsService.InvalidId()
        return rawId
    }
}

class SmsService(private val context: Context) {
    class PermissionMissing : Exception()
    class InvalidId : Exception()
    class NotFound : Exception()

    fun recent(limit: Int = 100): JSONArray = JSONArray(records().take(limit).map(::legacyJson))

    fun threads(): JSONArray = JSONArray(SmsMapper.threads(records()).map { thread ->
        JSONObject()
            .put("id", thread.id)
            .put("participants", JSONArray().put(thread.participant))
            .put("body", thread.body)
            .put("timestamp", thread.timestamp)
            .put("unreadCount", thread.unreadCount)
            .put("lastMessageType", "sms")
    })

    fun messages(rawThreadId: String): JSONArray {
        val threadId = SmsMapper.requireId(rawThreadId)
        val messages = SmsMapper.messages(records(), threadId)
        if (messages.isEmpty()) throw NotFound()
        return JSONArray(messages.map(::messageJson))
    }

    fun message(rawMessageId: String): JSONObject {
        val messageId = SmsMapper.requireId(rawMessageId)
        return records().firstOrNull { it.id == messageId }?.let(::messageJson) ?: throw NotFound()
    }

    private fun records(): List<SmsRecord> {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw PermissionMissing()
        }
        val result = mutableListOf<SmsRecord>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadId = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                result += SmsRecord(
                    id = cursor.getString(id),
                    threadId = cursor.getString(threadId),
                    address = cursor.getString(address) ?: "Unknown",
                    body = cursor.getString(body) ?: "",
                    timestamp = cursor.getLong(date),
                    incoming = cursor.getInt(type) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    read = cursor.getInt(read) != 0
                )
            }
        }
        return result
    }

    private fun legacyJson(message: SmsRecord) = JSONObject()
        .put("id", message.id)
        .put("address", message.address)
        .put("body", message.body)
        .put("date", message.timestamp)
        .put("type", if (message.incoming) "received" else "sent")

    private fun messageJson(message: SmsRecord) = JSONObject()
        .put("id", message.id)
        .put("threadId", message.threadId)
        .put("type", "sms")
        .put("sender", if (message.incoming) message.address else JSONObject.NULL)
        .put("receiver", if (message.incoming) JSONObject.NULL else message.address)
        .put("body", message.body)
        .put("timestamp", message.timestamp)
        .put("read", message.read)
        .put("delivery", if (message.incoming) "received" else "sent")
}

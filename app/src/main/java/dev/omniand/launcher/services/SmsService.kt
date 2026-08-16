package dev.omniand.launcher.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentValues
import android.telephony.SmsManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import dev.omniand.launcher.sms.SmsSetupManager

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
    class RoleRequired : Exception()
    class InvalidId : Exception()
    class InvalidInput : Exception()
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

    @Suppress("DEPRECATION")
    fun send(address: String, body: String): JSONObject {
        requirePermission(Manifest.permission.SEND_SMS)
        if (address.isBlank() || address.length > 100 || body.isBlank() || body.length > 2_000) throw InvalidInput()
        val manager = SmsManager.getDefault()
        val parts = manager.divideMessage(body)
        if (parts.size > 1) manager.sendMultipartTextMessage(address, null, parts, null, null)
        else manager.sendTextMessage(address, null, body, null, null)
        return JSONObject().put("sent", true)
    }

    fun setRead(rawMessageId: String, rawRead: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val messageId = SmsMapper.requireId(rawMessageId)
        val threadId = threadForMessage(messageId)
        val read = parseRead(rawRead)
        val changed = context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
            "${Telephony.Sms._ID} = ?", arrayOf(messageId)
        )
        if (changed == 0) throw NotFound()
        return JSONObject().put("id", messageId).put("threadId", threadId).put("read", read)
    }

    fun deleteMessage(rawMessageId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val messageId = SmsMapper.requireId(rawMessageId)
        val threadId = threadForMessage(messageId)
        val changed = context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(messageId)
        )
        if (changed == 0) throw NotFound()
        return JSONObject().put("deleted", true).put("id", messageId).put("threadId", threadId)
    }

    fun setThreadRead(rawThreadId: String, rawRead: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val read = parseRead(rawRead)
        val changed = context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId)
        )
        if (changed == 0) throw NotFound()
        return JSONObject().put("threadId", threadId).put("read", read).put("count", changed)
    }

    fun deleteThread(rawThreadId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val changed = context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId)
        )
        if (changed == 0) throw NotFound()
        return JSONObject().put("deleted", true).put("threadId", threadId).put("count", changed)
    }

    private fun records(): List<SmsRecord> {
        requirePermission(Manifest.permission.READ_SMS)
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

    private fun threadForMessage(messageId: String): String = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
        "${Telephony.Sms._ID} = ?", arrayOf(messageId), null
    )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: throw NotFound()

    private fun requirePermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw PermissionMissing()
    }

    private fun requireRole() {
        if (!SmsSetupManager.isRoleHeld(context)) throw RoleRequired()
    }

    private fun parseRead(rawRead: String): Boolean = when (rawRead) {
        "true" -> true
        "false" -> false
        else -> throw InvalidInput()
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

    private companion object {
        const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"
    }
}

package dev.omniand.launcher.services

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import dev.omniand.launcher.sms.SmsSendResultReceiver
import dev.omniand.launcher.sms.SmsSendTracker
import dev.omniand.launcher.sms.SmsSetupManager
import org.json.JSONArray
import org.json.JSONObject

enum class SmsDelivery(val apiValue: String) {
    RECEIVED("received"),
    OUTBOX("outbox"),
    PENDING("pending"),
    SENT("sent"),
    FAILED("failed");

    val incoming: Boolean
        get() = this == RECEIVED
}

data class SmsRecord(
    val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val delivery: SmsDelivery,
    val read: Boolean,
)

data class SmsThread(
    val id: String,
    val participant: String,
    val body: String,
    val timestamp: Long,
    val unreadCount: Int,
    val unreadCountCapped: Boolean,
    val lastMessageDelivery: SmsDelivery,
)

object SmsMapper {
    data class Page(val offset: Int, val limit: Int, val paged: Boolean)

    data class Unread(val count: Int, val capped: Boolean)

    fun unread(total: Int) = Unread(total.coerceAtMost(9), total >= 10)

    fun page(rawOffset: String?, rawLimit: String?, defaultLimit: Int): Page {
        if (rawOffset == null && rawLimit == null) return Page(0, 100, false)
        val offset =
            if (rawOffset == null) 0 else rawOffset.toIntOrNull() ?: throw SmsService.InvalidInput()
        val limit =
            if (rawLimit == null) defaultLimit
            else rawLimit.toIntOrNull() ?: throw SmsService.InvalidInput()
        if (offset < 0 || limit !in 1..100) throw SmsService.InvalidInput()
        return Page(offset, limit, true)
    }

    fun delivery(providerType: Int): SmsDelivery? =
        when (providerType) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsDelivery.RECEIVED
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SmsDelivery.OUTBOX
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> SmsDelivery.PENDING
            Telephony.Sms.MESSAGE_TYPE_SENT -> SmsDelivery.SENT
            Telephony.Sms.MESSAGE_TYPE_FAILED -> SmsDelivery.FAILED
            else -> null
        }

    fun threads(records: List<SmsRecord>): List<SmsThread> =
        records
            .groupBy { it.threadId }
            .map { (threadId, messages) ->
                val latest =
                    messages.maxWithOrNull(compareBy<SmsRecord> { it.timestamp }.thenBy { it.id })!!
                SmsThread(
                    id = threadId,
                    participant = latest.address,
                    body = latest.body,
                    timestamp = latest.timestamp,
                    unreadCount = messages.count { it.delivery.incoming && !it.read },
                    unreadCountCapped = false,
                    lastMessageDelivery = latest.delivery,
                )
            }
            .sortedWith(compareByDescending<SmsThread> { it.timestamp }.thenByDescending { it.id })

    fun messages(records: List<SmsRecord>, threadId: String): List<SmsRecord> =
        records
            .filter { it.threadId == threadId }
            .sortedWith(compareBy<SmsRecord> { it.timestamp }.thenBy { it.id })

    fun requireId(rawId: String): String {
        if (rawId.toLongOrNull() == null || rawId.toLong() < 0) throw SmsService.InvalidId()
        return rawId
    }
}

/**
 * Provides the Android SMS-provider boundary after Web capability authorization has succeeded.
 * Reads still require Android permission, while provider mutations additionally require OmniAnd to
 * hold the default-SMS role; failures use stable errors consumed by the Messages UI.
 */
class SmsService(private val context: Context) {
    class PermissionMissing : Exception()

    class RoleRequired : Exception()

    class InvalidId : Exception()

    class InvalidInput : Exception()

    class NotFound : Exception()

    fun recent(limit: Int = 100): JSONArray = JSONArray(records().take(limit).map(::legacyJson))

    fun threads(rawOffset: String?, rawLimit: String?): Any {
        val page = SmsMapper.page(rawOffset, rawLimit, 30)
        val rows = threadRecords(page.offset, page.limit + 1)
        val values = JSONArray(rows.take(page.limit).map(::threadJson))
        return if (!page.paged) values
        else
            JSONObject()
                .put("threads", values)
                .put(
                    "nextOffset",
                    if (rows.size > page.limit) page.offset + page.limit else JSONObject.NULL,
                )
    }

    fun messages(rawThreadId: String, rawOffset: String?, rawLimit: String?): Any {
        val threadId = SmsMapper.requireId(rawThreadId)
        val page = SmsMapper.page(rawOffset, rawLimit, 50)
        val rows = records(page.limit + 1, page.offset, threadId)
        val messages = rows.take(page.limit).reversed()
        if (messages.isEmpty() && page.offset == 0) throw NotFound()
        val values = JSONArray(messages.map(::messageJson))
        return if (!page.paged) values
        else
            JSONObject()
                .put("messages", values)
                .put(
                    "nextOffset",
                    if (rows.size > page.limit) page.offset + page.limit else JSONObject.NULL,
                )
    }

    fun message(rawMessageId: String): JSONObject {
        val messageId = SmsMapper.requireId(rawMessageId)
        return recordById(messageId)?.let(::messageJson) ?: throw NotFound()
    }

    @Suppress("DEPRECATION")
    fun send(address: String, body: String): JSONObject {
        requirePermission(Manifest.permission.SEND_SMS)
        if (address.isBlank() || address.length > 100 || body.isBlank() || body.length > 2_000)
            throw InvalidInput()
        val manager = SmsManager.getDefault()
        val parts = manager.divideMessage(body)
        val messageUri = persistOutgoing(address, body)
        val messageId =
            messageUri.lastPathSegment
                ?: throw IllegalStateException("SMS provider returned no identifier")
        SmsSendTracker.start(context, messageId, parts.size)
        val callbacks =
            ArrayList(parts.indices.map { part -> sentCallback(messageId, part, parts.size) })
        try {
            if (parts.size > 1)
                manager.sendMultipartTextMessage(address, null, parts, callbacks, null)
            else manager.sendTextMessage(address, null, body, callbacks.single(), null)
        } catch (error: Exception) {
            SmsSendTracker.failImmediately(context, messageId)
            throw error
        }
        return JSONObject()
            .put("accepted", true)
            .put("id", messageId)
            .put("delivery", SmsDelivery.OUTBOX.apiValue)
    }

    private fun persistOutgoing(address: String, body: String): Uri {
        requireRole()
        val now = System.currentTimeMillis()
        return context.contentResolver.insert(
            Telephony.Sms.Outbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, 0)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                put(Telephony.Sms.ERROR_CODE, 0)
            },
        ) ?: throw IllegalStateException("Unable to store outgoing SMS")
    }

    private fun sentCallback(messageId: String, part: Int, partCount: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            messageId.hashCode() * 31 + part,
            Intent(context, SmsSendResultReceiver::class.java)
                .putExtra(SmsSendResultReceiver.EXTRA_MESSAGE_ID, messageId)
                .putExtra(SmsSendResultReceiver.EXTRA_PART, part)
                .putExtra(SmsSendResultReceiver.EXTRA_PART_COUNT, partCount),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun setRead(rawMessageId: String, rawRead: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val messageId = SmsMapper.requireId(rawMessageId)
        val threadId = threadForMessage(messageId)
        val read = parseRead(rawRead)
        val changed =
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject().put("id", messageId).put("threadId", threadId).put("read", read)
    }

    fun deleteMessage(rawMessageId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val messageId = SmsMapper.requireId(rawMessageId)
        val threadId = threadForMessage(messageId)
        val changed =
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject().put("deleted", true).put("id", messageId).put("threadId", threadId)
    }

    fun setThreadRead(rawThreadId: String, rawRead: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val read = parseRead(rawRead)
        val changed =
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject().put("threadId", threadId).put("read", read).put("count", changed)
    }

    fun deleteThread(rawThreadId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val changed =
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject().put("deleted", true).put("threadId", threadId).put("count", changed)
    }

    /** Reads a bounded provider page, newest first, optionally restricted to one conversation. */
    private fun records(
        limit: Int = 100,
        offset: Int = 0,
        threadId: String? = null,
        messageId: String? = null,
    ): List<SmsRecord> {
        requirePermission(Manifest.permission.READ_SMS)
        val result = mutableListOf<SmsRecord>()
        val projection =
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
            )
        context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                projection,
                when {
                    messageId != null -> "${Telephony.Sms._ID} = ?"
                    threadId != null -> "${Telephony.Sms.THREAD_ID} = ?"
                    else -> null
                },
                (messageId ?: threadId)?.let { arrayOf(it) },
                "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC LIMIT $limit OFFSET $offset",
            )
            ?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadId = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (cursor.moveToNext()) {
                    val delivery = SmsMapper.delivery(cursor.getInt(type)) ?: continue
                    result +=
                        SmsRecord(
                            id = cursor.getString(id),
                            threadId = cursor.getString(threadId),
                            address = cursor.getString(address) ?: "Unknown",
                            body = cursor.getString(body) ?: "",
                            timestamp = cursor.getLong(date),
                            delivery = delivery,
                            read = cursor.getInt(read) != 0,
                        )
                }
            }
        return result
    }

    /** Reads a bounded conversation page and enriches only those threads with SMS provider data. */
    private fun threadRecords(offset: Int, limit: Int): List<SmsThread> {
        requirePermission(Manifest.permission.READ_SMS)
        val result = mutableListOf<SmsThread>()
        context.contentResolver
            .query(
                Telephony.Threads.CONTENT_URI.buildUpon()
                    .appendQueryParameter("simple", "true")
                    .build(),
                arrayOf(Telephony.Threads._ID, Telephony.Threads.DATE),
                null,
                null,
                "${Telephony.Threads.DATE} DESC, ${Telephony.Threads._ID} DESC",
            )
            ?.use { cursor ->
                if (offset > 0) cursor.moveToPosition(offset - 1)
                while (result.size < limit && cursor.moveToNext()) {
                    val threadId = cursor.getString(0)
                    val latest = records(1, 0, threadId).firstOrNull() ?: continue
                    val unread = SmsMapper.unread(unreadCount(threadId))
                    result +=
                        SmsThread(
                            threadId,
                            latest.address,
                            latest.body,
                            latest.timestamp,
                            unread.count,
                            unread.capped,
                            latest.delivery,
                        )
                }
            }
        return result
    }

    private fun unreadCount(threadId: String): Int =
        context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId, Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 10",
            )
            ?.use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count += 1
                count
            } ?: 0

    private fun threadForMessage(messageId: String): String =
        context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId),
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: throw NotFound()

    private fun recordById(messageId: String): SmsRecord? {
        requirePermission(Manifest.permission.READ_SMS)
        return records(1, messageId = messageId).firstOrNull()
    }

    private fun requirePermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw PermissionMissing()
    }

    private fun requireRole() {
        if (!SmsSetupManager.isRoleHeld(context)) throw RoleRequired()
    }

    private fun parseRead(rawRead: String): Boolean =
        when (rawRead) {
            "true" -> true
            "false" -> false
            else -> throw InvalidInput()
        }

    private fun legacyJson(message: SmsRecord) =
        JSONObject()
            .put("id", message.id)
            .put("address", message.address)
            .put("body", message.body)
            .put("date", message.timestamp)
            .put("type", if (message.delivery.incoming) "received" else "sent")
            .put("delivery", message.delivery.apiValue)

    private fun messageJson(message: SmsRecord) =
        JSONObject()
            .put("id", message.id)
            .put("threadId", message.threadId)
            .put("type", "sms")
            .put("sender", if (message.delivery.incoming) message.address else JSONObject.NULL)
            .put("receiver", if (message.delivery.incoming) JSONObject.NULL else message.address)
            .put("body", message.body)
            .put("timestamp", message.timestamp)
            .put("read", message.read)
            .put("delivery", message.delivery.apiValue)

    private fun threadJson(thread: SmsThread) =
        JSONObject()
            .put("id", thread.id)
            .put("participants", JSONArray().put(thread.participant))
            .put("body", thread.body)
            .put("timestamp", thread.timestamp)
            .put("unreadCount", thread.unreadCount)
            .put("unreadCountCapped", thread.unreadCountCapped)
            .put("lastMessageType", "sms")
            .put("lastMessageDelivery", thread.lastMessageDelivery.apiValue)

    private companion object {
        const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"
    }
}

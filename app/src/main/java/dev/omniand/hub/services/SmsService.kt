package dev.omniand.hub.services

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Telephony
import android.telephony.CarrierConfigManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import dev.omniand.hub.sms.MmsDownloadCoordinator
import dev.omniand.hub.sms.MmsOutgoing
import dev.omniand.hub.sms.MmsUploadStore
import dev.omniand.hub.sms.SmsEventBroadcaster
import dev.omniand.hub.sms.SmsSendResultReceiver
import dev.omniand.hub.sms.SmsSendTracker
import dev.omniand.hub.sms.SmsSetupManager
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

interface MessageRecord {
    val id: String
    val threadId: String
    val address: String
    val body: String
    val timestamp: Long
    val delivery: SmsDelivery
    val read: Boolean
}

data class SmsRecord(
    override val id: String,
    override val threadId: String,
    override val address: String,
    override val body: String,
    override val timestamp: Long,
    override val delivery: SmsDelivery,
    override val read: Boolean,
) : MessageRecord

data class MmsAttachment(val id: String, val mime: String, val name: String)

data class MmsRecord(
    override val id: String,
    override val threadId: String,
    override val address: String,
    override val body: String,
    override val timestamp: Long,
    override val delivery: SmsDelivery,
    override val read: Boolean,
    val subject: String?,
    val state: String,
    val attachments: List<MmsAttachment>,
) : MessageRecord

data class SmsThread(
    val id: String,
    val participant: String,
    val body: String,
    val timestamp: Long,
    val unreadCount: Int,
    val unreadCountCapped: Boolean,
    val lastMessageDelivery: SmsDelivery,
    val lastMessageType: String = "sms",
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

    class MmsUnavailable(val code: String) : Exception()

    data class Part(val bytes: ByteArray, val mime: String, val name: String, val inline: Boolean)

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
        val sourceLimit = page.offset + page.limit + 1
        val rows =
            (records(sourceLimit, 0, threadId) + mmsRecords(sourceLimit, threadId = threadId))
                .sortedWith(
                    compareByDescending<MessageRecord> { it.timestamp }.thenByDescending { it.id }
                )
                .drop(page.offset)
                .take(page.limit + 1)
        val messages = rows.take(page.limit).reversed()
        if (messages.isEmpty() && page.offset == 0) throw NotFound()
        val values = JSONArray(messages.map(::unifiedJson))
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
        if (rawMessageId.startsWith("mms:")) {
            val id = SmsMapper.requireId(rawMessageId.removePrefix("mms:"))
            return mmsRecords(messageId = id).firstOrNull()?.let(::mmsJson) ?: throw NotFound()
        }
        val messageId = SmsMapper.requireId(rawMessageId)
        return recordById(messageId)?.let(::messageJson) ?: throw NotFound()
    }

    fun part(rawMessageId: String, rawPartId: String): Part {
        if (!rawMessageId.startsWith("mms:")) throw InvalidId()
        val messageId = SmsMapper.requireId(rawMessageId.removePrefix("mms:"))
        val partId = SmsMapper.requireId(rawPartId)
        requirePermission(Manifest.permission.READ_SMS)
        val metadata =
            context.contentResolver
                .query(
                    Uri.parse("content://mms/part/$partId"),
                    arrayOf("mid", "ct", "name", "fn", "text"),
                    null,
                    null,
                    null,
                )
                ?.use {
                    if (!it.moveToFirst() || it.getString(0) != messageId) null
                    else arrayOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
                } ?: throw NotFound()
        val mime = metadata[0] ?: "application/octet-stream"
        val bytes =
            if (mime.startsWith("text/")) metadata[3].orEmpty().toByteArray()
            else
                context.contentResolver
                    .openInputStream(Uri.parse("content://mms/part/$partId"))
                    ?.use { it.readBytes() } ?: throw NotFound()
        if (bytes.size > MmsUploadStore.MAX_SIZE) throw InvalidInput()
        val inline =
            mime.matches(
                Regex(
                    "^(text/plain|image/(jpeg|png|gif|webp)|audio/(mpeg|ogg|wav)|video/(mp4|webm))$",
                    RegexOption.IGNORE_CASE,
                )
            )
        return Part(bytes, mime, sanitizeName(metadata[2] ?: metadata[1] ?: "attachment"), inline)
    }

    fun retryDownload(rawMessageId: String): JSONObject {
        if (!rawMessageId.startsWith("mms:")) throw InvalidId()
        val providerId = SmsMapper.requireId(rawMessageId.removePrefix("mms:"))
        val entry =
            MmsDownloadCoordinator.journal(context).entries().firstOrNull {
                it.optString("providerId") == providerId
            } ?: throw NotFound()
        MmsDownloadCoordinator.journal(context)
            .update(entry.getString("id"), dev.omniand.hub.sms.MmsJournalState.NOTIFIED)
        MmsDownloadCoordinator.schedule(context, entry.getString("id"), 0)
        SmsEventBroadcaster.publish("delivery", rawMessageId)
        return JSONObject().put("scheduled", true).put("id", rawMessageId)
    }

    private fun sanitizeName(value: String) =
        value.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(128).ifBlank { "attachment" }

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

    fun send(
        address: String,
        body: String,
        subject: String?,
        uploadIds: List<String>,
        owner: String,
    ): JSONObject {
        val smsManager = SmsManager.getDefault()
        val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val carrier =
            runCatching {
                    context
                        .getSystemService(CarrierConfigManager::class.java)
                        .getConfigForSubId(subscriptionId)
                }
                .getOrNull() ?: PersistableBundle()
        val segmentThreshold =
            carrier?.getInt(CarrierConfigManager.KEY_MMS_SMS_TO_MMS_TEXT_THRESHOLD_INT, -1) ?: -1
        val lengthThreshold =
            carrier?.getInt(CarrierConfigManager.KEY_MMS_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_INT, -1)
                ?: -1
        val thresholdMms =
            (segmentThreshold > 0 && smsManager.divideMessage(body).size >= segmentThreshold) ||
                (lengthThreshold > 0 && body.length > lengthThreshold)
        if (subject.isNullOrBlank() && uploadIds.isEmpty() && !thresholdMms)
            return send(address, body)
        requirePermission(Manifest.permission.SEND_SMS)
        requirePermission(Manifest.permission.READ_PHONE_STATE)
        requireRole()
        if (uploadIds.size > 10 || uploadIds.toSet().size != uploadIds.size) throw InvalidInput()
        if (carrier?.getBoolean(CarrierConfigManager.KEY_MMS_MMS_ENABLED_BOOL, true) == false)
            throw MmsUnavailable("mms-disabled")
        val subjectLimit =
            carrier
                ?.getInt(CarrierConfigManager.KEY_MMS_SUBJECT_MAX_LENGTH_INT, 40)
                ?.coerceAtLeast(1) ?: 40
        if (subject != null && subject.length > subjectLimit)
            throw MmsUnavailable("mms-subject-too-long")
        val store = MmsUploadStore(context)
        val uploads =
            try {
                store.resolve(owner, uploadIds)
            } catch (_: MmsUploadStore.Invalid) {
                throw InvalidInput()
            }
        val accepted =
            try {
                MmsOutgoing.send(
                    context,
                    address,
                    body,
                    subject,
                    uploads,
                    carrier
                        .getInt(
                            CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT,
                            MmsUploadStore.MAX_SIZE.toInt(),
                        )
                        .toLong(),
                    carrier.getInt(CarrierConfigManager.KEY_MMS_MAX_IMAGE_WIDTH_INT, 4_096),
                    carrier.getInt(CarrierConfigManager.KEY_MMS_MAX_IMAGE_HEIGHT_INT, 4_096),
                    subscriptionId,
                )
            } catch (_: IllegalArgumentException) {
                throw InvalidInput()
            }
        store.consume(owner, uploadIds)
        return JSONObject()
            .put("accepted", true)
            .put("id", "mms:${accepted.providerId}")
            .put("type", "mms")
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
        val mms = rawMessageId.startsWith("mms:")
        val messageId =
            SmsMapper.requireId(if (mms) rawMessageId.removePrefix("mms:") else rawMessageId)
        val threadId =
            if (mms) mmsRecords(messageId = messageId).firstOrNull()?.threadId ?: throw NotFound()
            else threadForMessage(messageId)
        val read = parseRead(rawRead)
        val changed =
            context.contentResolver.update(
                if (mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
                "_id = ?",
                arrayOf(messageId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject()
            .put("id", if (mms) "mms:$messageId" else messageId)
            .put("threadId", threadId)
            .put("read", read)
    }

    fun deleteMessage(rawMessageId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val mms = rawMessageId.startsWith("mms:")
        val messageId =
            SmsMapper.requireId(if (mms) rawMessageId.removePrefix("mms:") else rawMessageId)
        val threadId =
            if (mms) mmsRecords(messageId = messageId).firstOrNull()?.threadId ?: throw NotFound()
            else threadForMessage(messageId)
        val changed =
            context.contentResolver.delete(
                if (mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
                "_id = ?",
                arrayOf(messageId),
            )
        if (changed == 0) throw NotFound()
        return JSONObject()
            .put("deleted", true)
            .put("id", if (mms) "mms:$messageId" else messageId)
            .put("threadId", threadId)
    }

    fun setThreadRead(rawThreadId: String, rawRead: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val read = parseRead(rawRead)
        val smsChanged =
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) },
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        val mmsChanged =
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Mms.READ, if (read) 1 else 0) },
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        if (smsChanged + mmsChanged == 0) throw NotFound()
        return JSONObject()
            .put("threadId", threadId)
            .put("read", read)
            .put("count", smsChanged + mmsChanged)
    }

    fun deleteThread(rawThreadId: String): JSONObject {
        requireRole()
        requirePermission(WRITE_SMS_PERMISSION)
        val threadId = SmsMapper.requireId(rawThreadId)
        val smsChanged =
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        val mmsChanged =
            context.contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId),
            )
        if (smsChanged + mmsChanged == 0) throw NotFound()
        return JSONObject()
            .put("deleted", true)
            .put("threadId", threadId)
            .put("count", smsChanged + mmsChanged)
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
                    val latest =
                        (records(1, 0, threadId) + mmsRecords(threadId = threadId)).maxWithOrNull(
                            compareBy<MessageRecord> { it.timestamp }.thenBy { it.id }
                        ) ?: continue
                    val unread = SmsMapper.unread(unreadCount(threadId) + unreadMmsCount(threadId))
                    result +=
                        SmsThread(
                            threadId,
                            latest.address,
                            latest.body,
                            latest.timestamp,
                            unread.count,
                            unread.capped,
                            latest.delivery,
                            if (latest is MmsRecord) "mms" else "sms",
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

    private fun mmsRecords(
        limit: Int = 101,
        threadId: String? = null,
        messageId: String? = null,
    ): List<MmsRecord> {
        requirePermission(Manifest.permission.READ_SMS)
        val result = mutableListOf<MmsRecord>()
        context.contentResolver
            .query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id", "thread_id", "date", "read", "msg_box", "m_type", "sub"),
                when {
                    messageId != null -> "_id = ?"
                    threadId != null -> "thread_id = ?"
                    else -> null
                },
                (messageId ?: threadId)?.let { arrayOf(it) },
                "date DESC LIMIT $limit",
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val box = cursor.getInt(4)
                    val delivery =
                        when (box) {
                            1 -> SmsDelivery.RECEIVED
                            2 -> SmsDelivery.SENT
                            4 -> SmsDelivery.OUTBOX
                            5 -> SmsDelivery.FAILED
                            else -> SmsDelivery.PENDING
                        }
                    val parts = mmsParts(id)
                    result +=
                        MmsRecord(
                            "mms:$id",
                            cursor.getString(1),
                            mmsAddress(id, delivery.incoming),
                            parts.second,
                            cursor.getLong(2) * 1000,
                            delivery,
                            cursor.getInt(3) != 0,
                            cursor.getString(6),
                            if (cursor.getInt(5) == 130) notificationState(id)
                            else if (delivery == SmsDelivery.FAILED) "failed" else "available",
                            parts.first,
                        )
                }
            }
        return result
    }

    private fun notificationState(providerId: String): String {
        val state =
            MmsDownloadCoordinator.journal(context)
                .entries()
                .firstOrNull { it.optString("providerId") == providerId }
                ?.optString("state")
        return if (state == "FAILED" || state == "QUARANTINED") "failed" else "downloading"
    }

    private fun mmsAddress(id: String, incoming: Boolean): String =
        context.contentResolver
            .query(
                Uri.parse("content://mms/$id/addr"),
                arrayOf("address"),
                "type = ?",
                arrayOf(if (incoming) "137" else "151"),
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Unknown"

    private fun mmsParts(id: String): Pair<List<MmsAttachment>, String> {
        val attachments = mutableListOf<MmsAttachment>()
        val text = mutableListOf<String>()
        context.contentResolver
            .query(
                Uri.parse("content://mms/$id/part"),
                arrayOf("_id", "ct", "name", "fn", "text"),
                null,
                null,
                "seq ASC",
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(1) ?: "application/octet-stream"
                    if (mime.startsWith("text/")) cursor.getString(4)?.let(text::add)
                    else
                        attachments +=
                            MmsAttachment(
                                cursor.getString(0),
                                mime,
                                cursor.getString(3) ?: cursor.getString(2) ?: "attachment",
                            )
                }
            }
        return attachments to text.joinToString("\n")
    }

    private fun unreadMmsCount(threadId: String): Int =
        context.contentResolver
            .query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id"),
                "thread_id = ? AND msg_box = 1 AND read = 0",
                arrayOf(threadId),
                "date DESC LIMIT 10",
            )
            ?.use { it.count } ?: 0

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

    private fun unifiedJson(message: MessageRecord) =
        if (message is MmsRecord) mmsJson(message) else messageJson(message as SmsRecord)

    private fun mmsJson(message: MmsRecord) =
        JSONObject()
            .put("id", message.id)
            .put("threadId", message.threadId)
            .put("type", "mms")
            .put("sender", if (message.delivery.incoming) message.address else JSONObject.NULL)
            .put("receiver", if (message.delivery.incoming) JSONObject.NULL else message.address)
            .put("body", message.body)
            .put("subject", message.subject ?: JSONObject.NULL)
            .put("timestamp", message.timestamp)
            .put("read", message.read)
            .put("delivery", message.delivery.apiValue)
            .put("mmsState", message.state)
            .put(
                "attachments",
                JSONArray(
                    message.attachments.map { attachment ->
                        JSONObject()
                            .put("id", attachment.id)
                            .put("mime", attachment.mime)
                            .put("name", attachment.name)
                            .put("url", "/api/sms/messages/${message.id}/parts/${attachment.id}")
                    }
                ),
            )

    private fun threadJson(thread: SmsThread) =
        JSONObject()
            .put("id", thread.id)
            .put("participants", JSONArray().put(thread.participant))
            .put("body", thread.body)
            .put("timestamp", thread.timestamp)
            .put("unreadCount", thread.unreadCount)
            .put("unreadCountCapped", thread.unreadCountCapped)
            .put("lastMessageType", thread.lastMessageType)
            .put("lastMessageDelivery", thread.lastMessageDelivery.apiValue)

    private companion object {
        const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"
    }
}

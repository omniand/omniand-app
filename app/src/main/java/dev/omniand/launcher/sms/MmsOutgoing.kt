package dev.omniand.launcher.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu.EncodedStringValue
import com.google.android.mms.pdu.PduBody
import com.google.android.mms.pdu.PduComposer
import com.google.android.mms.pdu.PduParser
import com.google.android.mms.pdu.PduPart
import com.google.android.mms.pdu.SendConf
import com.google.android.mms.pdu.SendReq
import java.io.File

/** Composes, provider-persists, and submits one-recipient MMS messages to carrier transport. */
object MmsOutgoing {
    data class Accepted(val providerId: String)

    fun send(
        context: Context,
        address: String,
        body: String,
        subject: String?,
        uploads: List<MmsUploadStore.Completed>,
        maxMessageSize: Long = MmsUploadStore.MAX_SIZE,
        maxImageWidth: Int = 4_096,
        maxImageHeight: Int = 4_096,
        subscriptionId: Int = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId(),
    ): Accepted {
        require(address.isNotBlank() && address.length <= 100)
        require(
            body.length <= 2_000 &&
                (body.isNotBlank() || !subject.isNullOrBlank() || uploads.isNotEmpty())
        )
        require(uploads.sumOf { it.size } + body.toByteArray().size <= MmsUploadStore.MAX_SIZE)
        val request =
            SendReq().apply {
                setTo(arrayOf(EncodedStringValue(address)))
                if (!subject.isNullOrBlank()) setSubject(EncodedStringValue(subject))
                setBody(
                    PduBody().apply {
                        if (body.isNotBlank())
                            addPart(part("text/plain", "text.txt", body.toByteArray()))
                        uploads.forEach { upload ->
                            require(
                                upload.mime.matches(
                                    Regex(
                                        "^(text/plain|image/(jpeg|png|gif|webp)|audio/[a-z0-9.+-]+|video/[a-z0-9.+-]+)$"
                                    )
                                )
                            )
                            addPart(
                                part(
                                    upload.mime,
                                    upload.name,
                                    prepare(upload, maxImageWidth, maxImageHeight),
                                )
                            )
                        }
                    }
                )
            }
        val encoded = PduComposer(context, request).make() ?: error("Unable to compose MMS PDU")
        require(encoded.size <= minOf(maxMessageSize, MmsUploadStore.MAX_SIZE))
        val providerId = MmsProviderAdapter(context).persistOutgoing(request, address)
        val file = File(context.cacheDir, "mms-transport/send-$providerId.pdu")
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(encoded)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val callback =
                PendingIntent.getBroadcast(
                    context,
                    providerId.hashCode(),
                    Intent(context, MmsSendResultReceiver::class.java)
                        .putExtra(EXTRA_ID, providerId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                .sendMultimediaMessage(context, uri, null, null, callback)
            return Accepted(providerId)
        } catch (error: Exception) {
            MmsProviderAdapter(context).setOutgoingResult(providerId, false)
            file.delete()
            throw error
        }
    }

    private fun part(mime: String, name: String, bytes: ByteArray) =
        PduPart().apply {
            contentType = mime.toByteArray(Charsets.US_ASCII)
            this.name = name.take(128).toByteArray(Charsets.UTF_8)
            contentId = "<${name.hashCode()}>".toByteArray(Charsets.US_ASCII)
            data = bytes
        }

    private fun prepare(
        upload: MmsUploadStore.Completed,
        maxWidth: Int,
        maxHeight: Int,
    ): ByteArray {
        val original = upload.file.readBytes()
        if (!upload.mime.matches(Regex("^image/(jpeg|png|webp)$"))) return original
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= maxWidth && bounds.outHeight <= maxHeight) return original
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return original
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        val resized =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        return java.io.ByteArrayOutputStream().use { output ->
            resized.compress(
                if (upload.mime == "image/png") Bitmap.CompressFormat.PNG
                else Bitmap.CompressFormat.JPEG,
                85,
                output,
            )
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
            output.toByteArray()
        }
    }

    const val EXTRA_ID = "mmsProviderId"
}

class MmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(MmsOutgoing.EXTRA_ID) ?: return
        val sent = resultCode == Activity.RESULT_OK
        val messageId =
            intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let { bytes ->
                runCatching {
                        (PduParser(bytes).parse() as? SendConf)
                            ?.messageId
                            ?.toString(Charsets.ISO_8859_1)
                    }
                    .getOrNull()
            }
        MmsProviderAdapter(context).setOutgoingResult(id, sent, messageId)
        File(context.cacheDir, "mms-transport/send-$id.pdu").delete()
        SmsEventBroadcaster.publish("delivery", "mms:$id")
    }
}

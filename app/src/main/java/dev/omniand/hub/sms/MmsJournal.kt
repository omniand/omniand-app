package dev.omniand.hub.sms

import com.google.android.mms.pdu.DeliveryInd
import com.google.android.mms.pdu.GenericPdu
import com.google.android.mms.pdu.NotificationInd
import com.google.android.mms.pdu.PduParser
import com.google.android.mms.pdu.ReadOrigInd
import com.google.android.mms.pdu.RetrieveConf
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

enum class MmsJournalState {
    NOTIFIED,
    DOWNLOADING,
    RETRY,
    COMPLETE,
    FAILED,
    QUARANTINED,
}

data class MmsEnvelope(
    val kind: String,
    val transactionId: String?,
    val contentLocation: String?,
    val messageId: String?,
    val expiryMillis: Long?,
    val sender: String?,
    val subject: String?,
)

/** Parses only identities needed before provider persistence or carrier retrieval. */
object MmsPduCodec {
    fun parse(bytes: ByteArray): Pair<GenericPdu, MmsEnvelope> {
        val pdu = PduParser(bytes).parse() ?: throw IllegalArgumentException("Malformed MMS PDU")
        val envelope =
            when (pdu) {
                is NotificationInd ->
                    MmsEnvelope(
                        "notification",
                        pdu.transactionId.text(),
                        pdu.contentLocation.text(),
                        null,
                        pdu.expiry.takeIf { it > 0 }?.times(1000),
                        pdu.from?.string,
                        pdu.subject?.string,
                    )
                is RetrieveConf ->
                    MmsEnvelope(
                        "retrieve",
                        pdu.transactionId.text(),
                        null,
                        pdu.messageId.text(),
                        null,
                        pdu.from?.string,
                        pdu.subject?.string,
                    )
                is DeliveryInd ->
                    MmsEnvelope("delivery", null, null, pdu.messageId.text(), null, null, null)
                is ReadOrigInd ->
                    MmsEnvelope(
                        "read",
                        null,
                        null,
                        pdu.messageId.text(),
                        null,
                        pdu.from?.string,
                        null,
                    )
                else -> MmsEnvelope("other", null, null, null, null, null, null)
            }
        return pdu to envelope
    }

    private fun ByteArray?.text() = this?.toString(Charsets.ISO_8859_1)
}

/** Atomic, deduplicated MMS state journal with migration from legacy subscription-prefixed PDUs. */
class MmsJournal(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private var migrating = false

    init {
        check(root.isDirectory || root.mkdirs())
    }

    @Synchronized
    fun record(subscriptionId: Int, bytes: ByteArray): JSONObject {
        if (!migrating) migrateLegacy()
        val envelope =
            try {
                MmsPduCodec.parse(bytes).second
            } catch (_: Exception) {
                val id =
                    sha256("$subscriptionId\u0000malformed\u0000${sha256(bytes)}".toByteArray())
                read(id)?.let {
                    return it
                }
                writePayload(id, bytes)
                return JSONObject()
                    .put("version", VERSION)
                    .put("id", id)
                    .put("subscriptionId", subscriptionId)
                    .put("kind", "malformed")
                    .put("created", now())
                    .put("expiry", now())
                    .put("attempts", 0)
                    .put("nextAttempt", 0)
                    .put("state", MmsJournalState.QUARANTINED.name)
                    .put("error", "malformed-pdu")
                    .put("providerId", JSONObject.NULL)
                    .also { writeState(id, it) }
            }
        val identity =
            envelope.contentLocation
                ?: envelope.transactionId
                ?: envelope.messageId
                ?: sha256(bytes)
        val id = sha256("$subscriptionId\u0000${envelope.kind}\u0000$identity".toByteArray())
        read(id)?.let {
            return it
        }
        val created = now()
        val expiry =
            minOf(envelope.expiryMillis ?: created + MAX_RETRY_AGE, created + MAX_RETRY_AGE)
        writePayload(id, bytes)
        return JSONObject()
            .put("version", VERSION)
            .put("id", id)
            .put("subscriptionId", subscriptionId)
            .put("kind", envelope.kind)
            .put("transactionId", envelope.transactionId)
            .put("contentLocation", envelope.contentLocation)
            .put("messageId", envelope.messageId)
            .put("sender", envelope.sender)
            .put("subject", envelope.subject)
            .put("created", created)
            .put("expiry", expiry)
            .put("attempts", 0)
            .put("nextAttempt", created)
            .put("state", MmsJournalState.NOTIFIED.name)
            .put("error", JSONObject.NULL)
            .put("providerId", JSONObject.NULL)
            .also { writeState(id, it) }
    }

    @Synchronized
    fun update(
        id: String,
        state: MmsJournalState,
        error: String? = null,
        providerId: String? = null,
    ): JSONObject {
        val value = read(id) ?: throw IllegalArgumentException("Unknown MMS journal entry")
        value.put("state", state.name).put("error", error)
        if (providerId != null) value.put("providerId", providerId)
        if (state == MmsJournalState.RETRY) {
            val attempts = value.getInt("attempts") + 1
            value.put("attempts", attempts)
            value.put("nextAttempt", now() + retryDelay(attempts))
        }
        writeState(id, value)
        if (state == MmsJournalState.COMPLETE) payload(id).delete()
        return value
    }

    fun read(id: String): JSONObject? = runCatching { JSONObject(state(id).readText()) }.getOrNull()

    fun entries(): List<JSONObject> =
        root
            .listFiles { file -> file.extension == "json" }
            ?.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
            .orEmpty()

    fun payload(id: String) = File(root, "$id.pdu")

    @Synchronized
    fun cleanup() {
        val cutoff = now() - QUARANTINE_AGE
        root
            .listFiles { file -> file.extension == "json" }
            ?.forEach { file ->
                val value = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (
                    value == null ||
                        (value.optString("state") in setOf("FAILED", "QUARANTINED") &&
                            value.optLong("created") < cutoff)
                ) {
                    file.delete()
                    payload(file.nameWithoutExtension).delete()
                }
            }
    }

    private fun migrateLegacy() {
        migrating = true
        try {
            root
                .listFiles { file ->
                    file.extension == "pdu" && !state(file.nameWithoutExtension).exists()
                }
                ?.filter { !it.nameWithoutExtension.matches(Regex("[0-9a-f]{64}")) }
                ?.forEach { file ->
                    val bytes = file.readBytes()
                    val newline = bytes.indexOf('\n'.code.toByte())
                    if (newline <= 0) return@forEach
                    val subscription =
                        bytes.copyOfRange(0, newline).toString(Charsets.US_ASCII).toIntOrNull()
                            ?: -1
                    runCatching { record(subscription, bytes.copyOfRange(newline + 1, bytes.size)) }
                        .onSuccess { file.delete() }
                }
        } finally {
            migrating = false
        }
    }

    private fun writePayload(id: String, bytes: ByteArray) = atomic(payload(id), bytes)

    private fun writeState(id: String, value: JSONObject) =
        atomic(state(id), value.toString().toByteArray())

    private fun state(id: String) = File(root, "$id.json")

    private fun atomic(target: File, bytes: ByteArray) {
        val temporary = File(root, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(target))
    }

    private fun retryDelay(attempt: Int) =
        minOf(30_000L shl (attempt - 1).coerceAtMost(10), 6 * 60 * 60 * 1000L)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = 1
        const val MAX_RETRY_AGE = 7L * 24 * 60 * 60 * 1000
        const val QUARANTINE_AGE = 30L * 24 * 60 * 60 * 1000
    }
}

package dev.omniand.hub.sms

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Owns bounded, sequential attachment uploads used by body-less local WebView requests. */
class MmsUploadStore(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Invalid(val code: String) : Exception(code)

    data class Completed(
        val id: String,
        val file: File,
        val name: String,
        val mime: String,
        val size: Long,
    )

    private val root = File(context.noBackupFilesDir, "mms-uploads")

    @Synchronized
    fun create(owner: String, name: String, mime: String, size: Long, sha256: String): JSONObject {
        cleanup()
        if (name.isBlank() || name.length > 128 || mime.length > 100 || size !in 1..MAX_SIZE)
            throw Invalid("invalid-upload")
        if (!sha256.matches(Regex("[0-9a-fA-F]{64}"))) throw Invalid("invalid-upload")
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        metadata(id)
            .writeText(
                JSONObject()
                    .put("owner", owner)
                    .put("name", name)
                    .put("mime", mime.lowercase())
                    .put("size", size)
                    .put("sha256", sha256.lowercase())
                    .put("created", now())
                    .put("next", 0)
                    .put("complete", false)
                    .toString()
            )
        return JSONObject().put("id", id).put("chunkSize", CHUNK_SIZE)
    }

    @Synchronized
    fun append(owner: String, id: String, index: Int, bytes: ByteArray): JSONObject {
        val state = state(owner, id)
        if (state.getBoolean("complete") || index != state.getInt("next"))
            throw Invalid("upload-chunk-order")
        if (bytes.isEmpty() || bytes.size > CHUNK_SIZE) throw Invalid("invalid-upload-chunk")
        val payload = payload(id)
        if (payload.length() + bytes.size > state.getLong("size")) throw Invalid("upload-too-large")
        FileOutputStream(payload, true).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        state.put("next", index + 1)
        metadata(id).writeText(state.toString())
        return JSONObject().put("received", payload.length()).put("next", index + 1)
    }

    @Synchronized
    fun complete(owner: String, id: String): JSONObject {
        val state = state(owner, id)
        val payload = payload(id)
        if (payload.length() != state.getLong("size")) throw Invalid("upload-size-mismatch")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.readBytes()).toHex()
        if (digest != state.getString("sha256")) throw Invalid("upload-digest-mismatch")
        state.put("complete", true)
        metadata(id).writeText(state.toString())
        return JSONObject().put("id", id).put("complete", true)
    }

    @Synchronized
    fun abort(owner: String, id: String): JSONObject {
        state(owner, id)
        metadata(id).delete()
        payload(id).delete()
        return JSONObject().put("aborted", true)
    }

    @Synchronized
    fun resolve(owner: String, ids: List<String>): List<Completed> = ids.map { id ->
        val state = state(owner, id)
        if (!state.getBoolean("complete")) throw Invalid("upload-incomplete")
        Completed(
            id,
            payload(id),
            state.getString("name"),
            state.getString("mime"),
            state.getLong("size"),
        )
    }

    @Synchronized
    fun consume(owner: String, ids: List<String>) {
        ids.forEach { id ->
            state(owner, id)
            metadata(id).delete()
            payload(id).delete()
        }
    }

    @Synchronized
    fun cleanup() {
        if (!root.isDirectory) return
        root
            .listFiles { file -> file.extension == "json" }
            ?.forEach { file ->
                val state = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (state == null || now() - state.optLong("created") >= MAX_AGE) {
                    file.delete()
                    File(root, "${file.nameWithoutExtension}.bin").delete()
                }
            }
    }

    private fun state(owner: String, id: String): JSONObject {
        if (!id.matches(Regex("[0-9a-f-]{36}"))) throw Invalid("upload-not-found")
        val file = metadata(id)
        val state =
            runCatching { JSONObject(file.readText()) }.getOrNull()
                ?: throw Invalid("upload-not-found")
        if (state.getString("owner") != owner || now() - state.getLong("created") >= MAX_AGE)
            throw Invalid("upload-not-found")
        return state
    }

    private fun metadata(id: String) = File(root, "$id.json")

    private fun payload(id: String) = File(root, "$id.bin")

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val CHUNK_SIZE = 24 * 1024
        const val MAX_SIZE = 10L * 1024 * 1024
        const val MAX_AGE = 60L * 60 * 1000
    }
}

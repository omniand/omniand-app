package dev.omniand.hub.sms

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Owns completed, owner-bound MMS attachments received through one multipart request. */
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

    /** Moves a verified temporary multipart payload into owner-bound attachment staging. */
    @Synchronized
    fun stage(owner: String, name: String, mime: String, sha256: String, file: File): JSONObject {
        cleanup()
        val size = file.length()
        if (
            owner.isBlank() ||
                name.isBlank() ||
                name.length > 128 ||
                mime.length > 100 ||
                size !in 1..MAX_SIZE ||
                !sha256.matches(Regex("[0-9a-fA-F]{64}"))
        )
            throw Invalid("invalid-upload")
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHex()
        if (digest != sha256.lowercase()) throw Invalid("upload-digest-mismatch")
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        return try {
            if (!file.renameTo(payload(id))) {
                file.copyTo(payload(id), overwrite = true)
                file.delete()
            }
            metadata(id)
                .writeText(
                    JSONObject()
                        .put("owner", owner)
                        .put("name", name)
                        .put("mime", mime.lowercase())
                        .put("size", size)
                        .put("sha256", sha256.lowercase())
                        .put("created", now())
                        .put("complete", true)
                        .toString()
                )
            JSONObject().put("id", id).put("complete", true)
        } catch (error: Exception) {
            metadata(id).delete()
            payload(id).delete()
            throw error
        } finally {
            file.delete()
        }
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
        const val MAX_SIZE = 10L * 1024 * 1024
        const val MAX_AGE = 60L * 60 * 1000
    }
}

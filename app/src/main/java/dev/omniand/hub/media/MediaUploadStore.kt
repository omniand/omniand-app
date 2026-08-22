package dev.omniand.hub.media

import android.content.Context
import dev.omniand.hub.services.MediaService
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/** Maintains owner-bound sequential upload sessions and removes expired staging files. */
class MediaUploadStore(private val context: Context) {
    class Invalid(val code: String) : Exception(code)

    private val root = File(context.cacheDir, "media-uploads").apply { mkdirs() }

    @Synchronized
    fun create(owner: String, name: String, mime: String, size: Long, sha256: String): JSONObject {
        cleanup()
        if (
            name.isBlank() ||
                name.length > 255 ||
                mime.length > 100 ||
                size !in 1..MAX_FILE ||
                !sha256.matches(Regex("[a-fA-F0-9]{64}"))
        )
            throw Invalid("invalid-upload")
        val sessions =
            root
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".json") }
                .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
                .filter { it.optString("owner") == owner }
        if (sessions.size >= MAX_FILES) throw Invalid("file-count-limit")
        val active = sessions.sumOf { it.optLong("size") }
        if (active + size > MAX_ACTIVE) throw Invalid("staging-limit")
        val id = UUID.randomUUID().toString()
        metadata(id)
            .writeText(
                JSONObject()
                    .put("owner", owner)
                    .put("name", name)
                    .put("mime", mime)
                    .put("size", size)
                    .put("sha256", sha256.lowercase())
                    .put("offset", 0)
                    .put("expires", System.currentTimeMillis() + EXPIRY)
                    .toString()
            )
        data(id).createNewFile()
        return JSONObject().put("id", id).put("offset", 0)
    }

    @Synchronized
    fun append(owner: String, id: String, offset: Long, bytes: ByteArray): JSONObject {
        val info = read(owner, id)
        if (
            offset != info.getLong("offset") ||
                bytes.isEmpty() ||
                bytes.size > MAX_CHUNK ||
                offset + bytes.size > info.getLong("size")
        )
            throw Invalid("invalid-upload-chunk")
        RandomAccessFile(data(id), "rw").use { file ->
            file.seek(offset)
            file.write(bytes)
        }
        info.put("offset", offset + bytes.size)
        metadata(id).writeText(info.toString())
        return JSONObject().put("id", id).put("offset", info.getLong("offset"))
    }

    @Synchronized
    fun complete(owner: String, id: String): JSONObject {
        val info = read(owner, id)
        val file = data(id)
        if (
            file.length() != info.getLong("size") ||
                MediaService.sha256(file) != info.getString("sha256")
        )
            throw Invalid("hash-mismatch")
        return try {
            MediaService(context).publish(file, info.getString("name"), info.getString("mime"))
        } finally {
            remove(id)
        }
    }

    @Synchronized
    fun abort(owner: String, id: String): JSONObject {
        read(owner, id)
        remove(id)
        return JSONObject().put("aborted", true)
    }

    private fun read(owner: String, id: String): JSONObject {
        if (!id.matches(Regex("[a-f0-9-]{36}"))) throw Invalid("invalid-upload")
        val file = metadata(id)
        if (!file.isFile) throw Invalid("upload-not-found")
        val info = JSONObject(file.readText())
        if (info.getString("owner") != owner) throw Invalid("upload-not-found")
        if (info.getLong("expires") < System.currentTimeMillis()) {
            remove(id)
            throw Invalid("upload-expired")
        }
        return info
    }

    private fun cleanup() {
        root
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".json") }
            .forEach { file ->
                runCatching {
                    if (JSONObject(file.readText()).getLong("expires") < System.currentTimeMillis())
                        remove(file.name.removeSuffix(".json"))
                }
            }
    }

    private fun remove(id: String) {
        metadata(id).delete()
        data(id).delete()
    }

    private fun metadata(id: String) = File(root, "$id.json")

    private fun data(id: String) = File(root, "$id.data")

    companion object {
        const val MAX_CHUNK = 256 * 1024
        const val MAX_FILES = 20
        const val MAX_FILE = 500L * 1024 * 1024
        const val MAX_ACTIVE = 1024L * 1024 * 1024
        const val EXPIRY = 60L * 60 * 1000
    }
}

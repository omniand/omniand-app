package dev.omniand.hub.files

import android.content.Context
import dev.omniand.hub.services.FilesService
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Persists owner-bound upload chunks until a client explicitly completes or cancels them. */
object FilesChunkManager {
    private const val MAX_CHUNK = 16L * 1024 * 1024
    private const val MAX_CHUNKS = 2048
    private const val MAX_UPLOAD = FilesService.MAX_UPLOAD_SIZE

    fun create(
        context: Context,
        owner: String,
        parent: String,
        name: String,
        size: Long,
        sha256: String,
        totalChunks: Int,
        chunkSize: Long,
        conflict: String,
    ): JSONObject {
        if (
            size !in 1..MAX_UPLOAD ||
                totalChunks !in 1..MAX_CHUNKS ||
                chunkSize !in 1..MAX_CHUNK ||
                totalChunks.toLong() * chunkSize < size ||
                !sha256.matches(Regex("[0-9a-fA-F]{64}")) ||
                conflict !in setOf("fail", "replace", "keep-both")
        )
            throw FilesService.Invalid("invalid-upload")
        val directory = directory(context)
        val id = UUID.randomUUID().toString()
        val session =
            Session(
                id,
                owner,
                parent,
                name,
                size,
                sha256.lowercase(),
                totalChunks,
                chunkSize,
                conflict,
                directory,
            )
        session.writeMetadata()
        return session.json()
    }

    fun status(context: Context, owner: String, id: String): JSONObject =
        load(context, owner, id).json()

    fun writeChunk(context: Context, owner: String, id: String, index: Int, bytes: ByteArray) {
        val session = load(context, owner, id)
        if (
            index !in 0 until session.totalChunks ||
                bytes.isEmpty() ||
                bytes.size.toLong() > MAX_CHUNK
        )
            throw FilesService.Invalid("invalid-chunk")
        val expected =
            if (index == session.totalChunks - 1) {
                session.size - index * session.chunkSize
            } else session.chunkSize
        if (bytes.size.toLong() != expected) throw FilesService.Invalid("invalid-chunk-size")
        FileOutputStream(session.chunk(index)).use { it.write(bytes) }
        session.received += index
        session.writeMetadata()
    }

    fun complete(context: Context, owner: String, id: String): JSONObject {
        val session = load(context, owner, id)
        if (
            session.received.size != session.totalChunks ||
                (0 until session.totalChunks).any { !session.chunk(it).isFile }
        )
            throw FilesService.Invalid("chunks-incomplete")
        val merged = File(session.directory, ".${session.id}.merged")
        try {
            FileOutputStream(merged).use { output ->
                for (index in 0 until session.totalChunks) session.chunk(index).inputStream().use {
                    it.copyTo(output)
                }
                output.fd.sync()
            }
            val result =
                FilesService(context)
                    .publishUpload(
                        session.parent,
                        session.name,
                        session.sha256,
                        session.conflict,
                        merged,
                    )
            session.delete()
            FilesEventBroadcaster.publish("upload")
            return result
        } finally {
            merged.delete()
        }
    }

    fun delete(context: Context, owner: String, id: String): JSONObject {
        val session = load(context, owner, id)
        session.delete()
        return JSONObject().put("deleted", true).put("id", id)
    }

    private fun load(context: Context, owner: String, id: String): Session {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) throw FilesService.Invalid("upload-not-found")
        val metadata = File(directory(context), "$id.json")
        if (!metadata.isFile) throw FilesService.Invalid("upload-not-found")
        val json =
            runCatching { JSONObject(metadata.readText()) }
                .getOrElse { throw FilesService.Invalid("upload-not-found") }
        if (json.optString("owner") != owner) throw FilesService.Invalid("upload-not-found")
        return Session.from(json, metadata.parentFile!!)
    }

    private fun directory(context: Context) =
        File(context.cacheDir, "file-chunk-uploads").apply { mkdirs() }

    private class Session(
        val id: String,
        val owner: String,
        val parent: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val totalChunks: Int,
        val chunkSize: Long,
        val conflict: String,
        val directory: File,
        val received: MutableSet<Int> = linkedSetOf(),
    ) {
        fun chunk(index: Int) = File(directory, "$id.$index.part")

        fun json() =
            JSONObject()
                .put("id", id)
                .put("size", size)
                .put("totalChunks", totalChunks)
                .put("chunkSize", chunkSize)
                .put("received", JSONArray(received.sorted()))

        fun writeMetadata() {
            JSONObject()
                .put("id", id)
                .put("owner", owner)
                .put("parent", parent)
                .put("name", name)
                .put("size", size)
                .put("sha256", sha256)
                .put("totalChunks", totalChunks)
                .put("chunkSize", chunkSize)
                .put("conflict", conflict)
                .put("received", JSONArray(received.sorted()))
                .also { File(directory, "$id.json").writeText(it.toString()) }
        }

        fun delete() {
            File(directory, "$id.json").delete()
            for (index in 0 until totalChunks) chunk(index).delete()
        }

        companion object {
            fun from(json: JSONObject, directory: File) =
                Session(
                    json.getString("id"),
                    json.getString("owner"),
                    json.getString("parent"),
                    json.getString("name"),
                    json.getLong("size"),
                    json.getString("sha256"),
                    json.getInt("totalChunks"),
                    json.getLong("chunkSize"),
                    json.getString("conflict"),
                    directory,
                    buildSet {
                            val values = json.optJSONArray("received") ?: return@buildSet
                            for (index in 0 until values.length()) add(values.getInt(index))
                        }
                        .toMutableSet(),
                )
        }
    }
}

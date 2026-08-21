package dev.omniand.launcher.services

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import dev.omniand.launcher.media.MediaEventBroadcaster
import dev.omniand.launcher.media.MediaSetupManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Maps opaque IDs to visible MediaStore rows without exposing paths or location metadata. */
class MediaService(private val context: Context) {
    data class Resource(
        val stream: InputStream,
        val length: Long,
        val mime: String,
        val name: String,
        val modified: Long,
    )

    class Invalid(val code: String) : Exception(code)

    fun list(type: String, offset: Int, limit: Int): JSONObject {
        requireRead()
        if (type !in setOf("all", "image", "video") || offset < 0 || limit !in 1..100)
            throw Invalid("invalid-query")
        val rows = query(type, offset, limit + 1)
        val more = rows.size > limit
        return JSONObject()
            .put("items", JSONArray(rows.take(limit)))
            .put("nextOffset", if (more) offset + limit else JSONObject.NULL)
    }

    fun item(id: String): JSONObject {
        requireRead()
        return queryOne(decodeId(id)) ?: throw Invalid("not-found")
    }

    fun thumbnail(id: String): ByteArray {
        requireRead()
        val ref = decodeId(id)
        ensureVisible(ref)
        val bitmap = context.contentResolver.loadThumbnail(uri(ref), Size(512, 512), null)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    }

    fun content(id: String): Resource {
        requireRead()
        val ref = decodeId(id)
        val metadata = queryOne(ref) ?: throw Invalid("not-found")
        val descriptor =
            context.contentResolver.openFileDescriptor(uri(ref), "r") ?: throw Invalid("not-found")
        return Resource(
            ParcelFileDescriptor.AutoCloseInputStream(descriptor),
            metadata.getLong("size"),
            metadata.getString("mimeType"),
            sanitizeName(metadata.getString("name")),
            metadata.getLong("date"),
        )
    }

    /** Deletes independently so the caller receives stable per-row partial-failure results. */
    fun delete(ids: List<String>): JSONObject {
        if (ids.isEmpty() || ids.size > 100) throw Invalid("invalid-batch")
        val results = JSONArray()
        ids.forEach { id ->
            val result = JSONObject().put("id", id)
            try {
                val ref = decodeId(id)
                val row = queryOne(ref) ?: throw Invalid("not-found")
                if (!canDelete(row))
                    throw Invalid(
                        if (Build.VERSION.SDK_INT >= 31) "media-management-required"
                        else "media-management-unavailable"
                    )
                if (context.contentResolver.delete(uri(ref), null, null) != 1)
                    throw Invalid("not-found")
                result.put("deleted", true)
            } catch (error: Invalid) {
                result.put("deleted", false).put("code", error.code)
            } catch (_: SecurityException) {
                result.put("deleted", false).put("code", "android-permission-required")
            }
            results.put(result)
        }
        MediaEventBroadcaster.publish("deleted")
        return JSONObject().put("results", results)
    }

    /** Validates staged bytes, atomically publishes with IS_PENDING, and rolls back failed rows. */
    fun publish(file: File, name: String, claimedMime: String): JSONObject {
        val mime = validateMedia(file, claimedMime)
        val image = mime.startsWith("image/")
        val collection =
            if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeName(name))
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (image) "Pictures" else "Movies") + "/OmniAnd",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
        val target =
            context.contentResolver.insert(collection, values)
                ?: throw Invalid("storage-unavailable")
        try {
            context.contentResolver.openOutputStream(target, "w")!!.use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= 29)
                context.contentResolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            MediaEventBroadcaster.publish("uploaded")
            val row =
                queryOne(
                    Ref(
                        if (image) "image" else "video",
                        MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        ContentUris.parseId(target),
                    )
                )
            return row ?: JSONObject().put("published", true)
        } catch (error: Exception) {
            context.contentResolver.delete(target, null, null)
            throw error
        }
    }

    private fun query(type: String, offset: Int, limit: Int): List<JSONObject> {
        val selection =
            when (type) {
                "image" ->
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
                "video" ->
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
                else ->
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            }
        val queryArguments =
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
        return context.contentResolver
            .query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection(),
                queryArguments,
                null,
            )
            ?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(map(cursor)) }
            }
            .orEmpty()
    }

    private fun queryOne(ref: Ref): JSONObject? =
        context.contentResolver
            .query(
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(ref.volume),
                    ref.row,
                ),
                projection(),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                map(cursor, ref.volume).takeIf { it.getString("type") == ref.type }
            }

    private fun ensureVisible(ref: Ref) {
        if (queryOne(ref) == null) throw Invalid("not-found")
    }

    private fun projection() =
        buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.Files.FileColumns.MEDIA_TYPE)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.MIME_TYPE)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.DATE_ADDED)
                add(MediaStore.Images.Media.DATE_TAKEN)
                add(MediaStore.Video.Media.DURATION)
                if (Build.VERSION.SDK_INT >= 29) {
                    add(MediaStore.MediaColumns.VOLUME_NAME)
                    add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                }
            }
            .toTypedArray()

    private fun map(
        cursor: android.database.Cursor,
        fallbackVolume: String = MediaStore.VOLUME_EXTERNAL,
    ): JSONObject {
        fun long(column: String) =
            cursor
                .getColumnIndex(column)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong) ?: 0L
        fun string(column: String) =
            cursor
                .getColumnIndex(column)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
                .orEmpty()
        val mediaType = long(MediaStore.Files.FileColumns.MEDIA_TYPE).toInt()
        val type =
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image"
        val volume = string(MediaStore.MediaColumns.VOLUME_NAME).ifEmpty { fallbackVolume }
        val taken = long(MediaStore.Images.Media.DATE_TAKEN)
        val date = if (taken > 0) taken else long(MediaStore.MediaColumns.DATE_ADDED) * 1000
        val owner = string(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val relative = string(MediaStore.MediaColumns.RELATIVE_PATH)
        return JSONObject()
            .put("id", encodeId(Ref(type, volume, long(MediaStore.MediaColumns._ID))))
            .put("type", type)
            .put("name", string(MediaStore.MediaColumns.DISPLAY_NAME))
            .put("mimeType", safeMime(string(MediaStore.MediaColumns.MIME_TYPE), type))
            .put("width", long(MediaStore.MediaColumns.WIDTH))
            .put("height", long(MediaStore.MediaColumns.HEIGHT))
            .put("duration", long(MediaStore.Video.Media.DURATION))
            .put("size", long(MediaStore.MediaColumns.SIZE))
            .put("date", date)
            .put("owned", owner == context.packageName || relative.endsWith("/OmniAnd/"))
    }

    private fun canDelete(row: JSONObject) =
        row.getBoolean("owned") || MediaSetupManager.canManage(context)

    private fun requireRead() {
        val granted =
            if (Build.VERSION.SDK_INT >= 33)
                listOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        if (Build.VERSION.SDK_INT >= 34)
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        else "",
                    )
                    .any {
                        it.isNotEmpty() &&
                            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    }
            else
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        if (!granted) throw Invalid("android-permission-required")
    }

    private fun validateMedia(file: File, claimed: String): String {
        if (claimed.startsWith("image/")) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (
                options.outWidth <= 0 ||
                    options.outHeight <= 0 ||
                    options.outMimeType !in SAFE_IMAGES
            )
                throw Invalid("invalid-media")
            return options.outMimeType
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes")
                throw Invalid("invalid-media")
            claimed.takeIf { it in SAFE_VIDEOS } ?: "video/mp4"
        } finally {
            retriever.release()
        }
    }

    private data class Ref(val type: String, val volume: String, val row: Long)

    private fun uri(ref: Ref): Uri =
        ContentUris.withAppendedId(
            if (ref.type == "image") MediaStore.Images.Media.getContentUri(ref.volume)
            else MediaStore.Video.Media.getContentUri(ref.volume),
            ref.row,
        )

    private fun encodeId(ref: Ref) =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("${ref.type}:${ref.volume}:${ref.row}".toByteArray())

    private fun decodeId(id: String): Ref =
        try {
            val parts = String(Base64.getUrlDecoder().decode(id)).split(':')
            if (
                parts.size != 3 ||
                    parts[0] !in setOf("image", "video") ||
                    !parts[1].matches(Regex("[a-zA-Z0-9_-]+"))
            )
                throw IllegalArgumentException()
            Ref(parts[0], parts[1], parts[2].toLong().also { require(it >= 0) })
        } catch (_: Exception) {
            throw Invalid("invalid-id")
        }

    private fun safeMime(value: String, type: String) =
        value.takeIf { it in SAFE_IMAGES || it in SAFE_VIDEOS }
            ?: if (type == "image") "application/octet-stream" else "application/octet-stream"

    private fun sanitizeName(value: String) =
        value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\u0000-\\u001f]"), "_")
            .take(160)
            .ifBlank { "media" }

    companion object {
        val SAFE_IMAGES =
            setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "image/heic",
                "image/heif",
                "image/avif",
            )
        val SAFE_VIDEOS =
            setOf("video/mp4", "video/webm", "video/3gpp", "video/quicktime", "video/x-matroska")

        fun sha256(file: File): String =
            file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
    }
}

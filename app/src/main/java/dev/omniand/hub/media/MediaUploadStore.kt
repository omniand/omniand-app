package dev.omniand.hub.media

import android.content.Context
import dev.omniand.hub.services.MediaService
import java.io.File
import org.json.JSONObject

/** Validates and publishes one owner-bound multipart upload from temporary storage. */
class MediaUploadStore(private val context: Context) {
    class Invalid(val code: String) : Exception(code)

    /** Publishes a fully received multipart file and always removes its temporary payload. */
    @Synchronized
    fun publish(owner: String, name: String, mime: String, sha256: String, file: File): JSONObject {
        try {
            validate(owner, name, mime, file.length(), sha256)
            if (MediaService.sha256(file) != sha256.lowercase()) throw Invalid("hash-mismatch")
            return MediaService(context).publish(file, name, mime)
        } finally {
            file.delete()
        }
    }

    private fun validate(owner: String, name: String, mime: String, size: Long, sha256: String) {
        if (
            owner.isBlank() ||
                name.isBlank() ||
                name.length > 255 ||
                mime.length > 100 ||
                size !in 1..MAX_FILE ||
                !sha256.matches(Regex("[a-fA-F0-9]{64}"))
        )
            throw Invalid("invalid-upload")
    }

    companion object {
        const val MAX_FILE = 500L * 1024 * 1024
    }
}

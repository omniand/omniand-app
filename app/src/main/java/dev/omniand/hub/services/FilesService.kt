package dev.omniand.hub.services

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import dev.omniand.hub.files.FilesEventBroadcaster
import dev.omniand.hub.files.FilesSetupManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Capability-independent shared-storage model with opaque, root-bound entry identities. */
class FilesService(private val context: Context) {
    data class Root(val id: String, val name: String, val directory: File, val removable: Boolean)

    data class Resource(
        val stream: FileInputStream,
        val length: Long,
        val mimeType: String,
        val name: String,
        val modified: Long,
    )

    class Invalid(val code: String) : Exception(code)

    fun setupRequired() {
        if (!FilesSetupManager.granted()) throw Invalid("files-access-required")
    }

    /** Enumerates only mounted shared-storage roots made available by Android. */
    fun roots(): List<Root> {
        setupRequired()
        val manager = context.getSystemService(StorageManager::class.java)
        val candidates =
            if (Build.VERSION.SDK_INT >= 30) {
                manager.storageVolumes.mapNotNull { volume ->
                    volume.directory?.takeIf(File::canRead)?.let { directory ->
                        Triple(
                            volume.uuid ?: "primary",
                            volume.getDescription(context),
                            directory to volume.isRemovable,
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                context.getExternalFilesDirs(null).mapIndexedNotNull { index, appDirectory ->
                    val root =
                        appDirectory?.absolutePath?.substringBefore("/Android/data/")?.let(::File)
                    root?.takeIf(File::canRead)?.let {
                        Triple(
                            if (index == 0) "primary" else "volume-$index",
                            if (index == 0) "Internal storage" else "Storage ${index + 1}",
                            it to (index > 0),
                        )
                    }
                }
            }
        return candidates
            .distinctBy { it.third.first.canonicalPath }
            .map { (identity, name, pair) ->
                Root(rootKey(identity, pair.first), name, pair.first.canonicalFile, pair.second)
            }
    }

    fun rootsJson(): JSONObject =
        JSONObject()
            .put(
                "items",
                JSONArray(
                    roots().map { root ->
                        JSONObject()
                            .put("id", encode(root.id, ""))
                            .put("name", root.name)
                            .put("removable", root.removable)
                            .put("free", root.directory.usableSpace)
                            .put("total", root.directory.totalSpace)
                    }
                ),
            )

    /** Lists one resolved directory with deterministic sorting and offset pagination. */
    fun list(
        parentId: String,
        offset: Int,
        limit: Int,
        sort: String,
        direction: String,
    ): JSONObject {
        if (
            offset < 0 ||
                limit !in 1..200 ||
                sort !in setOf("name", "size", "modified") ||
                direction !in setOf("asc", "desc")
        )
            throw Invalid("invalid-query")
        val parent = resolve(parentId, directory = true)
        val comparator =
            when (sort) {
                "size" ->
                    compareBy<File> { it.length() }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                "modified" ->
                    compareBy<File> { it.lastModified() }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
        val entries =
            parent.file
                .listFiles()
                ?.filter(::visible)
                ?.sortedWith(if (direction == "desc") comparator.reversed() else comparator)
                ?: throw Invalid("not-readable")
        FilesEventBroadcaster.watch(parent.file)
        val page = entries.drop(offset).take(limit + 1)
        touch(parentId)
        return JSONObject()
            .put("parent", entryJson(parent))
            .put("items", JSONArray(page.take(limit).map { entryJson(parent.root, it) }))
            .put("nextOffset", if (page.size > limit) offset + limit else JSONObject.NULL)
    }

    fun details(id: String): JSONObject {
        val resolved = resolve(id)
        touch(id)
        return entryJson(resolved)
    }

    fun content(id: String): Resource {
        val resolved = resolve(id, directory = false)
        if (!resolved.file.isFile || !resolved.file.canRead()) throw Invalid("not-readable")
        touch(id)
        return Resource(
            FileInputStream(resolved.file),
            resolved.file.length(),
            mime(resolved.file.name),
            safeName(resolved.file.name),
            resolved.file.lastModified(),
        )
    }

    /** Runs a bounded recursive name search without following symbolic links. */
    fun search(rootId: String, query: String, limit: Int): JSONObject {
        if (query.isBlank() || query.length > 120 || limit !in 1..200)
            throw Invalid("invalid-query")
        val start = resolve(rootId, directory = true)
        val result = mutableListOf<JSONObject>()
        val pending = ArrayDeque<File>().apply { add(start.file) }
        var scanned = 0
        while (pending.isNotEmpty() && result.size < limit && scanned < MAX_SEARCH_ENTRIES) {
            val current = pending.removeFirst()
            current.listFiles()?.filter(::visible)?.forEach { child ->
                scanned += 1
                if (child.name.contains(query, ignoreCase = true))
                    result += entryJson(start.root, child)
                if (child.isDirectory && !isSymlink(child) && pending.size < MAX_SEARCH_ENTRIES)
                    pending += child
            }
        }
        return JSONObject()
            .put("items", JSONArray(result.take(limit)))
            .put("truncated", pending.isNotEmpty() || scanned >= MAX_SEARCH_ENTRIES)
    }

    fun favorites(): JSONObject {
        val values = prefs().getStringSet(FAVORITES, emptySet()).orEmpty()
        val items = values.mapNotNull { id -> runCatching { entryJson(resolve(id)) }.getOrNull() }
        return JSONObject().put("items", JSONArray(items))
    }

    fun favorite(id: String, enabled: Boolean): JSONObject {
        resolve(id)
        val values = prefs().getStringSet(FAVORITES, emptySet()).orEmpty().toMutableSet()
        if (enabled) values += id else values -= id
        prefs().edit().putStringSet(FAVORITES, values).apply()
        FilesEventBroadcaster.publish("favorites")
        return JSONObject().put("favorite", enabled)
    }

    fun recents(limit: Int): JSONObject {
        if (limit !in 1..100) throw Invalid("invalid-query")
        val values = JSONObject(prefs().getString(RECENTS, "{}") ?: "{}")
        val ids = values.keys().asSequence().sortedByDescending { values.optLong(it) }.take(limit)
        return JSONObject()
            .put(
                "items",
                JSONArray(
                    ids.mapNotNull { id -> runCatching { entryJson(resolve(id)) }.getOrNull() }
                        .toList()
                ),
            )
    }

    fun createFolder(parentId: String, name: String): JSONObject {
        val parent = resolve(parentId, directory = true)
        val destination = child(parent, name)
        if (destination.exists()) throw Invalid("conflict")
        if (!destination.mkdir()) throw Invalid("write-failed")
        FilesEventBroadcaster.publish()
        return entryJson(parent.root, destination)
    }

    fun rename(id: String, name: String): JSONObject {
        val source = resolve(id)
        if (isAndroidContainer(source.root, source.file)) throw Invalid("protected-path")
        val destination =
            child(source.copy(file = source.file.parentFile ?: throw Invalid("invalid-path")), name)
        if (destination.exists()) throw Invalid("conflict")
        if (!source.file.renameTo(destination)) throw Invalid("write-failed")
        FilesEventBroadcaster.publish()
        return entryJson(source.root, destination)
    }

    /** Executes recursive jobs item-by-item so partial failures remain explicit and cancellable. */
    fun operate(
        operation: String,
        ids: List<String>,
        destinationId: String?,
        conflict: String,
        cancelled: () -> Boolean,
        progress: (completed: Int, total: Int) -> Unit,
    ): JSONArray {
        if (operation !in setOf("copy", "move", "delete") || ids.isEmpty() || ids.size > 100)
            throw Invalid("invalid-job")
        val destination = destinationId?.let { resolve(it, directory = true) }
        if (operation != "delete" && destination == null) throw Invalid("destination-required")
        val results = JSONArray()
        ids.forEachIndexed { index, id ->
            if (cancelled()) throw Invalid("cancelled")
            val result = JSONObject().put("id", id)
            try {
                val source = resolve(id)
                if (source.file == source.root.directory) throw Invalid("root-operation-forbidden")
                if (isAndroidContainer(source.root, source.file)) throw Invalid("protected-path")
                if (operation == "delete") {
                    deleteRecursively(source.file, cancelled)
                } else {
                    val targetParent = destination!!
                    val requested = child(targetParent, source.file.name)
                    if (
                        source.file.isDirectory &&
                            targetParent.file.canonicalPath.startsWith(
                                source.file.canonicalPath + File.separator
                            )
                    )
                        throw Invalid("recursive-destination")
                    val target = conflictDestination(requested, conflict)
                    if (target.exists() && !target.deleteRecursively())
                        throw Invalid("replace-failed")
                    if (operation == "move" && source.file.renameTo(target)) {
                        // Atomic rename completed the item.
                    } else {
                        copyRecursively(source.file, target, cancelled)
                        if (operation == "move") deleteRecursively(source.file, cancelled)
                    }
                }
                result.put("success", true)
            } catch (error: Invalid) {
                result.put("success", false).put("code", error.code)
            } catch (_: Exception) {
                result.put("success", false).put("code", "operation-failed")
            }
            results.put(result)
            progress(index + 1, ids.size)
        }
        FilesEventBroadcaster.publish("operation")
        return results
    }

    /** Verifies a staged upload, then publishes it through a temporary sibling file. */
    fun publishUpload(
        parentId: String,
        name: String,
        expectedSha256: String,
        conflict: String,
        staged: File,
    ): JSONObject {
        val parent = resolve(parentId, directory = true)
        if (!expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) throw Invalid("invalid-checksum")
        if (!digest(staged).equals(expectedSha256, ignoreCase = true))
            throw Invalid("checksum-mismatch")
        if (staged.length() > parent.file.usableSpace) throw Invalid("insufficient-space")
        val destination = conflictDestination(child(parent, name), conflict)
        val temporary = File(parent.file, ".omniand-${UUID.randomUUID()}.tmp")
        try {
            staged.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (destination.exists() && !destination.delete()) throw Invalid("replace-failed")
            if (!temporary.renameTo(destination)) throw Invalid("write-failed")
        } finally {
            temporary.delete()
        }
        FilesEventBroadcaster.publish()
        return entryJson(parent.root, destination)
    }

    /** Revalidates an opaque ID against currently mounted roots and rejects every escape route. */
    internal fun resolve(id: String, directory: Boolean? = null): Resolved {
        val decoded = decode(id)
        val root = roots().firstOrNull { it.id == decoded.first } ?: throw Invalid("unknown-volume")
        val relative = normalize(decoded.second)
        if (
            relative.split('/').let { parts ->
                parts.size >= 2 &&
                    parts[0].equals("Android", true) &&
                    parts[1].lowercase(Locale.ROOT) in setOf("data", "obb")
            }
        )
            throw Invalid("protected-path")
        val file = if (relative.isEmpty()) root.directory else File(root.directory, relative)
        val canonical =
            runCatching { file.canonicalFile }.getOrElse { throw Invalid("invalid-path") }
        if (
            canonical != root.directory &&
                !canonical.path.startsWith(root.directory.path + File.separator)
        )
            throw Invalid("path-escape")
        rejectSymlinks(root.directory, file)
        if (!file.exists()) throw Invalid("not-found")
        if (directory == true && !file.isDirectory) throw Invalid("not-directory")
        if (directory == false && file.isDirectory) throw Invalid("is-directory")
        return Resolved(root, file)
    }

    internal data class Resolved(val root: Root, val file: File)

    private fun entryJson(resolved: Resolved) = entryJson(resolved.root, resolved.file)

    private fun entryJson(root: Root, file: File): JSONObject {
        val relative =
            root.directory
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        return JSONObject()
            .put("id", encode(root.id, relative))
            .put("rootId", encode(root.id, ""))
            .put("name", if (relative.isEmpty()) root.name else file.name)
            .put("directory", file.isDirectory)
            .put("size", if (file.isFile) file.length() else JSONObject.NULL)
            .put("modified", file.lastModified())
            .put("mimeType", if (file.isFile) mime(file.name) else JSONObject.NULL)
            .put(
                "favorite",
                prefs()
                    .getStringSet(FAVORITES, emptySet())
                    .orEmpty()
                    .contains(encode(root.id, relative)),
            )
    }

    private fun child(parent: Resolved, name: String): File {
        if (
            name.isBlank() ||
                name.length > 255 ||
                name in setOf(".", "..") ||
                name.any { it == '/' || it == '\\' || it == '\u0000' }
        )
            throw Invalid("invalid-name")
        val result = File(parent.file, name)
        if (isProtectedAndroidPath(parent.root, result)) throw Invalid("protected-path")
        return result
    }

    private fun conflictDestination(requested: File, policy: String): File {
        if (policy !in setOf("fail", "replace", "keep-both"))
            throw Invalid("invalid-conflict-policy")
        if (!requested.exists()) return requested
        if (policy == "fail") throw Invalid("conflict")
        if (policy == "replace") return requested
        val stem = requested.nameWithoutExtension
        val suffix = requested.extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        for (number in 1..9999) {
            val candidate = File(requested.parentFile, "$stem ($number)$suffix")
            if (!candidate.exists()) return candidate
        }
        throw Invalid("conflict")
    }

    private fun rejectSymlinks(root: File, candidate: File) {
        var current: File? = candidate
        while (current != null && current != root.parentFile) {
            if (Build.VERSION.SDK_INT >= 26 && Files.isSymbolicLink(current.toPath()))
                throw Invalid("symlink-not-allowed")
            if (current == root) break
            current = current.parentFile
        }
    }

    /** Copies without following links and checks cancellation between bounded stream chunks. */
    private fun copyRecursively(source: File, destination: File, cancelled: () -> Boolean) {
        if (cancelled()) throw Invalid("cancelled")
        if (isSymlink(source)) throw Invalid("symlink-not-allowed")
        if (source.isDirectory) {
            if (!destination.mkdir()) throw Invalid("write-failed")
            source.listFiles()?.filter(::visible)?.forEach { child ->
                copyRecursively(child, File(destination, child.name), cancelled)
            } ?: throw Invalid("not-readable")
        } else {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw Invalid("cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            destination.setLastModified(source.lastModified())
        }
    }

    private fun deleteRecursively(file: File, cancelled: () -> Boolean) {
        if (cancelled()) throw Invalid("cancelled")
        if (isSymlink(file)) throw Invalid("symlink-not-allowed")
        if (file.isDirectory)
            file.listFiles()?.filter(::visible)?.forEach { deleteRecursively(it, cancelled) }
        if (!file.delete()) throw Invalid("delete-failed")
    }

    private fun visible(file: File): Boolean {
        if (file.name in setOf(".", "..") || file.name.startsWith(".omniand-") || isSymlink(file))
            return false
        val parts = file.absolutePath.replace(File.separatorChar, '/').split('/')
        return parts.indices.none { index ->
            parts[index].equals("Android", true) &&
                parts.getOrNull(index + 1)?.lowercase(Locale.ROOT) in setOf("data", "obb")
        }
    }

    private fun isAndroidContainer(root: Root, file: File): Boolean =
        root.directory
            .toPath()
            .relativize(file.toPath())
            .toString()
            .replace(File.separatorChar, '/')
            .equals("Android", ignoreCase = true)

    private fun isProtectedAndroidPath(root: Root, file: File): Boolean {
        val parts =
            root.directory
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/')
                .split('/')
        return parts.size >= 2 &&
            parts[0].equals("Android", true) &&
            parts[1].lowercase(Locale.ROOT) in setOf("data", "obb")
    }

    private fun isSymlink(file: File) =
        Build.VERSION.SDK_INT >= 26 && Files.isSymbolicLink(file.toPath())

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun touch(id: String) {
        val values =
            JSONObject(prefs().getString(RECENTS, "{}") ?: "{}").put(id, System.currentTimeMillis())
        while (values.length() > 100) values.remove(
            values.keys().asSequence().minByOrNull { values.optLong(it) }
        )
        prefs().edit().putString(RECENTS, values.toString()).apply()
    }

    companion object {
        const val MAX_UPLOAD_SIZE = 2L * 1024 * 1024 * 1024
        private const val MAX_SEARCH_ENTRIES = 10_000
        private const val PREFS = "files-state"
        private const val FAVORITES = "favorites"
        private const val RECENTS = "recents"

        internal fun normalize(path: String): String {
            if (path.startsWith('/') || path.contains('\u0000') || path.contains('\\'))
                throw Invalid("invalid-path")
            val parts = path.split('/').filter(String::isNotEmpty)
            if (parts.any { it == "." || it == ".." }) throw Invalid("path-traversal")
            return parts.joinToString("/")
        }

        private fun rootKey(identity: String, directory: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$identity\u0000${directory.canonicalPath}".toByteArray(StandardCharsets.UTF_8)
                )
                .take(18)
                .toByteArray()
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        internal fun encode(root: String, relative: String): String =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("$root\u0000$relative".toByteArray(StandardCharsets.UTF_8))

        internal fun decode(id: String): Pair<String, String> {
            if (id.length !in 2..4096) throw Invalid("invalid-id")
            val value =
                runCatching { String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8) }
                    .getOrElse { throw Invalid("invalid-id") }
            val split = value.indexOf('\u0000')
            if (split <= 0) throw Invalid("invalid-id")
            return value.substring(0, split) to value.substring(split + 1)
        }

        private fun mime(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }

        private fun safeName(value: String): String =
            value.replace(Regex("[\\r\\n\\\"\\\\]"), "_").take(255).ifBlank { "download" }

        private fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

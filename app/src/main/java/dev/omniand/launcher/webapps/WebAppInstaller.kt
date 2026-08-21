package dev.omniand.launcher.webapps

import android.content.Context
import dev.omniand.launcher.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

object WebAppInstaller {
    private const val MAX_PACKAGE_BYTES = 5L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 100
    private val validId = Regex("[a-z][a-z0-9-]{0,31}")
    private val knownCapabilities =
        setOf(
            "sms.read",
            "sms.send",
            "sms.modify",
            "contacts.read",
            "contacts.write",
            "media.read",
            "media.write",
        )

    data class Installed(
        val id: String,
        val name: String,
        val version: String,
        val permissions: Set<String>,
    )

    data class Expected(val id: String, val version: String, val permissions: Set<String>)

    data class ValidatedPackage(val metadata: Installed, val root: File, val staging: File) :
        AutoCloseable {
        override fun close() {
            staging.deleteRecursively()
        }
    }

    /**
     * Downloads and validates a package without making it active. The caller owns the staging dir.
     */
    fun prepare(
        context: Context,
        packageUrl: String,
        expected: Expected? = null,
    ): ValidatedPackage {
        requireAllowedOrigin(packageUrl)
        val staging = File(context.cacheDir, "webapp-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Unable to prepare installation" }
        try {
            downloadAndExtract(packageUrl, staging)
            val packageRoot = findPackageRoot(staging)
            val manifestFile = File(packageRoot, "manifest.json")
            check(manifestFile.isFile) { "Package manifest is missing" }
            check(File(packageRoot, "index.html").isFile) { "Package entry point is missing" }
            val manifest = JSONObject(manifestFile.readText())
            val id = manifest.getString("id")
            val name = manifest.getString("name").trim()
            val version = manifest.getString("version")
            check(validId.matches(id)) { "Invalid application id" }
            check(name.isNotEmpty() && name.length <= 80) { "Invalid application name" }
            check(id != "store") { "Reserved application id" }
            val iconPath = manifest.optString("icon").takeIf(String::isNotBlank)
            if (iconPath != null) {
                check(
                    !iconPath.startsWith('/') &&
                        !iconPath.contains("..") &&
                        iconPath.endsWith(".png", ignoreCase = true)
                ) {
                    "The application icon must be a relative PNG path"
                }
                val icon = File(packageRoot, iconPath)
                check(icon.isFile && icon.length() <= 512 * 1024) {
                    "Application icon is missing or too large"
                }
            }
            val permissionsJson = manifest.optJSONArray("permissions")
            val declaredPermissions = mutableSetOf<String>()
            if (permissionsJson != null)
                for (index in 0 until permissionsJson.length()) {
                    val permission = permissionsJson.getString(index)
                    check(permission in knownCapabilities) { "Unknown capability" }
                    declaredPermissions += permission
                }
            validateExpected(Installed(id, name, version, declaredPermissions), expected)

            val metadata = Installed(id, name, version, declaredPermissions)
            return ValidatedPackage(metadata, packageRoot, staging)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    internal fun validateExpected(installed: Installed, expected: Expected?) {
        if (expected == null) return
        check(installed.id == expected.id) { "Package application id does not match the catalog" }
        check(installed.version == expected.version) {
            "Package version does not match the catalog"
        }
        check(installed.permissions == expected.permissions) {
            "Package capabilities do not match the catalog"
        }
    }

    internal fun activate(
        packageRoot: File,
        target: File,
        backup: File,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        if (backup.exists()) backup.deleteRecursively()
        if (target.exists()) check(move(target, backup)) { "Unable to update application" }
        try {
            check(move(packageRoot, target)) { "Unable to activate application" }
            backup.deleteRecursively()
        } catch (error: Exception) {
            if (!target.exists() && backup.exists()) move(backup, target)
            throw error
        }
    }

    private fun requireAllowedOrigin(packageUrl: String) {
        val requested = URI(packageUrl)
        val store = URI(BuildConfig.STORE_URL)
        check(requested.scheme in setOf("http", "https")) { "Unsupported package URL" }
        check(requested.userInfo == null && requested.fragment == null) { "Invalid package URL" }
        check(
            requested.scheme == store.scheme &&
                requested.host == store.host &&
                effectivePort(requested) == effectivePort(store)
        ) {
            "Package must come from the configured store"
        }
    }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }

    private fun downloadAndExtract(packageUrl: String, destination: File) {
        val connection = URL(packageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/zip")
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Store returned HTTP ${connection.responseCode}"
            }
            val declaredSize = connection.contentLengthLong
            check(declaredSize < 0 || declaredSize <= MAX_PACKAGE_BYTES) { "Package is too large" }
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                var entryCount = 0
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++entryCount <= MAX_ENTRIES) { "Package contains too many files" }
                    val output = File(destination, entry.name)
                    check(
                        output.canonicalPath.startsWith(destination.canonicalPath + File.separator)
                    ) {
                        "Invalid package path"
                    }
                    if (entry.isDirectory) {
                        check(output.isDirectory || output.mkdirs()) {
                            "Unable to create package directory"
                        }
                    } else {
                        val parent = output.parentFile
                        check(parent == null || parent.isDirectory || parent.mkdirs()) {
                            "Unable to create package directory"
                        }
                        FileOutputStream(output).use { file ->
                            val buffer = ByteArray(8192)
                            var entryBytes = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                check(
                                    entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_PACKAGE_BYTES
                                ) {
                                    "Package is too large"
                                }
                                file.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun findPackageRoot(staging: File): File {
        if (File(staging, "manifest.json").isFile) return staging
        val children = staging.listFiles().orEmpty()
        check(children.size == 1 && children[0].isDirectory) {
            "Package must contain one application"
        }
        return children[0]
    }
}

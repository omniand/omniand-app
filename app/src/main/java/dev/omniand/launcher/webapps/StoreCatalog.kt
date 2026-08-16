package dev.omniand.launcher.webapps

import dev.omniand.launcher.BuildConfig
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI

data class CatalogApp(
    val id: String,
    val version: String,
    val permissions: Set<String>,
    val packageUrl: String
)

data class UpdateInfo(
    val currentVersion: String,
    val available: Boolean,
    val availableVersion: String? = null,
    val addedCapabilities: Set<String> = emptySet(),
    val catalogApp: CatalogApp? = null
)

object SemanticVersion {
    private val pattern = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )

    private data class Parsed(val major: BigInteger, val minor: BigInteger, val patch: BigInteger, val pre: List<String>?)

    fun compare(left: String, right: String): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        compareValues(a.major, b.major).takeIf { it != 0 }?.let { return it }
        compareValues(a.minor, b.minor).takeIf { it != 0 }?.let { return it }
        compareValues(a.patch, b.patch).takeIf { it != 0 }?.let { return it }
        if (a.pre == null || b.pre == null) return when {
            a.pre == null && b.pre == null -> 0
            a.pre == null -> 1
            else -> -1
        }
        for (index in 0 until maxOf(a.pre.size, b.pre.size)) {
            val x = a.pre.getOrNull(index) ?: return -1
            val y = b.pre.getOrNull(index) ?: return 1
            if (x == y) continue
            val xNumber = x.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val yNumber = y.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            return when {
                xNumber != null && yNumber != null -> compareValues(xNumber, yNumber)
                xNumber != null -> -1
                yNumber != null -> 1
                else -> x.compareTo(y)
            }
        }
        return 0
    }

    private fun parse(value: String): Parsed? {
        val match = pattern.matchEntire(value) ?: return null
        val numbers = (1..3).map { match.groupValues[it].toBigInteger() }
        val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.')
        if (pre?.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') } == true) return null
        return Parsed(numbers[0], numbers[1], numbers[2], pre)
    }
}

object StoreCatalog {
    internal const val MAX_CATALOG_BYTES = 256 * 1024

    fun check(installed: WebApp, storeUrl: String = BuildConfig.STORE_URL): UpdateInfo {
        check(installed.fileRoot != null) { "Built-in applications cannot be updated" }
        val entries = fetch(storeUrl)
        return findUpdate(installed, entries)
    }

    internal fun findUpdate(installed: WebApp, entries: List<CatalogApp>): UpdateInfo {
        val entry = entries.firstOrNull { it.id == installed.id }
            ?: return UpdateInfo(installed.version, false)
        val comparison = SemanticVersion.compare(entry.version, installed.version)
        if (comparison == null || comparison <= 0) return UpdateInfo(installed.version, false)
        return UpdateInfo(
            installed.version, true, entry.version,
            entry.permissions - installed.permissions, entry
        )
    }

    internal fun parse(bytes: ByteArray, storeUrl: String): List<CatalogApp> {
        check(bytes.size <= MAX_CATALOG_BYTES) { "Store catalog is too large" }
        val base = URI(storeUrl)
        check(base.scheme in setOf("http", "https") && base.host != null) { "Invalid configured Store URL" }
        val catalog = JSONArray(bytes.toString(Charsets.UTF_8))
        return buildList {
            for (index in 0 until catalog.length()) {
                val item = catalog.getJSONObject(index)
                val permissions = item.optJSONArray("permissions") ?: JSONArray()
                val resolved = base.resolve(item.getString("packageUrl"))
                check(sameOrigin(base, resolved)) { "Catalog package has a foreign origin" }
                check(resolved.scheme in setOf("http", "https") && resolved.userInfo == null && resolved.fragment == null) {
                    "Invalid catalog package URL"
                }
                add(CatalogApp(
                    item.getString("id"), item.optString("version"),
                    buildSet { for (permissionIndex in 0 until permissions.length()) add(permissions.getString(permissionIndex)) },
                    resolved.toString()
                ))
            }
        }
    }

    private fun fetch(storeUrl: String): List<CatalogApp> {
        val catalogUrl = URI(storeUrl).resolve("catalog/apps.json").toURL()
        val connection = catalogUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Store returned HTTP ${connection.responseCode}" }
            check(connection.contentLengthLong < 0 || connection.contentLengthLong <= MAX_CATALOG_BYTES) { "Store catalog is too large" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_CATALOG_BYTES) { "Store catalog is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return parse(output.toByteArray(), storeUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, true) && left.host.equals(right.host, true) && effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }
}

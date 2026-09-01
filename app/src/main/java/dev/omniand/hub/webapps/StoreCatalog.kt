package dev.omniand.hub.webapps

import dev.omniand.hub.BuildConfig
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray

data class CatalogApp(
    val id: String,
    val name: String,
    val tagline: String,
    val version: String,
    val category: String,
    val permissions: Set<String>,
    val packageUrl: String,
    val iconUrl: String,
    val localizedNames: Map<String, String> = emptyMap(),
    val localizedTaglines: Map<String, String> = emptyMap(),
    val localizedCategories: Map<String, String> = emptyMap(),
)

fun CatalogApp.displayName(languageTags: String?): String =
    AppLocalization.select(name, localizedNames, languageTags)

fun CatalogApp.displayTagline(languageTags: String?): String =
    AppLocalization.select(tagline, localizedTaglines, languageTags)

fun CatalogApp.displayCategory(languageTags: String?): String =
    AppLocalization.select(category, localizedCategories, languageTags)

object SemanticVersion {
    private val pattern =
        Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

    private data class Parsed(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
        val pre: List<String>?,
    )

    fun compare(left: String, right: String): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        compareValues(a.major, b.major)
            .takeIf { it != 0 }
            ?.let {
                return it
            }
        compareValues(a.minor, b.minor)
            .takeIf { it != 0 }
            ?.let {
                return it
            }
        compareValues(a.patch, b.patch)
            .takeIf { it != 0 }
            ?.let {
                return it
            }
        if (a.pre == null || b.pre == null)
            return when {
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
        if (pre?.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') } == true)
            return null
        return Parsed(numbers[0], numbers[1], numbers[2], pre)
    }
}

/** Fetches and strictly validates the external static catalog consumed only by Platform Home. */
object StoreCatalog {
    internal const val MAX_CATALOG_BYTES = 256 * 1024
    internal const val MAX_ICON_BYTES = 512 * 1024
    private val validId = Regex("[a-z][a-z0-9-]{0,31}")

    fun fetch(catalogUrl: String = BuildConfig.CATALOG_URL): List<CatalogApp> {
        val bytes =
            download(
                URI(catalogUrl).resolve("catalog/apps.json"),
                MAX_CATALOG_BYTES,
                "application/json",
            )
        return parse(bytes, catalogUrl)
    }

    fun fetchIcon(app: CatalogApp, catalogUrl: String = BuildConfig.CATALOG_URL): ByteArray {
        val configured = validateBase(catalogUrl)
        val icon = URI(app.iconUrl)
        check(sameOrigin(configured, icon)) { "Catalog icon has a foreign origin" }
        val bytes = download(icon, MAX_ICON_BYTES, "image/png")
        check(
            bytes.size >= PNG_SIGNATURE.size &&
                bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            "Catalog icon is not a PNG"
        }
        return bytes
    }

    internal fun state(catalogVersion: String, installedVersion: String?): String {
        if (installedVersion == null) return "available"
        val comparison = SemanticVersion.compare(catalogVersion, installedVersion)
        return if (comparison != null && comparison > 0) "update-available" else "installed"
    }

    internal fun parse(bytes: ByteArray, catalogUrl: String): List<CatalogApp> {
        check(bytes.size <= MAX_CATALOG_BYTES) { "Catalog is too large" }
        val base = validateBase(catalogUrl)
        val catalog = JSONArray(bytes.toString(Charsets.UTF_8))
        val ids = mutableSetOf<String>()
        return buildList {
            for (index in 0 until catalog.length()) {
                val item = catalog.getJSONObject(index)
                val id = item.getString("id")
                val name = item.getString("name").trim()
                val tagline = item.getString("tagline").trim()
                val version = item.getString("version")
                val category = item.getString("category").trim()
                val localizedNames = AppLocalization.strings(item, "name", 80)
                val localizedTaglines = AppLocalization.strings(item, "tagline", 160)
                val localizedCategories = AppLocalization.strings(item, "category", 80)
                check(validId.matches(id) && id != "store") { "Invalid or reserved application id" }
                check(ids.add(id)) { "Duplicate catalog application id" }
                check(name.isNotEmpty() && name.length <= 80) { "Invalid application name" }
                check(tagline.isNotEmpty() && tagline.length <= 160) {
                    "Invalid application tagline"
                }
                check(category.isNotEmpty() && category.length <= 80) {
                    "Invalid application category"
                }
                check(SemanticVersion.compare(version, version) == 0) {
                    "Invalid application version"
                }
                val permissions = item.optJSONArray("permissions") ?: JSONArray()
                val declared = buildSet {
                    for (permissionIndex in 0 until permissions.length()) {
                        val permission = permissions.getString(permissionIndex)
                        check(permission in WebCapabilities.known) { "Unknown capability" }
                        check(add(permission)) { "Duplicate capability" }
                    }
                }
                val packageUri = validateResource(base, item.getString("packageUrl"), ".zip")
                val iconUri = validateResource(base, item.getString("iconUrl"), ".png")
                check(packageUri.path.endsWith("/$id-$version.zip")) {
                    "Catalog package URL does not match its id and version"
                }
                check(iconUri.path.endsWith("/catalog/icons/$id.png")) {
                    "Catalog icon URL does not match its id"
                }
                add(
                    CatalogApp(
                        id,
                        name,
                        tagline,
                        version,
                        category,
                        declared,
                        packageUri.toString(),
                        iconUri.toString(),
                        localizedNames,
                        localizedTaglines,
                        localizedCategories,
                    )
                )
            }
        }
    }

    private fun validateBase(url: String): URI =
        URI(url).also {
            check(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null) {
                "Invalid configured catalog URL"
            }
        }

    private fun validateResource(base: URI, value: String, suffix: String): URI =
        base.resolve(value).also {
            check(sameOrigin(base, it)) { "Catalog resource has a foreign origin" }
            check(
                it.userInfo == null &&
                    it.fragment == null &&
                    it.query == null &&
                    it.path.endsWith(suffix)
            ) {
                "Invalid catalog resource URL"
            }
        }

    /** Downloads one bounded static resource without redirects or content-type ambiguity. */
    private fun download(uri: URI, limit: Int, acceptedType: String): ByteArray {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", acceptedType)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Catalog server returned HTTP ${connection.responseCode}"
            }
            check(connection.contentType?.substringBefore(';') == acceptedType) {
                "Catalog resource has an invalid content type"
            }
            check(connection.contentLengthLong < 0 || connection.contentLengthLong <= limit) {
                "Catalog resource is too large"
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= limit) { "Catalog resource is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, true) &&
            left.host.equals(right.host, true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            else -> 80
        }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}

package dev.omniand.hub.webapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.wrappers.WrapperInstaller
import java.io.File
import org.json.JSONObject

data class WebApp(
    val id: String,
    val name: String,
    val version: String,
    val permissions: Set<String>,
    val assetRoot: String? = null,
    val packageName: String? = null,
    val iconPath: String? = null,
)

/** Discovers catalog applications exclusively from trusted, installed wrapper APKs. */
object WebAppRegistry {
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
    @Volatile private var cached: List<WebApp>? = null

    fun apps(context: Context): List<WebApp> {
        removeLegacyStorage(context)
        return cached ?: synchronized(this) { cached ?: discover(context).also { cached = it } }
    }

    fun invalidate() {
        cached = null
    }

    fun byHost(context: Context, host: String): WebApp? =
        apps(context).firstOrNull { host.substringBefore('.').equals(it.id, ignoreCase = true) }

    fun byCanonicalHost(context: Context, host: String): WebApp? =
        apps(context).firstOrNull {
            host.equals("${it.id}.${BuildConfig.PLATFORM_HOST}", ignoreCase = true)
        }

    fun isPlatformHost(context: Context, host: String): Boolean = byHost(context, host) == null

    fun originFor(app: WebApp): String = "https://${app.id}.${BuildConfig.PLATFORM_HOST}"

    fun localhostOriginFor(app: WebApp): String = "http://${app.id}.localhost:8080"

    fun developmentOriginFor(app: WebApp, baseHost: String, port: Int): String =
        "http://${app.id}.${baseHost.lowercase()}:$port"

    fun openAsset(context: Context, app: WebApp, relative: String): ByteArray {
        val root = app.assetRoot
        if (root != null) return context.assets.open("$root/$relative").use { it.readBytes() }
        val packageName = checkNotNull(app.packageName)
        val packageContext =
            context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        return packageContext.assets.open("webapp/$relative").use { it.readBytes() }
    }

    /** Queries only launcher-visible packages and rejects every inconsistent wrapper. */
    private fun discover(context: Context): List<WebApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.GET_META_DATA)
            .mapNotNull { resolved ->
                val packageName = resolved.activityInfo.packageName
                runCatching {
                        val info =
                            context.packageManager.getApplicationInfo(
                                packageName,
                                PackageManager.GET_META_DATA,
                            )
                        val id =
                            info.metaData?.getString("dev.omniand.APP_ID")
                                ?: error("Missing app id")
                        check(
                            validId.matches(id) && packageName == WrapperInstaller.packageName(id)
                        )
                        check(WrapperInstaller.isTrustedWrapper(context, id))
                        val packageContext =
                            context.createPackageContext(
                                packageName,
                                Context.CONTEXT_IGNORE_SECURITY,
                            )
                        val manifest =
                            JSONObject(
                                packageContext.assets
                                    .open("webapp/manifest.json")
                                    .bufferedReader()
                                    .use {
                                        it.readText()
                                    }
                            )
                        check(manifest.getString("id") == id)
                        val name = manifest.getString("name").trim()
                        check(name.isNotEmpty() && name.length <= 80)
                        val version = manifest.getString("version")
                        val permissions = buildSet {
                            manifest.optJSONArray("permissions")?.let { values ->
                                for (index in 0 until values.length()) {
                                    val capability = values.getString(index)
                                    check(capability in knownCapabilities)
                                    add(capability)
                                }
                            }
                        }
                        packageContext.assets.open("webapp/index.html").close()
                        val icon =
                            manifest.optString("icon").takeIf { path ->
                                path.isNotBlank() &&
                                    !path.startsWith('/') &&
                                    !path.contains("..") &&
                                    runCatching {
                                            packageContext.assets.open("webapp/$path").close()
                                        }
                                        .isSuccess
                            }
                        WebApp(
                            id,
                            name,
                            version,
                            permissions,
                            packageName = packageName,
                            iconPath = icon,
                        )
                    }
                    .getOrNull()
            }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }

    @Synchronized
    private fun removeLegacyStorage(context: Context) {
        val preferences = context.getSharedPreferences("web-app-registry", Context.MODE_PRIVATE)
        if (preferences.getBoolean("private-webapps-removed-v2", false)) return
        File(context.filesDir, "webapps").deleteRecursively()
        preferences.edit().putBoolean("private-webapps-removed-v2", true).apply()
    }
}

package dev.omniand.launcher.webapps

import android.content.Context
import dev.omniand.launcher.BuildConfig
import org.json.JSONObject
import java.io.File

data class WebApp(
    val id: String,
    val name: String,
    val version: String,
    val permissions: Set<String>,
    val assetRoot: String? = null,
    val fileRoot: File? = null,
    val iconPath: String? = null
)

object WebAppRegistry {
    private val builtInApps = listOf(
        WebApp("store", "Store", BuildConfig.VERSION_NAME, setOf("apps.install"), "web/apps/store", iconPath = "icon.png")
    )

    fun apps(context: Context): List<WebApp> {
        removeLegacyBundledApps(context)
        return builtInApps + installedApps(context)
    }

    fun byHost(context: Context, host: String): WebApp? =
        apps(context).firstOrNull { host.substringBefore('.').equals(it.id, ignoreCase = true) }

    fun isPlatformHost(context: Context, host: String): Boolean = byHost(context, host) == null

    fun originFor(app: WebApp): String = "https://${app.id}.${BuildConfig.PLATFORM_HOST}"

    fun developmentOriginFor(app: WebApp, baseHost: String, port: Int): String =
        "http://${app.id}.${baseHost.lowercase()}:$port"

    fun installedRoot(context: Context): File = File(context.filesDir, "webapps")

    private fun installedApps(context: Context): List<WebApp> = installedRoot(context).listFiles()
        ?.filter(File::isDirectory)
        ?.mapNotNull(::fromDirectory)
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    private fun fromDirectory(directory: File): WebApp? = runCatching {
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val id = manifest.getString("id")
        if (id != directory.name || builtInApps.any { it.id == id }) return null
        val permissions = manifest.optJSONArray("permissions")
        val iconPath = manifest.optString("icon").takeIf { it.isNotBlank() && !it.startsWith('/') && !it.contains("..") }
        WebApp(id, manifest.getString("name"), manifest.getString("version"), buildSet {
            if (permissions != null) for (index in 0 until permissions.length()) add(permissions.getString(index))
        }, fileRoot = directory, iconPath = iconPath?.takeIf { File(directory, it).isFile })
    }.getOrNull()

    @Synchronized
    private fun removeLegacyBundledApps(context: Context) {
        val preferences = context.getSharedPreferences("web-app-registry", Context.MODE_PRIVATE)
        if (preferences.getBoolean("legacy-bundled-apps-removed-v1", false)) return
        listOf("messages", "test").forEach { File(installedRoot(context), it).deleteRecursively() }
        preferences.edit()
            .putBoolean("bundled-apps-seeded-v1", false)
            .putBoolean("legacy-bundled-apps-removed-v1", true)
            .apply()
    }
}

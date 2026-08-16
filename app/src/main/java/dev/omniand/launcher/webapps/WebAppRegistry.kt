package dev.omniand.launcher.webapps

import android.content.Context
import org.json.JSONObject
import java.io.File

data class WebApp(
    val id: String,
    val name: String,
    val permissions: Set<String>,
    val assetRoot: String? = null,
    val fileRoot: File? = null
)

object WebAppRegistry {
    private val builtInApps = listOf(
        WebApp("store", "Store", setOf("apps.install"), assetRoot = "web/apps/store")
    )

    fun apps(context: Context): List<WebApp> = builtInApps + installedApps(context)

    fun byHost(context: Context, host: String): WebApp? =
        apps(context).firstOrNull { hostLabel(host) == it.id }

    fun isLauncherHost(context: Context, host: String): Boolean = byHost(context, host) == null

    fun originFor(app: WebApp, launcherHost: String, port: Int): String =
        "http://${app.id}.${launcherHost.lowercase()}:$port"

    fun installedRoot(context: Context): File = File(context.filesDir, "webapps")

    private fun installedApps(context: Context): List<WebApp> =
        installedRoot(context).listFiles()
            ?.filter(File::isDirectory)
            ?.mapNotNull(::fromDirectory)
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    private fun fromDirectory(directory: File): WebApp? = runCatching {
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val id = manifest.getString("id")
        if (id != directory.name || builtInApps.any { it.id == id }) return null
        val permissions = manifest.optJSONArray("permissions")
        WebApp(
            id = id,
            name = manifest.getString("name"),
            permissions = buildSet {
                if (permissions != null) for (index in 0 until permissions.length()) add(permissions.getString(index))
            },
            fileRoot = directory
        )
    }.getOrNull()

    private fun hostLabel(host: String): String = host.lowercase().substringBefore('.')
}

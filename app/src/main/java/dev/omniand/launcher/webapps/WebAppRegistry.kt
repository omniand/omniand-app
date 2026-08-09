package dev.omniand.launcher.webapps

data class WebApp(
    val id: String,
    val name: String,
    val assetRoot: String,
    val permissions: Set<String>
)

object WebAppRegistry {
    val apps = listOf(
        WebApp("messages", "Messages", "web/apps/messages", setOf("sms.read")),
        WebApp("test", "Permission test", "web/apps/test", emptySet())
    )

    fun byHost(host: String): WebApp? = apps.firstOrNull { hostLabel(host) == it.id }

    fun isLauncherHost(host: String): Boolean = byHost(host) == null

    fun originFor(app: WebApp, launcherHost: String, port: Int): String =
        "http://${app.id}.${launcherHost.lowercase()}:$port"

    private fun hostLabel(host: String): String = host.lowercase().substringBefore('.')

}

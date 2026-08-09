package dev.omniand.launcher.webapps

data class WebApp(
    val id: String,
    val name: String,
    val port: Int,
    val assetRoot: String,
    val permissions: Set<String>
)

object WebAppRegistry {
    val apps = listOf(
        WebApp("messages", "Messages", 8081, "web/apps/messages", setOf("sms.read")),
        WebApp("test", "Permission test", 8082, "web/apps/test", emptySet())
    )

    fun byPort(port: Int): WebApp? = apps.firstOrNull { it.port == port }
}

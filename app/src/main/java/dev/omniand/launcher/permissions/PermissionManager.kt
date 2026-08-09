package dev.omniand.launcher.permissions

import dev.omniand.launcher.webapps.WebAppRegistry

object PermissionManager {
    fun hasCapability(appId: String?, capability: String): Boolean =
        WebAppRegistry.apps.firstOrNull { it.id == appId }?.permissions?.contains(capability) == true
}

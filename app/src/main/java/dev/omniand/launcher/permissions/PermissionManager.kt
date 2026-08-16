package dev.omniand.launcher.permissions

import android.content.Context
import dev.omniand.launcher.webapps.WebAppRegistry

object PermissionManager {
    fun hasCapability(context: Context, appId: String?, capability: String): Boolean =
        WebAppRegistry.apps(context).firstOrNull { it.id == appId }?.permissions?.contains(capability) == true
}

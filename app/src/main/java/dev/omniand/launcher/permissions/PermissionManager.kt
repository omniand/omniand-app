package dev.omniand.launcher.permissions

import android.content.Context
import dev.omniand.launcher.webapps.WebAppRegistry

/**
 * Resolves Web capabilities from the installed package registry.
 *
 * A missing or unknown app identity always fails closed. This check is intentionally independent of
 * Android runtime permissions: protected endpoints require both layers to authorize access.
 */
object PermissionManager {
    fun hasCapability(context: Context, appId: String?, capability: String): Boolean =
        WebAppRegistry.apps(context)
            .firstOrNull { it.id == appId }
            ?.permissions
            ?.contains(capability) == true
}

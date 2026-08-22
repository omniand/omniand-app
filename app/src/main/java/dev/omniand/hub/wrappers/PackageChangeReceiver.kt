package dev.omniand.hub.wrappers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.omniand.hub.webapps.WebAppRegistry

/** Invalidates wrapper discovery as soon as Android adds, replaces, or removes a package. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WebAppRegistry.invalidate()
    }
}

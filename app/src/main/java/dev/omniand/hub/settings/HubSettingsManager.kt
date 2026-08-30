package dev.omniand.hub.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.background.BackgroundHostingManager
import dev.omniand.hub.background.NotificationSetupActivity
import dev.omniand.hub.background.PresenceTracker
import dev.omniand.hub.contacts.ContactsSetupManager
import dev.omniand.hub.media.MediaSetupManager
import dev.omniand.hub.pairing.ConnectComputerActivity
import dev.omniand.hub.pairing.DeviceIdentity
import dev.omniand.hub.pairing.PairingState
import dev.omniand.hub.sms.SmsSetupManager
import dev.omniand.hub.tunnel.TunnelState
import org.json.JSONObject

/** Builds the stable phone-only Hub settings document and opens allowlisted Android setup flows. */
object HubSettingsManager {
    val permissionGroups =
        setOf(
            "sms",
            "contacts",
            "media",
            "notifications",
            "wrapper-installation",
            "background-hosting",
        )

    fun state(context: Context): JSONObject {
        val notifications =
            Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        return JSONObject()
            .put(
                "configuration",
                JSONObject()
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("remoteOrigin", "https://connect.${BuildConfig.PLATFORM_HOST}")
                    .put("localOrigin", "http://localhost:8080")
                    .put("catalogUrl", BuildConfig.CATALOG_URL),
            )
            .put("sms", SmsSetupManager.state(context))
            .put("contacts", ContactsSetupManager.state(context))
            .put("media", MediaSetupManager.state(context))
            .put(
                "notifications",
                JSONObject()
                    .put("granted", notifications)
                    .put("applicable", Build.VERSION.SDK_INT >= 33),
            )
            .put(
                "wrapperInstallation",
                JSONObject()
                    .put("granted", context.packageManager.canRequestPackageInstalls())
                    .put("applicable", Build.VERSION.SDK_INT >= 26),
            )
            .put(
                "backgroundHosting",
                JSONObject()
                    .put("enabled", BackgroundHostingManager.isEnabled(context))
                    .put("serviceRunning", BackgroundHostingManager.isServiceRunning())
                    .put("batteryExempt", BackgroundHostingManager.isBatteryExempt(context))
                    .put("wakeLockActive", PresenceTracker.isWakeLockHeld())
                    .put("connectedDesktopClients", PresenceTracker.connectedClients()),
            )
            .put(
                "remoteAccess",
                JSONObject()
                    .put("enrolled", DeviceIdentity(context).credential() != null)
                    .put("deviceId", DeviceIdentity(context).deviceId)
                    .put("connectionState", TunnelState.state)
                    .put("scanning", PairingState.scanning)
                    .put("error", PairingState.error ?: TunnelState.error),
            )
    }

    fun connectComputer(context: Context) {
        context.startActivity(
            Intent(context, ConnectComputerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun request(context: Context, group: String): Boolean {
        if (group !in permissionGroups) return false
        when (group) {
            "sms" -> SmsSetupManager.request(context, setOf("sms.read", "sms.send", "sms.modify"))
            "contacts" ->
                ContactsSetupManager.request(context, setOf("contacts.read", "contacts.write"))
            "media" -> MediaSetupManager.request(context, setOf("media.read", "media.write"))
            "notifications" ->
                context.startActivity(
                    Intent(context, NotificationSetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            "wrapper-installation" ->
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            "background-hosting" -> BackgroundHostingManager.requestAccess(context)
        }
        return true
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

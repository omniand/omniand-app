package dev.omniand.hub.wrappers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.omniand.hub.MainActivity
import dev.omniand.hub.WebAppActivity
import dev.omniand.hub.contacts.ContactsSetupManager
import dev.omniand.hub.media.MediaSetupManager
import dev.omniand.hub.sms.SmsSetupManager
import dev.omniand.hub.webapps.WebAppRegistry

/** Reduces asynchronous system installer outcomes into the persisted HTTP operation contract. */
class PackageInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            InstallOperations.update(context, operationId, "pending-user-action")
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null && (MainActivity.isActive || WebAppActivity.isStoreActive)) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            WebAppRegistry.invalidate()
            val operation = InstallOperations.get(context, operationId)
            val app =
                operation?.optString("id")?.let { id ->
                    WebAppRegistry.apps(context).find { it.id == id }
                }
            if (app != null) {
                if (app.permissions.any { it.startsWith("sms.") })
                    SmsSetupManager.recordPending(context, app.permissions)
                if (app.permissions.any { it.startsWith("contacts.") })
                    ContactsSetupManager.recordPending(context, app.permissions)
                if (app.permissions.any { it.startsWith("media.") })
                    MediaSetupManager.recordPending(context, app.permissions)
                InstallOperations.update(context, operationId, "installed")
            } else
                InstallOperations.update(
                    context,
                    operationId,
                    "failed",
                    "Installed wrapper verification failed",
                )
            return
        }
        val finalStatus =
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else "failed"
        InstallOperations.update(
            context,
            operationId,
            finalStatus,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }

    companion object {
        const val EXTRA_OPERATION_ID = "dev.omniand.OPERATION_ID"
    }
}

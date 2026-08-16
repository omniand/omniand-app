package dev.omniand.launcher.sms

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.provider.Settings
import android.app.AlertDialog
import dev.omniand.launcher.wrappers.WrapperInstaller
import org.json.JSONObject

object SmsSetupManager {
    private const val PREFS = "sms-setup"
    private const val PENDING = "pending"
    private const val CAPABILITIES = "capabilities"

    fun recordPending(context: Context, capabilities: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, true).putStringSet(CAPABILITIES, capabilities).apply()
    }

    fun request(context: Context, capabilities: Set<String>) {
        recordPending(context, capabilities)
        context.startActivity(Intent(context, SmsSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openPendingSetup(activity: Activity) {
        if (activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false)) {
            activity.startActivity(Intent(activity, SmsSetupActivity::class.java))
        }
    }

    fun capabilities(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CAPABILITIES, emptySet()).orEmpty()

    fun complete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING, false).apply()
    }

    fun isRoleHeld(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
    } else Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun state(context: Context): JSONObject {
        val caps = capabilities(context)
        return JSONObject()
            .put("defaultSmsApp", isRoleHeld(context))
            .put("readPermission", granted(context, Manifest.permission.READ_SMS))
            .put("sendPermission", granted(context, Manifest.permission.SEND_SMS))
            .put("notificationsPermission", Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS))
            .put("pending", context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false))
            .put("roleRequired", "sms.modify" in caps)
            .put("messagesRelay", WrapperInstaller.relayState(context, "messages"))
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

class SmsSetupActivity : Activity() {
    private var roleRequested = false
    private var explanationShown = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Set up Messages"
        showExplanation()
    }

    override fun onResume() {
        super.onResume()
        if (roleRequested) requestPermissionsForCapabilities()
    }

    private fun showExplanation() {
        if (explanationShown) return
        explanationShown = true
        AlertDialog.Builder(this)
            .setTitle("Set up SMS access")
            .setMessage("OmniAnd needs Android SMS access for the installed Web app. If message editing is enabled, Android will also ask whether OmniAnd may become the default SMS app. You can decline and finish setup later.")
            .setPositiveButton("Continue") { _, _ -> requestNext() }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun requestNext() {
        val needsRole = "sms.modify" in SmsSetupManager.capabilities(this)
        if (needsRole && !SmsSetupManager.isRoleHeld(this)) {
            roleRequested = true
            if (Build.VERSION.SDK_INT >= 29) {
                val manager = getSystemService(RoleManager::class.java)
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_SMS), ROLE_REQUEST)
            } else {
                startActivityForResult(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName), ROLE_REQUEST)
            }
        } else requestPermissionsForCapabilities()
    }

    private fun requestPermissionsForCapabilities() {
        val caps = SmsSetupManager.capabilities(this)
        val permissions = mutableListOf<String>()
        if ("sms.read" in caps || "sms.modify" in caps) permissions += Manifest.permission.READ_SMS
        if ("sms.send" in caps || "sms.modify" in caps) permissions += Manifest.permission.SEND_SMS
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) finishSetup() else requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == PERMISSION_REQUEST) finishSetup()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ROLE_REQUEST) requestPermissionsForCapabilities()
    }

    private fun finishSetup() {
        SmsSetupManager.complete(this)
        finish()
    }

    companion object { private const val ROLE_REQUEST = 71; private const val PERMISSION_REQUEST = 72 }
}

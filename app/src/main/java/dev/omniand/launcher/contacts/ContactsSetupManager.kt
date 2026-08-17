package dev.omniand.launcher.contacts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import org.json.JSONObject

object ContactsSetupManager {
    private const val PREFS = "contacts-setup"
    private const val PENDING = "pending"
    private const val CAPABILITIES = "capabilities"

    fun recordPending(context: Context, capabilities: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, true).putStringSet(CAPABILITIES, capabilities).apply()
    }

    fun request(context: Context, capabilities: Set<String>) {
        recordPending(context, capabilities)
        context.startActivity(Intent(context, ContactsSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openPendingSetup(activity: Activity): Boolean {
        if (!activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false)) return false
        activity.startActivity(Intent(activity, ContactsSetupActivity::class.java))
        return true
    }

    fun capabilities(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(CAPABILITIES, emptySet()).orEmpty()

    fun state(context: Context) = JSONObject()
        .put("readPermission", granted(context, Manifest.permission.READ_CONTACTS))
        .put("writePermission", granted(context, Manifest.permission.WRITE_CONTACTS))
        .put("pending", context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false))

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun complete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING, false).apply()
    }
}

class ContactsSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Set up Contacts")
            .setMessage("OmniAnd needs Android Contacts access for the installed Web app. You can decline and finish setup later.")
            .setPositiveButton("Continue") { _, _ -> requestAccess() }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun requestAccess() {
        val caps = ContactsSetupManager.capabilities(this)
        val wanted = buildList {
            if ("contacts.read" in caps || "contacts.write" in caps) add(Manifest.permission.READ_CONTACTS)
            if ("contacts.write" in caps) add(Manifest.permission.WRITE_CONTACTS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) done() else requestPermissions(wanted.toTypedArray(), 81)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 81) done()
    }

    private fun done() { ContactsSetupManager.complete(this); finish() }
}

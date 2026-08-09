package dev.omniand.launcher.services

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

class AndroidAppsService(private val context: Context) {
    fun list(): JSONArray {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = manager.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(manager).toString().lowercase() }
        return JSONArray().apply {
            apps.forEach { info ->
                put(JSONObject()
                    .put("package", info.activityInfo.packageName)
                    .put("name", info.loadLabel(manager).toString()))
            }
        }
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}

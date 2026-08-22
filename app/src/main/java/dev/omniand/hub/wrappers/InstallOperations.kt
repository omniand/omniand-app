package dev.omniand.hub.wrappers

import android.content.Context
import java.util.UUID
import org.json.JSONObject

/** Persists PackageInstaller/uninstaller state across activity and process recreation. */
object InstallOperations {
    private const val PREFERENCES = "app-install-operations"

    fun create(context: Context, appId: String, kind: String): JSONObject {
        val id = UUID.randomUUID().toString()
        val value =
            JSONObject()
                .put("operationId", id)
                .put("id", appId)
                .put("kind", kind)
                .put("status", "pending-user-action")
        save(context, value)
        return value
    }

    fun get(context: Context, operationId: String): JSONObject? =
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(operationId, null)
            ?.let(::JSONObject)

    fun update(context: Context, operationId: String, status: String, message: String? = null) {
        val value = get(context, operationId) ?: return
        value.put("status", status)
        if (message != null) value.put("error", message)
        save(context, value)
    }

    private fun save(context: Context, value: JSONObject) {
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(value.getString("operationId"), value.toString())
            .apply()
    }
}

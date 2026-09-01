package dev.omniand.hub.settings

import android.content.Context

/** Consumes an automatically scheduled setup screen exactly once. */
object PendingSetup {
    fun consume(context: Context, preferencesName: String, key: String): Boolean {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return consume(
            isPending = { preferences.getBoolean(key, false) },
            clear = { preferences.edit().putBoolean(key, false).apply() },
        )
    }

    internal fun consume(isPending: () -> Boolean, clear: () -> Unit): Boolean {
        if (!isPending()) return false
        clear()
        return true
    }
}

package com.miyahna.water.data.local

import android.content.Context
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.DriverSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Synchronous SharedPreferences persistence for one-time Manager PIN unlock,
 * persisted active role (auto-launch Manager or Driver directly on startup),
 * and saved DriverSession so users are never repeatedly prompted to log in.
 */
object AuthSessionPrefs {
    private const val PREFS_NAME = "miyahna_auth_session"
    private const val KEY_SAVED_ROLE = "saved_role"
    private const val KEY_ROLE_EXPLICIT = "role_explicit"
    private const val KEY_MANAGER_UNLOCKED = "manager_unlocked"
    private const val KEY_DRIVER_SESSION_JSON = "driver_session_json"
    private const val KEY_DRIVER_EXPLICIT = "driver_explicit"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasExplicitRole(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROLE_EXPLICIT, false)

    fun getSavedRole(context: Context): AppRole? {
        val raw = prefs(context).getString(KEY_SAVED_ROLE, null) ?: return null
        return AppRole.entries.firstOrNull { it.wire == raw }
    }

    fun setSavedRole(context: Context, role: AppRole?) {
        prefs(context).edit().apply {
            putBoolean(KEY_ROLE_EXPLICIT, true)
            if (role == null) remove(KEY_SAVED_ROLE) else putString(KEY_SAVED_ROLE, role.wire)
        }.commit()
    }

    fun isManagerUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANAGER_UNLOCKED, false)

    fun setManagerUnlocked(context: Context, unlocked: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_MANAGER_UNLOCKED, unlocked)
            .commit()
    }

    fun hasExplicitDriverSession(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DRIVER_EXPLICIT, false)

    fun getDriverSession(context: Context): DriverSession? {
        val raw = prefs(context).getString(KEY_DRIVER_SESSION_JSON, null) ?: return null
        return runCatching { json.decodeFromString<DriverSession>(raw) }.getOrNull()
    }

    fun setDriverSession(context: Context, session: DriverSession?) {
        prefs(context).edit().apply {
            putBoolean(KEY_DRIVER_EXPLICIT, true)
            if (session == null) remove(KEY_DRIVER_SESSION_JSON)
            else putString(KEY_DRIVER_SESSION_JSON, json.encodeToString(session))
        }.commit()
    }
}

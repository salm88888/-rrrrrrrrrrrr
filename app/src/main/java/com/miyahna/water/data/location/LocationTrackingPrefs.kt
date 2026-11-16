package com.miyahna.water.data.location

import android.content.Context

/**
 * Lightweight synchronous SharedPreferences store backing permanent driver
 * background Foreground Service tracking state and Power Save parameters so
 * service restarts (START_STICKY) never race with asynchronous Room database loading.
 */
object LocationTrackingPrefs {
    private const val PREFS_NAME = "miyahna_fg_location_prefs"
    private const val KEY_PERSISTENT_TRACKING_ENABLED = "persistent_tracking_enabled"
    private const val KEY_ACTIVE_ACCEPTED_TRIP_ID = "active_accepted_trip_id"
    private const val KEY_SAMPLE_INTERVAL_SECONDS = "sample_interval_seconds"
    private const val KEY_POWER_SAVE_ENABLED = "power_save_enabled"
    private const val KEY_POWER_SAVE_INTERVAL_SECONDS = "power_save_interval_seconds"
    private const val KEY_STATIONARY_DWELL_SECONDS = "stationary_dwell_seconds"
    private const val KEY_POWER_SAVE_ACTIVE_NOW = "power_save_active_now"

    private const val DEFAULT_INTERVAL_SECONDS = 10
    private const val DEFAULT_POWER_SAVE_INTERVAL_SECONDS = 60
    private const val DEFAULT_STATIONARY_DWELL_SECONDS = 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setPersistentTracking(
        context: Context,
        enabled: Boolean,
        acceptedTripId: String? = null,
        intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
        powerSaveEnabled: Boolean = isPowerSaveEnabled(context),
        powerSaveIntervalSeconds: Int = getPowerSaveIntervalSeconds(context),
        stationaryDwellSeconds: Int = getStationaryDwellSeconds(context)
    ) {
        prefs(context).edit().apply {
            putBoolean(KEY_PERSISTENT_TRACKING_ENABLED, enabled)
            if (acceptedTripId != null) {
                putString(KEY_ACTIVE_ACCEPTED_TRIP_ID, acceptedTripId)
            } else if (!enabled) {
                remove(KEY_ACTIVE_ACCEPTED_TRIP_ID)
            }
            putInt(KEY_SAMPLE_INTERVAL_SECONDS, intervalSeconds.coerceIn(5, 60))
            putBoolean(KEY_POWER_SAVE_ENABLED, powerSaveEnabled)
            putInt(KEY_POWER_SAVE_INTERVAL_SECONDS, powerSaveIntervalSeconds.coerceIn(20, 90))
            putInt(KEY_STATIONARY_DWELL_SECONDS, stationaryDwellSeconds.coerceIn(15, 300))
            if (!enabled) {
                putBoolean(KEY_POWER_SAVE_ACTIVE_NOW, false)
            }
        }.apply()
    }

    fun setPowerSaveConfig(
        context: Context,
        enabled: Boolean,
        powerSaveIntervalSeconds: Int = DEFAULT_POWER_SAVE_INTERVAL_SECONDS,
        stationaryDwellSeconds: Int = DEFAULT_STATIONARY_DWELL_SECONDS,
        activeIntervalSeconds: Int = getSampleIntervalSeconds(context)
    ) {
        prefs(context).edit()
            .putBoolean(KEY_POWER_SAVE_ENABLED, enabled)
            .putInt(KEY_POWER_SAVE_INTERVAL_SECONDS, powerSaveIntervalSeconds.coerceIn(20, 90))
            .putInt(KEY_STATIONARY_DWELL_SECONDS, stationaryDwellSeconds.coerceIn(15, 300))
            .putInt(KEY_SAMPLE_INTERVAL_SECONDS, activeIntervalSeconds.coerceIn(5, 60))
            .apply()
    }

    fun setPowerSaveActiveNow(context: Context, active: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_POWER_SAVE_ACTIVE_NOW, active)
            .apply()
    }

    fun isPersistentTrackingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERSISTENT_TRACKING_ENABLED, false)

    fun getAcceptedTripId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_ACCEPTED_TRIP_ID, null)

    fun getSampleIntervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SAMPLE_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS).coerceIn(5, 60)

    fun isPowerSaveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POWER_SAVE_ENABLED, true)

    fun getPowerSaveIntervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_POWER_SAVE_INTERVAL_SECONDS, DEFAULT_POWER_SAVE_INTERVAL_SECONDS).coerceIn(20, 90)

    fun getStationaryDwellSeconds(context: Context): Int =
        prefs(context).getInt(KEY_STATIONARY_DWELL_SECONDS, DEFAULT_STATIONARY_DWELL_SECONDS).coerceIn(15, 300)

    fun isPowerSaveActiveNow(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POWER_SAVE_ACTIVE_NOW, false)

    fun clear(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_PERSISTENT_TRACKING_ENABLED, false)
            .putBoolean(KEY_POWER_SAVE_ACTIVE_NOW, false)
            .remove(KEY_ACTIVE_ACCEPTED_TRIP_ID)
            .apply()
    }
}

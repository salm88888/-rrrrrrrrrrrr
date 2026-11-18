package com.miyahna.water.data.location

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Helper for verifying and enforcing mandatory device-level GPS/Location availability
 * on the Driver's phone, and triggering the Play Services one-tap location resolution dialog
 * or Android Location Settings screen.
 */
object DeviceLocationHelper {

    fun isDeviceGpsEnabled(context: Context): Boolean {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return runCatching {
            LocationManagerCompat.isLocationEnabled(lm) ||
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    }

    fun promptEnableDeviceGps(
        activity: Activity,
        resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onAlreadyEnabled: () -> Unit = {}
    ) {
        if (isDeviceGpsEnabled(activity)) {
            onAlreadyEnabled()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        val settingsReq = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()

        runCatching {
            LocationServices.getSettingsClient(activity)
                .checkLocationSettings(settingsReq)
                .addOnSuccessListener {
                    if (isDeviceGpsEnabled(activity)) {
                        onAlreadyEnabled()
                    } else {
                        openLocationSettings(activity)
                    }
                }
                .addOnFailureListener { ex ->
                    if (ex is ResolvableApiException) {
                        val launched = runCatching {
                            val senderReq = IntentSenderRequest.Builder(ex.resolution).build()
                            resolutionLauncher.launch(senderReq)
                            true
                        }.getOrDefault(false)
                        if (!launched) {
                            openLocationSettings(activity)
                        }
                    } else {
                        openLocationSettings(activity)
                    }
                }
        }.onFailure {
            openLocationSettings(activity)
        }
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }
    }
}

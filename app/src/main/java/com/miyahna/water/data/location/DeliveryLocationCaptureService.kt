package com.miyahna.water.data.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.miyahna.water.MainActivity
import com.miyahna.water.MiyahnaApplication
import com.miyahna.water.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Foreground Android Service that captures the water truck's current GPS
 * coordinates on demand using FusedLocationProviderClient and persists the
 * delivery location log into Room via MiyahnaRepository.
 */
class DeliveryLocationCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private var activeTokenSource: CancellationTokenSource? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            activeTokenSource?.cancel()
            stopForegroundCompat()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val repo = (application as? MiyahnaApplication)?.repository
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            serviceScope.launch {
                repo?.reportDeliveryLocationCaptureError(getString(R.string.delivery_capture_permission_missing))
                repo?.setDeliveryLocationCapturing(false)
            }
            stopForegroundCompat()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            serviceScope.launch {
                repo?.reportDeliveryLocationCaptureError(getString(R.string.delivery_capture_unavailable))
                repo?.setDeliveryLocationCapturing(false)
            }
            stopForegroundCompat()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val tripId = intent?.getStringExtra(EXTRA_TRIP_ID)
        val tripNumber = intent?.getStringExtra(EXTRA_TRIP_NUMBER)
        val orderId = intent?.getStringExtra(EXTRA_ORDER_ID)
        val customerId = intent?.getStringExtra(EXTRA_CUSTOMER_ID)
        val customerName = intent?.getStringExtra(EXTRA_CUSTOMER_NAME)
        val deliveredLiters = intent?.takeIf { it.hasExtra(EXTRA_DELIVERED_LITERS) }
            ?.getIntExtra(EXTRA_DELIVERED_LITERS, 0)
            ?.takeIf { it > 0 }
        val notes = intent?.getStringExtra(EXTRA_NOTES)

        activeTokenSource?.cancel()
        val tokenSource = CancellationTokenSource()
        activeTokenSource = tokenSource

        serviceScope.launch {
            repo?.setDeliveryLocationCapturing(true)
            try {
                val location = fetchCurrentOrLastLocation(hasFine, tokenSource)
                if (location != null && repo != null) {
                    val speedKmH = if (location.hasSpeed()) (location.speed * 3.6) else 0.0
                    val accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                    val altitudeMeters = if (location.hasAltitude()) location.altitude else null
                    repo.logCapturedDeliveryLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = accuracyMeters,
                        altitudeMeters = altitudeMeters,
                        speedKmH = speedKmH,
                        tripId = tripId,
                        tripNumber = tripNumber,
                        orderId = orderId,
                        customerId = customerId,
                        customerName = customerName,
                        deliveredLiters = deliveredLiters,
                        notes = notes
                    )
                } else {
                    repo?.reportDeliveryLocationCaptureError(getString(R.string.delivery_capture_unavailable))
                }
            } catch (sec: SecurityException) {
                repo?.reportDeliveryLocationCaptureError(
                    sec.message ?: getString(R.string.delivery_capture_permission_missing)
                )
            } catch (t: Throwable) {
                repo?.reportDeliveryLocationCaptureError(
                    t.message ?: getString(R.string.delivery_capture_unavailable)
                )
            } finally {
                repo?.setDeliveryLocationCapturing(false)
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    @Throws(SecurityException::class)
    private suspend fun fetchCurrentOrLastLocation(
        hasFinePermission: Boolean,
        tokenSource: CancellationTokenSource
    ): Location? {
        val priority = if (hasFinePermission) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setDurationMillis(LOCATION_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MS)
            .build()

        val fresh = runCatching {
            fusedClient.getCurrentLocation(request, tokenSource.token).await()
        }.getOrNull()

        return fresh ?: runCatching {
            fusedClient.lastLocation.await()
        }.getOrNull()
    }

    private fun promoteToForeground(): Boolean {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.delivery_capture_notification_title))
            .setContentText(getString(R.string.delivery_capture_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
            true
        }.getOrDefault(false)
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_delivery_capture_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_delivery_capture_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        activeTokenSource?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "miyahna_delivery_capture"
        const val NOTIFICATION_ID = 4402
        const val ACTION_CAPTURE_DELIVERY_LOCATION = "com.miyahna.water.action.CAPTURE_DELIVERY_LOCATION"
        const val ACTION_CANCEL = "com.miyahna.water.action.CANCEL_DELIVERY_CAPTURE"

        const val EXTRA_TRIP_ID = "extra_trip_id"
        const val EXTRA_TRIP_NUMBER = "extra_trip_number"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_CUSTOMER_ID = "extra_customer_id"
        const val EXTRA_CUSTOMER_NAME = "extra_customer_name"
        const val EXTRA_DELIVERED_LITERS = "extra_delivered_liters"
        const val EXTRA_NOTES = "extra_notes"

        private const val LOCATION_TIMEOUT_MS = 10_000L
        private const val MAX_LOCATION_AGE_MS = 5_000L

        fun captureCurrentLocation(
            context: Context,
            tripId: String? = null,
            tripNumber: String? = null,
            orderId: String? = null,
            customerId: String? = null,
            customerName: String? = null,
            deliveredLiters: Int? = null,
            notes: String? = null
        ): Boolean {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                return false
            }
            val intent = Intent(context, DeliveryLocationCaptureService::class.java).apply {
                action = ACTION_CAPTURE_DELIVERY_LOCATION
                tripId?.let { putExtra(EXTRA_TRIP_ID, it) }
                tripNumber?.let { putExtra(EXTRA_TRIP_NUMBER, it) }
                orderId?.let { putExtra(EXTRA_ORDER_ID, it) }
                customerId?.let { putExtra(EXTRA_CUSTOMER_ID, it) }
                customerName?.let { putExtra(EXTRA_CUSTOMER_NAME, it) }
                deliveredLiters?.let { putExtra(EXTRA_DELIVERED_LITERS, it) }
                notes?.let { putExtra(EXTRA_NOTES, it) }
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }
    }
}

package com.miyahna.water.data.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.miyahna.water.MainActivity
import com.miyahna.water.MiyahnaApplication
import com.miyahna.water.R
import com.miyahna.water.data.sync.TripSyncWorker
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.TripStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persistent Foreground Location Service for Driver Mode.
 * Starts permanently as soon as the driver accepts a trip request or enters Driver Mode,
 * continues capturing and dispatching GPS coordinates even when the app exits the foreground
 * or is removed from recents, and resumes automatically via START_STICKY.
 *
 * Includes adaptive Power Save mode: lowers location polling frequency and GNSS accuracy
 * draw once the vehicle remains stationary past the configured dwell threshold while keeping
 * updates strictly inside gpsGapSeconds to maintain tracking integrity, and wakes back to
 * high-frequency tracking immediately on movement or hardware significant-motion trigger.
 */
class LocationTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var statusObserverJob: Job? = null
    private var settingsObserverJob: Job? = null
    private var immediateFixTokenSource: CancellationTokenSource? = null

    private val powerSavePolicy = GpsPowerSavePolicy()
    private var sensorManager: SensorManager? = null
    private var significantMotionSensor: Sensor? = null
    private var isSignificantMotionArmed = false

    @Volatile
    internal var activeLocationRequest: LocationRequest? = null
        private set

    internal val currentPowerSaveMode: GpsTrackingMode
        get() = powerSavePolicy.currentMode()

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isSignificantMotionArmed = false
            val decision = powerSavePolicy.onSignificantMotionDetected()
            if (decision.modeChanged) {
                applyPowerSaveDecision(decision, fetchImmediateFix = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = runCatching { getSystemService(SensorManager::class.java) }.getOrNull()
        significantMotionSensor = runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        }.getOrNull()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as? MiyahnaApplication)?.repository
        if (intent?.action == ACTION_STOP) {
            LocationTrackingPrefs.clear(this)
            disarmSignificantMotionTrigger()
            powerSavePolicy.reset()
            stopLocationUpdates()
            statusObserverJob?.cancel()
            settingsObserverJob?.cancel()
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val acceptedTripId = intent?.getStringExtra(EXTRA_ACCEPTED_TRIP_ID)
            ?: LocationTrackingPrefs.getAcceptedTripId(this)

        val settings = repo?.state?.value?.settings
        val storedInterval = LocationTrackingPrefs.getSampleIntervalSeconds(this)
        val configuredInterval = settings?.gpsSampleIntervalSeconds ?: storedInterval
        val intervalSeconds = (intent?.getIntExtra(EXTRA_INTERVAL_SECONDS, configuredInterval)
            ?: configuredInterval).coerceIn(5, 60)

        val storedPowerSaveEnabled = LocationTrackingPrefs.isPowerSaveEnabled(this)
        val powerSaveEnabled = intent?.takeIf { it.hasExtra(EXTRA_POWER_SAVE_ENABLED) }
            ?.getBooleanExtra(EXTRA_POWER_SAVE_ENABLED, storedPowerSaveEnabled)
            ?: settings?.gpsPowerSaveEnabled
            ?: storedPowerSaveEnabled

        val storedPowerSaveInterval = LocationTrackingPrefs.getPowerSaveIntervalSeconds(this)
        val powerSaveIntervalSeconds = (intent?.takeIf { it.hasExtra(EXTRA_POWER_SAVE_INTERVAL_SECONDS) }
            ?.getIntExtra(EXTRA_POWER_SAVE_INTERVAL_SECONDS, storedPowerSaveInterval)
            ?: settings?.gpsPowerSaveIntervalSeconds
            ?: storedPowerSaveInterval).coerceIn(20, 90)

        val storedDwellSeconds = LocationTrackingPrefs.getStationaryDwellSeconds(this)
        val stationaryDwellSeconds = (intent?.takeIf { it.hasExtra(EXTRA_STATIONARY_DWELL_SECONDS) }
            ?.getIntExtra(EXTRA_STATIONARY_DWELL_SECONDS, storedDwellSeconds)
            ?: settings?.gpsStationaryDwellSeconds
            ?: storedDwellSeconds).coerceIn(15, 300)

        if (intent?.action == ACTION_START) {
            LocationTrackingPrefs.setPersistentTracking(
                context = this,
                enabled = true,
                acceptedTripId = acceptedTripId,
                intervalSeconds = intervalSeconds,
                powerSaveEnabled = powerSaveEnabled,
                powerSaveIntervalSeconds = powerSaveIntervalSeconds,
                stationaryDwellSeconds = stationaryDwellSeconds
            )
        } else if (intent?.action == ACTION_UPDATE_CONFIG) {
            LocationTrackingPrefs.setPowerSaveConfig(
                context = this,
                enabled = powerSaveEnabled,
                powerSaveIntervalSeconds = powerSaveIntervalSeconds,
                stationaryDwellSeconds = stationaryDwellSeconds,
                activeIntervalSeconds = intervalSeconds
            )
        }

        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            disarmSignificantMotionTrigger()
            LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        val initialTitle = getString(R.string.tracking_notification_title)
        val initialText = getString(R.string.tracking_notification_text)
        if (!promoteToForeground(buildNotification(title = initialTitle, text = initialText))) {
            disarmSignificantMotionTrigger()
            LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        val configDecision = powerSavePolicy.updateConfig(
            buildPolicyConfig(
                settings = settings,
                activeIntervalSeconds = intervalSeconds,
                powerSaveEnabled = powerSaveEnabled,
                powerSaveIntervalSeconds = powerSaveIntervalSeconds,
                stationaryDwellSeconds = stationaryDwellSeconds,
                hasFinePermission = hasFine
            )
        )

        if (intent?.action == ACTION_UPDATE_CONFIG && locationCallback != null) {
            if (configDecision.modeChanged) {
                applyPowerSaveDecision(configDecision, fetchImmediateFix = configDecision.mode == GpsTrackingMode.ACTIVE)
            }
        } else {
            startLocationUpdates(
                intervalSeconds = configDecision.effectiveIntervalSeconds,
                useHighAccuracy = configDecision.useHighAccuracy,
                fetchImmediateFix = true
            )
            syncPowerSaveIndicators(configDecision.mode)
        }

        observeRepositoryForNotificationUpdates()
        observeSettingsForPowerSaveUpdates()

        // Verify persisted driver/trip state on START_STICKY system restarts (intent == null)
        if (intent == null && repo != null) {
            serviceScope.launch {
                repo.awaitInitialized()
                val shouldRun = LocationTrackingPrefs.isPersistentTrackingEnabled(this@LocationTrackingService) ||
                    repo.shouldKeepDriverBackgroundGpsRunning()
                if (!shouldRun) {
                    disarmSignificantMotionTrigger()
                    LocationTrackingPrefs.setPowerSaveActiveNow(this@LocationTrackingService, false)
                    stopLocationUpdates()
                    repo.setBackgroundGpsActive(false)
                    repo.setGpsPowerSaveActive(false)
                    runCatching {
                        ServiceCompat.stopForeground(this@LocationTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun buildPolicyConfig(
        settings: SystemSettings?,
        activeIntervalSeconds: Int,
        powerSaveEnabled: Boolean,
        powerSaveIntervalSeconds: Int,
        stationaryDwellSeconds: Int,
        hasFinePermission: Boolean
    ): GpsPowerSaveConfig = GpsPowerSaveConfig(
        powerSaveEnabled = powerSaveEnabled,
        activeIntervalSeconds = activeIntervalSeconds,
        powerSaveIntervalSeconds = powerSaveIntervalSeconds,
        stationaryDwellSeconds = stationaryDwellSeconds,
        minMovementSpeedKmH = settings?.minMovementSpeedKmH ?: 8.0,
        stopRadiusMeters = settings?.stopRadiusMeters ?: 80,
        gpsGapSeconds = settings?.gpsGapSeconds ?: 120,
        gpsWeakAccuracyMeters = settings?.gpsWeakAccuracyMeters ?: 60,
        hasFinePermission = hasFinePermission
    )

    private fun promoteToForeground(notification: Notification): Boolean {
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

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun observeRepositoryForNotificationUpdates() {
        val repo = (application as? MiyahnaApplication)?.repository ?: return
        statusObserverJob?.cancel()
        statusObserverJob = serviceScope.launch {
            repo.state
                .map { s ->
                    val activeTrip = s.trips.firstOrNull {
                        it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED
                    }
                    val readyTrip = s.trips.firstOrNull {
                        it.status == TripStatus.APPROVED && it.driverAcceptanceStatus == "accepted"
                    } ?: s.trips.firstOrNull { it.status == TripStatus.APPROVED }
                    Pair(activeTrip?.tripNumber ?: readyTrip?.tripNumber, s.isOnline)
                }
                .distinctUntilChanged()
                .collect { (tripNumber, isOnline) ->
                    val netLabel = if (isOnline) "متصل" else "عمل محلي"
                    val title = if (tripNumber != null) {
                        "مياهنا — رحلة $tripNumber"
                    } else {
                        getString(R.string.tracking_notification_title)
                    }
                    val text = "${getString(R.string.tracking_notification_text)} · $netLabel"
                    val nm = getSystemService(NotificationManager::class.java)
                    runCatching {
                        nm?.notify(NOTIFICATION_ID, buildNotification(title, text))
                    }
                }
        }
    }

    private fun observeSettingsForPowerSaveUpdates() {
        val repo = (application as? MiyahnaApplication)?.repository ?: return
        settingsObserverJob?.cancel()
        settingsObserverJob = serviceScope.launch {
            repo.state
                .map { it.settings }
                .distinctUntilChanged()
                .collect { settings ->
                    val hasFine = ContextCompat.checkSelfPermission(
                        this@LocationTrackingService,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        this@LocationTrackingService,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasFine && !hasCoarse) return@collect

                    LocationTrackingPrefs.setPowerSaveConfig(
                        context = this@LocationTrackingService,
                        enabled = settings.gpsPowerSaveEnabled,
                        powerSaveIntervalSeconds = settings.gpsPowerSaveIntervalSeconds,
                        stationaryDwellSeconds = settings.gpsStationaryDwellSeconds,
                        activeIntervalSeconds = settings.gpsSampleIntervalSeconds
                    )

                    val decision = powerSavePolicy.updateConfig(
                        buildPolicyConfig(
                            settings = settings,
                            activeIntervalSeconds = settings.gpsSampleIntervalSeconds,
                            powerSaveEnabled = settings.gpsPowerSaveEnabled,
                            powerSaveIntervalSeconds = settings.gpsPowerSaveIntervalSeconds,
                            stationaryDwellSeconds = settings.gpsStationaryDwellSeconds,
                            hasFinePermission = hasFine
                        )
                    )
                    if (decision.modeChanged && locationCallback != null) {
                        applyPowerSaveDecision(decision, fetchImmediateFix = decision.mode == GpsTrackingMode.ACTIVE)
                    }
                }
        }
    }

    internal fun handleLocationSample(
        lat: Double,
        lng: Double,
        speedKmH: Double,
        accuracy: Double?,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val decision = powerSavePolicy.onLocationSample(
            lat = lat,
            lng = lng,
            speedKmH = speedKmH,
            accuracy = accuracy,
            timestampMs = timestampMs
        )
        if (decision.modeChanged) {
            applyPowerSaveDecision(
                decision = decision,
                fetchImmediateFix = false
            )
        }
        dispatchLocationFix(
            lat = lat,
            lng = lng,
            speedKmH = speedKmH,
            accuracy = accuracy
        )
    }

    private fun applyPowerSaveDecision(
        decision: GpsPowerSaveDecision,
        fetchImmediateFix: Boolean
    ) {
        syncPowerSaveIndicators(decision.mode)
        if (decision.mode == GpsTrackingMode.POWER_SAVE_STATIONARY) {
            armSignificantMotionTrigger()
        } else {
            disarmSignificantMotionTrigger()
        }
        startLocationUpdates(
            intervalSeconds = decision.effectiveIntervalSeconds,
            useHighAccuracy = decision.useHighAccuracy,
            fetchImmediateFix = fetchImmediateFix
        )
    }

    private fun syncPowerSaveIndicators(mode: GpsTrackingMode) {
        val isPowerSave = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
        LocationTrackingPrefs.setPowerSaveActiveNow(this, isPowerSave)
        val repo = (application as? MiyahnaApplication)?.repository
        repo?.setGpsPowerSaveActive(isPowerSave)
    }

    private fun armSignificantMotionTrigger() {
        val mgr = sensorManager ?: return
        val sensor = significantMotionSensor ?: return
        if (!isSignificantMotionArmed) {
            isSignificantMotionArmed = runCatching {
                mgr.requestTriggerSensor(significantMotionListener, sensor)
            }.getOrDefault(false)
        }
    }

    private fun disarmSignificantMotionTrigger() {
        if (!isSignificantMotionArmed) return
        val mgr = sensorManager
        val sensor = significantMotionSensor
        if (mgr != null && sensor != null) {
            runCatching { mgr.cancelTriggerSensor(significantMotionListener, sensor) }
        }
        isSignificantMotionArmed = false
    }

    internal fun buildLocationRequest(
        intervalSeconds: Int,
        useHighAccuracy: Boolean
    ): LocationRequest {
        val intervalMs = intervalSeconds.coerceIn(5, 90) * 1000L
        val priority = if (useHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val minIntervalMs = if (useHighAccuracy) {
            (intervalMs / 2).coerceAtLeast(3_000L)
        } else {
            (intervalMs * 3 / 4).coerceAtLeast(15_000L)
        }
        return LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            // Keep distance 0f so stationary updates arrive inside gpsGapSeconds (no false GPS gaps)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun startLocationUpdates(
        intervalSeconds: Int,
        useHighAccuracy: Boolean,
        fetchImmediateFix: Boolean
    ) {
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
            disarmSignificantMotionTrigger()
            LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        stopLocationUpdates()
        val effectiveHighAccuracy = hasFine && useHighAccuracy
        val priority = if (effectiveHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = buildLocationRequest(intervalSeconds, effectiveHighAccuracy)
        activeLocationRequest = request

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                handleLocationSample(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    speedKmH = if (loc.hasSpeed()) (loc.speed * 3.6) else 0.0,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    timestampMs = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
        }
        locationCallback = callback
        try {
            if (fetchImmediateFix) {
                // Immediately dispatch last known / fresh GPS fix so Manager sees location right on trip acceptance
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        dispatchLocationFix(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            speedKmH = if (loc.hasSpeed()) (loc.speed * 3.6) else 0.0,
                            accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                        )
                    }
                }
                val tokenSource = CancellationTokenSource()
                immediateFixTokenSource = tokenSource
                fusedClient.getCurrentLocation(priority, tokenSource.token).addOnSuccessListener { loc ->
                    if (loc != null) {
                        handleLocationSample(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            speedKmH = if (loc.hasSpeed()) (loc.speed * 3.6) else 0.0,
                            accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                            timestampMs = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                    }
                }
            }

            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            repo?.setBackgroundGpsActive(true)
        } catch (_: SecurityException) {
            disarmSignificantMotionTrigger()
            LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun dispatchLocationFix(
        lat: Double,
        lng: Double,
        speedKmH: Double,
        accuracy: Double?
    ) {
        val activeRepo = (application as? MiyahnaApplication)?.repository ?: return
        serviceScope.launch {
            activeRepo.recordLiveGpsFix(
                lat = lat,
                lng = lng,
                speedKmH = speedKmH,
                accuracy = accuracy,
                source = "gps_android_fg"
            )
        }
    }

    private fun stopLocationUpdates() {
        immediateFixTokenSource?.cancel()
        immediateFixTokenSource = null
        locationCallback?.let { cb ->
            runCatching { fusedClient.removeLocationUpdates(cb) }
        }
        locationCallback = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_tracking_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep background GPS active when the user swipes/exits the app in Driver mode or with an accepted trip
        val repo = (application as? MiyahnaApplication)?.repository
        val state = repo?.state?.value
        val persistedEnabled = LocationTrackingPrefs.isPersistentTrackingEnabled(this)
        val loadedDriverActive = state?.isLoaded == true &&
            state.savedRole == AppRole.DRIVER &&
            state.driverSession != null
        val keepAlive = persistedEnabled || loadedDriverActive || state?.isLoaded != true

        if (keepAlive) {
            TripSyncWorker.enqueueWhenOnline(this)
        } else {
            disarmSignificantMotionTrigger()
            LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
            stopLocationUpdates()
            repo?.setBackgroundGpsActive(false)
            repo?.setGpsPowerSaveActive(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val repo = (application as? MiyahnaApplication)?.repository
        disarmSignificantMotionTrigger()
        LocationTrackingPrefs.setPowerSaveActiveNow(this, false)
        repo?.setBackgroundGpsActive(false)
        repo?.setGpsPowerSaveActive(false)
        statusObserverJob?.cancel()
        settingsObserverJob?.cancel()
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "miyahna_gps_tracking"
        const val NOTIFICATION_ID = 4401
        const val ACTION_START = "com.miyahna.water.action.START_TRACKING"
        const val ACTION_UPDATE_CONFIG = "com.miyahna.water.action.UPDATE_TRACKING_CONFIG"
        const val ACTION_STOP = "com.miyahna.water.action.STOP_TRACKING"
        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"
        const val EXTRA_ACCEPTED_TRIP_ID = "extra_accepted_trip_id"
        const val EXTRA_POWER_SAVE_ENABLED = "extra_power_save_enabled"
        const val EXTRA_POWER_SAVE_INTERVAL_SECONDS = "extra_power_save_interval_seconds"
        const val EXTRA_STATIONARY_DWELL_SECONDS = "extra_stationary_dwell_seconds"

        fun start(
            context: Context,
            intervalSeconds: Int,
            acceptedTripId: String? = null,
            powerSaveEnabled: Boolean = LocationTrackingPrefs.isPowerSaveEnabled(context),
            powerSaveIntervalSeconds: Int = LocationTrackingPrefs.getPowerSaveIntervalSeconds(context),
            stationaryDwellSeconds: Int = LocationTrackingPrefs.getStationaryDwellSeconds(context)
        ) {
            LocationTrackingPrefs.setPersistentTracking(
                context = context,
                enabled = true,
                acceptedTripId = acceptedTripId,
                intervalSeconds = intervalSeconds,
                powerSaveEnabled = powerSaveEnabled,
                powerSaveIntervalSeconds = powerSaveIntervalSeconds,
                stationaryDwellSeconds = stationaryDwellSeconds
            )
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds.coerceIn(5, 60))
                putExtra(EXTRA_POWER_SAVE_ENABLED, powerSaveEnabled)
                putExtra(EXTRA_POWER_SAVE_INTERVAL_SECONDS, powerSaveIntervalSeconds.coerceIn(20, 90))
                putExtra(EXTRA_STATIONARY_DWELL_SECONDS, stationaryDwellSeconds.coerceIn(15, 300))
                acceptedTripId?.let { putExtra(EXTRA_ACCEPTED_TRIP_ID, it) }
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun updatePowerSaveConfig(context: Context, settings: SystemSettings) {
            LocationTrackingPrefs.setPowerSaveConfig(
                context = context,
                enabled = settings.gpsPowerSaveEnabled,
                powerSaveIntervalSeconds = settings.gpsPowerSaveIntervalSeconds,
                stationaryDwellSeconds = settings.gpsStationaryDwellSeconds,
                activeIntervalSeconds = settings.gpsSampleIntervalSeconds
            )
            if (!LocationTrackingPrefs.isPersistentTrackingEnabled(context)) return
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_INTERVAL_SECONDS, settings.gpsSampleIntervalSeconds.coerceIn(5, 60))
                putExtra(EXTRA_POWER_SAVE_ENABLED, settings.gpsPowerSaveEnabled)
                putExtra(EXTRA_POWER_SAVE_INTERVAL_SECONDS, settings.gpsPowerSaveIntervalSeconds.coerceIn(20, 90))
                putExtra(EXTRA_STATIONARY_DWELL_SECONDS, settings.gpsStationaryDwellSeconds.coerceIn(15, 300))
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            LocationTrackingPrefs.clear(context)
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}

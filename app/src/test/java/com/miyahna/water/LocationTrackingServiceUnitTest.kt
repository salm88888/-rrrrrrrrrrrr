package com.miyahna.water

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Priority
import com.miyahna.water.data.location.DeliveryLocationCaptureService
import com.miyahna.water.data.location.GpsPowerSaveConfig
import com.miyahna.water.data.location.GpsPowerSavePolicy
import com.miyahna.water.data.location.GpsTrackingMode
import com.miyahna.water.data.location.LocationTrackingPrefs
import com.miyahna.water.data.location.LocationTrackingService
import com.miyahna.water.domain.GpsTrackingEngine
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.TripGPSPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationTrackingServiceUnitTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        LocationTrackingPrefs.clear(app)
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    @Test
    fun startPersistsAcceptedTripStateAndLaunchesForegroundService() {
        LocationTrackingService.start(app, intervalSeconds = 12, acceptedTripId = "trip-accept-77")

        assertTrue(LocationTrackingPrefs.isPersistentTrackingEnabled(app))
        assertEquals("trip-accept-77", LocationTrackingPrefs.getAcceptedTripId(app))
        assertEquals(12, LocationTrackingPrefs.getSampleIntervalSeconds(app))
        assertTrue(LocationTrackingPrefs.isPowerSaveEnabled(app))

        val startedIntent = shadowOf(app).nextStartedService
        assertEquals(LocationTrackingService.ACTION_START, startedIntent.action)
        assertEquals("trip-accept-77", startedIntent.getStringExtra(LocationTrackingService.EXTRA_ACCEPTED_TRIP_ID))
        assertEquals(12, startedIntent.getIntExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, -1))
    }

    @Test
    fun serviceReturnsStartStickySurvivesTaskRemovalAndStopsOnlyOnExplicitStop() {
        val controller = Robolectric.buildService(
            LocationTrackingService::class.java,
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, 10)
                putExtra(LocationTrackingService.EXTRA_ACCEPTED_TRIP_ID, "trip-accept-88")
            }
        ).create()
        val service = controller.get()

        val startMode = service.onStartCommand(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, 10)
                putExtra(LocationTrackingService.EXTRA_ACCEPTED_TRIP_ID, "trip-accept-88")
            },
            0,
            1
        )
        assertEquals(Service.START_STICKY, startMode)
        assertTrue(LocationTrackingPrefs.isPersistentTrackingEnabled(app))

        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        assertTrue(shadowNm.allNotifications.any { it.channelId == LocationTrackingService.CHANNEL_ID })

        // Simulate user swiping app away from Recents: service must remain alive & keep prefs enabled
        service.onTaskRemoved(Intent(Intent.ACTION_MAIN))
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(LocationTrackingPrefs.isPersistentTrackingEnabled(app))

        // Explicit ACTION_STOP (e.g., driver logout or switching away from Driver role) clears prefs & stops
        service.onStartCommand(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            },
            0,
            2
        )
        assertFalse(LocationTrackingPrefs.isPersistentTrackingEnabled(app))
        assertNull(LocationTrackingPrefs.getAcceptedTripId(app))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun servicesWithoutLocationPermissionStopGracefullyWithoutSecurityException() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val captureLaunched = DeliveryLocationCaptureService.captureCurrentLocation(app)
        assertFalse(captureLaunched)

        val trackService = Robolectric.buildService(
            LocationTrackingService::class.java,
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            }
        ).create().get()
        trackService.onStartCommand(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            },
            0,
            1
        )
        assertTrue(shadowOf(trackService).isStoppedBySelf)

        val captureService = Robolectric.buildService(
            DeliveryLocationCaptureService::class.java,
            Intent(app, DeliveryLocationCaptureService::class.java)
        ).create().get()
        captureService.onStartCommand(
            Intent(app, DeliveryLocationCaptureService::class.java),
            0,
            1
        )
        assertTrue(shadowOf(captureService).isStoppedBySelf)
    }

    @Test
    fun powerSavePolicyReducesFrequencyWhenStationaryAndWakesImmediatelyOnMovementOrMotion() {
        val policy = GpsPowerSavePolicy(
            GpsPowerSaveConfig(
                powerSaveEnabled = true,
                activeIntervalSeconds = 10,
                powerSaveIntervalSeconds = 60,
                stationaryDwellSeconds = 45,
                minMovementSpeedKmH = 8.0,
                stopRadiusMeters = 80,
                gpsGapSeconds = 120,
                gpsWeakAccuracyMeters = 60,
                hasFinePermission = true
            )
        )

        // Initial stationary fix establishes anchor without premature drop
        val d0 = policy.onLocationSample(15.4920, 45.3620, speedKmH = 0.0, accuracy = 10.0, timestampMs = 1_000L)
        assertEquals(GpsTrackingMode.ACTIVE, d0.mode)
        assertEquals(10, d0.effectiveIntervalSeconds)
        assertTrue(d0.useHighAccuracy)

        // Small jitter below dwell time stays active
        val d1 = policy.onLocationSample(15.49205, 45.36205, speedKmH = 0.5, accuracy = 12.0, timestampMs = 30_000L)
        assertEquals(GpsTrackingMode.ACTIVE, d1.mode)
        assertFalse(d1.modeChanged)

        // Crossing stationary dwell threshold enters POWER_SAVE_STATIONARY
        val d2 = policy.onLocationSample(15.49208, 45.36208, speedKmH = 0.0, accuracy = 12.0, timestampMs = 50_000L)
        assertEquals(GpsTrackingMode.POWER_SAVE_STATIONARY, d2.mode)
        assertTrue(d2.modeChanged)
        assertEquals(60, d2.effectiveIntervalSeconds)
        assertFalse(d2.useHighAccuracy)

        // Driving away wakes back to ACTIVE immediately
        val d3 = policy.onLocationSample(15.4922, 45.3622, speedKmH = 18.0, accuracy = 8.0, timestampMs = 70_000L)
        assertEquals(GpsTrackingMode.ACTIVE, d3.mode)
        assertTrue(d3.modeChanged)
        assertEquals(10, d3.effectiveIntervalSeconds)
        assertTrue(d3.useHighAccuracy)

        // Re-enter stationary power save and verify significant motion wake-up
        policy.onLocationSample(15.4950, 45.3650, speedKmH = 0.0, accuracy = 10.0, timestampMs = 100_000L)
        val enteredAgain = policy.onLocationSample(15.49502, 45.36502, speedKmH = 0.0, accuracy = 10.0, timestampMs = 150_000L)
        assertEquals(GpsTrackingMode.POWER_SAVE_STATIONARY, enteredAgain.mode)

        val motionWake = policy.onSignificantMotionDetected()
        assertEquals(GpsTrackingMode.ACTIVE, motionWake.mode)
        assertTrue(motionWake.modeChanged)
        assertEquals(10, motionWake.effectiveIntervalSeconds)
    }

    @Test
    fun powerSaveIntervalRespectsGpsGapThresholdToMaintainTrackingIntegrity() {
        val settings = SystemSettings(
            gpsSampleIntervalSeconds = 10,
            gpsPowerSaveEnabled = true,
            gpsPowerSaveIntervalSeconds = 90,
            gpsGapSeconds = 120,
            stopDetectionMinutes = 3
        )
        val config = GpsPowerSaveConfig(
            powerSaveEnabled = settings.gpsPowerSaveEnabled,
            activeIntervalSeconds = settings.gpsSampleIntervalSeconds,
            powerSaveIntervalSeconds = settings.gpsPowerSaveIntervalSeconds,
            stationaryDwellSeconds = settings.gpsStationaryDwellSeconds,
            gpsGapSeconds = settings.gpsGapSeconds
        )
        // Stationary interval is capped strictly below gpsGapSeconds (0.65 * 120 = 78s)
        assertTrue(config.safeStationaryIntervalSeconds < settings.gpsGapSeconds)

        // Simulate 4 stationary points spaced at safeStationaryIntervalSeconds -> 0 GPS gaps & stop detected!
        val intervalSec = config.safeStationaryIntervalSeconds.toLong()
        val pts = (0..3).map { i ->
            TripGPSPoint(
                id = "ps-$i",
                tripId = "trip-ps-integrity",
                lat = 15.4950,
                lng = 45.3650,
                speedKmH = 0.0,
                accuracy = 15.0,
                timestamp = java.time.Instant.ofEpochSecond(1_700_000_000L + i * intervalSec).toString(),
                seq = i + 1
            )
        }
        val analysis = GpsTrackingEngine.analyzeGpsTrack("trip-ps-integrity", pts, emptyList(), settings)
        assertEquals("Power save interval must not create false GPS gaps", 0, analysis.gpsGaps.size)
        assertEquals("Stop detection must still trigger accurately", 1, analysis.detectedStops.size)
    }

    @Test
    fun serviceAdaptsLocationRequestFrequencyBetweenStationaryAndMoving() {
        val controller = Robolectric.buildService(
            LocationTrackingService::class.java,
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            }
        ).create()
        val service = controller.get()

        service.onStartCommand(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_INTERVAL_SECONDS, 10)
                putExtra(LocationTrackingService.EXTRA_POWER_SAVE_ENABLED, true)
                putExtra(LocationTrackingService.EXTRA_POWER_SAVE_INTERVAL_SECONDS, 60)
                putExtra(LocationTrackingService.EXTRA_STATIONARY_DWELL_SECONDS, 30)
            },
            0,
            1
        )

        val initialReq = service.activeLocationRequest
        assertNotNull(initialReq)
        assertEquals(GpsTrackingMode.ACTIVE, service.currentPowerSaveMode)
        assertEquals(10_000L, initialReq?.intervalMillis)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, initialReq?.priority)

        // Feed stationary samples crossing the 30s dwell threshold
        service.handleLocationSample(15.4920, 45.3620, speedKmH = 0.0, accuracy = 10.0, timestampMs = 1_000L)
        service.handleLocationSample(15.49201, 45.36201, speedKmH = 0.2, accuracy = 10.0, timestampMs = 35_000L)

        val stationaryReq = service.activeLocationRequest
        assertEquals(GpsTrackingMode.POWER_SAVE_STATIONARY, service.currentPowerSaveMode)
        assertTrue(LocationTrackingPrefs.isPowerSaveActiveNow(app))
        assertEquals(60_000L, stationaryReq?.intervalMillis)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, stationaryReq?.priority)

        // Feed movement sample -> restores active 10s HIGH_ACCURACY immediately
        service.handleLocationSample(15.4930, 45.3630, speedKmH = 25.0, accuracy = 8.0, timestampMs = 95_000L)

        val resumedReq = service.activeLocationRequest
        assertEquals(GpsTrackingMode.ACTIVE, service.currentPowerSaveMode)
        assertFalse(LocationTrackingPrefs.isPowerSaveActiveNow(app))
        assertEquals(10_000L, resumedReq?.intervalMillis)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, resumedReq?.priority)
    }
}

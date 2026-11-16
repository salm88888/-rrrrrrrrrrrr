package com.miyahna.water.data.location

import com.miyahna.water.domain.GpsTrackingEngine
import kotlin.math.max
import kotlin.math.min

enum class GpsTrackingMode {
    ACTIVE,
    POWER_SAVE_STATIONARY
}

data class GpsPowerSaveConfig(
    val powerSaveEnabled: Boolean = true,
    val activeIntervalSeconds: Int = 10,
    val powerSaveIntervalSeconds: Int = 60,
    val stationaryDwellSeconds: Int = 60,
    val minMovementSpeedKmH: Double = 8.0,
    val stopRadiusMeters: Int = 80,
    val gpsGapSeconds: Int = 120,
    val gpsWeakAccuracyMeters: Int = 60,
    val hasFinePermission: Boolean = true
) {
    val safeActiveIntervalSeconds: Int
        get() = activeIntervalSeconds.coerceIn(5, 60)

    /**
     * Stationary update interval reduces wakeups significantly while staying strictly
     * below gpsGapSeconds so GpsTrackingEngine never records false GPS signal gaps.
     */
    val safeStationaryIntervalSeconds: Int
        get() {
            val active = safeActiveIntervalSeconds
            val maxSafeBeforeGap = max(active, (gpsGapSeconds.coerceAtLeast(30) * 0.65).toInt())
            val requested = powerSaveIntervalSeconds.coerceIn(max(20, active * 2), 90)
            return min(requested, maxSafeBeforeGap).coerceAtLeast(active)
        }

    val safeStationaryDwellMs: Long
        get() = stationaryDwellSeconds.coerceIn(15, 300) * 1000L

    val safeMinMovementSpeedKmH: Double
        get() = minMovementSpeedKmH.takeIf { it.isFinite() && it > 1.0 } ?: 8.0

    val stationarySpeedThresholdKmH: Double
        get() = (safeMinMovementSpeedKmH * 0.5).coerceIn(1.5, 4.0)

    val stationaryRadiusMeters: Double
        get() = stopRadiusMeters.toDouble().coerceIn(25.0, 60.0)

    val wakeDistanceMeters: Double
        get() = max(stationaryRadiusMeters, 45.0)

    val maxReliableAccuracyMeters: Double
        get() = max(gpsWeakAccuracyMeters.toDouble(), 65.0)
}

data class GpsPowerSaveDecision(
    val mode: GpsTrackingMode,
    val effectiveIntervalSeconds: Int,
    val useHighAccuracy: Boolean,
    val modeChanged: Boolean,
    val reason: String
)

/**
 * Adaptive GPS frequency state machine that lowers location polling cadence and GNSS
 * accuracy power draw when the vehicle remains stationary past a dwell threshold,
 * while instantly restoring high-frequency tracking on movement or significant-motion wake-up.
 */
class GpsPowerSavePolicy(
    initialConfig: GpsPowerSaveConfig = GpsPowerSaveConfig()
) {
    private val lock = Any()
    private var config: GpsPowerSaveConfig = initialConfig
    private var mode: GpsTrackingMode = GpsTrackingMode.ACTIVE
    private var anchorLat: Double? = null
    private var anchorLng: Double? = null
    private var stationarySinceMs: Long? = null

    fun currentMode(): GpsTrackingMode = synchronized(lock) { mode }

    fun currentDecision(reason: String = "snapshot"): GpsPowerSaveDecision = synchronized(lock) {
        buildDecision(modeChanged = false, reason = reason)
    }

    fun updateConfig(newConfig: GpsPowerSaveConfig): GpsPowerSaveDecision = synchronized(lock) {
        val prevMode = mode
        val prevInterval = effectiveIntervalFor(prevMode, config)
        val prevHighAccuracy = highAccuracyFor(prevMode, config)
        config = newConfig

        if (!newConfig.powerSaveEnabled && mode == GpsTrackingMode.POWER_SAVE_STATIONARY) {
            mode = GpsTrackingMode.ACTIVE
            anchorLat = null
            anchorLng = null
            stationarySinceMs = null
        }

        val nextInterval = effectiveIntervalFor(mode, newConfig)
        val nextHighAccuracy = highAccuracyFor(mode, newConfig)
        val changed = (mode != prevMode) || (nextInterval != prevInterval) || (nextHighAccuracy != prevHighAccuracy)
        buildDecision(
            modeChanged = changed,
            reason = if (!newConfig.powerSaveEnabled) "power_save_disabled" else "config_updated"
        )
    }

    fun onLocationSample(
        lat: Double,
        lng: Double,
        speedKmH: Double,
        accuracy: Double?,
        timestampMs: Long
    ): GpsPowerSaveDecision = synchronized(lock) {
        if (!config.powerSaveEnabled) {
            val wasStationary = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
            mode = GpsTrackingMode.ACTIVE
            anchorLat = null
            anchorLng = null
            stationarySinceMs = null
            return@synchronized buildDecision(modeChanged = wasStationary, reason = "power_save_disabled")
        }

        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return@synchronized buildDecision(modeChanged = false, reason = "invalid_coordinates_ignored")
        }

        val safeSpeed = speedKmH.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val reliableAccuracy = accuracy == null || !accuracy.isFinite() || accuracy <= config.maxReliableAccuracyMeters

        // Fast wake-up if speed clearly indicates driving
        if (safeSpeed >= config.safeMinMovementSpeedKmH) {
            val wasStationary = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
            mode = GpsTrackingMode.ACTIVE
            anchorLat = null
            anchorLng = null
            stationarySinceMs = null
            return@synchronized buildDecision(
                modeChanged = wasStationary,
                reason = if (wasStationary) "wake_speed_detected" else "active_moving"
            )
        }

        val aLat = anchorLat
        val aLng = anchorLng
        if (aLat == null || aLng == null) {
            anchorLat = lat
            anchorLng = lng
            stationarySinceMs = timestampMs
            return@synchronized buildDecision(modeChanged = false, reason = "stationary_anchor_set")
        }

        val displacementMeters = GpsTrackingEngine.haversineDistanceMeters(aLat, aLng, lat, lng)
        val movedBeyondStationaryZone = reliableAccuracy && displacementMeters >= config.wakeDistanceMeters
        if (movedBeyondStationaryZone) {
            val wasStationary = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
            mode = GpsTrackingMode.ACTIVE
            anchorLat = if (safeSpeed <= config.stationarySpeedThresholdKmH) lat else null
            anchorLng = if (safeSpeed <= config.stationarySpeedThresholdKmH) lng else null
            stationarySinceMs = if (safeSpeed <= config.stationarySpeedThresholdKmH) timestampMs else null
            return@synchronized buildDecision(
                modeChanged = wasStationary,
                reason = if (wasStationary) "wake_displacement_detected" else "anchor_shifted"
            )
        }

        val isLowSpeed = safeSpeed <= config.stationarySpeedThresholdKmH
        val withinStationaryRadius = displacementMeters <= config.stationaryRadiusMeters
        if (isLowSpeed && withinStationaryRadius) {
            val since = stationarySinceMs ?: timestampMs.also { stationarySinceMs = it }
            val dwellMs = (timestampMs - since).coerceAtLeast(0L)
            if (mode == GpsTrackingMode.ACTIVE && dwellMs >= config.safeStationaryDwellMs) {
                mode = GpsTrackingMode.POWER_SAVE_STATIONARY
                return@synchronized buildDecision(modeChanged = true, reason = "entered_stationary_power_save")
            }
            return@synchronized buildDecision(
                modeChanged = false,
                reason = if (mode == GpsTrackingMode.POWER_SAVE_STATIONARY) "stationary_power_save_holding" else "stationary_dwell_accumulating"
            )
        }

        // Moderate speed (between stationary threshold and minMovementSpeedKmH) without large displacement:
        // Keep active accuracy while resetting stationary dwell so stop-and-go traffic doesn't drop frequency early.
        val wasStationary = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
        mode = GpsTrackingMode.ACTIVE
        anchorLat = lat
        anchorLng = lng
        stationarySinceMs = timestampMs
        return@synchronized buildDecision(
            modeChanged = wasStationary,
            reason = if (wasStationary) "wake_moderate_speed" else "dwell_reset_traffic"
        )
    }

    fun onSignificantMotionDetected(): GpsPowerSaveDecision = synchronized(lock) {
        val wasStationary = mode == GpsTrackingMode.POWER_SAVE_STATIONARY
        mode = GpsTrackingMode.ACTIVE
        anchorLat = null
        anchorLng = null
        stationarySinceMs = null
        buildDecision(
            modeChanged = wasStationary,
            reason = if (wasStationary) "wake_significant_motion_sensor" else "significant_motion_while_active"
        )
    }

    fun reset() = synchronized(lock) {
        mode = GpsTrackingMode.ACTIVE
        anchorLat = null
        anchorLng = null
        stationarySinceMs = null
    }

    private fun effectiveIntervalFor(targetMode: GpsTrackingMode, cfg: GpsPowerSaveConfig): Int =
        if (targetMode == GpsTrackingMode.POWER_SAVE_STATIONARY && cfg.powerSaveEnabled) {
            cfg.safeStationaryIntervalSeconds
        } else {
            cfg.safeActiveIntervalSeconds
        }

    private fun highAccuracyFor(targetMode: GpsTrackingMode, cfg: GpsPowerSaveConfig): Boolean =
        cfg.hasFinePermission && (targetMode == GpsTrackingMode.ACTIVE || !cfg.powerSaveEnabled)

    private fun buildDecision(modeChanged: Boolean, reason: String): GpsPowerSaveDecision =
        GpsPowerSaveDecision(
            mode = mode,
            effectiveIntervalSeconds = effectiveIntervalFor(mode, config),
            useHighAccuracy = highAccuracyFor(mode, config),
            modeChanged = modeChanged,
            reason = reason
        )
}

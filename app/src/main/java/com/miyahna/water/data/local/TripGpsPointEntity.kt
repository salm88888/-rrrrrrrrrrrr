package com.miyahna.water.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miyahna.water.domain.TripGPSPoint

/**
 * Room entity persisting every GPS breadcrumb recorded by the background
 * LocationTrackingService during driver shifts and active trips, ensuring
 * zero route data loss across app exits, process kills, and offline zones.
 */
@Entity(
    tableName = "trip_gps_points",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["capturedAtEpochMs"]),
        Index(value = ["isSynced"])
    ]
)
data class TripGpsPointEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val speedKmH: Double = 0.0,
    val timestamp: String,
    val seq: Int = 0,
    val distanceFromPrevKm: Double = 0.0,
    val isWeakSignal: Boolean = false,
    val source: String = "gps_android_fg",
    val capturedAtEpochMs: Long,
    val isSynced: Boolean = false
)

fun TripGpsPointEntity.toDomain(): TripGPSPoint = TripGPSPoint(
    id = id,
    tripId = tripId,
    lat = lat,
    lng = lng,
    accuracy = accuracy,
    speedKmH = speedKmH,
    timestamp = timestamp,
    seq = seq,
    distanceFromPrevKm = distanceFromPrevKm,
    isWeakSignal = isWeakSignal,
    source = source
)

fun TripGPSPoint.toEntity(
    capturedAtEpochMs: Long = System.currentTimeMillis(),
    isSynced: Boolean = false
): TripGpsPointEntity = TripGpsPointEntity(
    id = id,
    tripId = tripId,
    lat = lat,
    lng = lng,
    accuracy = accuracy,
    speedKmH = speedKmH,
    timestamp = timestamp,
    seq = seq,
    distanceFromPrevKm = distanceFromPrevKm,
    isWeakSignal = isWeakSignal,
    source = source,
    capturedAtEpochMs = capturedAtEpochMs,
    isSynced = isSynced
)

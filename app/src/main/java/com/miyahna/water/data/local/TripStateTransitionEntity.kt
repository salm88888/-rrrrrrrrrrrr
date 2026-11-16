package com.miyahna.water.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miyahna.water.domain.TripStateTransition

@Entity(
    tableName = "trip_state_transitions",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["timestampEpochMs"]),
        Index(value = ["isSyncedToCloud"]),
        Index(value = ["reviewedByManager"])
    ]
)
data class TripStateTransitionEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val tripNumber: String,
    val truckId: String,
    val driverId: String,
    val driverName: String,
    val fromState: String,
    val toState: String,
    val triggerSource: String,
    val actorRole: String,
    val actorName: String,
    val reason: String? = null,
    val details: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val speedKmH: Double? = null,
    val timestamp: String,
    val timestampEpochMs: Long,
    val reviewedByManager: Boolean = false,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
    val isSyncedToCloud: Boolean = false
)

fun TripStateTransitionEntity.toDomain(): TripStateTransition = TripStateTransition(
    id = id,
    tripId = tripId,
    tripNumber = tripNumber,
    truckId = truckId,
    driverId = driverId,
    driverName = driverName,
    fromState = fromState,
    toState = toState,
    triggerSource = triggerSource,
    actorRole = actorRole,
    actorName = actorName,
    reason = reason,
    details = details,
    lat = lat,
    lng = lng,
    speedKmH = speedKmH,
    timestamp = timestamp,
    timestampEpochMs = timestampEpochMs,
    reviewedByManager = reviewedByManager,
    reviewedAt = reviewedAt,
    reviewNote = reviewNote,
    isSyncedToCloud = isSyncedToCloud
)

fun TripStateTransition.toEntity(isSynced: Boolean = isSyncedToCloud): TripStateTransitionEntity =
    TripStateTransitionEntity(
        id = id,
        tripId = tripId,
        tripNumber = tripNumber,
        truckId = truckId,
        driverId = driverId,
        driverName = driverName,
        fromState = fromState,
        toState = toState,
        triggerSource = triggerSource,
        actorRole = actorRole,
        actorName = actorName,
        reason = reason,
        details = details,
        lat = lat,
        lng = lng,
        speedKmH = speedKmH,
        timestamp = timestamp,
        timestampEpochMs = timestampEpochMs,
        reviewedByManager = reviewedByManager,
        reviewedAt = reviewedAt,
        reviewNote = reviewNote,
        isSyncedToCloud = isSynced
    )

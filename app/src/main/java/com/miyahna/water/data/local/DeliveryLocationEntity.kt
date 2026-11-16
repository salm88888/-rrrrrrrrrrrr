package com.miyahna.water.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miyahna.water.domain.DeliveryLocationLog

/**
 * Room entity persisting captured GPS coordinates of water truck deliveries
 * along with truck, driver, trip, customer, and accuracy metadata.
 */
@Entity(
    tableName = "delivery_locations",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["customerId"]),
        Index(value = ["orderId"]),
        Index(value = ["capturedAtEpochMs"])
    ]
)
data class DeliveryLocationEntity(
    @PrimaryKey val id: String,
    val tripId: String? = null,
    val tripNumber: String? = null,
    val orderId: String? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val truckId: String = "truck-1",
    val truckPlate: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val speedKmH: Double = 0.0,
    val deliveredLiters: Int? = null,
    val captureSource: String = "delivery_gps_service",
    val notes: String? = null,
    val capturedAtIso: String,
    val capturedAtEpochMs: Long,
    val isSynced: Boolean = false
)

fun DeliveryLocationEntity.toDomain(): DeliveryLocationLog = DeliveryLocationLog(
    id = id,
    tripId = tripId,
    tripNumber = tripNumber,
    orderId = orderId,
    customerId = customerId,
    customerName = customerName,
    truckId = truckId,
    truckPlate = truckPlate,
    driverId = driverId,
    driverName = driverName,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedKmH = speedKmH,
    deliveredLiters = deliveredLiters,
    captureSource = captureSource,
    notes = notes,
    capturedAtIso = capturedAtIso,
    capturedAtEpochMs = capturedAtEpochMs,
    isSynced = isSynced
)

fun DeliveryLocationLog.toEntity(): DeliveryLocationEntity = DeliveryLocationEntity(
    id = id,
    tripId = tripId,
    tripNumber = tripNumber,
    orderId = orderId,
    customerId = customerId,
    customerName = customerName,
    truckId = truckId,
    truckPlate = truckPlate,
    driverId = driverId,
    driverName = driverName,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedKmH = speedKmH,
    deliveredLiters = deliveredLiters,
    captureSource = captureSource,
    notes = notes,
    capturedAtIso = capturedAtIso,
    capturedAtEpochMs = capturedAtEpochMs,
    isSynced = isSynced
)

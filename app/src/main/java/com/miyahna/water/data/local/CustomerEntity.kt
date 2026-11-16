package com.miyahna.water.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miyahna.water.domain.Customer

/**
 * Room entity representing a water delivery customer record including
 * identification, name, primary and alternative contact numbers, district/address,
 * geolocation coordinates, tank capacity, and delivery aggregates.
 */
@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["name"]),
        Index(value = ["phone"]),
        Index(value = ["customerId"]),
        Index(value = ["district"])
    ]
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val name: String,
    val phone: String,
    val altPhone: String? = null,
    val district: String,
    val addressDetails: String = "",
    val lat: Double,
    val lng: Double,
    val tankCapacityLiters: Int = 2000,
    val tankRemainingLiters: Int? = null,
    val notes: String? = null,
    val createdAt: String,
    val isActive: Boolean = true,
    val totalOrdersCount: Int = 0,
    val totalLitersDelivered: Int = 0,
    val isOfflineCreated: Boolean = false
)

fun CustomerEntity.toDomain(): Customer = Customer(
    id = id,
    customerId = customerId,
    name = name,
    phone = phone,
    altPhone = altPhone,
    district = district,
    addressDetails = addressDetails,
    lat = lat,
    lng = lng,
    tankCapacityLiters = tankCapacityLiters,
    tankRemainingLiters = tankRemainingLiters,
    notes = notes,
    createdAt = createdAt,
    isActive = isActive,
    totalOrdersCount = totalOrdersCount,
    totalLitersDelivered = totalLitersDelivered,
    isOfflineCreated = isOfflineCreated
)

fun Customer.toEntity(): CustomerEntity = CustomerEntity(
    id = id,
    customerId = customerId,
    name = name,
    phone = phone,
    altPhone = altPhone,
    district = district,
    addressDetails = addressDetails,
    lat = lat,
    lng = lng,
    tankCapacityLiters = tankCapacityLiters,
    tankRemainingLiters = tankRemainingLiters,
    notes = notes,
    createdAt = createdAt,
    isActive = isActive,
    totalOrdersCount = totalOrdersCount,
    totalLitersDelivered = totalLitersDelivered,
    isOfflineCreated = isOfflineCreated
)

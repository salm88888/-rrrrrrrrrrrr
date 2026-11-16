package com.miyahna.water.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists the serialized full operational snapshot of Miyahna locally via Room
 * so all offline orders, trips, GPS tracks, invoices, expenses, and settings survive
 * cold launches, offline shifts, and process kills.
 */
@Entity(tableName = "app_state_snapshots")
data class AppStateSnapshotEntity(
    @PrimaryKey val key: String = "primary",
    val settingsJson: String,
    val truckJson: String,
    val driverAccountsJson: String,
    val customersJson: String,
    val ordersJson: String,
    val tripsJson: String,
    val invoicesJson: String,
    val reconciliationsJson: String,
    val expensesJson: String,
    val gpsEventsJson: String,
    val notificationsJson: String,
    val directivesJson: String,
    val savedRole: String?,
    val driverSessionJson: String?,
    val updatedAtEpochMs: Long,
    val fleetTrucksJson: String = "[]"
)

@Entity(tableName = "pending_offline_ops")
data class PendingOfflineOpEntity(
    @PrimaryKey val id: String,
    val type: String,
    val payloadJson: String,
    val createdAt: String
)

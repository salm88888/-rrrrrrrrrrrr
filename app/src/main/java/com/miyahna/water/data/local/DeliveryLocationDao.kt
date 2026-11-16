package com.miyahna.water.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for inserting, querying, observing, and managing water truck
 * GPS delivery location logs.
 */
@Dao
interface DeliveryLocationDao {
    @Query("SELECT * FROM delivery_locations ORDER BY capturedAtEpochMs DESC")
    fun observeAllDeliveryLocations(): Flow<List<DeliveryLocationEntity>>

    @Query("SELECT * FROM delivery_locations WHERE tripId = :tripId ORDER BY capturedAtEpochMs DESC")
    fun observeDeliveryLocationsForTrip(tripId: String): Flow<List<DeliveryLocationEntity>>

    @Query("SELECT * FROM delivery_locations WHERE customerId = :customerId ORDER BY capturedAtEpochMs DESC")
    fun observeDeliveryLocationsForCustomer(customerId: String): Flow<List<DeliveryLocationEntity>>

    @Query("SELECT * FROM delivery_locations ORDER BY capturedAtEpochMs DESC")
    suspend fun getAllDeliveryLocationsOnce(): List<DeliveryLocationEntity>

    @Query("SELECT * FROM delivery_locations WHERE id = :id LIMIT 1")
    suspend fun getDeliveryLocationById(id: String): DeliveryLocationEntity?

    @Query("SELECT * FROM delivery_locations ORDER BY capturedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestDeliveryLocation(): DeliveryLocationEntity?

    @Query("SELECT COUNT(*) FROM delivery_locations")
    suspend fun getDeliveryLocationsCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveryLocation(location: DeliveryLocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveryLocations(locations: List<DeliveryLocationEntity>)

    @Query("DELETE FROM delivery_locations WHERE id = :id")
    suspend fun deleteDeliveryLocationById(id: String): Int

    @Query("DELETE FROM delivery_locations")
    suspend fun clearAllDeliveryLocations()
}

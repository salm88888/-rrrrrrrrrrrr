package com.miyahna.water.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripGpsPointDao {
    @Query("SELECT * FROM trip_gps_points WHERE tripId = :tripId ORDER BY seq ASC, capturedAtEpochMs ASC")
    fun observePointsForTrip(tripId: String): Flow<List<TripGpsPointEntity>>

    @Query("SELECT COUNT(*) FROM trip_gps_points WHERE isSynced = 0")
    fun observeUnsyncedPointsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_gps_points")
    fun observeTotalPointsCount(): Flow<Int>

    @Query("SELECT * FROM trip_gps_points WHERE tripId = :tripId ORDER BY seq ASC, capturedAtEpochMs ASC")
    suspend fun getPointsForTripOnce(tripId: String): List<TripGpsPointEntity>

    @Query("SELECT * FROM trip_gps_points WHERE isSynced = 0 ORDER BY capturedAtEpochMs ASC")
    suspend fun getUnsyncedPointsOnce(): List<TripGpsPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TripGpsPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TripGpsPointEntity>)

    @Query("UPDATE trip_gps_points SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markPointsSynced(ids: List<String>)

    @Query("UPDATE trip_gps_points SET isSynced = 1 WHERE isSynced = 0")
    suspend fun markAllPointsSynced()

    @Query("DELETE FROM trip_gps_points WHERE tripId = :tripId")
    suspend fun deletePointsForTrip(tripId: String)

    @Query("DELETE FROM trip_gps_points")
    suspend fun clearAllPoints()
}

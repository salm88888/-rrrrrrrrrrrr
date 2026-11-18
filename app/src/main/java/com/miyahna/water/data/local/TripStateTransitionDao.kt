package com.miyahna.water.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripStateTransitionDao {
    @Query("SELECT * FROM trip_state_transitions ORDER BY timestampEpochMs DESC")
    fun observeAllTransitions(): Flow<List<TripStateTransitionEntity>>

    @Query("SELECT * FROM trip_state_transitions WHERE tripId = :tripId ORDER BY timestampEpochMs DESC")
    fun observeTransitionsForTrip(tripId: String): Flow<List<TripStateTransitionEntity>>

    @Query("SELECT * FROM trip_state_transitions ORDER BY timestampEpochMs DESC")
    suspend fun getAllTransitionsOnce(): List<TripStateTransitionEntity>

    @Query("SELECT * FROM trip_state_transitions WHERE isSyncedToCloud = 0 ORDER BY timestampEpochMs ASC")
    suspend fun getUnsyncedTransitionsOnce(): List<TripStateTransitionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransition(entry: TripStateTransitionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransitions(entries: List<TripStateTransitionEntity>)

    @Query("UPDATE trip_state_transitions SET isSyncedToCloud = 1 WHERE id = :id")
    suspend fun markTransitionSynced(id: String)

    @Query("UPDATE trip_state_transitions SET isSyncedToCloud = 1 WHERE id IN (:ids)")
    suspend fun markTransitionsSynced(ids: List<String>)

    @Query(
        "UPDATE trip_state_transitions SET reviewedByManager = 1, reviewedAt = :reviewedAt, reviewNote = :reviewNote WHERE id = :id"
    )
    suspend fun markTransitionReviewed(id: String, reviewedAt: String, reviewNote: String?)

    @Query("UPDATE trip_state_transitions SET isSyncedToCloud = 1 WHERE isSyncedToCloud = 0")
    suspend fun markAllTransitionsSynced()

    @Query("DELETE FROM trip_state_transitions WHERE tripId = :tripId")
    suspend fun deleteTransitionsForTrip(tripId: String)

    @Query("DELETE FROM trip_state_transitions")
    suspend fun clearAllTransitions()
}

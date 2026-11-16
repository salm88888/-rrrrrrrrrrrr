package com.miyahna.water.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MiyahnaStateDao {
    @Query("SELECT * FROM app_state_snapshots WHERE `key` = 'primary' LIMIT 1")
    suspend fun getSnapshot(): AppStateSnapshotEntity?

    @Query("SELECT * FROM app_state_snapshots WHERE `key` = 'primary' LIMIT 1")
    fun observeSnapshot(): Flow<AppStateSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: AppStateSnapshotEntity)

    @Query("SELECT * FROM pending_offline_ops ORDER BY createdAt ASC")
    suspend fun getPendingOps(): List<PendingOfflineOpEntity>

    @Query("SELECT COUNT(*) FROM pending_offline_ops")
    fun observePendingOpsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingOp(op: PendingOfflineOpEntity)

    @Query("DELETE FROM pending_offline_ops WHERE id IN (:ids)")
    suspend fun deletePendingOpsByIds(ids: List<String>)

    @Query("DELETE FROM pending_offline_ops")
    suspend fun clearAllPendingOps()
}

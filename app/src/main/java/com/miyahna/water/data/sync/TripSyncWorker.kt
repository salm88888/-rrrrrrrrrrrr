package com.miyahna.water.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miyahna.water.MiyahnaApplication
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker constrained on NetworkType.CONNECTED that wakes automatically
 * when internet connectivity is restored (even after exiting the app) to flush
 * locally recorded offline trips, GPS routes, and delivery logs to the manager.
 */
class TripSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as? MiyahnaApplication)?.repository
            ?: return Result.failure()

        repo.awaitInitialized()
        val flushed = runCatching { repo.flushPendingOfflineQueue() }.getOrDefault(false)
        if (!flushed) return Result.retry()

        val state = repo.state.value
        if (state.cloudUser != null && state.isOnline) {
            runCatching { repo.syncWithCloud() }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_SYNC_WORK_NAME = "miyahna_offline_trip_sync"

        fun enqueueWhenOnline(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<TripSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        15,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE_SYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}

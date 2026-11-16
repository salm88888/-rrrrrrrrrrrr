package com.miyahna.water.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miyahna.water.MiyahnaApplication
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that wakes in the background (even after exiting the app)
 * whenever network connectivity is available, syncs cloud updates from Firestore,
 * and triggers OS notifications for any newly arrived unread alerts or directives.
 */
class BackgroundNotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as? MiyahnaApplication)?.repository
            ?: return Result.failure()

        repo.awaitInitialized()
        runCatching { repo.flushPendingOfflineQueue() }

        val current = repo.state.value
        if (current.cloudUser != null && current.isOnline) {
            runCatching { repo.syncWithCloud() }
        }
        repo.checkAndDispatchPendingSystemNotifications()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC_ALERT_SYNC = "miyahna_background_alert_sync"

        fun schedulePeriodic(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<BackgroundNotificationSyncWorker>(
                    15,
                    TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_ALERT_SYNC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}

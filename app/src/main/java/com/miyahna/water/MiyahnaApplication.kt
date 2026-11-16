package com.miyahna.water

import android.app.Application
import com.miyahna.water.data.MiyahnaRepository
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.data.local.MiyahnaDatabase
import com.miyahna.water.data.notifications.MiyahnaNotificationHelper
import com.miyahna.water.data.sync.BackgroundNotificationSyncWorker

class MiyahnaApplication : Application() {
    val database: MiyahnaDatabase by lazy { MiyahnaDatabase.getInstance(this) }
    val cloudSyncManager: FirebaseSyncManager by lazy { FirebaseSyncManager(this) }
    val repository: MiyahnaRepository by lazy {
        MiyahnaRepository(
            context = this,
            stateDao = database.stateDao(),
            customerDao = database.customerDao(),
            deliveryLocationDao = database.deliveryLocationDao(),
            tripGpsPointDao = database.tripGpsPointDao(),
            tripStateTransitionDao = database.tripStateTransitionDao(),
            cloudSync = cloudSyncManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        MiyahnaNotificationHelper.ensureChannel(this)
        BackgroundNotificationSyncWorker.schedulePeriodic(this)
        // Initialize repository on startup/worker wake so cloud realtime & network sync stay armed
        repository
    }
}

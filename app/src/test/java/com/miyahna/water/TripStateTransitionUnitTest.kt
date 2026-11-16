package com.miyahna.water

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.miyahna.water.data.MiyahnaRepository
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.data.local.MiyahnaDatabase
import com.miyahna.water.data.local.toDomain
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.domain.ClusteringEngine
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripStateTransitionUnitTest {

    private lateinit var context: Context
    private lateinit var database: MiyahnaDatabase
    private lateinit var repository: MiyahnaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        val databaseId = if (resId != 0) context.getString(resId) else "demo-database"

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:935445559699:android:a4241bce0be2ea98951066")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key-unit-test")
                    .build()
            ).also { created ->
                FirebaseFirestore.getInstance(created, databaseId).apply {
                    firestoreSettings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                        .build()
                }
            }
        } else {
            FirebaseApp.getInstance()
        }

        val firestore = FirebaseFirestore.getInstance(app, databaseId)
        val auth = FirebaseAuth.getInstance(app)
        auth.signOut()

        database = Room.inMemoryDatabaseBuilder(context, MiyahnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val cloudSync = FirebaseSyncManager(
            context = context,
            providedFirestore = firestore,
            providedAuth = auth
        )

        repository = MiyahnaRepository(
            context = context,
            stateDao = database.stateDao(),
            customerDao = database.customerDao(),
            deliveryLocationDao = database.deliveryLocationDao(),
            tripGpsPointDao = database.tripGpsPointDao(),
            tripStateTransitionDao = database.tripStateTransitionDao(),
            cloudSync = cloudSync
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomDaoRoundTripSyncAndManagerReviewFlags() = runBlocking {
        val dao = database.tripStateTransitionDao()
        val t1 = TripStateTransition(
            id = "tlog-1",
            tripId = "trip-10",
            tripNumber = "TRIP-10",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم",
            fromState = "in_progress",
            toState = "paused",
            triggerSource = "driver_paused",
            actorRole = "driver",
            actorName = "سالم",
            reason = "استراحة سريعة",
            lat = 15.495,
            lng = 45.365,
            speedKmH = 0.0,
            timestamp = "2026-11-16T09:00:00Z",
            timestampEpochMs = 1794819600000L,
            reviewedByManager = false,
            isSyncedToCloud = false
        )
        dao.insertTransition(t1.toEntity())

        val unsynced = dao.getUnsyncedTransitionsOnce()
        assertEquals(1, unsynced.size)
        assertFalse(unsynced.first().toDomain().isSyncedToCloud)

        dao.markTransitionsSynced(listOf("tlog-1"))
        assertTrue(dao.getUnsyncedTransitionsOnce().isEmpty())

        dao.markTransitionReviewed(
            id = "tlog-1",
            reviewedAt = "2026-11-16T09:10:00Z",
            reviewNote = "تم الاعتماد"
        )
        val restored = dao.getAllTransitionsOnce().first { it.tripId == "trip-10" }.toDomain()
        assertTrue(restored.isSyncedToCloud)
        assertTrue(restored.reviewedByManager)
        assertEquals("تم الاعتماد", restored.reviewNote)
    }

    @Test
    fun repositoryLifecycleLogsAllStateTransitionsThroughCompletionAndReview() = runBlocking {
        repository.addOrder(
            existingCustomerId = null,
            customerName = "بيت آل جلال",
            phone = "770123456",
            district = "العرقين",
            addressDetails = "قرب المحطة",
            lat = 15.505,
            lng = 45.375,
            tankCapacity = 4000,
            requestedLiters = 3000,
            pricePerLiter = 12.0,
            source = OrderSource.MANAGER_MANUAL,
            isUrgent = false,
            notes = null
        )

        val snapAfterOrder = repository.state.value
        val proposals = ClusteringEngine.generateSmartClusters(
            orders = snapAfterOrder.orders,
            settings = snapAfterOrder.settings,
            maxTruckCapacity = snapAfterOrder.truck.maxCapacityLiters
        )
        assertTrue(proposals.isNotEmpty())

        // 1. Create & Approve trip -> none -> approved
        val trip = repository.createTripFromProposal(proposals.first(), approveImmediately = true)
        assertEquals(TripStatus.APPROVED, trip.status)

        // 2. Driver accepts -> approved -> accepted
        val acceptRes = repository.respondToManagerTrip(trip.id, accepted = true)
        assertTrue(acceptRes.success)

        // 3. Start trip -> accepted -> in_progress
        val startRes = repository.startTrip(trip.id)
        assertTrue(startRes.success)
        assertEquals(TripStatus.IN_PROGRESS, startRes.data?.status)

        // 4. Pause trip -> in_progress -> paused
        val pauseRes = repository.pauseTrip(trip.id, reason = "توقف للتعبئة", actorRole = "driver")
        assertTrue(pauseRes.success)
        assertEquals(TripStatus.PAUSED, pauseRes.data?.status)

        // 5. Resume trip -> paused -> in_progress
        val resumeRes = repository.resumeTrip(trip.id, reason = "استئناف المسار", actorRole = "driver")
        assertTrue(resumeRes.success)
        assertEquals(TripStatus.IN_PROGRESS, resumeRes.data?.status)

        // 6. Driver finishes trip -> in_progress -> completed_pending_review (or pending_sync when offline)
        val finishRes = repository.finishTripByDriver(trip.id)
        assertTrue(finishRes.success)
        val finishedStatus = finishRes.data?.status
        assertTrue(
            finishedStatus == TripStatus.COMPLETED_PENDING_REVIEW || finishedStatus == TripStatus.PENDING_SYNC
        )

        // 7. Manager closes trip -> completed_pending_review/pending_sync -> closed
        repository.closeTripAfterReview(trip.id, reviewNotes = "مكتملة بنجاح")
        val closedTrip = repository.state.value.trips.first { it.id == trip.id }
        assertEquals(TripStatus.CLOSED, closedTrip.status)

        val transitions = database.tripStateTransitionDao().getAllTransitionsOnce()
            .filter { it.tripId == trip.id }
            .map { it.toDomain() }
        // Room query orders DESC by timestampEpochMs; verify in chronological order
        val chronological = transitions.reversed()
        assertEquals(
            listOf(
                "approved",
                "accepted",
                "in_progress",
                "paused",
                "in_progress",
                finishedStatus!!.wire,
                "closed"
            ),
            chronological.map { it.toState }
        )
        assertEquals(
            listOf(
                "trip_created",
                "driver_accepted",
                "manual_start",
                "driver_paused",
                "driver_resumed",
                "driver_completed",
                "manager_closed"
            ),
            chronological.map { it.triggerSource }
        )

        // Verify Manager can mark any transition reviewed in both Room and repository StateFlow
        val pauseLogId = chronological.first { it.toState == "paused" }.id
        val reviewRes = repository.markTransitionReviewed(pauseLogId, note = "سبب مقبول")
        assertTrue(reviewRes.success)
        val reviewedInState = repository.state.value.tripStateTransitions.first { it.id == pauseLogId }
        assertTrue(reviewedInState.reviewedByManager)
        assertEquals("سبب مقبول", reviewedInState.reviewNote)

        // Verify clearOperationalData cleans up transitions
        repository.clearOperationalData()
        assertTrue(repository.state.value.tripStateTransitions.isEmpty())
        assertTrue(database.tripStateTransitionDao().getAllTransitionsOnce().isEmpty())
    }
}

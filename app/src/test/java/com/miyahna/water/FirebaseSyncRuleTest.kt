package com.miyahna.water

import com.google.firebase.firestore.FirebaseFirestoreException
import com.miyahna.water.base.FirestoreEmulatorTestBase
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FirebaseSyncRuleTest : FirestoreEmulatorTestBase() {

    private fun createSyncManager(): FirebaseSyncManager =
        FirebaseSyncManager(
            context = context,
            providedFirestore = firestore,
            providedAuth = auth
        )

    @Test
    fun upsertAndGetCustomer_authenticatedOwner_succeedsAndReadsFromFirestore() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val syncManager = createSyncManager()
        val id = "cust_${UUID.randomUUID().toString().take(8)}"
        val customer = Customer(
            id = id,
            customerId = "CUST-9001",
            name = "مؤسسة الوادي للمياه",
            phone = "771122334",
            district = "العرقين",
            addressDetails = "جوار المحطة الرئيسية",
            lat = 15.486,
            lng = 45.365,
            tankCapacityLiters = 4000,
            createdAt = "2026-11-16T10:00:00Z"
        )

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            syncManager.upsertCustomer(customer)
        }
        assertTrue("Expected upsertCustomer to succeed: ${saveResult.exceptionOrNull()}", saveResult.isSuccess)

        val fetched = withTimeout(DEFAULT_TIMEOUT_MS) {
            syncManager.getCustomerById(id).getOrThrow()
        }
        assertNotNull(fetched)
        assertEquals(customer.name, fetched?.name)
        assertEquals(customer.district, fetched?.district)
    }

    @Test
    fun observeRealTimeTrucks_authenticatedOwner_emitsLiveTruckUpdates() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val syncManager = createSyncManager()
        val truckId = "TRUCK_${UUID.randomUUID().toString().take(8)}"
        val truck = Truck(
            id = truckId,
            code = "T-LIVE",
            plateNumber = "مأرب 5050",
            maxCapacityLiters = 12000,
            currentWaterLiters = 9500,
            driverName = "سالم جلال",
            status = TruckStatus.ON_TRIP,
            lastLat = 15.489,
            lastLng = 45.372,
            lastSpeed = 42.0,
            lastUpdateTimestamp = "2026-11-16T10:05:00Z"
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            syncManager.upsertTruck(truck).getOrThrow()
        }

        val liveList = withTimeout(FLOW_TIMEOUT_MS) {
            syncManager.observeRealTimeTrucks().first { list -> list.any { it.id == truckId } }
        }
        val matched = liveList.firstOrNull { it.id == truckId }
        assertNotNull(matched)
        assertEquals(TruckStatus.ON_TRIP, matched?.status)
    }

    @Test
    fun getCustomerById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val aliceSync = createSyncManager()
        val id = "cust_cross_${UUID.randomUUID().toString().take(8)}"
        val customer = Customer(
            id = id,
            customerId = "CUST-9002",
            name = "عميل سري",
            phone = "770001122",
            district = "الصمدة",
            lat = 15.49,
            lng = 45.37,
            tankCapacityLiters = 2000,
            createdAt = "2026-11-16T10:10:00Z"
        )
        withTimeout(DEFAULT_TIMEOUT_MS) { aliceSync.upsertCustomer(customer).getOrThrow() }

        signInTestUser(BOB_EMAIL)
        val bobSync = createSyncManager()
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { bobSync.getCustomerById(id).getOrThrow() }
            fail("Expected PERMISSION_DENIED when Bob accesses Alice's customer")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    @Test
    fun unauthenticatedQueryAndListener_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val syncManager = createSyncManager()

        // Direct unauthenticated Firestore get must fail
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("customers").get().await()
            }
            fail("Expected unauthenticated query to be rejected by rules")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }

        // Unauthenticated snapshot listener (when attempting query) cancels with PERMISSION_DENIED
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                syncManager.observeRealTimeCustomers(requireAuth = false).first()
            }
            fail("Expected unauthenticated Flow listener to reject with PERMISSION_DENIED")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected wrapped FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    @Test
    fun logTripStateTransition_ownerWritesLifecycleAndObservesRealtime() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val syncManager = createSyncManager()
        val tripId = "trip_${UUID.randomUUID().toString().take(8)}"
        val baseMs = System.currentTimeMillis()
        val states = listOf(
            Triple("approved", "accepted", "driver_accepted"),
            Triple("accepted", "in_progress", "manual_start"),
            Triple("in_progress", "paused", "auto_stop_paused"),
            Triple("paused", "completed_pending_review", "driver_completed")
        )

        val entries = states.mapIndexed { idx, (fromS, toS, trig) ->
            TripStateTransition(
                id = "tlog_${tripId}_$idx",
                tripId = tripId,
                tripNumber = "TRIP-RULE-01",
                truckId = "truck-1",
                driverId = "drv-1",
                driverName = "سالم جلال",
                fromState = fromS,
                toState = toS,
                triggerSource = trig,
                actorRole = if (trig == "auto_stop_paused") "system" else "driver",
                actorName = if (trig == "auto_stop_paused") "التتبع التلقائي" else "سالم جلال",
                reason = "اختبار تحول الحالة $toS",
                lat = 15.492 + idx * 0.001,
                lng = 45.362 + idx * 0.001,
                speedKmH = if (toS == "paused") 0.0 else 35.0,
                timestamp = "2026-11-16T11:0${idx}:00Z",
                timestampEpochMs = baseMs + idx * 1000L,
                reviewedByManager = false,
                isSyncedToCloud = false
            )
        }

        val syncedIds = withTimeout(DEFAULT_TIMEOUT_MS) {
            syncManager.syncPendingTripStateLogs(entries).getOrThrow()
        }
        assertEquals(4, syncedIds.size)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            syncManager.observeRealTimeTripStateLogs().first { list ->
                list.count { it.tripId == tripId } == 4
            }
        }.filter { it.tripId == tripId }

        assertEquals(listOf("completed_pending_review", "paused", "in_progress", "accepted"), observed.map { it.toState })
        assertTrue(observed.all { it.isSyncedToCloud })
        assertTrue(observed.all { !it.reviewedByManager })
    }

    @Test
    fun logTripStateTransition_nonAdminReviewedTrue_failsWithPermissionDenied() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val syncManager = createSyncManager()
        val forgedEntry = TripStateTransition(
            id = "tlog_forged_${UUID.randomUUID().toString().take(8)}",
            tripId = "trip_forged_1",
            tripNumber = "TRIP-FORGE",
            fromState = "in_progress",
            toState = "paused",
            triggerSource = "driver_paused",
            actorRole = "driver",
            actorName = "سائق",
            timestamp = "2026-11-16T11:15:00Z",
            timestampEpochMs = System.currentTimeMillis(),
            reviewedByManager = true
        )

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            syncManager.logTripStateTransition(forgedEntry)
        }
        assertTrue("Expected non-admin reviewedByManager=true create to fail", result.isFailure)
        val fsEx = result.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun markTripStateTransitionReviewed_crossUserDenied_andBootstrappedAdminSucceeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val aliceSync = createSyncManager()
        val logId = "tlog_rev_${UUID.randomUUID().toString().take(8)}"
        val entry = TripStateTransition(
            id = logId,
            tripId = "trip_rev_1",
            tripNumber = "TRIP-REV-1",
            driverId = "drv-1",
            driverName = "سالم جلال",
            fromState = "in_progress",
            toState = "paused",
            triggerSource = "auto_stop_paused",
            actorRole = "system",
            actorName = "التتبع التلقائي",
            reason = "توقف غير مجدول لمدة 5 دقيقة",
            timestamp = "2026-11-16T11:20:00Z",
            timestampEpochMs = System.currentTimeMillis(),
            reviewedByManager = false
        )
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSync.logTripStateTransition(entry).getOrThrow()
        }

        // Cross-user Bob cannot read or mark reviewed
        signInTestUser(BOB_EMAIL)
        val bobSync = createSyncManager()
        val bobReviewRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobSync.markTripStateTransitionReviewed(logId, note = "اختراق")
        }
        assertTrue(bobReviewRes.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            bobReviewRes.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        // Bootstrapped Admin can observe Alice's log and mark it reviewed
        signInTestUser(ADMIN_EMAIL)
        val adminSync = createSyncManager()
        val adminList = withTimeout(FLOW_TIMEOUT_MS) {
            adminSync.observeRealTimeTripStateLogs().first { list -> list.any { it.id == logId } }
        }
        assertFalse(adminList.first { it.id == logId }.reviewedByManager)

        val adminReviewRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSync.markTripStateTransitionReviewed(logId, note = "تمت مراجعة سبب التوقف")
        }
        assertTrue("Expected admin review update to succeed: ${adminReviewRes.exceptionOrNull()}", adminReviewRes.isSuccess)

        val refreshed = withTimeout(FLOW_TIMEOUT_MS) {
            adminSync.observeRealTimeTripStateLogs().first { list ->
                list.firstOrNull { it.id == logId }?.reviewedByManager == true
            }
        }.first { it.id == logId }
        assertTrue(refreshed.reviewedByManager)
        assertEquals("تمت مراجعة سبب التوقف", refreshed.reviewNote)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_rule_test@test.com"
        const val BOB_EMAIL = "bob_rule_test@test.com"
        const val ADMIN_EMAIL = "salemjalaal1@gmail.com"
        const val DEFAULT_TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}

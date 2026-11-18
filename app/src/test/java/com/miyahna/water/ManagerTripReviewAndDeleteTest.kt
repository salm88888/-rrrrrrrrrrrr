package com.miyahna.water

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.data.MiyahnaRepository
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.data.local.MiyahnaDatabase
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSEventType
import com.miyahna.water.domain.GPSGap
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.manager.TripsManagementScreen
import com.miyahna.water.ui.theme.MiyahnaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerTripReviewAndDeleteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var db: MiyahnaDatabase
    private lateinit var cloudSync: FirebaseSyncManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("miyahna_auth_session", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, MiyahnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cloudSync = FirebaseSyncManager(app)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRepo(): MiyahnaRepository = MiyahnaRepository(
        context = app,
        stateDao = db.stateDao(),
        customerDao = db.customerDao(),
        deliveryLocationDao = db.deliveryLocationDao(),
        tripGpsPointDao = db.tripGpsPointDao(),
        tripStateTransitionDao = db.tripStateTransitionDao(),
        cloudSync = cloudSync
    )

    @Test
    fun repository_deleteTrip_onlyAllowsReviewedOrFinishedTrips_andCleansRoomAndQueue() = runBlocking {
        val repo = newRepo()
        repo.awaitInitialized()

        repo.addOrder(
            existingCustomerId = null,
            customerName = "عميل المراجعة",
            phone = "777555111",
            district = "العرق الشرقي",
            addressDetails = "قرب البوابة",
            lat = 15.497,
            lng = 45.369,
            tankCapacity = 3000,
            requestedLiters = 2000,
            pricePerLiter = 12.0,
            source = OrderSource.MANAGER_MANUAL,
            isUrgent = false,
            notes = null
        )
        val order = repo.state.value.orders.first()
        val cluster = com.miyahna.water.domain.ClusteringEngine.generateSmartClusters(
            orders = repo.state.value.orders,
            settings = repo.state.value.settings,
            maxTruckCapacity = repo.state.value.truck.maxCapacityLiters
        ).first()
        val trip = repo.createTripFromProposal(cluster, approveImmediately = true)

        // Driver accepts trip, starts trip & records GPS point
        val acceptRes = repo.respondToManagerTrip(trip.id, accepted = true)
        assertTrue(acceptRes.success)
        val startRes = repo.startTrip(trip.id, lat = 15.492, lng = 45.362)
        assertTrue(startRes.success)
        repo.recordLiveGpsFix(15.495, 45.365, speedKmH = 38.0, accuracy = 8.0)

        // Simulate driver turning GPS off & back on during trip -> records GPS-off note
        repo.setDeviceGpsEnabled(false)
        kotlinx.coroutines.delay(150)
        repo.setDeviceGpsEnabled(true)
        kotlinx.coroutines.delay(150)

        val activeTrip = repo.state.value.trips.first { it.id == trip.id }
        assertTrue(
            "Hardware GPS disable should create a gps_hardware_disabled gap note",
            activeTrip.gpsGaps.any { it.reason == "gps_hardware_disabled" }
        )

        // Cannot delete while IN_PROGRESS
        val blockedDelete = repo.deleteTrip(trip.id)
        assertFalse(blockedDelete.success)

        // Finish & complete manager review (CLOSED)
        repo.finishTripByDriver(trip.id)
        repo.closeTripAfterReview(trip.id, "تمت مراجعة خط السير")
        val closedTrip = repo.state.value.trips.first { it.id == trip.id }
        assertEquals(TripStatus.CLOSED, closedTrip.status)
        assertTrue(db.tripGpsPointDao().getPointsForTripOnce(trip.id).isNotEmpty())
        assertTrue(db.tripStateTransitionDao().getAllTransitionsOnce().any { it.tripId == trip.id })

        // Now manager deletes completed/reviewed trip
        val deleteRes = repo.deleteTrip(trip.id)
        assertTrue(deleteRes.success)
        assertTrue(repo.state.value.trips.none { it.id == trip.id })
        assertTrue(db.tripGpsPointDao().getPointsForTripOnce(trip.id).isEmpty())
        assertTrue(db.tripStateTransitionDao().getAllTransitionsOnce().none { it.tripId == trip.id })
        assertEquals(0, repo.state.value.gpsEvents.count { it.tripId == trip.id })

        // Order remains intact cleanly
        assertTrue(repo.state.value.orders.any { it.id == order.id })
    }

    @Test
    fun tripsManagementScreen_showsOnlyStreamlinedTelemetry_mapOverlays_andDeletesAfterReview() {
        val reviewedTrip = Trip(
            id = "trip-rev-1",
            tripNumber = "TRIP-2026-077",
            date = "2026-11-18",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم المأربي",
            truckPlate = "4455 مأرب",
            initialLoadLiters = 5000,
            deliveredLiters = 4000,
            status = TripStatus.CLOSED,
            proposedAt = "2026-11-18T08:00:00Z",
            startedAt = "2026-11-18T08:15:00Z",
            returnedToPlantAt = "2026-11-18T09:45:00Z",
            actualDistanceKm = 18.4,
            movingMinutes = 52.0,
            stoppedMinutes = 24.0,
            customerStops = listOf(
                TripCustomerStop(
                    orderId = "ord-1",
                    customerId = "cust-1",
                    customerName = "محطة الخير",
                    phone = "771234567",
                    district = "العرق",
                    lat = 15.497,
                    lng = 45.367,
                    stopSequence = 1,
                    requestedLiters = 4000,
                    deliveredLiters = 4000,
                    pricePerLiter = 12.0,
                    totalAmount = 48000.0,
                    paidAmount = 48000.0,
                    remainingAmount = 0.0,
                    status = StopStatus.DELIVERED
                )
            ),
            gpsPoints = listOf(
                TripGPSPoint(
                    id = "pt-1",
                    tripId = "trip-rev-1",
                    lat = 15.4920,
                    lng = 45.3620,
                    speedKmH = 34.0,
                    timestamp = "2026-11-18T08:15:00Z",
                    seq = 1
                ),
                TripGPSPoint(
                    id = "pt-2",
                    tripId = "trip-rev-1",
                    lat = 15.4970,
                    lng = 45.3670,
                    speedKmH = 48.0,
                    timestamp = "2026-11-18T08:40:00Z",
                    seq = 2
                )
            ),
            detectedStops = listOf(
                DetectedStop(
                    id = "stop-1",
                    tripId = "trip-rev-1",
                    lat = 15.4970,
                    lng = 45.3670,
                    startTime = "2026-11-18T08:40:00Z",
                    endTime = "2026-11-18T08:55:00Z",
                    durationMinutes = 15.0,
                    nearCustomerId = "cust-1",
                    nearCustomerName = "محطة الخير",
                    isUnplannedStop = false
                )
            ),
            gpsGaps = listOf(
                GPSGap(
                    id = "gap-1",
                    tripId = "trip-rev-1",
                    startTime = "2026-11-18T09:05:00Z",
                    endTime = "2026-11-18T09:08:00Z",
                    durationSeconds = 180,
                    reason = "gps_hardware_disabled",
                    fromLat = 15.4950,
                    fromLng = 45.3640,
                    toLat = 15.4940,
                    toLng = 45.3630
                )
            )
        )

        var deletedId by mutableStateOf<String?>(null)

        composeTestRule.setContent {
            MiyahnaTheme {
                var tripList by remember { mutableStateOf(listOf(reviewedTrip)) }
                TripsManagementScreen(
                    trips = tripList,
                    settings = SystemSettings(),
                    gpsEvents = listOf(
                        GPSEvent(
                            id = "ev-off-1",
                            tripId = reviewedTrip.id,
                            tripNumber = reviewedTrip.tripNumber,
                            timestamp = "2026-11-18T09:05:00Z",
                            eventType = GPSEventType.STOPPED,
                            lat = 15.4950,
                            lng = 45.3640,
                            source = "device_gps_off",
                            customerName = "تم إيقاف GPS من هاتف السائق"
                        )
                    ),
                    tripStateTransitions = listOf(
                        TripStateTransition(
                            id = "tr-1",
                            tripId = reviewedTrip.id,
                            tripNumber = reviewedTrip.tripNumber,
                            fromState = "in_progress",
                            toState = "closed",
                            triggerSource = "manager_closed",
                            actorRole = "manager",
                            actorName = "المدير العام",
                            timestamp = "2026-11-18T09:50:00Z"
                        )
                    ),
                    onApproveTrip = {},
                    onRejectTrip = { _, _ -> },
                    onStopTracking = { _, _ -> },
                    onCloseTrip = { _, _ -> },
                    onDeleteTrip = { id ->
                        deletedId = id
                        tripList = tripList.filterNot { it.id == id }
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Core requested telemetry is shown cleanly on the card
        composeTestRule.onNodeWithText("TRIP-2026-077").assertIsDisplayed()
        composeTestRule.onNodeWithText("السرعة: متوسط 41 كم/س · أقصى 48 كم/س").assertIsDisplayed()
        composeTestRule.onNodeWithText("مدد التوقف: حركة 52.0 د · توقف 15.0 د · أماكن التوقف: 1").assertIsDisplayed()
        assertEquals(2, composeTestRule.onAllNodesWithText("ملاحظات إيقاف GPS: 1").fetchSemanticsNodes().size)
        composeTestRule.onAllNodesWithText("ملاحظات إيقاف GPS: 1").onFirst().assertIsDisplayed()

        // Extraneous non-telemetry details are removed
        assertEquals(0, composeTestRule.onAllNodesWithText("توثيق تسليم").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("سجل تحولات الحالة", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("48,000 ر.ي", substring = true).fetchSemanticsNodes().size)

        // Open Review Map & Telemetry Dialog and inspect all 3 detail tabs + map
        composeTestRule.onNodeWithTag("trips_lazy_column").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("inspect_trip_map_${reviewedTrip.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("osm_map_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gps_point_row_pt-2").performScrollTo().assertIsDisplayed()

        // Switch to Stop Locations & Durations tab
        composeTestRule.onNodeWithTag("review_tab_stops").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("توقف: محطة الخير").performScrollTo().assertIsDisplayed()

        // Switch to GPS-Off Notes tab
        composeTestRule.onNodeWithTag("review_tab_gps_notes").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إيقاف GPS من هاتف السائق").performScrollTo().assertIsDisplayed()

        // Delete Trip after completed review from inside Dialog
        composeTestRule.onNodeWithTag("dialog_delete_trip_${reviewedTrip.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_delete_trip_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(reviewedTrip.id, deletedId)
        assertEquals(0, composeTestRule.onAllNodesWithText("TRIP-2026-077").fetchSemanticsNodes().size)
    }
}

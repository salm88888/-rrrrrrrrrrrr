package com.miyahna.water

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.data.MiyahnaDomainState
import com.miyahna.water.data.local.MiyahnaDatabase
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.data.location.DeviceLocationHelper
import com.miyahna.water.data.location.LocationTrackingPrefs
import com.miyahna.water.data.location.LocationTrackingService
import com.miyahna.water.domain.DriverSession
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.DriverTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.driver.DriverAppScreen
import com.miyahna.water.ui.theme.MiyahnaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverMandatoryGpsAndOfflineRouteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: MiyahnaApplication
    private lateinit var viewModel: MiyahnaViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as MiyahnaApplication
        viewModel = MiyahnaViewModel(app)
        LocationTrackingPrefs.clear(app)
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    @Test
    fun mandatoryGpsGate_blocksDriverTabsUntilPermissionAndDeviceGpsEnabled() {
        var hasPerm by mutableStateOf(false)
        var gpsEnabled by mutableStateOf(false)
        var permRequested = 0
        var gpsEnableRequested = 0

        val trip = Trip(
            id = "trip-pending-1",
            tripNumber = "TRIP-2026-0101",
            date = "2026-11-18",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم",
            truckPlate = "مأرب 900",
            initialLoadLiters = 4000,
            status = TripStatus.APPROVED,
            createdByRole = "manager",
            driverAcceptanceStatus = "pending",
            proposedAt = "2026-11-18T08:00:00Z"
        )
        val state = MiyahnaUiState(
            domain = MiyahnaDomainState(
                isLoaded = true,
                isOnline = true,
                driverSession = DriverSession(id = "drv-1", name = "سالم", username = "salem", phone = "777", loggedInAt = "2026-11-18T08:00:00Z"),
                settings = SystemSettings(),
                trips = listOf(trip)
            ),
            driverTab = DriverTab.TRIP
        )

        composeTestRule.setContent {
            MiyahnaTheme(darkDriverTheme = true) {
                DriverAppScreen(
                    uiState = state,
                    hasLocationPermission = hasPerm,
                    isDeviceGpsEnabled = gpsEnabled,
                    onRequestLocationPermission = { permRequested++ },
                    onEnableDeviceGps = { gpsEnableRequested++ },
                    onRequestCloudSignIn = {},
                    viewModel = viewModel
                )
            }
        }
        composeTestRule.waitForIdle()

        // Gate active when permission=false & deviceGps=false
        composeTestRule.onNodeWithTag("driver_mandatory_gps_gate").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("driver_accept_trip_button").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("grant_location_permission_button").performClick()
        composeTestRule.onNodeWithTag("enable_device_gps_button").performClick()
        assertEquals(1, permRequested)
        assertEquals(1, gpsEnableRequested)

        // Grant permission only -> still gated because device GPS is off
        hasPerm = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("driver_mandatory_gps_gate").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("enable_device_gps_button").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("driver_accept_trip_button").fetchSemanticsNodes().isEmpty())

        // Enable device GPS -> gate unlocks and trip acceptance button appears
        gpsEnabled = true
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithTag("driver_mandatory_gps_gate").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("driver_accept_trip_button").assertIsDisplayed()
    }

    @Test
    fun driverOrdersTab_displaysOnlyExistingOrdersAndAllowsSendingNewOrderDialog() {
        val activeOrder = Order(
            id = "ord-active-1",
            orderNumber = "ORD-2026-0201",
            customerId = "cust-1",
            customerName = "محطة الخير",
            phone = "777112233",
            district = "العرق الشرقي",
            lat = 15.495,
            lng = 45.365,
            requestedLiters = 3000,
            pricePerLiter = 12.0,
            totalAmount = 36000.0,
            status = OrderStatus.NEW,
            createdAt = "2026-11-18T09:00:00Z",
            isUrgent = true
        )
        val cancelledOrder = activeOrder.copy(
            id = "ord-cancelled-2",
            orderNumber = "ORD-2026-0202",
            customerName = "طلب ملغي لا يظهر",
            status = OrderStatus.CANCELLED
        )
        var ordersList by mutableStateOf(listOf(activeOrder, cancelledOrder))

        composeTestRule.setContent {
            MiyahnaTheme(darkDriverTheme = true) {
                DriverAppScreen(
                    uiState = MiyahnaUiState(
                        domain = MiyahnaDomainState(
                            isLoaded = true,
                            isOnline = false,
                            driverSession = DriverSession(id = "drv-1", name = "سالم", username = "salem", phone = "777", loggedInAt = "2026-11-18T08:00:00Z"),
                            orders = ordersList
                        ),
                        driverTab = DriverTab.FIELD_ORDER
                    ),
                    hasLocationPermission = true,
                    isDeviceGpsEnabled = true,
                    onRequestLocationPermission = {},
                    onEnableDeviceGps = {},
                    onRequestCloudSignIn = {},
                    viewModel = viewModel
                )
            }
        }
        composeTestRule.waitForIdle()

        // Existing non-cancelled order is shown; cancelled order is excluded
        composeTestRule.onNodeWithTag("driver_existing_order_card_ord-active-1").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("driver_existing_order_card_ord-cancelled-2").fetchSemanticsNodes().isEmpty())

        // Empty list shows clean empty state
        ordersList = emptyList()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("لا توجد طلبات موجودة حالياً").assertIsDisplayed()

        // Tapping Send Order opens modal dialog with submit button
        composeTestRule.onNodeWithTag("driver_open_send_order_dialog").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("driver_order_customer_name_input").performTextInput("عميل جديد")
        composeTestRule.onNodeWithTag("driver_submit_field_order").assertIsDisplayed()
    }

    @Test
    fun offlineGpsPointsPersistInRoomAndDeviceGpsToggleUpdatesServiceAndRepoState() = runBlocking {
        // Verify Room DAO keeps offline points unsynced until cloud confirmation
        val db = Room.inMemoryDatabaseBuilder(app, MiyahnaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.tripGpsPointDao()
            val p1 = TripGPSPoint(
                id = "pt-offline-1",
                tripId = "trip-offline-7",
                lat = 15.4920,
                lng = 45.3620,
                speedKmH = 22.0,
                timestamp = "2026-11-18T10:00:00Z",
                seq = 1,
                source = "gps_android_fg"
            )
            dao.insertPoint(p1.toEntity(capturedAtEpochMs = 1_795_000_000_000L, isSynced = false))

            val unsynced = dao.getUnsyncedPointsOnce()
            assertEquals(1, unsynced.size)
            assertFalse(unsynced.first().isSynced)

            // Repository offline queue flush must NOT drop or clear points while unauthenticated/offline
            val flushedWithoutAuth = app.repository.flushPendingOfflineQueue()
            assertFalse(flushedWithoutAuth)
            assertEquals(1, dao.getUnsyncedPointsOnce().size)

            dao.markPointsSynced(listOf("pt-offline-1"))
            assertTrue(dao.getUnsyncedPointsOnce().isEmpty())
        } finally {
            db.close()
        }

        // Verify LocationTrackingService & DeviceLocationHelper react to GPS hardware disable/enable
        val lm = app.getSystemService(LocationManager::class.java)
        val shadowLm = shadowOf(lm)
        shadowLm.setLocationEnabled(false)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertFalse(DeviceLocationHelper.isDeviceGpsEnabled(app))

        val service = Robolectric.buildService(
            LocationTrackingService::class.java,
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            }
        ).create().get()

        service.onStartCommand(
            Intent(app, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_ACCEPTED_TRIP_ID, "trip-gps-check")
            },
            0,
            1
        )
        assertFalse(app.repository.isDeviceGpsEnabled.value)
        val shadowNm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val disabledTitle = app.getString(R.string.gps_disabled_notification_title)
        assertTrue(
            shadowNm.allNotifications.any { notif ->
                notif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == disabledTitle
            }
        )

        // Re-enable device GPS -> provider broadcast updates repository & service state
        shadowLm.setLocationEnabled(true)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        assertTrue(DeviceLocationHelper.isDeviceGpsEnabled(app))
        service.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION).setPackage(app.packageName))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}

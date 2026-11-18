package com.miyahna.water

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.data.ActionResult
import com.miyahna.water.data.MiyahnaDomainState
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.ClusterProposal
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DeliveryLocationLog
import com.miyahna.water.domain.DepartureRecommendation
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.DriverSession
import com.miyahna.water.domain.Expense
import com.miyahna.water.domain.ExpenseCategory
import com.miyahna.water.domain.FleetFilterCriteria
import com.miyahna.water.domain.FleetFilterEngine
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSEventType
import com.miyahna.water.domain.GPSGap
import com.miyahna.water.domain.Invoice
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.NotificationPriority
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.DriverTab
import com.miyahna.water.ui.ManagerTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.auth.DriverAuthScreen
import com.miyahna.water.ui.auth.RoleSelectionPortalScreen
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.driver.DriverAppScreen
import com.miyahna.water.ui.driver.DriverWeeklyFuelReportCard
import com.miyahna.water.ui.manager.ManagerShellScreen
import com.miyahna.water.ui.theme.MiyahnaTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllScreensNavigationCrashTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: MiyahnaViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>() as MiyahnaApplication
        viewModel = MiyahnaViewModel(app)
    }

    @Test
    fun portalAndAuthScreens_openDialogsAndSwitchTabs_withoutCrashing() {
        var verified = false
        composeTestRule.setContent {
            MiyahnaTheme {
                RoleSelectionPortalScreen(
                    isOnline = true,
                    cloudUser = null,
                    pendingDriversCount = 2,
                    onVerifyManagerPin = { pin ->
                        verified = pin == "20262027"
                        verified
                    },
                    onSelectDriver = {},
                    onCloudSignIn = {},
                    onCloudSignOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("select_manager_role_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_pin_input").performTextInput("20262027")
        composeTestRule.onNodeWithTag("confirm_manager_pin_button").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun driverAuthScreen_switchesLoginAndRegisterTabs_withoutCrashing() {
        var cloudSignInClicks = 0
        composeTestRule.setContent {
            MiyahnaTheme(darkDriverTheme = true) {
                DriverAuthScreen(
                    onBackToPortal = {},
                    onLogin = { _, _, _ -> },
                    onRegister = { _, _, _, _, _, cb -> cb(ActionResult<Unit>(true)) },
                    isOnline = true,
                    cloudUser = null,
                    onCloudSignIn = { cloudSignInClicks++ }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("driver_auth_cloud_signin_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assert(cloudSignInClicks == 1)
        composeTestRule.onNodeWithTag("driver_login_identifier_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("طلب حساب سائق جديد").onFirst().performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("driver_register_submit_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("دخول السائق").onFirst().performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("driver_login_submit_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun managerShell_browsesAll13TabsInCompactAndExpandedWithStressData_withoutCrashing() {
        val stressState = buildStressUiState()
        var currentTab by mutableStateOf(ManagerTab.OVERVIEW)
        var widthClass by mutableStateOf(WindowWidthSizeClass.Compact)

        composeTestRule.setContent {
            MiyahnaTheme {
                ManagerShellScreen(
                    uiState = stressState.copy(managerTab = currentTab),
                    windowWidthSizeClass = widthClass,
                    viewModel = viewModel,
                    onRequestCloudSignIn = {}
                )
            }
        }

        // Browse all 13 Manager tabs in Compact layout
        for (tab in ManagerTab.entries) {
            currentTab = tab
            composeTestRule.waitForIdle()
        }

        // Browse all 13 Manager tabs in Expanded layout (NavigationRail + 2-pane Fleet)
        widthClass = WindowWidthSizeClass.Expanded
        for (tab in ManagerTab.entries) {
            currentTab = tab
            composeTestRule.waitForIdle()
        }

        // Open nested dialogs that previously triggered Scaffold / DropdownMenu / LazyColumn crashes
        currentTab = ManagerTab.ORDERS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_order_fab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()

        currentTab = ManagerTab.CUSTOMERS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_customer_fab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()

        currentTab = ManagerTab.EXPENSES
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_expense_fab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()

        currentTab = ManagerTab.TRIPS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("trips_lazy_column").performScrollToIndex(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("trips_lazy_column").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("inspect_trip_map_trip-dup").onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("close_trip_review_dialog_button").performClick()
        composeTestRule.waitForIdle()

        // Header Notifications & Directive dialogs
        composeTestRule.onNodeWithContentDescription("الإشعارات").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إغلاق").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("إرسال توجيه للسائق").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun managerShell_bottomBarAndFirebaseActions_workSmoothlyWithoutCrashing() {
        val stressState = buildStressUiState()
        var currentTab by mutableStateOf(ManagerTab.OVERVIEW)
        var cloudSignInClicks = 0

        composeTestRule.setContent {
            MiyahnaTheme {
                ManagerShellScreen(
                    uiState = stressState.copy(managerTab = currentTab),
                    windowWidthSizeClass = WindowWidthSizeClass.Compact,
                    viewModel = viewModel,
                    onRequestCloudSignIn = { cloudSignInClicks++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manager_firebase_status_button").performClick()
        composeTestRule.waitForIdle()
        assert(cloudSignInClicks == 1)

        composeTestRule.onNodeWithTag("overview_connect_firebase_button").performClick()
        composeTestRule.waitForIdle()
        assert(cloudSignInClicks == 2)

        composeTestRule.onNodeWithTag("manager_nav_operations").performClick()
        currentTab = ManagerTab.ORDERS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_subtab_trips").performClick()
        currentTab = ManagerTab.TRIPS
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manager_nav_map_fleet").performClick()
        currentTab = ManagerTab.FLEET_LIVE
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_subtab_map").performClick()
        currentTab = ManagerTab.MAP
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manager_nav_admin").performClick()
        currentTab = ManagerTab.CUSTOMERS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_subtab_drivers").performScrollTo().performClick()
        currentTab = ManagerTab.DRIVERS
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_driver_by_manager_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_subtab_settings").performScrollTo().performClick()
        currentTab = ManagerTab.SETTINGS
        composeTestRule.waitForIdle()
    }

    @Test
    fun driverAppScreen_browsesAll4TabsWithStressData_withoutCrashing() {
        val stressState = buildStressUiState()
        var currentDriverTab by mutableStateOf(DriverTab.TRIP)

        composeTestRule.setContent {
            MiyahnaTheme(darkDriverTheme = true) {
                DriverAppScreen(
                    uiState = stressState.copy(driverTab = currentDriverTab),
                    hasLocationPermission = true,
                    onRequestLocationPermission = {},
                    onRequestCloudSignIn = {},
                    viewModel = viewModel
                )
            }
        }

        for (tab in DriverTab.entries) {
            currentDriverTab = tab
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithContentDescription("إشعارات السائق").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("إغلاق").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun weeklyFuelReportCardAndLocationPicker_handleNanAndEmptyData_withoutCrashing() {
        val nanTrip = buildStressUiState().domain.trips.first()
        composeTestRule.setContent {
            MiyahnaTheme(darkDriverTheme = true) {
                DriverWeeklyFuelReportCard(
                    trips = listOf(nanTrip, nanTrip),
                    settings = SystemSettings(),
                    driverId = "drv-1",
                    driverName = "خالد"
                )
                LocationPickerDialog(
                    title = "اختيار موقع",
                    initialLat = Double.NaN,
                    initialLng = Double.POSITIVE_INFINITY,
                    plantLocation = PlantLocation(),
                    geofenceMeters = 120,
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun buildStressUiState(): MiyahnaUiState {
        val nowIso = "2026-11-16T10:15:30Z"
        val badCustomer1 = Customer(
            id = "cust-dup",
            customerId = "CUST-0001",
            name = "عميل اختبار 1",
            phone = "777000111",
            district = "العرق الشرقي",
            lat = 15.495,
            lng = 45.365,
            tankCapacityLiters = 2000,
            createdAt = nowIso,
            totalOrdersCount = 2,
            totalLitersDelivered = 4000
        )
        val badCustomer2 = badCustomer1.copy(
            name = "عميل مكرر المعرف",
            lat = Double.NaN,
            lng = Double.POSITIVE_INFINITY
        )

        val order1 = Order(
            id = "ord-dup",
            orderNumber = "ORD-2026-0001",
            customerId = badCustomer1.id,
            customerName = badCustomer1.name,
            phone = badCustomer1.phone,
            district = badCustomer1.district,
            lat = 15.495,
            lng = 45.365,
            requestedLiters = 2000,
            pricePerLiter = 12.0,
            totalAmount = Double.NaN,
            createdAt = nowIso,
            isUrgent = true,
            duplicateWarning = true
        )
        val order2 = order1.copy(orderNumber = "ORD-2026-0002", status = OrderStatus.DELIVERED, totalAmount = 24000.0)

        val stop1 = TripCustomerStop(
            stopSequence = 1,
            orderId = "ord-stop-dup",
            customerId = badCustomer1.id,
            customerName = badCustomer1.name,
            phone = badCustomer1.phone,
            district = badCustomer1.district,
            lat = 15.495,
            lng = 45.365,
            requestedLiters = 2000,
            deliveredLiters = 2000,
            pricePerLiter = 12.0,
            totalAmount = Double.NaN,
            paidAmount = Double.NaN,
            status = StopStatus.PENDING
        )
        val stop2 = stop1.copy(stopSequence = 2, lat = Double.NaN, lng = Double.NaN, totalAmount = 24000.0, paidAmount = 24000.0)

        val gpsPts = listOf(
            TripGPSPoint(id = "pt-1", tripId = "trip-dup", lat = 15.492, lng = 45.362, speedKmH = Double.NaN, timestamp = nowIso, seq = 1),
            TripGPSPoint(id = "pt-2", tripId = "trip-dup", lat = Double.NaN, lng = 45.366, speedKmH = 35.0, timestamp = nowIso, seq = 2)
        )
        val detectedStops = listOf(
            DetectedStop(
                id = "det-dup",
                tripId = "trip-dup",
                lat = 15.495,
                lng = 45.365,
                startTime = nowIso,
                durationMinutes = 5.0,
                isUnplannedStop = true
            ),
            DetectedStop(
                id = "det-dup",
                tripId = "trip-dup",
                lat = Double.NaN,
                lng = Double.NaN,
                startTime = "bad-ts",
                durationMinutes = 3.0
            )
        )

        val trip1 = Trip(
            id = "trip-dup",
            tripNumber = "TRIP-2026-0001",
            date = "2026-11-16",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "خالد المأربي",
            truckPlate = "مأرب 1234",
            initialLoadLiters = 4000,
            status = TripStatus.IN_PROGRESS,
            driverAcceptanceStatus = "pending",
            customerStops = listOf(stop1, stop2),
            orderIds = listOf(order1.id, order2.id),
            proposedAt = nowIso,
            gpsPoints = gpsPts,
            detectedStops = detectedStops,
            gpsGaps = listOf(GPSGap(id = "gap-1", tripId = "trip-dup", startTime = "short", endTime = nowIso, durationSeconds = 180)),
            estimatedDistanceKm = 12.0,
            actualDistanceKm = 14.5,
            estimatedFuelLiters = 4.5,
            actualFuelLiters = Double.NaN,
            expectedRevenue = Double.NaN,
            expectedProfit = Double.NaN,
            actualRevenue = Double.NaN,
            actualProfit = Double.NaN
        )
        val tripReview = trip1.copy(
            tripNumber = "TRIP-2026-0002",
            status = TripStatus.COMPLETED_PENDING_REVIEW
        )

        val truck1 = Truck(
            id = "truck-dup",
            code = "TRUCK-01",
            plateNumber = "1234",
            lastLat = Double.NaN,
            lastLng = Double.POSITIVE_INFINITY,
            lastSpeed = Double.NaN,
            odometerKm = Double.NaN,
            lastUpdateTimestamp = nowIso,
            status = TruckStatus.ON_TRIP,
            currentTripId = trip1.id
        )
        val truck2 = truck1.copy(code = "TRUCK-02", lastLat = 15.493, lastLng = 45.363, lastSpeed = 42.0, status = TruckStatus.IDLE)

        val notif = AppNotification(
            id = "notif-dup",
            createdAt = nowIso,
            targetRole = "manager",
            title = "تنبيه",
            message = "اختبار استقرار القوائم المكررة"
        )
        val driverNotif = notif.copy(targetRole = "driver")

        val directive = ManagerDirective(
            id = "dir-dup",
            createdAt = nowIso,
            title = "توجيه ميداني",
            message = "التوجه للمحطة التالية",
            status = DirectiveStatus.SENT
        )

        val transition = TripStateTransition(
            id = "tlog-dup",
            tripId = trip1.id,
            tripNumber = trip1.tripNumber,
            fromState = "approved",
            toState = "in_progress",
            triggerSource = "manual_start",
            actorRole = "driver",
            actorName = "خالد",
            timestamp = "bad-iso"
        )

        val domain = MiyahnaDomainState(
            isLoaded = true,
            isOnline = true,
            driverSession = DriverSession(id = "drv-1", name = "خالد المأربي", username = "khaled", phone = "777", loggedInAt = nowIso),
            settings = SystemSettings(),
            truck = truck1,
            fleetTrucks = listOf(truck1, truck2),
            driverAccounts = listOf(
                DriverAccount(id = "drv-dup", name = "سائق 1", username = "d1", phone = "771", password = "123", status = DriverAccountStatus.PENDING, createdAt = nowIso),
                DriverAccount(id = "drv-dup", name = "سائق 2", username = "d2", phone = "772", password = "123", status = DriverAccountStatus.APPROVED, createdAt = nowIso)
            ),
            customers = listOf(badCustomer1, badCustomer2),
            orders = listOf(order1, order2),
            trips = listOf(trip1, tripReview),
            invoices = listOf(
                Invoice(id = "inv-dup", invoiceNumber = "INV-1", orderId = "o1", orderNumber = "ORD-1", tripId = trip1.id, tripNumber = trip1.tripNumber, customerId = "c1", customerName = "عميل", phone = "777", district = "العرق", requestedLiters = 2000, deliveredLiters = 2000, pricePerLiter = 12.0, totalAmount = Double.NaN, paidAmount = Double.NaN, remainingAmount = 0.0, createdAt = nowIso, driverName = "خالد"),
                Invoice(id = "inv-dup", invoiceNumber = "INV-2", orderId = "o2", orderNumber = "ORD-2", tripId = trip1.id, tripNumber = trip1.tripNumber, customerId = "c1", customerName = "عميل 2", phone = "777", district = "العرق", requestedLiters = 1000, deliveredLiters = 1000, pricePerLiter = 12.0, totalAmount = 12000.0, paidAmount = 12000.0, remainingAmount = 0.0, createdAt = nowIso, driverName = "خالد")
            ),
            reconciliations = listOf(
                DriverReconciliation(id = "rec-dup", reconciliationNumber = "REC-1", tripId = trip1.id, tripNumber = trip1.tripNumber, driverId = "drv-1", driverName = "خالد", date = "2026-11-16", totalInvoicedAmount = 12000.0, totalCashCollected = 12000.0, actualCashHandedOver = Double.NaN, authorizedExpensesDeducted = Double.NaN, differenceAmount = Double.NaN, createdAt = nowIso),
                DriverReconciliation(id = "rec-dup", reconciliationNumber = "REC-2", tripId = trip1.id, tripNumber = trip1.tripNumber, driverId = "drv-1", driverName = "خالد", date = "2026-11-16", totalInvoicedAmount = 12000.0, totalCashCollected = 12000.0, actualCashHandedOver = 12000.0, authorizedExpensesDeducted = 0.0, differenceAmount = 0.0, createdAt = nowIso)
            ),
            expenses = listOf(
                Expense(id = "exp-dup", expenseNumber = "EXP-1", date = "2026-11-16", category = ExpenseCategory.DIESEL, description = "ديزل", amount = Double.NaN, liters = Double.NaN),
                Expense(id = "exp-dup", expenseNumber = "EXP-2", date = "2026-11-16", category = ExpenseCategory.OTHER, description = "صيانة", amount = 5000.0)
            ),
            gpsEvents = listOf(
                GPSEvent(id = "ev-1", tripId = "standby-2026-11-16", tripNumber = "خارج رحلة", timestamp = "bad-ts", eventType = GPSEventType.STOPPED, lat = Double.NaN, lng = Double.NaN)
            ),
            notifications = listOf(notif, notif, driverNotif, driverNotif),
            directives = listOf(directive, directive),
            tripStateTransitions = listOf(transition, transition)
        )

        val cluster = ClusterProposal(
            id = "cl-dup",
            orders = listOf(order1),
            totalLiters = 2000,
            capacityUtilization = 50.0,
            customerStops = listOf(stop1),
            estimatedDistanceKm = 10.0,
            estimatedFuelLiters = 3.8,
            fuelCost = 2470.0,
            productionCost = 7000.0,
            driverWageShare = 5000.0,
            maintenanceShare = 2500.0,
            totalCost = 16970.0,
            expectedRevenue = Double.NaN,
            expectedProfit = Double.NaN,
            profitMarginPercent = 0.0,
            recommendation = DepartureRecommendation.GO_NOW,
            reason = "اختبار التجميع",
            districtsCovered = listOf("العرق الشرقي")
        )

        val allTrucks = listOf(truck1, truck2)
        return MiyahnaUiState(
            domain = domain,
            clusters = listOf(cluster, cluster),
            filteredCustomers = domain.customers,
            deliveryLocations = listOf(
                DeliveryLocationLog(
                    id = "dloc-1",
                    tripId = trip1.id,
                    latitude = Double.NaN,
                    longitude = Double.NaN,
                    capturedAtIso = "short",
                    capturedAtEpochMs = 1L
                )
            ),
            fleetFilterCriteria = FleetFilterCriteria(),
            allFleetTrucks = allTrucks,
            filteredFleetTrucks = allTrucks,
            fleetSummary = FleetFilterEngine.computeSummary(allTrucks),
            selectedFleetTruckId = truck1.id
        )
    }
}

package com.miyahna.water

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.data.MiyahnaRepository
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.data.local.AuthSessionPrefs
import com.miyahna.water.data.local.MiyahnaDatabase
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.ui.auth.RoleSelectionPortalScreen
import com.miyahna.water.ui.common.CustomerOrderLookupSection
import com.miyahna.water.ui.manager.NewOrderDialog
import com.miyahna.water.ui.theme.MiyahnaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomerOrderSyncAndPersistentAuthTest {

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
    fun ordersFromDriverAndManager_autoSaveNewCustomers_reuseExisting_andAllowManagerEdit() = runBlocking {
        val repo = newRepo()
        repo.awaitInitialized()

        // 1. Driver records an order for a brand-new customer -> auto-saved to customers DB + Room
        val driverOrderRes = repo.addOrder(
            existingCustomerId = null,
            customerName = "أحمد الحميقاني",
            phone = "771234567",
            district = "العرق الشرقي",
            addressDetails = "جوار المسجد الكبير",
            lat = 15.496,
            lng = 45.366,
            tankCapacity = 3000,
            requestedLiters = 2000,
            pricePerLiter = 12.0,
            source = OrderSource.DRIVER_MANUAL,
            isUrgent = false,
            notes = "خزان علوي"
        )
        assertTrue(driverOrderRes.success)
        val driverOrder = driverOrderRes.data!!

        val customersAfterDriver = repo.state.value.customers
        assertEquals(1, customersAfterDriver.size)
        val savedCustomer = customersAfterDriver.first()
        assertEquals(driverOrder.customerId, savedCustomer.id)
        assertEquals("أحمد الحميقاني", savedCustomer.name)
        assertEquals("771234567", savedCustomer.phone)
        assertEquals(1, savedCustomer.totalOrdersCount)
        assertEquals(1, db.customerDao().getAllCustomersOnce().size)

        // 2. Manager edits any field of the customer in Customers DB
        val updateRes = repo.updateCustomer(
            customerId = savedCustomer.id,
            name = "أحمد صالح الحميقاني",
            phone = "771234567",
            altPhone = "733000111",
            district = "حصون آل جلال",
            addressDetails = "شارع الأربعين - البوابة الغربية",
            lat = 15.498,
            lng = 45.369,
            tankCapacityLiters = 5000,
            notes = "عميل منتظم"
        )
        assertTrue(updateRes.success)
        val editedCustomer = db.customerDao().getCustomerById(savedCustomer.id)
        assertNotNull(editedCustomer)
        assertEquals("أحمد صالح الحميقاني", editedCustomer!!.name)
        assertEquals("733000111", editedCustomer.altPhone)
        assertEquals("حصون آل جلال", editedCustomer.district)
        assertEquals(5000, editedCustomer.tankCapacityLiters)

        // 3. Manager records a new order using the same phone (without passing customerId) -> imports existing customer cleanly
        val managerRepeatRes = repo.addOrder(
            existingCustomerId = null,
            customerName = "أحمد الحميقاني",
            phone = "771234567",
            district = "حصون آل جلال",
            addressDetails = "شارع الأربعين",
            lat = 15.498,
            lng = 45.369,
            tankCapacity = 5000,
            requestedLiters = 4000,
            pricePerLiter = 12.0,
            source = OrderSource.MANAGER_MANUAL,
            isUrgent = true,
            notes = null
        )
        assertTrue(managerRepeatRes.success)
        assertEquals(savedCustomer.id, managerRepeatRes.data!!.customerId)
        assertEquals(1, repo.state.value.customers.size)
        assertEquals(2, repo.state.value.customers.first().totalOrdersCount)

        // 4. Manager records another new order for a different brand-new customer -> saved automatically
        val secondNewRes = repo.addOrder(
            existingCustomerId = null,
            customerName = "بقالة النصر",
            phone = "778889990",
            district = "الأشراف",
            addressDetails = "الخط العام",
            lat = 15.501,
            lng = 45.371,
            tankCapacity = 4000,
            requestedLiters = 2000,
            pricePerLiter = 12.0,
            source = OrderSource.MANAGER_MANUAL,
            isUrgent = false,
            notes = null
        )
        assertTrue(secondNewRes.success)
        assertEquals(2, repo.state.value.customers.size)
        assertEquals(2, db.customerDao().getAllCustomersOnce().size)
    }

    @Test
    fun managerOneTimePin_andDriverOneTimeLogin_persistAcrossRepositoryRestart() = runBlocking {
        // --- Manager one-time PIN flow ---
        val repo1 = newRepo()
        repo1.awaitInitialized()
        assertFalse(repo1.state.value.isManagerUnlocked)
        assertNull(repo1.state.value.savedRole)

        assertFalse(repo1.verifyManagerPin("0000"))
        assertTrue(repo1.verifyManagerPin("20262027"))
        assertTrue(repo1.state.value.isManagerUnlocked)
        assertEquals(AppRole.MANAGER, repo1.state.value.savedRole)

        // Simulate app cold restart -> fresh MiyahnaRepository should auto-open Manager without PIN prompt
        val repo2 = newRepo()
        repo2.awaitInitialized()
        assertEquals(AppRole.MANAGER, repo2.state.value.savedRole)
        assertTrue(repo2.state.value.isManagerUnlocked)

        // Temporarily switch back to portal -> Manager remains unlocked
        repo2.selectRole(null)
        val repo3 = newRepo()
        repo3.awaitInitialized()
        assertNull(repo3.state.value.savedRole)
        assertTrue(repo3.state.value.isManagerUnlocked)

        // Explicit lock clears manager unlock
        repo3.lockManagerSession()
        val repo4 = newRepo()
        repo4.awaitInitialized()
        assertFalse(repo4.state.value.isManagerUnlocked)
        assertNull(repo4.state.value.savedRole)

        // --- Driver one-time login flow ---
        val created = repo4.createDriverAccountByManager(
            name = "خالد المأربي",
            username = "khaled1",
            phone = "775554433",
            password = "pass123",
            googleEmail = null,
            licenseNumber = "LIC-88"
        )
        assertTrue(created.success)

        val loginRes = repo4.loginDriver("khaled1", "pass123")
        assertTrue(loginRes.success)
        assertEquals(AppRole.DRIVER, repo4.state.value.savedRole)
        assertNotNull(repo4.state.value.driverSession)

        // Simulate app cold restart -> fresh MiyahnaRepository opens Driver UI directly with session preserved
        val repo5 = newRepo()
        repo5.awaitInitialized()
        assertEquals(AppRole.DRIVER, repo5.state.value.savedRole)
        assertNotNull(repo5.state.value.driverSession)
        assertEquals("خالد المأربي", repo5.state.value.driverSession!!.name)
        assertTrue(AuthSessionPrefs.isManagerUnlocked(app).not())

        // Explicit driver logout clears session & role
        repo5.logoutDriver()
        val repo6 = newRepo()
        repo6.awaitInitialized()
        assertNull(repo6.state.value.driverSession)
        assertNull(repo6.state.value.savedRole)
    }

    @Test
    fun customerLookupAndNewOrderDialog_importExistingCustomerAndSwitchToNewCustomer() {
        val customers = listOf(
            Customer(
                id = "c-101",
                customerId = "CUST-0101",
                name = "مجمع الخير السكني",
                phone = "770101010",
                district = "العرق الشرقي",
                addressDetails = "خلف المستشفى",
                lat = 15.495,
                lng = 45.365,
                tankCapacityLiters = 5000,
                createdAt = "2026-11-18T08:00:00Z",
                totalOrdersCount = 4
            ),
            Customer(
                id = "c-102",
                customerId = "CUST-0102",
                name = "استراحة النخيل",
                phone = "770202020",
                district = "حصون آل جلال",
                addressDetails = "الشارع العام",
                lat = 15.499,
                lng = 45.369,
                tankCapacityLiters = 3000,
                createdAt = "2026-11-18T08:00:00Z",
                totalOrdersCount = 1
            )
        )

        var importedId: String? = null
        composeTestRule.setContent {
            MiyahnaTheme {
                NewOrderDialog(
                    customers = customers,
                    districts = AlWadiDistricts.districtNames,
                    settings = SystemSettings(),
                    defaultSource = OrderSource.MANAGER_MANUAL,
                    onAddCustomDistrict = {},
                    onDismiss = {},
                    onSubmit = { custId, _, _, _, _, _, _, _, _, _, _, _, _ ->
                        importedId = custId
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Initially in new customer registration mode
        composeTestRule.onNodeWithTag("new_customer_info_banner").assertIsDisplayed()

        // Type partial name -> live suggestion from customers DB appears -> tap to import
        composeTestRule.onNodeWithTag("order_customer_name_input").performTextInput("مجمع الخير")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_suggestion_c-101").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Imported banner shown with customer details
        composeTestRule.onNodeWithTag("imported_customer_banner").assertIsDisplayed()

        // Switch back to registering a new customer
        composeTestRule.onNodeWithTag("switch_to_new_customer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("new_customer_info_banner").assertIsDisplayed()

        // Open searchable customer picker dialog, search, and select second customer
        composeTestRule.onNodeWithTag("open_customer_import_picker_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_import_search_input").performTextInput("النخيل")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("import_customer_item_c-102").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("imported_customer_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_new_order_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("c-102", importedId)
    }

    @Test
    fun roleSelectionPortal_bypassesPinDialog_whenManagerIsAlreadyUnlocked() {
        var unlocked by mutableStateOf(false)
        var directEntries = 0

        composeTestRule.setContent {
            MiyahnaTheme {
                RoleSelectionPortalScreen(
                    isOnline = true,
                    cloudUser = null,
                    pendingDriversCount = 0,
                    onVerifyManagerPin = { it == "20262027" },
                    onSelectDriver = {},
                    onCloudSignIn = {},
                    onCloudSignOut = {},
                    isManagerUnlocked = unlocked,
                    hasSavedDriverSession = true,
                    onEnterManagerDirectly = { directEntries++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Driver card shows saved login badge
        composeTestRule.onNodeWithTag("select_driver_role_card").performScrollTo()
        composeTestRule.onNodeWithText("دخول محفوظ").performScrollTo().assertIsDisplayed()

        // Locked: tapping Manager opens PIN prompt
        composeTestRule.onNodeWithTag("select_manager_role_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manager_pin_input").assertIsDisplayed()
        composeTestRule.onNodeWithText("إلغاء").performClick()
        composeTestRule.waitForIdle()

        // Unlocked: tapping Manager enters directly without PIN dialog
        unlocked = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("select_manager_role_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, directEntries)
        assertTrue(composeTestRule.onAllNodesWithTag("manager_pin_input").fetchSemanticsNodes().isEmpty())
    }
}

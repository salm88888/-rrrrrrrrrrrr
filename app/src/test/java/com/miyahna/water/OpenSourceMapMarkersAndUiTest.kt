package com.miyahna.water

import android.app.Application
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.common.MapMarkerIcons
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.theme.MiyahnaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenSourceMapMarkersAndUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleCustomer(
        id: String,
        name: String,
        phone: String,
        lat: Double,
        lng: Double
    ) = Customer(
        id = id,
        customerId = id,
        name = name,
        phone = phone,
        district = "حصون آل جلال",
        lat = lat,
        lng = lng,
        createdAt = "2026-01-01T00:00:00Z"
    )

    private fun sampleOrder(
        id: String,
        customerId: String,
        customerName: String,
        phone: String,
        lat: Double,
        lng: Double,
        status: OrderStatus
    ) = Order(
        id = id,
        orderNumber = "ORD-$id",
        customerId = customerId,
        customerName = customerName,
        phone = phone,
        district = "حصون آل جلال",
        lat = lat,
        lng = lng,
        requestedLiters = 2000,
        pricePerLiter = 12.0,
        totalAmount = 24000.0,
        createdAt = "2026-01-01T00:00:00Z",
        status = status
    )

    @Test
    fun isCustomerOrdering_distinguishesActiveOrdersAndNormalCustomers() {
        val orderingCustomer = sampleCustomer("c1", "زبون طالب", "771111111", 15.4950, 45.3650)
        val deliveredOnlyCustomer = sampleCustomer("c2", "زبون مستلم", "772222222", 15.4980, 45.3680)
        val normalCustomer = sampleCustomer("c3", "زبون عادي", "773333333", 15.5010, 45.3710)
        val stopCustomer = sampleCustomer("c4", "زبون محطة", "774444444", 15.5040, 45.3740)

        val orders = listOf(
            sampleOrder("o1", "c1", "زبون طالب", "771111111", 15.4950, 45.3650, OrderStatus.WAITING_CLUSTERING),
            sampleOrder("o2", "c2", "زبون مستلم", "772222222", 15.4980, 45.3680, OrderStatus.DELIVERED),
            sampleOrder("o3", "c3", "زبون عادي", "773333333", 15.5010, 45.3710, OrderStatus.CANCELLED)
        )
        val plannedStops = listOf(
            TripCustomerStop(
                stopSequence = 1,
                orderId = "o4",
                customerId = "c4",
                customerName = "زبون محطة",
                phone = "774444444",
                district = "حصون آل جلال",
                lat = 15.5040,
                lng = 45.3740,
                requestedLiters = 2000,
                pricePerLiter = 12.0,
                totalAmount = 24000.0,
                status = StopStatus.PENDING
            )
        )

        val activeOrders = MapMarkerIcons.activeOrdersFrom(orders)
        val activeIds = MapMarkerIcons.activeCustomerIds(orders, plannedStops)

        assertTrue(MapMarkerIcons.isCustomerOrdering(orderingCustomer, activeIds, activeOrders, plannedStops))
        assertTrue(MapMarkerIcons.isCustomerOrdering(stopCustomer, activeIds, activeOrders, plannedStops))
        assertFalse(MapMarkerIcons.isCustomerOrdering(deliveredOnlyCustomer, activeIds, activeOrders, plannedStops))
        assertFalse(MapMarkerIcons.isCustomerOrdering(normalCustomer, activeIds, activeOrders, plannedStops))
    }

    @Test
    fun customMarkerBitmaps_generateValidFactoryTruckAndRedGrayTankIcons() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val factory = MapMarkerIcons.factoryBitmap(context)
        val selectedTruck = MapMarkerIcons.tankerTruckBitmap(context, TruckStatus.ON_TRIP, isSelected = true)
        val idleTruck = MapMarkerIcons.tankerTruckBitmap(context, TruckStatus.IDLE, isSelected = false)
        val redTank = MapMarkerIcons.customerTankBitmap(context, isOrdering = true)
        val grayTank = MapMarkerIcons.customerTankBitmap(context, isOrdering = false)

        assertTrue(factory.width >= 48 && factory.height >= 48)
        assertTrue(selectedTruck.width > idleTruck.width)
        assertTrue(idleTruck.width >= 46 && idleTruck.height >= 46)
        assertTrue(redTank.width > grayTank.width)
        assertTrue(grayTank.width >= 30 && grayTank.height >= 30)

        // Verify Red Tank vs Gray Tank color tokens
        val redFill = MapMarkerIcons.customerTankFillColor(isOrdering = true)
        val grayFill = MapMarkerIcons.customerTankFillColor(isOrdering = false)
        assertTrue(Color.red(redFill) > 200 && Color.green(redFill) < 100 && Color.blue(redFill) < 100)
        assertTrue(
            Color.red(grayFill) in 120..180 &&
                Color.green(grayFill) in 140..190 &&
                Color.blue(grayFill) in 160..210
        )

        // Drawables should wrap non-empty cached bitmaps
        assertTrue(MapMarkerIcons.factoryDrawable(context).intrinsicWidth > 0)
        assertTrue(MapMarkerIcons.tankerTruckDrawable(context, TruckStatus.ON_TRIP, true).intrinsicWidth > 0)
        assertTrue(MapMarkerIcons.customerTankDrawable(context, isOrdering = true).intrinsicWidth > 0)
        assertTrue(MapMarkerIcons.customerTankDrawable(context, isOrdering = false).intrinsicWidth > 0)
    }

    @Test
    fun maribInteractiveMap_defaultsToOsmAndSwitchesModesAndLegendCounts() {
        val plant = PlantLocation(lat = 15.4920, lng = 45.3620)
        val truck = Truck(
            id = "t1",
            code = "W-01",
            plateNumber = "مأرب 101",
            status = TruckStatus.ON_TRIP,
            lastLat = 15.4890,
            lastLng = 45.3710,
            lastSpeed = 35.0
        )
        val customers = listOf(
            sampleCustomer("c1", "عميل طالب", "771000001", 15.4940, 45.3660),
            sampleCustomer("c2", "عميل عادي", "771000002", 15.4990, 45.3750)
        )
        val orders = listOf(
            sampleOrder("o1", "c1", "عميل طالب", "771000001", 15.4940, 45.3660, OrderStatus.IN_DELIVERY)
        )

        composeTestRule.setContent {
            MiyahnaTheme {
                MaribInteractiveMap(
                    plantLocation = plant,
                    geofenceMeters = 120,
                    customers = customers,
                    orders = orders,
                    trucks = listOf(truck),
                    selectedTruckId = "t1",
                    showCustomers = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeTestRule.onNodeWithTag("osm_map_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_fit_all_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_focus_truck_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_focus_plant_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_zoom_in_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_zoom_out_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_layers_button").assertIsDisplayed()

        composeTestRule.onNodeWithText("المصنع").assertIsDisplayed()
        composeTestRule.onNodeWithText("الوايت (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("خزان أحمر: طلب (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("خزان رمادي: عادي (1)").assertIsDisplayed()

        // Open and toggle OSM layer menu
        composeTestRule.onNodeWithTag("osm_layers_button").performClick()
        composeTestRule.onNodeWithText("قمر صناعي").assertIsDisplayed()
        composeTestRule.onNodeWithTag("osm_layer_satellite").performClick()

        // Click camera quick controls
        composeTestRule.onNodeWithTag("osm_focus_truck_button").performClick()
        composeTestRule.onNodeWithTag("osm_focus_plant_button").performClick()
        composeTestRule.onNodeWithTag("osm_fit_all_button").performClick()

        // Switch to Local Offline Vector map and back to OSM
        composeTestRule.onNodeWithTag("switch_vector_map_chip").performClick()
        composeTestRule.onNodeWithTag("switch_osm_map_chip").performClick()
        composeTestRule.onNodeWithTag("osm_map_view").assertIsDisplayed()
    }
}

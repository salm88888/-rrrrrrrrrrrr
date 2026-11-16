package com.miyahna.water

import com.miyahna.water.domain.FleetFilterCriteria
import com.miyahna.water.domain.FleetFilterEngine
import com.miyahna.water.domain.FleetSortOption
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetFilterUnitTest {
    private val truck1 = Truck(
        id = "truck-1",
        code = "TRUCK-01",
        plateNumber = "مأرب 101",
        model = "هينو 6000L",
        maxCapacityLiters = 6000,
        currentWaterLiters = 4200,
        driverId = "drv-1",
        driverName = "سالم الجلال",
        driverPhone = "777111222",
        status = TruckStatus.IDLE,
        lastLat = 15.4920,
        lastLng = 45.3620,
        lastSpeed = 0.0,
        lastUpdateTimestamp = "2026-03-01T08:00:00Z"
    )

    private val truck2 = Truck(
        id = "truck-2",
        code = "TRUCK-02",
        plateNumber = "مأرب 202",
        model = "ايسوزو 8000L",
        maxCapacityLiters = 8000,
        currentWaterLiters = 7500,
        driverId = "drv-2",
        driverName = "علي المرادي",
        driverPhone = "777333444",
        status = TruckStatus.LOADING,
        lastLat = 15.4930,
        lastLng = 45.3640,
        lastSpeed = 0.0,
        lastUpdateTimestamp = "2026-03-01T08:05:00Z"
    )

    private val truck3 = Truck(
        id = "truck-3",
        code = "TRUCK-03",
        plateNumber = "مأرب 303",
        model = "مرسيدس 10000L",
        maxCapacityLiters = 10000,
        currentWaterLiters = 0,
        driverId = "drv-3",
        driverName = "ناصر العبيدي",
        driverPhone = "777555666",
        status = TruckStatus.MAINTENANCE,
        lastLat = 15.4910,
        lastLng = 45.3610,
        lastSpeed = 0.0,
        lastUpdateTimestamp = "2026-03-01T07:30:00Z"
    )

    @Test
    fun resolveAllTrucksMergesPrimaryFleetAndActiveTripTelemetry() {
        val activeTripForTruck1 = Trip(
            id = "trip-live-1",
            tripNumber = "TRP-100",
            date = "2026-03-01",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم الجلال",
            truckPlate = "مأرب 101",
            initialLoadLiters = 6000,
            remainingLiters = 4000,
            status = TripStatus.IN_PROGRESS,
            proposedAt = "2026-03-01T07:50:00Z",
            gpsPoints = listOf(
                TripGPSPoint(
                    id = "pt-1",
                    tripId = "trip-live-1",
                    lat = 15.5012,
                    lng = 45.3888,
                    speedKmH = 44.5,
                    timestamp = "2026-03-01T08:15:00Z"
                )
            )
        )

        val tripOnlyTruck4 = Trip(
            id = "trip-4",
            tripNumber = "TRP-101",
            date = "2026-03-01",
            truckId = "truck-4",
            driverId = "drv-4",
            driverName = "خالد الشبواني",
            truckPlate = "مأرب 404",
            initialLoadLiters = 5000,
            remainingLiters = 2500,
            status = TripStatus.IN_PROGRESS,
            proposedAt = "2026-03-01T08:10:00Z"
        )

        val resolved = FleetFilterEngine.resolveAllTrucks(
            primaryTruck = truck1,
            fleetTrucks = listOf(truck1, truck2, truck3),
            trips = listOf(activeTripForTruck1, tripOnlyTruck4)
        )

        assertEquals(4, resolved.size)
        val liveTruck1 = resolved.first { it.id == "truck-1" }
        assertEquals(TruckStatus.ON_TRIP, liveTruck1.status)
        assertEquals(15.5012, liveTruck1.lastLat, 0.0001)
        assertEquals(45.3888, liveTruck1.lastLng, 0.0001)
        assertEquals(44.5, liveTruck1.lastSpeed, 0.01)
        assertEquals("trip-live-1", liveTruck1.currentTripId)

        val synthesized = resolved.first { it.id == "truck-4" }
        assertEquals("TRUCK-4", synthesized.code)
        assertEquals(TruckStatus.ON_TRIP, synthesized.status)
        assertEquals(2500, synthesized.currentWaterLiters)
    }

    @Test
    fun computeSummaryCalculatesCountsAndTotalWaterCapacity() {
        val movingTruck1 = truck1.copy(status = TruckStatus.ON_TRIP, lastSpeed = 38.0, currentTripId = "trip-1")
        val fleet = listOf(movingTruck1, truck2, truck3)

        val summary = FleetFilterEngine.computeSummary(fleet)
        assertEquals(3, summary.totalCount)
        assertEquals(1, summary.onTripCount)
        assertEquals(1, summary.loadingCount)
        assertEquals(0, summary.idleCount)
        assertEquals(1, summary.maintenanceCount)
        assertEquals(1, summary.movingCount)
        assertEquals(4200 + 7500, summary.totalCurrentWaterLiters)
        assertEquals(6000 + 8000 + 10000, summary.totalMaxCapacityLiters)
    }

    @Test
    fun filterAndSortAppliesQueryStatusMovingActiveTripAndSortOrder() {
        val movingTruck1 = truck1.copy(status = TruckStatus.ON_TRIP, lastSpeed = 42.0, currentTripId = "trip-1")
        val fleet = listOf(truck3, truck2, movingTruck1)

        // Default status priority: ON_TRIP -> LOADING -> IDLE -> MAINTENANCE
        val byPriority = FleetFilterEngine.filterAndSort(fleet, FleetFilterCriteria())
        assertEquals(listOf("truck-1", "truck-2", "truck-3"), byPriority.map { it.id })

        // Query by driver name or plate
        val byQuery = FleetFilterEngine.filterAndSort(fleet, FleetFilterCriteria(query = "202"))
        assertEquals(listOf("truck-2"), byQuery.map { it.id })

        // Query by phone
        val byPhone = FleetFilterEngine.filterAndSort(fleet, FleetFilterCriteria(query = "777555"))
        assertEquals(listOf("truck-3"), byPhone.map { it.id })

        // Status filter
        val onlyLoading = FleetFilterEngine.filterAndSort(
            fleet,
            FleetFilterCriteria(statusFilter = TruckStatus.LOADING)
        )
        assertEquals(listOf("truck-2"), onlyLoading.map { it.id })

        // Moving + active trip filter
        val movingAndActive = FleetFilterEngine.filterAndSort(
            fleet,
            FleetFilterCriteria(movingOnly = true, activeTripOnly = true)
        )
        assertEquals(listOf("truck-1"), movingAndActive.map { it.id })

        // Sort by water load descending
        val byWaterDesc = FleetFilterEngine.filterAndSort(
            fleet,
            FleetFilterCriteria(sortOption = FleetSortOption.WATER_LOAD_DESC)
        )
        assertEquals(listOf("truck-2", "truck-1", "truck-3"), byWaterDesc.map { it.id })

        // Empty filter check
        assertFalse(FleetFilterCriteria().hasActiveFilters)
        assertTrue(FleetFilterCriteria(movingOnly = true).hasActiveFilters)
    }
}

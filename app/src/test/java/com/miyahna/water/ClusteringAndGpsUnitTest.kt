package com.miyahna.water

import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.ClusteringEngine
import com.miyahna.water.domain.GpsTrackingEngine
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.TripGPSPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusteringAndGpsUnitTest {
    @Test
    fun clusteringRespectsTruckMaxCapacityAndProducesProposal() {
        val settings = SystemSettings()
        val orders = listOf(
            Order(
                id = "o1",
                orderNumber = "ORD-1",
                customerId = "c1",
                customerName = "عميل 1",
                phone = "770000001",
                district = AlWadiDistricts.DEFAULT_DISTRICT,
                lat = 15.4950,
                lng = 45.3650,
                requestedLiters = 3000,
                pricePerLiter = 12.0,
                totalAmount = 36000.0,
                createdAt = "2026-01-01T08:00:00Z",
                status = OrderStatus.WAITING_CLUSTERING
            ),
            Order(
                id = "o2",
                orderNumber = "ORD-2",
                customerId = "c2",
                customerName = "عميل 2",
                phone = "770000002",
                district = "العرقين",
                lat = 15.4960,
                lng = 45.3820,
                requestedLiters = 2500,
                pricePerLiter = 12.0,
                totalAmount = 30000.0,
                createdAt = "2026-01-01T08:05:00Z",
                status = OrderStatus.WAITING_CLUSTERING
            )
        )

        val proposals = ClusteringEngine.generateSmartClusters(orders, settings, maxTruckCapacity = 6000)
        assertEquals(1, proposals.size)
        assertEquals(5500, proposals.first().totalLiters)
        assertTrue(proposals.first().expectedProfit > 0.0)
    }

    @Test
    fun gpsTrackAnalysisDetectsProlongedUnplannedStop() {
        val settings = SystemSettings(stopDetectionMinutes = 3, minMovementSpeedKmH = 8.0)
        val pts = listOf(
            TripGPSPoint(id = "p1", tripId = "t1", lat = 15.505, lng = 45.390, speedKmH = 1.0, timestamp = "2026-01-01T08:00:00Z"),
            TripGPSPoint(id = "p2", tripId = "t1", lat = 15.50505, lng = 45.39005, speedKmH = 0.5, timestamp = "2026-01-01T08:04:00Z")
        )
        val analysis = GpsTrackingEngine.analyzeGpsTrack("t1", pts, emptyList(), settings)
        assertEquals(1, analysis.detectedStops.size)
        assertTrue(analysis.detectedStops.first().isUnplannedStop)
    }

    @Test
    fun enginesHandleNonFiniteCoordinatesAndZeroCapacityWithoutCrashing() {
        val settings = SystemSettings()
        val orders = listOf(
            Order(
                id = "o-nan",
                orderNumber = "ORD-NAN",
                customerId = "c-nan",
                customerName = "عميل غير محدد",
                phone = "770000003",
                district = AlWadiDistricts.DEFAULT_DISTRICT,
                lat = Double.NaN,
                lng = Double.POSITIVE_INFINITY,
                requestedLiters = 2000,
                pricePerLiter = Double.NaN,
                totalAmount = Double.NaN,
                createdAt = "2026-01-01T08:00:00Z",
                status = OrderStatus.WAITING_CLUSTERING
            )
        )
        val proposals = ClusteringEngine.generateSmartClusters(orders, settings, maxTruckCapacity = 0)
        assertEquals(1, proposals.size)

        val dist = ClusteringEngine.haversineDistanceKm(Double.NaN, 45.0, 15.0, Double.NEGATIVE_INFINITY)
        assertEquals(0.0, dist, 0.0)

        val badPts = listOf(
            TripGPSPoint(id = "p-nan", tripId = "t-nan", lat = Double.NaN, lng = 45.39, speedKmH = Double.NaN, accuracy = Double.NaN, timestamp = "bad"),
            TripGPSPoint(id = "p-inf", tripId = "t-nan", lat = 15.5, lng = Double.POSITIVE_INFINITY, speedKmH = 12.0, accuracy = 5.0, timestamp = "2026-01-01T08:00:00Z")
        )
        val gpsResult = GpsTrackingEngine.analyzeGpsTrack("t-nan", badPts, emptyList(), settings)
        assertTrue(gpsResult.points.isEmpty())

        assertEquals(AlWadiDistricts.DEFAULT_DISTRICT, AlWadiDistricts.getClosestDistrict(Double.NaN, Double.NaN))
    }
}

package com.miyahna.water

import com.miyahna.water.data.mergeTripModels
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripAuditEntry
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMergeAndAuditUnitTest {

    @Test
    fun mergeTripModelsPropagatesDriverAcceptanceWhenStatusRemainsApproved() {
        val managerLocal = Trip(
            id = "trip-101",
            tripNumber = "TRIP-2026-0101",
            date = "2026-03-01",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم",
            truckPlate = "م أ ر 101",
            initialLoadLiters = 4000,
            status = TripStatus.APPROVED,
            driverAcceptanceStatus = "pending",
            proposedAt = "2026-03-01T08:00:00Z",
            auditLogs = listOf(
                TripAuditEntry(
                    id = "aud-1",
                    timestamp = "2026-03-01T08:00:00Z",
                    actorRole = "manager",
                    actorName = "المدير العام",
                    action = "اعتماد الرحلة"
                )
            )
        )

        val driverCloud = managerLocal.copy(
            driverAcceptanceStatus = "accepted",
            driverRespondedAt = "2026-03-01T08:02:00Z",
            auditLogs = managerLocal.auditLogs + TripAuditEntry(
                id = "aud-2",
                timestamp = "2026-03-01T08:02:00Z",
                actorRole = "driver",
                actorName = "سالم",
                action = "قبول الرحلة والانتقال التلقائي للخلفية"
            )
        )

        val merged = mergeTripModels(managerLocal, driverCloud, SystemSettings())!!
        assertEquals("accepted", merged.driverAcceptanceStatus)
        assertEquals("2026-03-01T08:02:00Z", merged.driverRespondedAt)
        assertEquals(2, merged.auditLogs.size)
    }

    @Test
    fun mergeTripModelsCombinesGpsStopsAndManagerDeliveryUpdates() {
        val stop = TripCustomerStop(
            stopSequence = 1,
            orderId = "ord-1",
            customerId = "cust-1",
            customerName = "محمد",
            phone = "770000111",
            district = "العرقين",
            lat = 15.501,
            lng = 45.381,
            requestedLiters = 2000,
            pricePerLiter = 12.0,
            totalAmount = 24000.0
        )

        val driverLocal = Trip(
            id = "trip-202",
            tripNumber = "TRIP-2026-0202",
            date = "2026-03-01",
            truckId = "truck-1",
            driverId = "drv-1",
            driverName = "سالم",
            truckPlate = "م أ ر 101",
            initialLoadLiters = 2000,
            status = TripStatus.IN_PROGRESS,
            driverAcceptanceStatus = "accepted",
            customerStops = listOf(stop),
            proposedAt = "2026-03-01T08:00:00Z",
            gpsPoints = listOf(
                TripGPSPoint(id = "p1", tripId = "trip-202", lat = 15.515, lng = 45.395, speedKmH = 1.0, timestamp = "2026-03-01T08:10:00Z", seq = 1),
                TripGPSPoint(id = "p2", tripId = "trip-202", lat = 15.51505, lng = 45.39505, speedKmH = 0.5, timestamp = "2026-03-01T08:15:00Z", seq = 2)
            )
        )

        val managerCloud = driverLocal.copy(
            gpsPoints = emptyList(),
            customerStops = listOf(
                stop.copy(
                    status = StopStatus.DELIVERED,
                    deliveredLiters = 2000,
                    paidAmount = 24000.0,
                    remainingAmount = 0.0
                )
            )
        )

        val merged = mergeTripModels(driverLocal, managerCloud, SystemSettings(stopDetectionMinutes = 3))!!
        assertEquals(StopStatus.DELIVERED, merged.customerStops.first().status)
        assertEquals(2000, merged.deliveredLiters)
        assertEquals(2, merged.gpsPoints.size)
        assertEquals(1, merged.detectedStops.size)
        assertTrue(merged.detectedStops.first().isUnplannedStop)
    }
}

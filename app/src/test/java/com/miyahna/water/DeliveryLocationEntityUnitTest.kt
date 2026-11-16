package com.miyahna.water

import com.miyahna.water.data.local.DeliveryLocationEntity
import com.miyahna.water.data.local.toDomain
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.domain.DeliveryLocationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryLocationEntityUnitTest {
    @Test
    fun deliveryLocationRoundTripPreservesCoordinatesAndTruckMetadata() {
        val domainLog = DeliveryLocationLog(
            id = "dloc-1710000000-123",
            tripId = "trip-42",
            tripNumber = "TRIP-2026-0042",
            orderId = "ord-99",
            customerId = "cust-101",
            customerName = "سالم الجلال",
            truckId = "truck-1",
            truckPlate = "أ م أ 4521",
            driverId = "drv-12",
            driverName = "ناصر الوادي",
            latitude = 15.495512,
            longitude = 45.367234,
            accuracyMeters = 6.5f,
            altitudeMeters = 1120.4,
            speedKmH = 1.2,
            deliveredLiters = 2000,
            captureSource = "delivery_gps_service",
            notes = "تم التفريغ عند البوابة الشمالية",
            capturedAtIso = "2026-11-15T12:30:00Z",
            capturedAtEpochMs = 1794745800000L,
            isSynced = true
        )

        val entity: DeliveryLocationEntity = domainLog.toEntity()
        assertEquals("delivery_gps_service", entity.captureSource)
        assertEquals(15.495512, entity.latitude, 0.000001)
        assertEquals(45.367234, entity.longitude, 0.000001)
        assertEquals("أ م أ 4521", entity.truckPlate)
        assertEquals(2000, entity.deliveredLiters)

        val restored = entity.toDomain()
        assertEquals(domainLog, restored)
    }

    @Test
    fun deliveryLocationEntityHandlesAdHocDropOffWithoutTrip() {
        val entity = DeliveryLocationEntity(
            id = "dloc-adhoc",
            latitude = 15.4920,
            longitude = 45.3620,
            capturedAtIso = "2026-11-15T13:00:00Z",
            capturedAtEpochMs = 1794747600000L
        )

        val domain = entity.toDomain()
        assertNull(domain.tripId)
        assertNull(domain.customerId)
        assertEquals("truck-1", domain.truckId)
        assertFalse(domain.isSynced)
    }
}

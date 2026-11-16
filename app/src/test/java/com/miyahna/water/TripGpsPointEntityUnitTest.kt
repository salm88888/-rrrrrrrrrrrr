package com.miyahna.water

import com.miyahna.water.data.local.TripGpsPointEntity
import com.miyahna.water.data.local.toDomain
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.domain.TripGPSPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripGpsPointEntityUnitTest {
    @Test
    fun offlineTripGpsPointRoundTripPreservesRouteMetricsAndSyncState() {
        val point = TripGPSPoint(
            id = "pt-trip-99-1794745800000",
            tripId = "trip-99",
            lat = 15.49612,
            lng = 45.36841,
            accuracy = 8.0,
            speedKmH = 42.5,
            timestamp = "2026-11-15T14:10:00Z",
            seq = 7,
            distanceFromPrevKm = 0.64,
            isWeakSignal = false,
            source = "gps_android_fg"
        )

        val entity: TripGpsPointEntity = point.toEntity(
            capturedAtEpochMs = 1794745800000L,
            isSynced = false
        )

        assertEquals("trip-99", entity.tripId)
        assertEquals(7, entity.seq)
        assertEquals(0.64, entity.distanceFromPrevKm, 0.0001)
        assertFalse(entity.isSynced)

        val restored = entity.toDomain()
        assertEquals(point, restored)
    }

    @Test
    fun standbyBackgroundShiftPointUsesBackgroundSourceAndSyncedFlag() {
        val standby = TripGPSPoint(
            id = "pt-bg-1794749999999-101",
            tripId = "driver-bg-2026-11-15",
            lat = 15.4910,
            lng = 45.3610,
            accuracy = 12.0,
            speedKmH = 18.0,
            timestamp = "2026-11-15T15:00:00Z",
            seq = 0,
            distanceFromPrevKm = 0.0,
            isWeakSignal = false,
            source = "gps_android_fg_bg_shift"
        )

        val syncedEntity = standby.toEntity(
            capturedAtEpochMs = 1794749999999L,
            isSynced = true
        )
        assertTrue(syncedEntity.isSynced)
        assertEquals("gps_android_fg_bg_shift", syncedEntity.source)
        assertEquals(standby, syncedEntity.toDomain())
    }
}

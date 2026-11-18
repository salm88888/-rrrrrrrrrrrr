package com.miyahna.water.domain

enum class FleetSortOption(val labelAr: String) {
    STATUS_PRIORITY("الحالة التشغيلية"),
    SPEED_DESC("الأعلى سرعة"),
    WATER_LOAD_DESC("الأعلى حمولة"),
    CODE_ASC("الرمز التصاعدي")
}

data class FleetFilterCriteria(
    val query: String = "",
    val statusFilter: TruckStatus? = null,
    val movingOnly: Boolean = false,
    val activeTripOnly: Boolean = false,
    val sortOption: FleetSortOption = FleetSortOption.STATUS_PRIORITY
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || statusFilter != null || movingOnly || activeTripOnly
}

data class FleetStatusSummary(
    val totalCount: Int = 0,
    val onTripCount: Int = 0,
    val loadingCount: Int = 0,
    val idleCount: Int = 0,
    val maintenanceCount: Int = 0,
    val movingCount: Int = 0,
    val totalCurrentWaterLiters: Int = 0,
    val totalMaxCapacityLiters: Int = 0
)

object FleetFilterEngine {
    fun resolveAllTrucks(
        primaryTruck: Truck,
        fleetTrucks: List<Truck>,
        trips: List<Trip>
    ): List<Truck> {
        val activeTripByTruckId = trips
            .filter { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
            .associateBy { it.truckId }

        val baseList = (listOf(primaryTruck) + fleetTrucks).distinctBy { it.id }
        val knownIds = baseList.mapTo(HashSet()) { it.id }

        val tripSynthesizedTrucks = trips
            .filter { it.truckId.isNotBlank() && it.truckId !in knownIds }
            .distinctBy { it.truckId }
            .map { trip ->
                val lastPt = trip.gpsPoints.lastOrNull()
                val isActiveOrPaused = trip.status == TripStatus.IN_PROGRESS || trip.status == TripStatus.PAUSED
                Truck(
                    id = trip.truckId,
                    code = trip.truckId.uppercase(),
                    plateNumber = trip.truckPlate.ifBlank { "غير محدد" },
                    driverId = trip.driverId,
                    driverName = trip.driverName,
                    status = if (isActiveOrPaused) TruckStatus.ON_TRIP else TruckStatus.IDLE,
                    lastLat = lastPt?.lat ?: trip.startLocation?.lat ?: 15.4698538,
                    lastLng = lastPt?.lng ?: trip.startLocation?.lng ?: 45.3636985,
                    lastSpeed = lastPt?.speedKmH ?: 0.0,
                    lastUpdateTimestamp = lastPt?.timestamp ?: trip.startedAt ?: trip.proposedAt,
                    currentTripId = if (isActiveOrPaused) trip.id else null,
                    currentWaterLiters = trip.remainingLiters
                )
            }

        return (baseList + tripSynthesizedTrucks)
            .distinctBy { it.id }
            .map { t ->
                val liveTrip = activeTripByTruckId[t.id]
                val latestPt = liveTrip?.gpsPoints?.lastOrNull()
                val updated = if (liveTrip != null && latestPt != null && latestPt.timestamp > t.lastUpdateTimestamp) {
                    t.copy(
                        status = TruckStatus.ON_TRIP,
                        currentTripId = liveTrip.id,
                        lastLat = latestPt.lat,
                        lastLng = latestPt.lng,
                        lastSpeed = latestPt.speedKmH,
                        lastUpdateTimestamp = latestPt.timestamp
                    )
                } else {
                    t
                }
                val safeLat = if (updated.lastLat.isFinite()) updated.lastLat else 15.4698538
                val safeLng = if (updated.lastLng.isFinite()) updated.lastLng else 45.3636985
                val safeSpeed = if (updated.lastSpeed.isFinite()) updated.lastSpeed else 0.0
                if (safeLat != updated.lastLat || safeLng != updated.lastLng || safeSpeed != updated.lastSpeed) {
                    updated.copy(lastLat = safeLat, lastLng = safeLng, lastSpeed = safeSpeed)
                } else {
                    updated
                }
            }
    }

    fun computeSummary(trucks: List<Truck>): FleetStatusSummary = FleetStatusSummary(
        totalCount = trucks.size,
        onTripCount = trucks.count { it.status == TruckStatus.ON_TRIP },
        loadingCount = trucks.count { it.status == TruckStatus.LOADING },
        idleCount = trucks.count { it.status == TruckStatus.IDLE },
        maintenanceCount = trucks.count { it.status == TruckStatus.MAINTENANCE },
        movingCount = trucks.count { it.lastSpeed >= 2.0 },
        totalCurrentWaterLiters = trucks.sumOf { it.currentWaterLiters },
        totalMaxCapacityLiters = trucks.sumOf { it.maxCapacityLiters }
    )

    fun filterAndSort(trucks: List<Truck>, criteria: FleetFilterCriteria): List<Truck> {
        val cleanQuery = criteria.query.trim()
        val filtered = trucks.filter { truck ->
            val matchesStatus = criteria.statusFilter == null || truck.status == criteria.statusFilter
            val matchesMoving = !criteria.movingOnly || truck.lastSpeed >= 2.0
            val matchesActiveTrip = !criteria.activeTripOnly || !truck.currentTripId.isNullOrBlank()
            val matchesQuery = cleanQuery.isEmpty() ||
                truck.code.contains(cleanQuery, ignoreCase = true) ||
                truck.plateNumber.contains(cleanQuery, ignoreCase = true) ||
                truck.model.contains(cleanQuery, ignoreCase = true) ||
                truck.driverName.contains(cleanQuery, ignoreCase = true) ||
                truck.driverPhone.contains(cleanQuery, ignoreCase = true)

            matchesStatus && matchesMoving && matchesActiveTrip && matchesQuery
        }

        return when (criteria.sortOption) {
            FleetSortOption.STATUS_PRIORITY -> filtered.sortedWith(
                compareBy<Truck> { statusPriority(it.status) }
                    .thenByDescending { it.lastSpeed }
                    .thenBy { it.code }
            )
            FleetSortOption.SPEED_DESC -> filtered.sortedByDescending { it.lastSpeed }
            FleetSortOption.WATER_LOAD_DESC -> filtered.sortedByDescending { it.currentWaterLiters }
            FleetSortOption.CODE_ASC -> filtered.sortedBy { it.code }
        }
    }

    private fun statusPriority(status: TruckStatus): Int = when (status) {
        TruckStatus.ON_TRIP -> 0
        TruckStatus.LOADING -> 1
        TruckStatus.IDLE -> 2
        TruckStatus.MAINTENANCE -> 3
    }
}

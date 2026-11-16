package com.miyahna.water.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.roundToInt

data class ProfitabilitySummary(
    val totalDeliveredWater: Int,
    val totalRevenue: Double,
    val totalProductionCost: Double,
    val totalDeliveryCost: Double,
    val totalOperatingCost: Double,
    val totalOperatingProfit: Double,
    val overallMarginPercent: Double,
    val totalDeliveredHouses: Int,
    val costPerHouse: Int,
    val costPer1000Liters: Int,
    val costPerLiterDelivered: Double,
    val netProfitPerLiter: Double
)

data class WeeklyFuelBucket(
    val dateKey: String,
    val dayShort: String,
    val dayNumber: Int,
    val fuelLiters: Double,
    val tripsCount: Int,
    val isToday: Boolean
)

data class WeeklyFuelTripRow(
    val trip: Trip,
    val dateKey: String,
    val distanceKm: Double,
    val fuelLiters: Double,
    val ratePerKm: Double,
    val variancePercent: Int
)

enum class FuelEfficiencyStatus(val labelAr: String) {
    EFFICIENT("كفاءة عالية"),
    NORMAL("ضمن المعدل"),
    HIGH("أعلى من المرجعي"),
    EMPTY("بانتظار الإنجاز")
}

data class WeeklyFuelReport(
    val tripRows: List<WeeklyFuelTripRow>,
    val totalFuelLiters: Double,
    val totalDistanceKm: Double,
    val avgFuelPerTrip: Double,
    val avgDistancePerTrip: Double,
    val avgLitersPerKm: Double,
    val referenceRatePerKm: Double,
    val estimatedFuelCost: Int,
    val efficiencyStatus: FuelEfficiencyStatus,
    val dailyBuckets: List<WeeklyFuelBucket>,
    val maxDailyFuel: Double,
    val rangeLabel: String
)

object UnitEconomicsEngine {
    private fun Double.safeRoundToInt(): Int = if (this.isFinite()) this.roundToInt() else 0

    private fun Double.finiteOrZero(): Double = if (this.isFinite()) this else 0.0

    private val COMPLETED_TRIP_STATUSES = setOf(
        TripStatus.RETURNED_TO_PLANT,
        TripStatus.PENDING_SYNC,
        TripStatus.SYNCED,
        TripStatus.COMPLETED_PENDING_REVIEW,
        TripStatus.CLOSED
    )

    fun calculateProfitabilitySummary(
        trips: List<Trip>,
        invoices: List<Invoice>,
        settings: SystemSettings
    ): ProfitabilitySummary {
        val totalDeliveredWater = trips.sumOf { it.deliveredLiters.coerceAtLeast(0) }
        val totalRevenue = invoices.sumOf { it.totalAmount.finiteOrZero() }
        val prodPerLiter = settings.waterProductionCostPerLiter.finiteOrZero()
        val totalProductionCost = totalDeliveredWater * prodPerLiter
        val totalTripsCost = trips.sumOf { (it.actualCost ?: it.totalCost).finiteOrZero() }
        val totalDeliveryCost = max(0.0, totalTripsCost - totalProductionCost)
        val totalOperatingCost = totalProductionCost + totalDeliveryCost
        val totalOperatingProfit = totalRevenue - totalOperatingCost
        val overallMargin = if (totalRevenue > 0.0) {
            ((totalOperatingProfit / totalRevenue) * 1000.0).safeRoundToInt() / 10.0
        } else 0.0

        val totalHouses = trips.sumOf { trip ->
            trip.customerStops.count { it.status == StopStatus.DELIVERED }
        }
        val costPerHouse = if (totalHouses > 0) (totalDeliveryCost / totalHouses).safeRoundToInt() else 0
        val costPer1000L = if (totalDeliveredWater > 0) {
            (totalDeliveryCost / (totalDeliveredWater / 1000.0)).safeRoundToInt()
        } else 0
        val costPerLiter = if (totalDeliveredWater > 0) {
            ((totalOperatingCost / totalDeliveredWater) * 100.0).safeRoundToInt() / 100.0
        } else 0.0
        val sellPerLiter = settings.defaultSellingPricePerLiter.finiteOrZero()
        val netProfitPerLiter = if (totalDeliveredWater > 0) {
            (((sellPerLiter - (totalOperatingCost / totalDeliveredWater)) * 100.0).safeRoundToInt() / 100.0)
        } else 0.0

        return ProfitabilitySummary(
            totalDeliveredWater = totalDeliveredWater,
            totalRevenue = totalRevenue,
            totalProductionCost = totalProductionCost,
            totalDeliveryCost = totalDeliveryCost,
            totalOperatingCost = totalOperatingCost,
            totalOperatingProfit = totalOperatingProfit,
            overallMarginPercent = overallMargin,
            totalDeliveredHouses = totalHouses,
            costPerHouse = costPerHouse,
            costPer1000Liters = costPer1000L,
            costPerLiterDelivered = costPerLiter,
            netProfitPerLiter = netProfitPerLiter
        )
    }

    fun buildDriverWeeklyFuelReport(
        trips: List<Trip>,
        settings: SystemSettings,
        driverId: String? = null,
        driverName: String? = null
    ): WeeklyFuelReport {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        val weekEnd = weekStart.plusDays(6)

        val refRate = if (settings.truckDieselConsumptionPerKm.isFinite() && settings.truckDieselConsumptionPerKm > 0.0) {
            settings.truckDieselConsumptionPerKm
        } else 0.28
        val dieselPrice = max(0.0, settings.dieselPricePerLiter.finiteOrZero())
        val cleanDriverName = driverName?.trim().orEmpty()

        fun tripLocalDate(trip: Trip): LocalDate? {
            val candidate = trip.returnedToPlantAt ?: trip.completedAt ?: trip.startedAt ?: trip.proposedAt
            val parsedInstant = runCatching { Instant.parse(candidate) }.getOrNull()
            if (parsedInstant != null) {
                return parsedInstant.atZone(zone).toLocalDate()
            }
            return runCatching { LocalDate.parse(trip.date) }.getOrNull()
        }

        val weeklyCompleted = trips.filter { trip ->
            if (trip.status !in COMPLETED_TRIP_STATUSES) return@filter false
            val belongs = (driverId.isNullOrBlank() && cleanDriverName.isEmpty()) ||
                (!driverId.isNullOrBlank() && trip.driverId == driverId) ||
                (cleanDriverName.isNotEmpty() && trip.driverName.trim() == cleanDriverName)
            if (!belongs) return@filter false
            val d = tripLocalDate(trip) ?: return@filter false
            !d.isBefore(weekStart) && !d.isAfter(weekEnd)
        }.sortedByDescending {
            it.returnedToPlantAt ?: it.completedAt ?: it.startedAt ?: it.proposedAt
        }

        val rows = weeklyCompleted.map { trip ->
            val rawDist = trip.actualDistanceKm?.takeIf { it.isFinite() && it > 0.0 }
                ?: trip.estimatedDistanceKm.takeIf { it.isFinite() && it > 0.0 }
                ?: 0.0
            val fuelLiters = trip.actualFuelLiters?.takeIf { it.isFinite() && it > 0.0 }
                ?: trip.estimatedFuelLiters.takeIf { it.isFinite() && it > 0.0 }
                ?: (((rawDist * refRate) * 100.0).safeRoundToInt() / 100.0)
            val baseline = if (trip.estimatedFuelLiters.isFinite() && trip.estimatedFuelLiters > 0.0) {
                trip.estimatedFuelLiters
            } else {
                (((rawDist * refRate) * 100.0).safeRoundToInt() / 100.0)
            }
            val ratePerKm = if (rawDist > 0.0) {
                (((fuelLiters / rawDist) * 100.0).safeRoundToInt() / 100.0)
            } else refRate
            val variancePct = if (baseline > 0.0) {
                (((fuelLiters - baseline) / baseline) * 100.0).safeRoundToInt()
            } else 0
            val dKey = tripLocalDate(trip)?.toString() ?: trip.date
            WeeklyFuelTripRow(
                trip = trip,
                dateKey = dKey,
                distanceKm = (rawDist * 10.0).safeRoundToInt() / 10.0,
                fuelLiters = (fuelLiters * 100.0).safeRoundToInt() / 100.0,
                ratePerKm = ratePerKm,
                variancePercent = variancePct
            )
        }

        val totalFuel = (rows.sumOf { it.fuelLiters } * 10.0).safeRoundToInt() / 10.0
        val totalDistance = (rows.sumOf { it.distanceKm } * 10.0).safeRoundToInt() / 10.0
        val avgFuel = if (rows.isNotEmpty()) ((totalFuel / rows.size) * 10.0).safeRoundToInt() / 10.0 else 0.0
        val avgDist = if (rows.isNotEmpty()) ((totalDistance / rows.size) * 10.0).safeRoundToInt() / 10.0 else 0.0
        val avgLPerKm = if (totalDistance > 0.0) {
            ((totalFuel / totalDistance) * 100.0).safeRoundToInt() / 100.0
        } else refRate
        val estCost = (totalFuel * dieselPrice).safeRoundToInt()

        val effStatus = when {
            rows.isEmpty() -> FuelEfficiencyStatus.EMPTY
            avgLPerKm <= refRate * 0.95 -> FuelEfficiencyStatus.EFFICIENT
            avgLPerKm <= refRate * 1.10 -> FuelEfficiencyStatus.NORMAL
            else -> FuelEfficiencyStatus.HIGH
        }

        val dayLabels = listOf("س", "ح", "ن", "ث", "ر", "خ", "ج") // Sat -> Fri
        val buckets = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            val key = date.toString()
            val dayRows = rows.filter { it.dateKey == key }
            val dayFuel = (dayRows.sumOf { it.fuelLiters } * 10.0).safeRoundToInt() / 10.0
            WeeklyFuelBucket(
                dateKey = key,
                dayShort = dayLabels[offset.toInt()],
                dayNumber = date.dayOfMonth,
                fuelLiters = dayFuel,
                tripsCount = dayRows.size,
                isToday = date == today
            )
        }

        val maxDaily = max(1.0, buckets.maxOfOrNull { it.fuelLiters } ?: 1.0)
        val rangeLabel = "${weekStart.dayOfMonth}/${weekStart.monthValue} – ${weekEnd.dayOfMonth}/${weekEnd.monthValue}"

        return WeeklyFuelReport(
            tripRows = rows,
            totalFuelLiters = totalFuel,
            totalDistanceKm = totalDistance,
            avgFuelPerTrip = avgFuel,
            avgDistancePerTrip = avgDist,
            avgLitersPerKm = avgLPerKm,
            referenceRatePerKm = refRate,
            estimatedFuelCost = estCost,
            efficiencyStatus = effStatus,
            dailyBuckets = buckets,
            maxDailyFuel = maxDaily,
            rangeLabel = rangeLabel
        )
    }
}

package com.miyahna.water.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object ClusteringEngine {
    const val MARIB_ROAD_FACTOR = 1.35

    private fun Double.safeRoundToInt(): Int = if (this.isFinite()) this.roundToInt() else 0

    fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return 0.0
        val earthRadiusKm = 6371.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2) * sin(dLon / 2)
        val clampedA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(clampedA), sqrt(1.0 - clampedA))
        return if (c.isFinite()) earthRadiusKm * c else 0.0
    }

    fun estimateRoadDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val straight = haversineDistanceKm(lat1, lon1, lat2, lon2)
        return ((straight * MARIB_ROAD_FACTOR) * 100.0).safeRoundToInt() / 100.0
    }

    data class RouteOptimizationResult(
        val sequence: List<Order>,
        val totalDistanceKm: Double
    )

    fun optimizeStopSequence(
        plantLat: Double,
        plantLng: Double,
        orders: List<Order>
    ): RouteOptimizationResult {
        if (orders.isEmpty()) return RouteOptimizationResult(emptyList(), 0.0)
        if (orders.size == 1) {
            val d = estimateRoadDistanceKm(plantLat, plantLng, orders[0].lat, orders[0].lng) * 2.0
            return RouteOptimizationResult(orders, (d * 10.0).safeRoundToInt() / 10.0)
        }

        val unvisited = orders.toMutableList()
        val route = mutableListOf<Order>()
        var currentLat = plantLat
        var currentLng = plantLng
        var totalDist = 0.0

        while (unvisited.isNotEmpty()) {
            var nearestIdx = 0
            var minDist = Double.POSITIVE_INFINITY
            for (i in unvisited.indices) {
                val dist = estimateRoadDistanceKm(currentLat, currentLng, unvisited[i].lat, unvisited[i].lng)
                if (dist < minDist) {
                    minDist = dist
                    nearestIdx = i
                }
            }
            val next = unvisited.removeAt(nearestIdx)
            route.add(next)
            totalDist += minDist
            currentLat = next.lat
            currentLng = next.lng
        }

        totalDist += estimateRoadDistanceKm(currentLat, currentLng, plantLat, plantLng)
        return RouteOptimizationResult(route, (totalDist * 10.0).safeRoundToInt() / 10.0)
    }

    fun generateSmartClusters(
        orders: List<Order>,
        settings: SystemSettings,
        maxTruckCapacity: Int = 6000
    ): List<ClusterProposal> {
        val safeCapacity = maxTruckCapacity.coerceAtLeast(1000)
        val candidates = orders.filter {
            it.status == OrderStatus.NEW ||
                it.status == OrderStatus.CONFIRMED ||
                it.status == OrderStatus.WAITING_CLUSTERING
        }
        if (candidates.isEmpty()) return emptyList()

        val remaining = candidates.toMutableList()
        val proposals = mutableListOf<ClusterProposal>()
        var clusterCounter = 1

        while (remaining.isNotEmpty()) {
            val urgentIdx = remaining.indexOfFirst { it.isUrgent }
            val seedIdx = if (urgentIdx >= 0) urgentIdx else 0
            val seed = remaining.removeAt(seedIdx)
            val batch = mutableListOf(seed)
            var currentLiters = seed.requestedLiters

            while (remaining.isNotEmpty()) {
                var bestIdx = -1
                var minDistance = Double.POSITIVE_INFINITY
                val lastOrder = batch.last()

                for (i in remaining.indices) {
                    val cand = remaining[i]
                    if (currentLiters + cand.requestedLiters <= safeCapacity) {
                        val dist = estimateRoadDistanceKm(lastOrder.lat, lastOrder.lng, cand.lat, cand.lng)
                        if (dist < minDistance) {
                            minDistance = dist
                            bestIdx = i
                        }
                    }
                }

                if (bestIdx != -1 && minDistance <= 6.0) {
                    val matched = remaining.removeAt(bestIdx)
                    batch.add(matched)
                    currentLiters += matched.requestedLiters
                } else {
                    break
                }
            }

            proposals.add(evaluateCluster(batch, "cluster-prop-${clusterCounter++}", settings, safeCapacity))
        }

        val priorityOrder = mapOf(
            DepartureRecommendation.URGENT_OVERRIDE to 1,
            DepartureRecommendation.GO_NOW to 2,
            DepartureRecommendation.FEASIBLE_PARTIAL to 3,
            DepartureRecommendation.WAIT_MORE to 4
        )
        return proposals.sortedBy { priorityOrder[it.recommendation] ?: 99 }
    }

    fun evaluateCluster(
        clusterOrders: List<Order>,
        clusterId: String,
        settings: SystemSettings,
        maxTruckCapacity: Int = 6000
    ): ClusterProposal {
        val safeCapacity = maxTruckCapacity.coerceAtLeast(1)
        val opt = optimizeStopSequence(settings.plantLocation.lat, settings.plantLocation.lng, clusterOrders)
        val totalLiters = clusterOrders.sumOf { it.requestedLiters }
        val capacityUtilization = ((totalLiters.toDouble() / safeCapacity) * 1000.0).safeRoundToInt() / 10.0

        val estimatedFuelLiters = ((opt.totalDistanceKm * settings.truckDieselConsumptionPerKm) * 100.0).safeRoundToInt() / 100.0
        val fuelCost = (estimatedFuelLiters * settings.dieselPricePerLiter).safeRoundToInt().toDouble()
        val productionCost = (totalLiters * settings.waterProductionCostPerLiter).safeRoundToInt().toDouble()
        val driverWageShare = settings.driverWagePerTrip
        val maintenanceShare = settings.maintenanceAllowancePerTrip
        val totalCost = fuelCost + productionCost + driverWageShare + maintenanceShare

        val expectedRevenue = clusterOrders.sumOf { it.totalAmount }.let { if (it.isFinite()) it else 0.0 }
        val expectedProfit = expectedRevenue - totalCost
        val profitMarginPercent = if (expectedRevenue > 0.0 && expectedProfit.isFinite()) {
            (((expectedProfit / expectedRevenue) * 1000.0).safeRoundToInt() / 10.0)
        } else 0.0

        val hasUrgent = clusterOrders.any { it.isUrgent }
        val districtsCovered = clusterOrders.map { it.district }.distinct()

        val (recommendation, reason) = when {
            hasUrgent && capacityUtilization < 60.0 -> {
                DepartureRecommendation.URGENT_OVERRIDE to
                    "يحتوي على طلب عاجل جداً يتطلب استثناءً واعتماداً للخروج رغم انخفاض حمولة الوايت"
            }
            totalLiters >= 4500 && expectedProfit > 15000.0 -> {
                DepartureRecommendation.GO_NOW to
                    "حمولة مثالية ($capacityUtilization%) وربحية تشغيلية مرتفعة (${expectedProfit.safeRoundToInt()} ر.ي) في ${districtsCovered.joinToString(" و ")}"
            }
            totalLiters >= 3000 && profitMarginPercent >= 35.0 -> {
                DepartureRecommendation.FEASIBLE_PARTIAL to
                    "رحلة جزئية مجدية اقتصادياً بهامش ربح $profitMarginPercent%، يمكن الخروج الآن أو الانتظار لضم طلب إضافي"
            }
            else -> {
                DepartureRecommendation.WAIT_MORE to
                    "الحمولة منخفضة ($totalLiters لتر / $capacityUtilization%)، يُنصح بالانتظار لتجميع طلبات إضافية لخفض تكلفة النقل"
            }
        }

        val stops = opt.sequence.mapIndexed { idx, order ->
            TripCustomerStop(
                stopSequence = idx + 1,
                orderId = order.id,
                customerId = order.customerId,
                customerName = order.customerName,
                phone = order.phone,
                district = order.district,
                addressDetails = order.addressDetails,
                lat = order.lat,
                lng = order.lng,
                tankCapacity = order.tankCapacity,
                requestedLiters = order.requestedLiters,
                deliveredLiters = 0,
                pricePerLiter = order.pricePerLiter,
                totalAmount = order.totalAmount,
                paidAmount = 0.0,
                remainingAmount = order.totalAmount,
                status = StopStatus.PENDING
            )
        }

        return ClusterProposal(
            id = clusterId,
            orders = opt.sequence,
            totalLiters = totalLiters,
            capacityUtilization = capacityUtilization,
            customerStops = stops,
            estimatedDistanceKm = opt.totalDistanceKm,
            estimatedFuelLiters = estimatedFuelLiters,
            fuelCost = fuelCost,
            productionCost = productionCost,
            driverWageShare = driverWageShare,
            maintenanceShare = maintenanceShare,
            totalCost = totalCost,
            expectedRevenue = expectedRevenue,
            expectedProfit = expectedProfit,
            profitMarginPercent = profitMarginPercent,
            recommendation = recommendation,
            reason = reason,
            districtsCovered = districtsCovered
        )
    }
}

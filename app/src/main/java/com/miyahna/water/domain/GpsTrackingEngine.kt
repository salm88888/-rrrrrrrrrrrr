package com.miyahna.water.domain

import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GeofenceCheck(
    val inside: Boolean,
    val distanceMeters: Int
)

data class PlantDepartureEvaluation(
    val distanceFromPlantMeters: Int,
    val geofenceRadius: Int,
    val triggerDistanceMeters: Int,
    val maxAllowedAccuracy: Double,
    val isReliableAccuracy: Boolean,
    val insidePlant: Boolean,
    val outsideTriggerZone: Boolean,
    val highConfidenceFastDeparture: Boolean
)

data class TrackAnalysisResult(
    val points: List<TripGPSPoint>,
    val detectedStops: List<DetectedStop>,
    val gpsGaps: List<GPSGap>,
    val totalDistanceKm: Double,
    val movingMinutes: Double,
    val stoppedMinutes: Double
)

data class PlanVsActualSummary(
    val plannedStopsCount: Int,
    val deliveredStopsCount: Int,
    val unvisitedPlannedStops: List<TripCustomerStop>,
    val stopsWithLinkedGpsVisitCount: Int,
    val unplannedStops: List<DetectedStop>,
    val totalDetectedStopsCount: Int,
    val totalStoppedMinutes: Double,
    val totalMovingMinutes: Double,
    val plannedDistanceKm: Double,
    val actualDistanceKm: Double,
    val plannedLiters: Int,
    val deliveredLiters: Int,
    val gpsPointsCount: Int,
    val gpsGapsCount: Int
)

data class TripCompletionReadiness(
    val qualified: Boolean,
    val hasDeparted: Boolean,
    val hasConfirmedReturn: Boolean,
    val visitedCustomerStop: Boolean,
    val maxDistanceFromOriginMeters: Int,
    val filteredDistanceKm: Double,
    val elapsedSeconds: Long,
    val departureThresholdMeters: Int,
    val geofenceRadius: Int,
    val currentDistanceFromPlantMeters: Int = 0,
    val reason: String
)

object GpsTrackingEngine {
    private fun Double.safeRoundToInt(): Int = if (this.isFinite()) this.roundToInt() else 0

    fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        ClusteringEngine.haversineDistanceKm(lat1, lon1, lat2, lon2)

    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        haversineDistanceKm(lat1, lon1, lat2, lon2) * 1000.0

    fun isWithinPlantGeofence(lat: Double, lng: Double, settings: SystemSettings): GeofenceCheck {
        val distanceMeters = haversineDistanceMeters(
            lat, lng, settings.plantLocation.lat, settings.plantLocation.lng
        ).safeRoundToInt()
        val radius = max(60, settings.plantGeofenceMeters)
        return GeofenceCheck(inside = distanceMeters <= radius, distanceMeters = distanceMeters)
    }

    fun evaluatePlantDepartureTrigger(
        lat: Double,
        lng: Double,
        accuracy: Double?,
        speedKmH: Double?,
        settings: SystemSettings
    ): PlantDepartureEvaluation {
        val distanceFromPlantMeters = haversineDistanceMeters(
            lat, lng, settings.plantLocation.lat, settings.plantLocation.lng
        ).safeRoundToInt()
        val geofenceRadius = max(60, settings.plantGeofenceMeters)
        val triggerDistanceMeters = max(geofenceRadius + 35, 150)
        val maxAllowedAccuracy = max(settings.gpsWeakAccuracyMeters.toDouble(), 80.0)
        val isReliableAccuracy = accuracy == null || !accuracy.isFinite() || accuracy <= maxAllowedAccuracy
        val insidePlant = distanceFromPlantMeters <= geofenceRadius
        val outsideTriggerZone = isReliableAccuracy && distanceFromPlantMeters >= triggerDistanceMeters
        val minMoveSpeed = settings.minMovementSpeedKmH
        val highConfidenceFastDeparture = outsideTriggerZone &&
            speedKmH != null &&
            speedKmH >= minMoveSpeed &&
            distanceFromPlantMeters >= triggerDistanceMeters + 50

        return PlantDepartureEvaluation(
            distanceFromPlantMeters = distanceFromPlantMeters,
            geofenceRadius = geofenceRadius,
            triggerDistanceMeters = triggerDistanceMeters,
            maxAllowedAccuracy = maxAllowedAccuracy,
            isReliableAccuracy = isReliableAccuracy,
            insidePlant = insidePlant,
            outsideTriggerZone = outsideTriggerZone,
            highConfidenceFastDeparture = highConfidenceFastDeparture
        )
    }

    private fun parseEpochMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    fun analyzeGpsTrack(
        tripId: String,
        rawPoints: List<TripGPSPoint>,
        approvedStops: List<TripCustomerStop>,
        settings: SystemSettings
    ): TrackAnalysisResult {
        val stopThresholdSec = max(30, settings.stopDetectionMinutes * 60)
        val minMoveSpeed = settings.minMovementSpeedKmH
        val stopRadiusMeters = settings.stopRadiusMeters.toDouble()
        val weakAccuracyMeters = settings.gpsWeakAccuracyMeters.toDouble()
        val gpsGapSeconds = settings.gpsGapSeconds.toDouble()
        val stationaryJitterMeters = 35.0

        val uniqueSorted = rawPoints
            .filter { it.lat.isFinite() && it.lng.isFinite() }
            .distinctBy { it.id }
            .sortedBy { parseEpochMillis(it.timestamp) }

        if (uniqueSorted.isEmpty()) {
            return TrackAnalysisResult(
                points = emptyList(),
                detectedStops = emptyList(),
                gpsGaps = emptyList(),
                totalDistanceKm = 0.0,
                movingMinutes = 0.0,
                stoppedMinutes = 0.0
            )
        }

        var totalDistanceKm = 0.0
        var movingSeconds = 0.0
        var stoppedSeconds = 0.0
        val gpsGaps = mutableListOf<GPSGap>()
        val enriched = mutableListOf<TripGPSPoint>()

        for (i in uniqueSorted.indices) {
            val raw = uniqueSorted[i]
            val isWeak = raw.accuracy != null && raw.accuracy > weakAccuracyMeters
            if (i == 0) {
                enriched.add(
                    raw.copy(
                        tripId = tripId,
                        seq = 1,
                        distanceFromPrevKm = 0.0,
                        isWeakSignal = isWeak
                    )
                )
                continue
            }

            val prev = enriched[i - 1]
            val dtSec = max(0.0, (parseEpochMillis(raw.timestamp) - parseEpochMillis(prev.timestamp)) / 1000.0)
            val segKm = haversineDistanceKm(prev.lat, prev.lng, raw.lat, raw.lng)
            val segMeters = segKm * 1000.0
            val derivedSpeedKmH = if (dtSec > 0.0) (segKm / dtSec) * 3600.0 else 0.0

            val speed = if (raw.speedKmH >= 0.0 && raw.speedKmH.isFinite()) {
                raw.speedKmH
            } else {
                ((min(130.0, derivedSpeedKmH) * 10.0).safeRoundToInt() / 10.0)
            }

            val isTeleportSpike = dtSec in 0.01..59.99 && derivedSpeedKmH > 160.0
            val isWeakSignalDrift = isWeak &&
                segMeters <= max(raw.accuracy ?: 0.0, 120.0) &&
                speed < minMoveSpeed
            val isStationaryJitter = segMeters < 18.0 && speed < 2.0

            val validDistKm = if (isTeleportSpike || isWeakSignalDrift || isStationaryJitter) {
                0.0
            } else {
                totalDistanceKm += segKm
                (segKm * 10000.0).safeRoundToInt() / 10000.0
            }

            if (dtSec > gpsGapSeconds) {
                gpsGaps.add(
                    GPSGap(
                        id = "gap-${prev.id}-${raw.id}",
                        tripId = tripId,
                        startTime = prev.timestamp,
                        endTime = raw.timestamp,
                        durationSeconds = dtSec.safeRoundToInt(),
                        reason = if (isWeak || prev.isWeakSignal) "weak_signal" else "gps_paused",
                        fromLat = prev.lat,
                        fromLng = prev.lng,
                        toLat = raw.lat,
                        toLng = raw.lng
                    )
                )
            }

            if (speed >= minMoveSpeed && segMeters > 10.0) {
                movingSeconds += min(dtSec, gpsGapSeconds)
            } else {
                stoppedSeconds += min(dtSec, gpsGapSeconds)
            }

            enriched.add(
                raw.copy(
                    tripId = tripId,
                    seq = i + 1,
                    speedKmH = speed,
                    distanceFromPrevKm = validDistKm,
                    isWeakSignal = isWeak
                )
            )
        }

        val detectedStops = mutableListOf<DetectedStop>()
        var cluster = mutableListOf<TripGPSPoint>()

        fun finalizeCluster(pts: List<TripGPSPoint>, isStillOpen: Boolean) {
            if (pts.isEmpty()) return
            val first = pts.first()
            val last = pts.last()
            val durationSec = max(0.0, (parseEpochMillis(last.timestamp) - parseEpochMillis(first.timestamp)) / 1000.0)
            if (durationSec < stopThresholdSec) return

            val avgLat = pts.sumOf { it.lat } / pts.size
            val avgLng = pts.sumOf { it.lng } / pts.size

            var nearCustomerId: String? = null
            var nearCustomerName: String? = null
            var minCustomerDist = Double.POSITIVE_INFINITY

            for (stop in approvedStops) {
                val distMeters = haversineDistanceMeters(avgLat, avgLng, stop.lat, stop.lng)
                if (distMeters <= stopRadiusMeters && distMeters < minCustomerDist) {
                    minCustomerDist = distMeters
                    nearCustomerId = stop.customerId
                    nearCustomerName = stop.customerName
                }
            }

            val plantDist = haversineDistanceMeters(
                avgLat, avgLng, settings.plantLocation.lat, settings.plantLocation.lng
            )
            val isNearPlant = plantDist <= max(60, settings.plantGeofenceMeters)

            detectedStops.add(
                DetectedStop(
                    id = "stop-${first.id}",
                    tripId = tripId,
                    lat = (avgLat * 1_000_000.0).safeRoundToInt() / 1_000_000.0,
                    lng = (avgLng * 1_000_000.0).safeRoundToInt() / 1_000_000.0,
                    startTime = first.timestamp,
                    endTime = last.timestamp,
                    durationMinutes = ((durationSec / 60.0) * 10.0).safeRoundToInt() / 10.0,
                    isActive = isStillOpen,
                    isProlongedStop = true,
                    nearCustomerId = nearCustomerId,
                    nearCustomerName = nearCustomerName,
                    distanceToCustomerMeters = nearCustomerId?.let { minCustomerDist.safeRoundToInt() },
                    isUnplannedStop = nearCustomerId == null && !isNearPlant
                )
            )
        }

        for (pt in enriched) {
            val isLowSpeed = pt.speedKmH < minMoveSpeed
            if (!isLowSpeed) {
                finalizeCluster(cluster, isStillOpen = false)
                cluster = mutableListOf()
                continue
            }
            if (cluster.isEmpty()) {
                cluster.add(pt)
            } else {
                val anchor = cluster.first()
                val dist = haversineDistanceMeters(anchor.lat, anchor.lng, pt.lat, pt.lng)
                if (dist <= stationaryJitterMeters) {
                    cluster.add(pt)
                } else {
                    finalizeCluster(cluster, isStillOpen = false)
                    cluster = mutableListOf(pt)
                }
            }
        }
        if (cluster.isNotEmpty()) {
            finalizeCluster(cluster, isStillOpen = true)
        }

        return TrackAnalysisResult(
            points = enriched,
            detectedStops = detectedStops,
            gpsGaps = gpsGaps,
            totalDistanceKm = (totalDistanceKm * 100.0).safeRoundToInt() / 100.0,
            movingMinutes = ((movingSeconds / 60.0) * 10.0).safeRoundToInt() / 10.0,
            stoppedMinutes = ((stoppedSeconds / 60.0) * 10.0).safeRoundToInt() / 10.0
        )
    }

    fun buildPlanVsActualSummary(trip: Trip): PlanVsActualSummary {
        val stops = trip.customerStops
        val detected = trip.detectedStops
        val deliveredCount = stops.count { it.status == StopStatus.DELIVERED }
        val unvisited = stops.filter { it.status != StopStatus.DELIVERED }
        val visitedIds = detected.mapNotNull { it.nearCustomerId }.toSet()
        val linkedVisits = stops.count { it.customerId in visitedIds }
        val unplanned = detected.filter { it.isUnplannedStop }
        val totalStopped = (detected.sumOf { it.durationMinutes } * 10.0).safeRoundToInt() / 10.0

        return PlanVsActualSummary(
            plannedStopsCount = stops.size,
            deliveredStopsCount = deliveredCount,
            unvisitedPlannedStops = unvisited,
            stopsWithLinkedGpsVisitCount = linkedVisits,
            unplannedStops = unplanned,
            totalDetectedStopsCount = detected.size,
            totalStoppedMinutes = totalStopped,
            totalMovingMinutes = trip.movingMinutes,
            plannedDistanceKm = trip.estimatedDistanceKm,
            actualDistanceKm = trip.actualDistanceKm ?: 0.0,
            plannedLiters = trip.initialLoadLiters,
            deliveredLiters = trip.deliveredLiters,
            gpsPointsCount = trip.gpsPoints.size,
            gpsGapsCount = trip.gpsGaps.size
        )
    }

    fun evaluateTripCompletionReadiness(
        trip: Trip,
        settings: SystemSettings,
        points: List<TripGPSPoint> = trip.gpsPoints,
        candidateReturnLocation: Pair<Double, Double>? = null,
        candidateReturnAccuracy: Double? = null
    ): TripCompletionReadiness {
        val geofenceRadius = max(60, settings.plantGeofenceMeters)
        val departureThresholdMeters = geofenceRadius
        val stopVisitRadiusMeters = max(settings.stopRadiusMeters, 100)
        val minNearbyCustomerOriginMeters = max(geofenceRadius / 2, 120)
        val maxAllowedAccuracy = max(settings.gpsWeakAccuracyMeters.toDouble(), 80.0)

        val analysis = analyzeGpsTrack(trip.id, points, trip.customerStops, settings)
        val analyzedPoints = analysis.points
        val reliablePoints = analyzedPoints.filter { it.accuracy == null || it.accuracy <= maxAllowedAccuracy }

        val originLat = trip.startLocation?.lat ?: settings.plantLocation.lat
        val originLng = trip.startLocation?.lng ?: settings.plantLocation.lng

        val startMs = listOfNotNull(
            parseEpochMillis(trip.startedAt).takeIf { it > 0L },
            analyzedPoints.firstOrNull()?.let { parseEpochMillis(it.timestamp) }?.takeIf { it > 0L }
        ).minOrNull() ?: System.currentTimeMillis()
        val latestMs = analyzedPoints.lastOrNull()?.let { parseEpochMillis(it.timestamp) }?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val elapsedSeconds = max(0L, (latestMs - startMs) / 1000L)

        var maxDistanceFromPlant = 0.0
        var maxDistanceFromOrigin = 0.0
        var consecutiveOutside = 0
        var departureConfirmIdx = -1
        var consecutiveCustomerHits = 0
        var visitedCustomerStop = trip.detectedStops.any { it.nearCustomerId != null }

        for (i in reliablePoints.indices) {
            val pt = reliablePoints[i]
            val distOrigin = haversineDistanceMeters(pt.lat, pt.lng, originLat, originLng)
            val distPlant = haversineDistanceMeters(pt.lat, pt.lng, settings.plantLocation.lat, settings.plantLocation.lng)
            if (distOrigin > maxDistanceFromOrigin) maxDistanceFromOrigin = distOrigin
            if (distPlant > maxDistanceFromPlant) maxDistanceFromPlant = distPlant

            val outside = distPlant > geofenceRadius
            if (outside) {
                consecutiveOutside++
                if ((consecutiveOutside >= 2 || distPlant >= geofenceRadius + 50.0) && departureConfirmIdx == -1) {
                    departureConfirmIdx = i
                }
            } else {
                consecutiveOutside = 0
            }

            val nearCustomer = distOrigin >= minNearbyCustomerOriginMeters && trip.customerStops.any { stop ->
                haversineDistanceMeters(pt.lat, pt.lng, stop.lat, stop.lng) <= stopVisitRadiusMeters
            }
            if (nearCustomer) {
                consecutiveCustomerHits++
                if (consecutiveCustomerHits >= 2) {
                    visitedCustomerStop = true
                    if (departureConfirmIdx == -1) departureConfirmIdx = i
                }
            } else {
                consecutiveCustomerHits = 0
            }
        }

        val hasDeparted = departureConfirmIdx != -1
        val trailingCandidate = candidateReturnLocation
            ?.takeIf { candidateReturnAccuracy == null || candidateReturnAccuracy <= maxAllowedAccuracy }
            ?.let { (cLat, cLng) ->
                val last = reliablePoints.lastOrNull()
                val alreadyIncluded = last != null &&
                    haversineDistanceMeters(last.lat, last.lng, cLat, cLng) < 1.0
                if (alreadyIncluded) null else cLat to cLng
            }

        val postDeparture = buildList {
            if (hasDeparted) {
                reliablePoints.drop(departureConfirmIdx + 1).forEach { pt ->
                    add(Triple(pt.lat, pt.lng, pt.speedKmH))
                }
                trailingCandidate?.let { (cLat, cLng) ->
                    add(Triple(cLat, cLng, 0.0))
                }
            }
        }

        fun distanceToPlant(lat: Double, lng: Double): Double =
            haversineDistanceMeters(lat, lng, settings.plantLocation.lat, settings.plantLocation.lng)

        val latestPoint = postDeparture.lastOrNull()
            ?: reliablePoints.lastOrNull()?.let { Triple(it.lat, it.lng, it.speedKmH) }
            ?: trailingCandidate?.let { Triple(it.first, it.second, 0.0) }
        val currentDistanceFromPlantMeters = latestPoint?.let { (lat, lng, _) ->
            distanceToPlant(lat, lng).safeRoundToInt()
        } ?: Int.MAX_VALUE

        val latestPost = postDeparture.lastOrNull()
        val prevPost = postDeparture.dropLast(1).lastOrNull()
        val hasConfirmedReturn = if (latestPost != null) {
            val latestDist = distanceToPlant(latestPost.first, latestPost.second)
            val prevInside = prevPost != null && distanceToPlant(prevPost.first, prevPost.second) <= geofenceRadius
            latestDist <= geofenceRadius && (
                prevInside ||
                    latestDist <= (geofenceRadius * 0.8) ||
                    latestPost.third < settings.minMovementSpeedKmH
                )
        } else {
            false
        }

        val filteredDistKm = analysis.totalDistanceKm
        val minDistRequired = 0.25
        val minDurationRequired = 20L

        val hasSufficientTravel = (visitedCustomerStop && filteredDistKm >= minDistRequired) ||
            (maxDistanceFromPlant > geofenceRadius && filteredDistKm >= minDistRequired)
        val hasMinDuration = elapsedSeconds >= minDurationRequired
        val qualified = hasDeparted && hasSufficientTravel && hasMinDuration && hasConfirmedReturn

        val maxObservedPlantMeters = max(maxDistanceFromOrigin, maxDistanceFromPlant).safeRoundToInt()
        val reason = when {
            !hasDeparted ->
                "لم يبتعد الوايت عن محيط المصنع بعد (أقصى مسافة: ${maxObservedPlantMeters}م من أصل ${departureThresholdMeters}م)"
            !hasSufficientTravel ->
                "المسافة الفعلية المقطوعة غير كافية ($filteredDistKm كم من أصل $minDistRequired كم المطلوبة)"
            !hasMinDuration ->
                "مدة الرحلة قصيرة جداً (${elapsedSeconds}ث من أصل ${minDurationRequired}ث المطلوبة)"
            !hasConfirmedReturn ->
                "الوايت خارج محيط المصنع (${geofenceRadius}م) — ستُسجل العودة وتُغلق الرحلة تلقائياً عند العودة"
            else ->
                "تم رصد العودة إلى محيط المصنع (${currentDistanceFromPlantMeters}م) وإغلاق الرحلة تلقائياً"
        }

        return TripCompletionReadiness(
            qualified = qualified,
            hasDeparted = hasDeparted,
            hasConfirmedReturn = hasConfirmedReturn,
            visitedCustomerStop = visitedCustomerStop,
            maxDistanceFromOriginMeters = maxObservedPlantMeters,
            filteredDistanceKm = filteredDistKm,
            elapsedSeconds = elapsedSeconds,
            departureThresholdMeters = departureThresholdMeters,
            geofenceRadius = geofenceRadius,
            currentDistanceFromPlantMeters = currentDistanceFromPlantMeters,
            reason = reason
        )
    }
}

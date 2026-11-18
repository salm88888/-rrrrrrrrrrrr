package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.domain.DeliveryLocationLog
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSEventType
import com.miyahna.water.domain.GpsTrackingEngine
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.MapMarkerIcons
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.isValidLatLng
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.common.safeShortTime
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

private enum class TripFilterMode(val labelAr: String) {
    ALL("الكل"),
    ACTIVE("النشطة"),
    REVIEW("بانتظار المراجعة"),
    ALERTS("توقفات / إيقاف GPS"),
    CLOSED("مكتملة المراجعة")
}

private enum class TripReviewDetailTab(val labelAr: String) {
    ROUTE_SPEED("خط السير والسرعة"),
    STOPS("أماكن ومدد التوقف"),
    GPS_NOTES("ملاحظات إيقاف GPS")
}

private data class GpsOffNoteItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val lat: Double?,
    val lng: Double?
)

private data class TripTelemetryStats(
    val startTimeIso: String?,
    val endTimeIso: String?,
    val actualDistanceKm: Double,
    val avgSpeedKmH: Int,
    val maxSpeedKmH: Int,
    val movingMinutes: Double,
    val stoppedMinutes: Double,
    val gpsPointsCount: Int,
    val stopsCount: Int,
    val gpsOffCount: Int
)

private fun computeTripTelemetry(
    trip: Trip,
    gpsOffNotesCount: Int
): TripTelemetryStats {
    val summary = GpsTrackingEngine.buildPlanVsActualSummary(trip)
    val validSpeeds = trip.gpsPoints.mapNotNull { pt ->
        pt.speedKmH.takeIf { it.isFinite() && it >= 0.0 }
    }
    val avgSpeed = if (validSpeeds.isNotEmpty()) validSpeeds.average().safeRoundToInt() else 0
    val maxSpeed = validSpeeds.maxOrNull()?.safeRoundToInt() ?: 0
    val startIso = trip.startedAt ?: trip.gpsPoints.firstOrNull()?.timestamp
    val endIso = trip.returnedToPlantAt ?: trip.completedAt ?: trip.gpsPoints.lastOrNull()?.timestamp
    return TripTelemetryStats(
        startTimeIso = startIso,
        endTimeIso = endIso,
        actualDistanceKm = summary.actualDistanceKm,
        avgSpeedKmH = avgSpeed,
        maxSpeedKmH = maxSpeed,
        movingMinutes = summary.totalMovingMinutes,
        stoppedMinutes = summary.totalStoppedMinutes,
        gpsPointsCount = summary.gpsPointsCount,
        stopsCount = trip.detectedStops.size,
        gpsOffCount = gpsOffNotesCount
    )
}

private fun buildGpsOffNotes(
    trip: Trip,
    gpsEvents: List<GPSEvent>,
    transitions: List<TripStateTransition>
): List<GpsOffNoteItem> {
    val items = mutableListOf<GpsOffNoteItem>()
    trip.gpsGaps.forEach { gap ->
        val durLabel = if (gap.durationSeconds >= 60) {
            "${gap.durationSeconds / 60} د (${gap.durationSeconds} ث)"
        } else {
            "${gap.durationSeconds} ثانية"
        }
        val lat = gap.fromLat?.takeIf { it.isFinite() } ?: gap.toLat?.takeIf { it.isFinite() }
        val lng = gap.fromLng?.takeIf { it.isFinite() } ?: gap.toLng?.takeIf { it.isFinite() }
        items.add(
            GpsOffNoteItem(
                id = "gap_${gap.id}",
                title = MapMarkerIcons.gpsGapReasonAr(gap.reason),
                subtitle = "المدة: $durLabel · من ${formatShortTime(gap.startTime)} إلى ${formatShortTime(gap.endTime)}",
                lat = lat,
                lng = lng
            )
        )
    }

    val coveredTimes = trip.gpsGaps.map { it.startTime }.toSet()
    gpsEvents
        .filter { it.tripId == trip.id && it.source == "device_gps_off" }
        .forEach { ev ->
            if (ev.timestamp !in coveredTimes) {
                items.add(
                    GpsOffNoteItem(
                        id = "ev_${ev.id}",
                        title = ev.customerName ?: "تم إيقاف إشارة GPS",
                        subtitle = "التوقيت: ${formatShortTime(ev.timestamp)}",
                        lat = ev.lat.takeIf { isValidLatLng(it, ev.lng) },
                        lng = ev.lng.takeIf { isValidLatLng(ev.lat, it) }
                    )
                )
            }
        }

    transitions
        .filter { it.tripId == trip.id && (it.toState == "paused" || it.triggerSource == "auto_gps_gap") }
        .forEach { tr ->
            val reasonText = tr.reason ?: tr.details
            if (!reasonText.isNullOrBlank()) {
                items.add(
                    GpsOffNoteItem(
                        id = "tr_${tr.id}",
                        title = "إيقاف التتبع / GPS: $reasonText",
                        subtitle = "التوقيت: ${formatShortTime(tr.timestamp)} · ${tr.actorName}",
                        lat = tr.lat?.takeIf { tr.lng != null && isValidLatLng(it, tr.lng) },
                        lng = tr.lng?.takeIf { tr.lat != null && isValidLatLng(tr.lat, it) }
                    )
                )
            }
        }

    trip.auditLogs
        .filter { log ->
            log.action.contains("GPS", ignoreCase = true) ||
                log.action.contains("إيقاف مؤقت") ||
                (log.details?.contains("GPS", ignoreCase = true) == true)
        }
        .forEach { log ->
            val detailText = log.details?.takeIf { it.isNotBlank() } ?: log.action
            if (items.none { it.title.contains(detailText) }) {
                items.add(
                    GpsOffNoteItem(
                        id = "aud_${log.id}",
                        title = detailText,
                        subtitle = "التوقيت: ${formatShortTime(log.timestamp)} · ${log.actorName}",
                        lat = null,
                        lng = null
                    )
                )
            }
        }

    return items.distinctBy { it.id }
}

private fun isTripReviewCompleted(status: TripStatus): Boolean =
    status == TripStatus.CLOSED || status == TripStatus.REJECTED

private fun isTripReadyToCompleteReview(status: TripStatus): Boolean =
    status == TripStatus.RETURNED_TO_PLANT ||
        status == TripStatus.SYNCED ||
        status == TripStatus.COMPLETED_PENDING_REVIEW ||
        status == TripStatus.PENDING_SYNC

@Suppress("UNUSED_PARAMETER")
@Composable
fun TripsManagementScreen(
    trips: List<Trip>,
    deliveryLocations: List<DeliveryLocationLog> = emptyList(),
    settings: SystemSettings,
    gpsEvents: List<GPSEvent> = emptyList(),
    tripStateTransitions: List<TripStateTransition> = emptyList(),
    onApproveTrip: (String) -> Unit,
    onRejectTrip: (String, String) -> Unit,
    onPauseTrip: (String, String?) -> Unit = { _, _ -> },
    onResumeTrip: (String, String?) -> Unit = { _, _ -> },
    onStopTracking: (String, String?) -> Unit,
    onCloseTrip: (String, String?) -> Unit,
    onDeleteTrip: (String) -> Unit = {},
    onLogDeliveryForStop: (tripId: String, orderId: String, liters: Int, paidAmount: Double) -> Unit = { _, _, _, _ -> },
    onReviewModRequest: (tripId: String, requestId: String, approved: Boolean, notes: String?) -> Unit = { _, _, _, _ -> },
    onMarkTransitionReviewed: (logId: String, note: String?) -> Unit = { _, _ -> }
) {
    var mapInspectTripId by remember { mutableStateOf<String?>(null) }
    var mapFocusLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var selectedDetailTab by remember { mutableStateOf(TripReviewDetailTab.ROUTE_SPEED) }
    var filterMode by remember { mutableStateOf(TripFilterMode.ALL) }
    var deleteConfirmTrip by remember { mutableStateOf<Trip?>(null) }
    val expandedDetailsMap = remember { mutableStateMapOf<String, Boolean>() }

    val gpsOffNotesByTrip = remember(trips, gpsEvents, tripStateTransitions) {
        trips.associate { trip ->
            trip.id to buildGpsOffNotes(trip, gpsEvents, tripStateTransitions)
        }
    }

    val filteredTrips = remember(trips, filterMode, gpsOffNotesByTrip) {
        when (filterMode) {
            TripFilterMode.ALL -> trips
            TripFilterMode.ACTIVE -> trips.filter {
                it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED || it.status == TripStatus.APPROVED
            }
            TripFilterMode.REVIEW -> trips.filter {
                isTripReadyToCompleteReview(it.status) || it.status == TripStatus.PENDING_APPROVAL
            }
            TripFilterMode.ALERTS -> trips.filter { t ->
                t.detectedStops.isNotEmpty() || (gpsOffNotesByTrip[t.id]?.isNotEmpty() == true)
            }
            TripFilterMode.CLOSED -> trips.filter { it.status == TripStatus.CLOSED }
        }
    }

    val totalDetectedStops = remember(trips) { trips.sumOf { it.detectedStops.size } }
    val totalGpsOffNotes = remember(gpsOffNotesByTrip) { gpsOffNotesByTrip.values.sumOf { it.size } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trips_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "مراجعة تتبع الرحلات (${trips.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            label = "نشطة: ${trips.count { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }}",
                            containerColor = EmeraldAccent.copy(alpha = 0.14f),
                            contentColor = EmeraldAccent
                        )
                        if (totalDetectedStops > 0) {
                            StatusPill(
                                label = "أماكن التوقف: $totalDetectedStops",
                                containerColor = AmberWarning.copy(alpha = 0.16f),
                                contentColor = AmberWarning
                            )
                        }
                        if (totalGpsOffNotes > 0) {
                            StatusPill(
                                label = "ملاحظات إيقاف GPS: $totalGpsOffNotes",
                                containerColor = RoseError.copy(alpha = 0.14f),
                                contentColor = RoseError
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TripFilterMode.entries.forEach { mode ->
                            FilterChip(
                                selected = filterMode == mode,
                                onClick = { filterMode = mode },
                                label = { Text(mode.labelAr) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("trips_filter_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        if (filteredTrips.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.LocalShipping,
                    title = if (trips.isEmpty()) "لا توجد رحلات مسجلة" else "لا توجد رحلات ضمن هذا الفلتر",
                    subtitle = if (trips.isEmpty()) {
                        "تظهر هنا الرحلات مع خط السير والتوقيت والسرعة والتوقفات وملاحظات إيقاف GPS"
                    } else {
                        "جرّب اختيار فلتر آخر لعرض الرحلات"
                    }
                )
            }
        } else {
            itemsIndexed(filteredTrips, key = { idx, trip -> "${trip.id}_$idx" }) { _, trip ->
                val gpsOffNotes = gpsOffNotesByTrip[trip.id].orEmpty()
                val telemetry = computeTripTelemetry(trip, gpsOffNotes.size)
                val isExpanded = expandedDetailsMap[trip.id] ?: false

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trip_card_${trip.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        when {
                            gpsOffNotes.isNotEmpty() -> RoseError.copy(alpha = 0.65f)
                            trip.status == TripStatus.PAUSED || trip.detectedStops.any { it.isUnplannedStop } ->
                                AmberWarning.copy(alpha = 0.65f)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header: Trip number, driver/truck, status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(trip.tripNumber, style = NumericTextStyle, color = SkyPrimary)
                                Text(
                                    text = "${trip.driverName} · ${trip.truckPlate} · ${trip.date}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                label = trip.status.labelAr,
                                containerColor = when (trip.status) {
                                    TripStatus.IN_PROGRESS -> EmeraldAccent.copy(alpha = 0.14f)
                                    TripStatus.PAUSED -> AmberWarning.copy(alpha = 0.16f)
                                    TripStatus.REJECTED -> RoseError.copy(alpha = 0.14f)
                                    TripStatus.CLOSED -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = when (trip.status) {
                                    TripStatus.IN_PROGRESS -> EmeraldAccent
                                    TripStatus.PAUSED -> AmberWarning
                                    TripStatus.REJECTED -> RoseError
                                    TripStatus.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> SkyPrimary
                                }
                            )
                        }

                        // Core 5 telemetry metrics only: Timing, Speed, Route distance, Stop durations/locations, GPS-off notes
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val startStr = telemetry.startTimeIso?.let { formatShortTime(it) } ?: "—"
                                val endStr = telemetry.endTimeIso?.let { formatShortTime(it) } ?: "مستمرة"
                                Text(
                                    text = "التوقيت: انطلاق $startStr ← نهاية $endStr · المسافة: ${telemetry.actualDistanceKm} كم (${telemetry.gpsPointsCount} نقطة)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "السرعة: متوسط ${telemetry.avgSpeedKmH} كم/س · أقصى ${telemetry.maxSpeedKmH} كم/س",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SkyPrimary
                                )
                                Text(
                                    text = "مدد التوقف: حركة ${telemetry.movingMinutes} د · توقف ${telemetry.stoppedMinutes} د · أماكن التوقف: ${telemetry.stopsCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "ملاحظات إيقاف GPS: ${telemetry.gpsOffCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (telemetry.gpsOffCount > 0) RoseError else EmeraldAccent
                                )
                            }
                        }

                        // Expandable Stop Locations/Durations & GPS-off Notes
                        if (trip.detectedStops.isNotEmpty() || gpsOffNotes.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (gpsOffNotes.isNotEmpty()) RoseError.copy(alpha = 0.45f)
                                    else AmberWarning.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (gpsOffNotes.isNotEmpty()) Icons.Default.SignalWifiOff else Icons.Default.PauseCircle,
                                                contentDescription = null,
                                                tint = if (gpsOffNotes.isNotEmpty()) RoseError else AmberWarning,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "التوقفات (${trip.detectedStops.size}) وملاحظات إيقاف GPS (${gpsOffNotes.size})",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TextButton(
                                            onClick = { expandedDetailsMap[trip.id] = !isExpanded },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("toggle_stops_${trip.id}")
                                        ) {
                                            Text(if (isExpanded) "إخفاء" else "عرض")
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null
                                            )
                                        }
                                    }

                                    if (isExpanded) {
                                        trip.detectedStops.forEachIndexed { idx, detected ->
                                            DetectedStopRow(
                                                index = idx + 1,
                                                stop = detected,
                                                onInspectOnMap = {
                                                    selectedDetailTab = TripReviewDetailTab.STOPS
                                                    mapFocusLatLng = detected.lat to detected.lng
                                                    mapInspectTripId = trip.id
                                                }
                                            )
                                        }
                                        gpsOffNotes.forEach { note ->
                                            GpsOffNoteRow(
                                                note = note,
                                                onInspectOnMap = note.lat?.let { lat ->
                                                    note.lng?.let { lng ->
                                                        {
                                                            selectedDetailTab = TripReviewDetailTab.GPS_NOTES
                                                            mapFocusLatLng = lat to lng
                                                            mapInspectTripId = trip.id
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Essential Review & Lifecycle Actions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    selectedDetailTab = TripReviewDetailTab.ROUTE_SPEED
                                    mapFocusLatLng = trip.gpsPoints.lastOrNull()?.let { it.lat to it.lng }
                                        ?: trip.detectedStops.firstOrNull()?.let { it.lat to it.lng }
                                    mapInspectTripId = trip.id
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("inspect_trip_map_${trip.id}")
                            ) {
                                Icon(Icons.Default.GpsFixed, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("الخريطة والتفاصيل")
                            }

                            when {
                                trip.status == TripStatus.DRAFT || trip.status == TripStatus.PENDING_APPROVAL -> {
                                    OutlinedButton(
                                        onClick = { onApproveTrip(trip.id) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("approve_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("اعتماد")
                                    }
                                    OutlinedButton(
                                        onClick = { onRejectTrip(trip.id, "رفض من الإدارة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("reject_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "رفض الرحلة", tint = RoseError)
                                    }
                                }
                                trip.status == TripStatus.IN_PROGRESS -> {
                                    OutlinedButton(
                                        onClick = { onPauseTrip(trip.id, "إيقاف مؤقت للمراجعة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("manager_pause_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.PauseCircle, contentDescription = null, tint = AmberWarning)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف مؤقت")
                                    }
                                    OutlinedButton(
                                        onClick = { onStopTracking(trip.id, "إنهاء التتبع من الإدارة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("stop_tracking_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.StopCircle, contentDescription = null, tint = RoseError)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف التتبع", color = RoseError)
                                    }
                                }
                                trip.status == TripStatus.PAUSED -> {
                                    OutlinedButton(
                                        onClick = { onResumeTrip(trip.id, "استئناف من الإدارة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("manager_resume_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = EmeraldAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("استئناف")
                                    }
                                    OutlinedButton(
                                        onClick = { onStopTracking(trip.id, "إنهاء التتبع من الإدارة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("stop_tracking_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.StopCircle, contentDescription = null, tint = RoseError)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف التتبع", color = RoseError)
                                    }
                                }
                                isTripReadyToCompleteReview(trip.status) -> {
                                    OutlinedButton(
                                        onClick = { onCloseTrip(trip.id, "تمت مراجعة المسار والتوقفات") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("close_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("اعتماد إكمال المراجعة")
                                    }
                                }
                                isTripReviewCompleted(trip.status) -> {
                                    OutlinedButton(
                                        onClick = { deleteConfirmTrip = trip },
                                        border = BorderStroke(1.dp, RoseError),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("delete_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("حذف الرحلة")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val activeInspectTrip = mapInspectTripId?.let { id -> trips.firstOrNull { it.id == id } }
    activeInspectTrip?.let { trip ->
        val tripGpsOffNotes = gpsOffNotesByTrip[trip.id].orEmpty()
        val tripGpsOffEvents = remember(gpsEvents, trip.id) {
            gpsEvents.filter { it.tripId == trip.id && it.source == "device_gps_off" }
        }
        val telemetry = computeTripTelemetry(trip, tripGpsOffNotes.size)

        Dialog(
            onDismissRequest = { mapInspectTripId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مراجعة مسار ${trip.tripNumber}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${trip.driverName} · ${trip.truckPlate}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { mapInspectTripId = null },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("close_trip_review_dialog_button")
                        ) {
                            Text("إغلاق")
                        }
                    }

                    // Key Review Summary Banner (only requested telemetry)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val startStr = telemetry.startTimeIso?.let { formatShortTime(it) } ?: "—"
                            val endStr = telemetry.endTimeIso?.let { formatShortTime(it) } ?: "مستمرة"
                            Text(
                                text = "التوقيت: $startStr ← $endStr · المسافة: ${telemetry.actualDistanceKm} كم",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "متوسط السرعة: ${telemetry.avgSpeedKmH} كم/س · أقصى سرعة: ${telemetry.maxSpeedKmH} كم/س",
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyPrimary
                            )
                            Text(
                                text = "الحركة: ${telemetry.movingMinutes} د · مدد التوقف: ${telemetry.stoppedMinutes} د · إيقاف GPS: ${telemetry.gpsOffCount}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // Interactive Map showing ONLY Route Track, Speed/Timing Checkpoints, Stop Locations & Durations, GPS Off Notes
                    MaribInteractiveMap(
                        plantLocation = settings.plantLocation,
                        geofenceMeters = settings.plantGeofenceMeters,
                        showCustomers = false,
                        plannedStops = emptyList(),
                        gpsPoints = trip.gpsPoints,
                        detectedStops = trip.detectedStops,
                        gpsGaps = trip.gpsGaps,
                        gpsOffEvents = tripGpsOffEvents,
                        focusLatLng = mapFocusLatLng,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )

                    // 3 Telemetry Detail Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TripReviewDetailTab.entries.forEach { tab ->
                            val countBadge = when (tab) {
                                TripReviewDetailTab.ROUTE_SPEED -> trip.gpsPoints.size
                                TripReviewDetailTab.STOPS -> trip.detectedStops.size
                                TripReviewDetailTab.GPS_NOTES -> tripGpsOffNotes.size
                            }
                            FilterChip(
                                selected = selectedDetailTab == tab,
                                onClick = { selectedDetailTab = tab },
                                label = { Text("${tab.labelAr} ($countBadge)") },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("review_tab_${tab.name.lowercase()}")
                            )
                        }
                    }

                    when (selectedDetailTab) {
                        TripReviewDetailTab.ROUTE_SPEED -> {
                            if (trip.gpsPoints.isEmpty()) {
                                Text(
                                    text = "لا توجد نقاط خط سير مسجلة لهذه الرحلة بعد",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    trip.gpsPoints.forEachIndexed { idx, pt ->
                                        val isFocused = mapFocusLatLng == (pt.lat to pt.lng)
                                        Surface(
                                            color = if (isFocused) SkyPrimary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(10.dp),
                                            border = if (isFocused) BorderStroke(1.dp, SkyPrimary) else null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { mapFocusLatLng = pt.lat to pt.lng }
                                                .testTag("gps_point_row_${pt.id}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Speed,
                                                        contentDescription = null,
                                                        tint = SkyPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Column {
                                                        Text(
                                                            text = "#${if (pt.seq > 0) pt.seq else idx + 1} · التوقيت: ${formatShortTime(pt.timestamp)} · السرعة: ${pt.speedKmH.safeRoundToInt()} كم/س",
                                                            style = MaterialTheme.typography.labelLarge,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Text(
                                                            text = "${formatLatLng(pt.lat, pt.lng, 5)}${if (pt.isWeakSignal) " · إشارة ضعيفة" else ""}",
                                                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                Icon(
                                                    Icons.Default.LocationOn,
                                                    contentDescription = "عرض النقطة على الخريطة",
                                                    tint = SkyPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TripReviewDetailTab.STOPS -> {
                            if (trip.detectedStops.isEmpty()) {
                                Text(
                                    text = "لم يتم رصد توقفات طويلة خلال هذه الرحلة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    trip.detectedStops.forEachIndexed { idx, st ->
                                        DetectedStopRow(
                                            index = idx + 1,
                                            stop = st,
                                            onInspectOnMap = { mapFocusLatLng = st.lat to st.lng }
                                        )
                                    }
                                }
                            }
                        }

                        TripReviewDetailTab.GPS_NOTES -> {
                            if (tripGpsOffNotes.isEmpty()) {
                                Text(
                                    text = "لا توجد ملاحظات إيقاف أو انقطاع GPS مسجلة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmeraldAccent
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tripGpsOffNotes.forEach { note ->
                                        GpsOffNoteRow(
                                            note = note,
                                            onInspectOnMap = note.lat?.let { lat ->
                                                note.lng?.let { lng ->
                                                    { mapFocusLatLng = lat to lng }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // Complete Review or Delete Trip right from inside the Review Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isTripReadyToCompleteReview(trip.status)) {
                            Button(
                                onClick = { onCloseTrip(trip.id, "تمت مراجعة خط السير والتوقفات") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("dialog_close_trip_${trip.id}")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("اعتماد إكمال المراجعة")
                            }
                        }
                        if (isTripReviewCompleted(trip.status)) {
                            Button(
                                onClick = { deleteConfirmTrip = trip },
                                colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("dialog_delete_trip_${trip.id}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("حذف الرحلة")
                            }
                        }
                    }
                }
            }
        }
    }

    deleteConfirmTrip?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmTrip = null },
            title = { Text("تأكيد حذف الرحلة") },
            text = {
                Text(
                    "هل تريد حذف الرحلة ${target.tripNumber} نهائياً بعد اكتمال مراجعتها؟ سيتم إزالتها من التطبيق وقاعدة البيانات."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTrip(target.id)
                        if (mapInspectTripId == target.id) {
                            mapInspectTripId = null
                        }
                        deleteConfirmTrip = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_delete_trip_button")
                ) {
                    Text("حذف نهائياً")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteConfirmTrip = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun DetectedStopRow(
    index: Int,
    stop: DetectedStop,
    onInspectOnMap: () -> Unit
) {
    val label = when {
        stop.isUnplannedStop -> "توقف غير مجدول #$index"
        !stop.nearCustomerName.isNullOrBlank() -> "توقف: ${stop.nearCustomerName}"
        else -> "توقف في نطاق المصنع #$index"
    }
    val accent = when {
        stop.isUnplannedStop -> AmberWarning
        !stop.nearCustomerName.isNullOrBlank() -> EmeraldAccent
        else -> SkyPrimary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                val endLabel = stop.endTime?.let { formatShortTime(it) } ?: "مستمر"
                Text(
                    text = "مدة التوقف: ${stop.durationMinutes} دقيقة · من ${formatShortTime(stop.startTime)} إلى $endLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatLatLng(stop.lat, stop.lng, 5),
                    style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            OutlinedButton(
                onClick = onInspectOnMap,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("الخريطة")
            }
        }
    }
}

@Composable
private fun GpsOffNoteRow(
    note: GpsOffNoteItem,
    onInspectOnMap: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = RoseError.copy(alpha = 0.25f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = RoseError,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = RoseError
                    )
                    Text(
                        text = note.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (note.lat != null && note.lng != null) {
                        Text(
                            text = formatLatLng(note.lat, note.lng, 5),
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (onInspectOnMap != null) {
                OutlinedButton(
                    onClick = onInspectOnMap,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("الخريطة")
                }
            }
        }
    }
}

private fun formatShortTime(iso: String): String = safeShortTime(iso)

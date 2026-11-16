package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.domain.DeliveryLocationLog
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSGap
import com.miyahna.water.domain.GpsTrackingEngine
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.tripTransitionStateLabelAr
import com.miyahna.water.domain.tripTransitionTriggerLabelAr
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
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
    ALERTS("توقفات / تنبيهات"),
    CLOSED("مغلقة")
}

private enum class TransitionLogFilter(val labelAr: String) {
    ALL("الكل"),
    UNREVIEWED("بانتظار المراجعة"),
    ACCEPTED("مقبولة"),
    IN_TRANSIT("قيد التنفيذ"),
    PAUSED("متوقفة مؤقتاً"),
    COMPLETED("مكتملة"),
    CLOSED("مغلقة")
}

@Composable
fun TripsManagementScreen(
    trips: List<Trip>,
    deliveryLocations: List<DeliveryLocationLog>,
    settings: SystemSettings,
    gpsEvents: List<GPSEvent> = emptyList(),
    tripStateTransitions: List<TripStateTransition> = emptyList(),
    onApproveTrip: (String) -> Unit,
    onRejectTrip: (String, String) -> Unit,
    onPauseTrip: (String, String?) -> Unit = { _, _ -> },
    onResumeTrip: (String, String?) -> Unit = { _, _ -> },
    onStopTracking: (String, String?) -> Unit,
    onCloseTrip: (String, String?) -> Unit,
    onLogDeliveryForStop: (tripId: String, orderId: String, liters: Int, paidAmount: Double) -> Unit,
    onReviewModRequest: (tripId: String, requestId: String, approved: Boolean, notes: String?) -> Unit,
    onMarkTransitionReviewed: (logId: String, note: String?) -> Unit = { _, _ -> }
) {
    var deliveryTarget by remember { mutableStateOf<Pair<Trip, TripCustomerStop>?>(null) }
    var mapInspectTrip by remember { mutableStateOf<Trip?>(null) }
    var mapFocusLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var filterMode by remember { mutableStateOf(TripFilterMode.ALL) }
    val expandedStopsMap = remember { mutableStateMapOf<String, Boolean>() }
    val expandedTransitionsMap = remember { mutableStateMapOf<String, Boolean>() }
    var showAllTransitionsDialog by remember { mutableStateOf(false) }
    var reviewTargetTransition by remember { mutableStateOf<TripStateTransition?>(null) }

    val filteredTrips = remember(trips, filterMode) {
        when (filterMode) {
            TripFilterMode.ALL -> trips
            TripFilterMode.ACTIVE -> trips.filter {
                it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED || it.status == TripStatus.APPROVED
            }
            TripFilterMode.REVIEW -> trips.filter {
                it.status == TripStatus.COMPLETED_PENDING_REVIEW ||
                    it.status == TripStatus.RETURNED_TO_PLANT ||
                    it.status == TripStatus.PENDING_SYNC ||
                    it.status == TripStatus.SYNCED ||
                    it.status == TripStatus.PENDING_APPROVAL
            }
            TripFilterMode.ALERTS -> trips.filter { t ->
                t.detectedStops.any { it.isUnplannedStop } || t.gpsGaps.isNotEmpty()
            }
            TripFilterMode.CLOSED -> trips.filter { it.status == TripStatus.CLOSED }
        }
    }

    val totalUnplannedStops = remember(trips) {
        trips.sumOf { t -> t.detectedStops.count { it.isUnplannedStop } }
    }
    val totalGpsGaps = remember(trips) {
        trips.sumOf { it.gpsGaps.size }
    }
    val unreviewedTransitionsCount = remember(tripStateTransitions) {
        tripStateTransitions.count { !it.reviewedByManager }
    }
    val transitionsByTrip = remember(tripStateTransitions) {
        tripStateTransitions.groupBy { it.tripId }
    }
    val recentStandbyEvents = remember(gpsEvents) {
        gpsEvents.filter { it.tripId.startsWith("standby-") }.takeLast(6).reversed()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "مركز مراقبة الرحلات والتنقلات (${trips.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { showAllTransitionsDialog = true },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("open_all_transitions_log_button")
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("سجل الحالات (${tripStateTransitions.size})")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            label = "نشطة: ${trips.count { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED || it.status == TripStatus.APPROVED }}",
                            containerColor = EmeraldAccent.copy(alpha = 0.14f),
                            contentColor = EmeraldAccent
                        )
                        StatusPill(
                            label = "تحولات بانتظار المراجعة: $unreviewedTransitionsCount",
                            containerColor = if (unreviewedTransitionsCount > 0) SkyPrimary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (unreviewedTransitionsCount > 0) SkyPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusPill(
                            label = "توقفات غير مجدولة: $totalUnplannedStops",
                            containerColor = if (totalUnplannedStops > 0) AmberWarning.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (totalUnplannedStops > 0) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusPill(
                            label = "انقطاع GPS: $totalGpsGaps",
                            containerColor = if (totalGpsGaps > 0) RoseError.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (totalGpsGaps > 0) RoseError else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

        if (recentStandbyEvents.isNotEmpty() && filterMode == TripFilterMode.ALL) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "سجل تنقلات وتوقفات السائق خارج الرحلات (${recentStandbyEvents.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SkyPrimary
                        )
                        recentStandbyEvents.forEach { ev ->
                            Text(
                                text = "• ${ev.eventType.labelAr} (${safeShortTime(ev.timestamp)}) · ${formatLatLng(ev.lat, ev.lng, 5)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        "قم بتجميع الطلبات من تبويب التجميع الذكي لإنشاء واعتماد أول رحلة"
                    } else {
                        "جرّب اختيار فلتر آخر لعرض جميع الرحلات"
                    }
                )
            }
        } else {
            itemsIndexed(filteredTrips, key = { idx, trip -> "${trip.id}_$idx" }) { _, trip ->
                val summary = GpsTrackingEngine.buildPlanVsActualSummary(trip)
                val hasAlerts = trip.detectedStops.any { it.isUnplannedStop } || trip.gpsGaps.isNotEmpty()
                val defaultExpanded = trip.status == TripStatus.IN_PROGRESS || trip.status == TripStatus.PAUSED || hasAlerts
                val isStopsExpanded = expandedStopsMap[trip.id] ?: defaultExpanded
                val tripTransitions = transitionsByTrip[trip.id].orEmpty()
                val unreviewedForTrip = tripTransitions.count { !it.reviewedByManager }
                val isTransitionsExpanded = expandedTransitionsMap[trip.id] ?: (trip.status == TripStatus.IN_PROGRESS || trip.status == TripStatus.PAUSED || unreviewedForTrip > 0)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trip_card_${trip.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        when {
                            trip.gpsGaps.isNotEmpty() -> RoseError.copy(alpha = 0.6f)
                            trip.status == TripStatus.PAUSED || summary.unplannedStops.isNotEmpty() -> AmberWarning.copy(alpha = 0.65f)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(trip.tripNumber, style = NumericTextStyle, color = SkyPrimary)
                                Text(
                                    text = "السائق: ${trip.driverName} · اللوحة: ${trip.truckPlate} · ${trip.date}",
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

                        // Driver acceptance & live timing audit row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val acceptanceLabel = when (trip.driverAcceptanceStatus) {
                                "accepted" -> "وافق السائق (خلفي نشط)"
                                "declined" -> "اعتذر السائق"
                                "pending" -> "بانتظار قبول السائق"
                                else -> "جاهزة"
                            }
                            StatusPill(
                                label = acceptanceLabel,
                                containerColor = when (trip.driverAcceptanceStatus) {
                                    "accepted" -> EmeraldAccent.copy(alpha = 0.14f)
                                    "declined" -> RoseError.copy(alpha = 0.14f)
                                    else -> AmberWarning.copy(alpha = 0.14f)
                                },
                                contentColor = when (trip.driverAcceptanceStatus) {
                                    "accepted" -> EmeraldAccent
                                    "declined" -> RoseError
                                    else -> AmberWarning
                                }
                            )
                            trip.driverRespondedAt?.let {
                                StatusPill(
                                    label = "الرد: ${formatShortTime(it)}",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            trip.startedAt?.let {
                                StatusPill(
                                    label = "انطلاق: ${formatShortTime(it)}",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            trip.returnedToPlantAt?.let {
                                StatusPill(
                                    label = "عودة: ${formatShortTime(it)}",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Plan vs Actual & Movement Telemetry metrics
                        Text(
                            text = "المخطط: ${summary.plannedDistanceKm} كم (${formatNumber(summary.plannedLiters)} لتر) | الفعلي: ${summary.actualDistanceKm} كم (${formatNumber(summary.deliveredLiters)} لتر مسلم)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "حركة: ${summary.totalMovingMinutes} د · توقف: ${summary.totalStoppedMinutes} د · نقاط GPS: ${summary.gpsPointsCount} · توقفات مرصودة: ${summary.totalDetectedStopsCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SkyPrimary
                        )

                        val tripDeliveryLogs = deliveryLocations.filter { it.tripId == trip.id }
                        if (tripDeliveryLogs.isNotEmpty()) {
                            val latestLog = tripDeliveryLogs.first()
                            Text(
                                text = "مواقع تسليم GPS موثقة: ${tripDeliveryLogs.size} · آخر موقع: ${formatLatLng(latestLog.latitude, latestLog.longitude, 4)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldAccent
                            )
                        }

                        // Expandable Detected Stops & GPS Gaps Inspector
                        if (trip.detectedStops.isNotEmpty() || trip.gpsGaps.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (summary.unplannedStops.isNotEmpty()) AmberWarning.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                imageVector = if (summary.unplannedStops.isNotEmpty()) Icons.Default.Warning else Icons.Default.PauseCircle,
                                                contentDescription = null,
                                                tint = if (summary.unplannedStops.isNotEmpty()) AmberWarning else SkyPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "سجل التوقفات (${trip.detectedStops.size}) والانقطاعات (${trip.gpsGaps.size})",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TextButton(
                                            onClick = { expandedStopsMap[trip.id] = !isStopsExpanded },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("toggle_stops_${trip.id}")
                                        ) {
                                            Text(if (isStopsExpanded) "إخفاء" else "عرض الكل")
                                            Icon(
                                                imageVector = if (isStopsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null
                                            )
                                        }
                                    }

                                    if (isStopsExpanded) {
                                        trip.detectedStops.forEachIndexed { idx, detected ->
                                            DetectedStopRow(
                                                index = idx + 1,
                                                stop = detected,
                                                onInspectOnMap = {
                                                    mapFocusLatLng = detected.lat to detected.lng
                                                    mapInspectTrip = trip
                                                }
                                            )
                                        }
                                        trip.gpsGaps.forEach { gap ->
                                            GpsGapAlertRow(gap = gap)
                                        }
                                    }
                                }
                            }
                        }

                        // Customer stops with Manager-exclusive delivery logging
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            trip.customerStops.forEach { stop ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${stop.stopSequence}. ${stop.customerName} (${stop.district})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "مطلوب: ${formatNumber(stop.requestedLiters)}L · مسلم: ${formatNumber(stop.deliveredLiters)}L · مدفوع: ${formatNumber(stop.paidAmount.safeRoundToInt())} ر.ي",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (stop.status != StopStatus.DELIVERED && trip.status != TripStatus.REJECTED && trip.status != TripStatus.CLOSED) {
                                        OutlinedButton(
                                            onClick = { deliveryTarget = trip to stop },
                                            modifier = Modifier.testTag("log_delivery_${stop.orderId}")
                                        ) {
                                            Text("توثيق تسليم")
                                        }
                                    } else {
                                        StatusPill(
                                            label = stop.status.labelAr,
                                            containerColor = EmeraldAccent.copy(alpha = 0.14f),
                                            contentColor = EmeraldAccent
                                        )
                                    }
                                }
                            }
                        }

                        // Pending modification requests
                        trip.modificationRequests.filter { it.status == "pending" }.forEach { req ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
                                border = BorderStroke(1.dp, AmberWarning)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("طلب تعديل مسار من ${req.requestedBy}: ${req.reason}", style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onReviewModRequest(trip.id, req.id, true, null) }, modifier = Modifier.weight(1f)) {
                                            Text("اعتماد التعديل")
                                        }
                                        OutlinedButton(onClick = { onReviewModRequest(trip.id, req.id, false, "مرفوض من الإدارة") }, modifier = Modifier.weight(1f)) {
                                            Text("رفض")
                                        }
                                    }
                                }
                            }
                        }

                        // Expandable Manager Trip State Transition Audit Log
                        if (tripTransitions.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (unreviewedForTrip > 0) SkyPrimary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
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
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = SkyPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "سجل تحولات الحالة (${tripTransitions.size})" +
                                                    if (unreviewedForTrip > 0) " · $unreviewedForTrip للمراجعة" else " · تمت المراجعة",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TextButton(
                                            onClick = { expandedTransitionsMap[trip.id] = !isTransitionsExpanded },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("toggle_transitions_${trip.id}")
                                        ) {
                                            Text(if (isTransitionsExpanded) "إخفاء" else "عرض")
                                            Icon(
                                                imageVector = if (isTransitionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null
                                            )
                                        }
                                    }

                                    if (isTransitionsExpanded) {
                                        tripTransitions.take(8).forEach { log ->
                                            TripStateTransitionRow(
                                                log = log,
                                                onInspectLocation = log.lat?.let { lat ->
                                                    log.lng?.let { lng ->
                                                        {
                                                            mapFocusLatLng = lat to lng
                                                            mapInspectTrip = trip
                                                        }
                                                    }
                                                },
                                                onReviewClick = {
                                                    reviewTargetTransition = log
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Lifecycle Actions Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    mapFocusLatLng = trip.gpsPoints.lastOrNull()?.let { it.lat to it.lng }
                                    mapInspectTrip = trip
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("inspect_trip_map_${trip.id}")
                            ) {
                                Icon(Icons.Default.GpsFixed, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("المسار والتوقفات")
                            }
                            when (trip.status) {
                                TripStatus.DRAFT, TripStatus.PENDING_APPROVAL -> {
                                    Button(
                                        onClick = { onApproveTrip(trip.id) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("approve_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("اعتماد")
                                    }
                                    OutlinedButton(
                                        onClick = { onRejectTrip(trip.id, "إعادة ترتيب الطلبات") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("reject_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "رفض الرحلة", tint = RoseError)
                                    }
                                }
                                TripStatus.IN_PROGRESS -> {
                                    OutlinedButton(
                                        onClick = { onPauseTrip(trip.id, "إيقاف مؤقت للمراجعة من الإدارة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("manager_pause_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.PauseCircle, contentDescription = null, tint = AmberWarning)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف مؤقت")
                                    }
                                    Button(
                                        onClick = { onStopTracking(trip.id, "إنهاء التتبع من الإدارة") },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("stop_tracking_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.StopCircle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف التتبع")
                                    }
                                }
                                TripStatus.PAUSED -> {
                                    Button(
                                        onClick = { onResumeTrip(trip.id, "استئناف من الإدارة") },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("manager_resume_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("استئناف")
                                    }
                                    Button(
                                        onClick = { onStopTracking(trip.id, "إنهاء التتبع من الإدارة") },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("stop_tracking_trip_${trip.id}")
                                    ) {
                                        Icon(Icons.Default.StopCircle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إيقاف التتبع")
                                    }
                                }
                                TripStatus.RETURNED_TO_PLANT, TripStatus.SYNCED, TripStatus.COMPLETED_PENDING_REVIEW -> {
                                    Button(
                                        onClick = { onCloseTrip(trip.id, "تمت المراجعة") },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("close_trip_${trip.id}")
                                    ) {
                                        Text("اعتماد المراجعة وإغلاق")
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    deliveryTarget?.let { (trip, stop) ->
        var litersText by remember(trip.id, stop.orderId) { mutableStateOf(stop.requestedLiters.toString()) }
        var paidText by remember(trip.id, stop.orderId) { mutableStateOf(stop.totalAmount.safeRoundToInt().toString()) }
        AlertDialog(
            onDismissRequest = { deliveryTarget = null },
            title = { Text("توثيق تسليم مياه وتحصيل — ${stop.customerName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("الكمية المطلوبة: ${formatNumber(stop.requestedLiters)} لتر · الإجمالي: ${formatNumber(stop.totalAmount.safeRoundToInt())} ر.ي")
                    OutlinedTextField(
                        value = litersText,
                        onValueChange = { litersText = it.filter(Char::isDigit) },
                        label = { Text("الكمية المسلمة فعلياً (لتر)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = paidText,
                        onValueChange = { paidText = it.filter(Char::isDigit) },
                        label = { Text("المبلغ النقدي المحصل (ر.ي)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onLogDeliveryForStop(
                            trip.id,
                            stop.orderId,
                            litersText.toIntOrNull() ?: stop.requestedLiters,
                            paidText.toDoubleOrNull() ?: 0.0
                        )
                        deliveryTarget = null
                    },
                    modifier = Modifier.testTag("confirm_log_delivery_button")
                ) {
                    Text("إصدار الفاتورة والتوثيق")
                }
            },
            dismissButton = {
                TextButton(onClick = { deliveryTarget = null }) { Text("إلغاء") }
            }
        )
    }

    mapInspectTrip?.let { trip ->
        Dialog(
            onDismissRequest = { mapInspectTrip = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 16.dp),
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
                            Text("خريطة مسار وتوقفات ${trip.tripNumber}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "حركة: ${trip.movingMinutes} د · توقف: ${trip.stoppedMinutes} د · ${trip.gpsPoints.size} نقطة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { mapInspectTrip = null }) { Text("إغلاق") }
                    }
                    MaribInteractiveMap(
                        plantLocation = settings.plantLocation,
                        geofenceMeters = settings.plantGeofenceMeters,
                        plannedStops = trip.customerStops,
                        gpsPoints = trip.gpsPoints,
                        detectedStops = trip.detectedStops,
                        focusLatLng = mapFocusLatLng,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                    if (trip.detectedStops.isNotEmpty()) {
                        Text(
                            text = "اضغط على أي توقف لتركيز الخريطة عليه:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            trip.detectedStops.forEachIndexed { idx, st ->
                                val label = when {
                                    st.isUnplannedStop -> "توقف غير مجدول #${idx + 1} (${st.durationMinutes} د)"
                                    !st.nearCustomerName.isNullOrBlank() -> "${st.nearCustomerName} (${st.durationMinutes} د)"
                                    else -> "المصنع #${idx + 1} (${st.durationMinutes} د)"
                                }
                                FilterChip(
                                    selected = mapFocusLatLng == (st.lat to st.lng),
                                    onClick = { mapFocusLatLng = st.lat to st.lng },
                                    label = { Text(label) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (st.isUnplannedStop) Icons.Default.Warning else Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = if (st.isUnplannedStop) AmberWarning else EmeraldAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAllTransitionsDialog) {
        var logFilter by remember { mutableStateOf(TransitionLogFilter.ALL) }
        val filteredLogs = remember(tripStateTransitions, logFilter) {
            when (logFilter) {
                TransitionLogFilter.ALL -> tripStateTransitions
                TransitionLogFilter.UNREVIEWED -> tripStateTransitions.filter { !it.reviewedByManager }
                TransitionLogFilter.ACCEPTED -> tripStateTransitions.filter { it.toState == "accepted" || it.toState == "declined" }
                TransitionLogFilter.IN_TRANSIT -> tripStateTransitions.filter { it.toState == "in_progress" || it.toState == "in_transit" }
                TransitionLogFilter.PAUSED -> tripStateTransitions.filter { it.toState == "paused" }
                TransitionLogFilter.COMPLETED -> tripStateTransitions.filter {
                    it.toState == "completed_pending_review" || it.toState == "pending_sync" || it.toState == "completed"
                }
                TransitionLogFilter.CLOSED -> tripStateTransitions.filter { it.toState == "closed" }
            }
        }
        Dialog(
            onDismissRequest = { showAllTransitionsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("سجل تحولات حالة الرحلات (${filteredLogs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("تدقيق ومراجعة كل تغيرات الحالة في Firestore والسجل المحلي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { showAllTransitionsDialog = false },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("إغلاق") }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TransitionLogFilter.entries.forEach { f ->
                            FilterChip(
                                selected = logFilter == f,
                                onClick = { logFilter = f },
                                label = { Text(f.labelAr) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("transition_filter_${f.name.lowercase()}")
                            )
                        }
                    }
                    if (filteredLogs.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Default.History,
                            title = "لا توجد سجلات تحول مطابقة",
                            subtitle = "تظهر هنا كل تحولات الرحلات فور حدوثها."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(filteredLogs, key = { idx, log -> "${log.id}_$idx" }) { _, log ->
                                TripStateTransitionRow(
                                    log = log,
                                    showTripNumber = true,
                                    onInspectLocation = null,
                                    onReviewClick = { reviewTargetTransition = log }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    reviewTargetTransition?.let { log ->
        var reviewNote by remember(log.id) { mutableStateOf(log.reviewNote ?: "تمت المراجعة الإدارية") }
        AlertDialog(
            onDismissRequest = { reviewTargetTransition = null },
            title = { Text("مراجعة تحول الحالة — ${log.tripNumber}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${tripTransitionStateLabelAr(log.fromState)} ← ${tripTransitionStateLabelAr(log.toState)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SkyPrimary
                    )
                    Text(
                        text = "المُشغّل: ${tripTransitionTriggerLabelAr(log.triggerSource)} · الفاعل: ${log.actorName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    log.reason?.let {
                        Text("السبب: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = reviewNote,
                        onValueChange = { reviewNote = it },
                        label = { Text("ملاحظة المراجعة الإدارية") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onMarkTransitionReviewed(log.id, reviewNote)
                        reviewTargetTransition = null
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_review_transition_button")
                ) {
                    Text("اعتماد المراجعة")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { reviewTargetTransition = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun TripStateTransitionRow(
    log: TripStateTransition,
    showTripNumber: Boolean = false,
    onInspectLocation: (() -> Unit)? = null,
    onReviewClick: () -> Unit
) {
    val toAccent = when (log.toState) {
        "paused", "declined" -> AmberWarning
        "in_progress", "in_transit", "accepted", "closed" -> EmeraldAccent
        "rejected" -> RoseError
        else -> SkyPrimary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val prefix = if (showTripNumber && log.tripNumber.isNotBlank()) "${log.tripNumber} · " else ""
                Text(
                    text = "$prefix${tripTransitionStateLabelAr(log.fromState)} ← ${tripTransitionStateLabelAr(log.toState)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = toAccent
                )
                Text(
                    text = "${tripTransitionTriggerLabelAr(log.triggerSource)} · ${log.actorName} · ${formatShortTime(log.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val noteOrDetails = log.reason ?: log.details
                if (!noteOrDetails.isNullOrBlank()) {
                    Text(
                        text = noteOrDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (log.isSyncedToCloud) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                        contentDescription = if (log.isSyncedToCloud) "متزامن سحابياً" else "بانتظار المزامنة",
                        tint = if (log.isSyncedToCloud) EmeraldAccent else AmberWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (log.reviewedByManager) {
                            "تمت المراجعة${log.reviewNote?.let { ": $it" } ?: ""}"
                        } else {
                            "بانتظار المراجعة الإدارية"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (log.reviewedByManager) EmeraldAccent else SkyPrimary
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onInspectLocation != null) {
                    OutlinedButton(
                        onClick = onInspectLocation,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("transition_map_${log.id}")
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "موقع التحول", modifier = Modifier.size(16.dp))
                    }
                }
                if (!log.reviewedByManager) {
                    OutlinedButton(
                        onClick = onReviewClick,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review_transition_${log.id}")
                    ) {
                        Text("مراجعة")
                    }
                }
            }
        }
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
        !stop.nearCustomerName.isNullOrBlank() -> "توقف عند العميل: ${stop.nearCustomerName}"
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
                    text = "المدة: ${stop.durationMinutes} دقيقة · من ${formatShortTime(stop.startTime)} إلى $endLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val distPart = stop.distanceToCustomerMeters?.let { " · يبعد ${it}م عن العميل" } ?: ""
                Text(
                    text = "${formatLatLng(stop.lat, stop.lng, 5)}$distPart",
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
private fun GpsGapAlertRow(gap: GPSGap) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(color = RoseError.copy(alpha = 0.25f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Icon(Icons.Default.SignalWifiOff, contentDescription = null, tint = RoseError, modifier = Modifier.size(16.dp))
            Column {
                Text(
                    text = "انقطاع إشارة GPS لمدة ${gap.durationSeconds} ثانية",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoseError
                )
                Text(
                    text = "من ${formatShortTime(gap.startTime)} إلى ${formatShortTime(gap.endTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatShortTime(iso: String): String = safeShortTime(iso)

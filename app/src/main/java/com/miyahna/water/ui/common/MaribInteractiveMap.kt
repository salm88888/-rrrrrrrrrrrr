package com.miyahna.water.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.BuildConfig
import com.miyahna.water.R
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.theme.NumericTextStyle
import kotlin.math.hypot

fun isGoogleMapsApiKeyConfigured(): Boolean {
    val key = BuildConfig.MAPS_API_KEY.trim()
    return key.isNotEmpty() &&
        key != "YOUR_GOOGLE_MAPS_API_KEY" &&
        !key.equals("null", ignoreCase = true)
}

/**
 * Interactive Marib / Al-Wadi District map supporting native Google Maps Compose
 * (when MAPS_API_KEY is configured and Play Services are available)
 * and a crash-safe offline-capable vector map mode.
 */
@Composable
fun MaribInteractiveMap(
    plantLocation: PlantLocation,
    geofenceMeters: Int,
    customers: List<Customer> = emptyList(),
    truck: Truck? = null,
    trucks: List<Truck> = emptyList(),
    selectedTruckId: String? = null,
    onTruckSelected: ((String) -> Unit)? = null,
    showCustomers: Boolean = true,
    focusLatLng: Pair<Double, Double>? = null,
    plannedStops: List<TripCustomerStop> = emptyList(),
    gpsPoints: List<TripGPSPoint> = emptyList(),
    detectedStops: List<DetectedStop> = emptyList(),
    pickedPoint: Pair<Double, Double>? = null,
    onMapTapPick: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasMapsKey = remember { isGoogleMapsApiKeyConfigured() }
    val missingKeyHint = stringResource(R.string.maps_key_missing_hint)
    var useGoogleMaps by remember(hasMapsKey) { mutableStateOf(hasMapsKey) }
    var unavailableReason by remember { mutableStateOf<String?>(null) }
    var dismissKeyBanner by remember { mutableStateOf(false) }

    val resolvedTrucks = remember(truck, trucks) {
        if (trucks.isNotEmpty()) trucks else listOfNotNull(truck)
    }

    Box(modifier = modifier.clipToBounds()) {
        if (useGoogleMaps && hasMapsKey) {
            GoogleMaribMap(
                plantLocation = plantLocation,
                geofenceMeters = geofenceMeters,
                customers = customers,
                resolvedTrucks = resolvedTrucks,
                selectedTruckId = selectedTruckId,
                onTruckSelected = onTruckSelected,
                showCustomers = showCustomers,
                focusLatLng = focusLatLng,
                plannedStops = plannedStops,
                gpsPoints = gpsPoints,
                detectedStops = detectedStops,
                pickedPoint = pickedPoint,
                onMapTapPick = onMapTapPick,
                onMapsUnavailable = { reason ->
                    unavailableReason = reason
                    dismissKeyBanner = false
                    useGoogleMaps = false
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            OfflineVectorMaribMap(
                plantLocation = plantLocation,
                geofenceMeters = geofenceMeters,
                customers = customers,
                resolvedTrucks = resolvedTrucks,
                selectedTruckId = selectedTruckId,
                onTruckSelected = onTruckSelected,
                showCustomers = showCustomers,
                focusLatLng = focusLatLng,
                plannedStops = plannedStops,
                gpsPoints = gpsPoints,
                detectedStops = detectedStops,
                pickedPoint = pickedPoint,
                onMapTapPick = onMapTapPick,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top-Start Map Engine Switcher & Secrets Key Hint
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = useGoogleMaps,
                        onClick = {
                            if (!hasMapsKey) {
                                dismissKeyBanner = false
                                unavailableReason = missingKeyHint
                                useGoogleMaps = false
                            } else {
                                unavailableReason = null
                                useGoogleMaps = true
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(stringResource(R.string.map_mode_google), style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.testTag("switch_google_map_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF0EA5E9),
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                            labelColor = Color(0xFFE2E8F0)
                        )
                    )
                    FilterChip(
                        selected = !useGoogleMaps,
                        onClick = { useGoogleMaps = false },
                        leadingIcon = {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(stringResource(R.string.map_mode_vector), style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.testTag("switch_vector_map_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF10B981),
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                            labelColor = Color(0xFFE2E8F0)
                        )
                    )
                }
            }

            val bannerText = unavailableReason
                ?: if (!hasMapsKey && !dismissKeyBanner) stringResource(R.string.maps_key_missing_hint) else null

            if (bannerText != null) {
                Surface(
                    color = Color(0xFF1E293B).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBAE6FD),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = {
                                dismissKeyBanner = true
                                unavailableReason = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إخفاء التنبيه",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Legend Strip
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            color = Color(0xFF0F172A).copy(alpha = 0.88f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot("المصنع", Color(0xFF38BDF8))
                if (showCustomers && customers.isNotEmpty()) {
                    LegendDot("عملاء (${customers.size})", Color(0xFF10B981))
                }
                LegendDot("في رحلة (${resolvedTrucks.count { it.status == TruckStatus.ON_TRIP }})", Color(0xFF0EA5E9))
                LegendDot("تعبئة (${resolvedTrucks.count { it.status == TruckStatus.LOADING }})", Color(0xFFF59E0B))
                LegendDot("متاح (${resolvedTrucks.count { it.status == TruckStatus.IDLE }})", Color(0xFF10B981))
                if (resolvedTrucks.any { it.status == TruckStatus.MAINTENANCE }) {
                    LegendDot("صيانة", Color(0xFFF43F5E))
                }
                if (gpsPoints.isNotEmpty()) LegendDot("مسار GPS", Color(0xFF22C55E))
            }
        }
    }
}

@Composable
private fun OfflineVectorMaribMap(
    plantLocation: PlantLocation,
    geofenceMeters: Int,
    customers: List<Customer>,
    resolvedTrucks: List<Truck>,
    selectedTruckId: String?,
    onTruckSelected: ((String) -> Unit)?,
    showCustomers: Boolean,
    focusLatLng: Pair<Double, Double>?,
    plannedStops: List<TripCustomerStop>,
    gpsPoints: List<TripGPSPoint>,
    detectedStops: List<DetectedStop>,
    pickedPoint: Pair<Double, Double>?,
    onMapTapPick: ((Double, Double) -> Unit)?,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()

    val minLat = 15.4480
    val maxLat = 15.5120
    val minLng = 45.3350
    val maxLng = 45.4220

    LaunchedEffect(focusLatLng, canvasSize) {
        val focus = focusLatLng
        if (focus != null && isValidLatLng(focus.first, focus.second) && canvasSize.width >= 40 && canvasSize.height >= 40) {
            val w = canvasSize.width.toFloat()
            val h = canvasSize.height.toFloat()
            val center = Offset(w / 2f, h / 2f)
            val nx = ((focus.second - minLng) / (maxLng - minLng)).toFloat() * w
            val ny = ((maxLat - focus.first) / (maxLat - minLat)).toFloat() * h
            val targetZoom = (if (zoom.isFinite()) zoom else 1f).coerceIn(1.35f, 4.5f)
            val targetX = -((nx - center.x) * targetZoom)
            val targetY = -((ny - center.y) * targetZoom)
            if (targetX.isFinite() && targetY.isFinite()) {
                val maxPan = 2500f * targetZoom
                zoom = targetZoom
                panOffset = Offset(
                    x = targetX.coerceIn(-maxPan, maxPan),
                    y = targetY.coerceIn(-maxPan, maxPan)
                )
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0F172A))
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val curZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
                    val safeGestureZoom = if (gestureZoom.isFinite() && gestureZoom > 0f) gestureZoom else 1f
                    zoom = (curZoom * safeGestureZoom).coerceIn(0.75f, 4.5f)
                    val safePanX = if (pan.x.isFinite()) pan.x else 0f
                    val safePanY = if (pan.y.isFinite()) pan.y else 0f
                    val baseX = if (panOffset.x.isFinite()) panOffset.x else 0f
                    val baseY = if (panOffset.y.isFinite()) panOffset.y else 0f
                    val maxPan = 2500f * zoom
                    panOffset = Offset(
                        x = (baseX + safePanX).coerceIn(-maxPan, maxPan),
                        y = (baseY + safePanY).coerceIn(-maxPan, maxPan)
                    )
                }
            }
            .pointerInput(onMapTapPick, onTruckSelected, resolvedTrucks, zoom, panOffset) {
                if (onMapTapPick != null || onTruckSelected != null) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f || !tap.x.isFinite() || !tap.y.isFinite()) return@detectTapGestures
                        val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
                        val safePanX = if (panOffset.x.isFinite()) panOffset.x else 0f
                        val safePanY = if (panOffset.y.isFinite()) panOffset.y else 0f
                        val center = Offset(w / 2f, h / 2f)
                        if (onTruckSelected != null && resolvedTrucks.isNotEmpty()) {
                            var closestId: String? = null
                            var bestDist = 52f
                            resolvedTrucks.forEach { t ->
                                if (isValidLatLng(t.lastLat, t.lastLng)) {
                                    val nx = ((t.lastLng - minLng) / (maxLng - minLng)).toFloat() * w
                                    val ny = ((maxLat - t.lastLat) / (maxLat - minLat)).toFloat() * h
                                    val screenX = (nx - center.x) * safeZoom + center.x + safePanX
                                    val screenY = (ny - center.y) * safeZoom + center.y + safePanY
                                    val d = hypot((tap.x - screenX).toDouble(), (tap.y - screenY).toDouble()).toFloat()
                                    if (d.isFinite() && d < bestDist) {
                                        bestDist = d
                                        closestId = t.id
                                    }
                                }
                            }
                            if (closestId != null) {
                                onTruckSelected(closestId!!)
                                return@detectTapGestures
                            }
                        }
                        if (onMapTapPick != null) {
                            val worldX = (tap.x - center.x - safePanX) / safeZoom + center.x
                            val worldY = (tap.y - center.y - safePanY) / safeZoom + center.y
                            val lng = minLng + (worldX / w) * (maxLng - minLng)
                            val lat = maxLat - (worldY / h) * (maxLat - minLat)
                            if (lat.isFinite() && lng.isFinite()) {
                                val clampedLat = ((lat.coerceIn(15.35, 15.65)) * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                                val clampedLng = ((lng.coerceIn(45.20, 45.55)) * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                                onMapTapPick(clampedLat, clampedLng)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w < 40f || h < 40f) return@Canvas
            val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
            val safePanX = if (panOffset.x.isFinite()) panOffset.x else 0f
            val safePanY = if (panOffset.y.isFinite()) panOffset.y else 0f
            val center = Offset(w / 2f, h / 2f)

            fun toScreen(lat: Double, lng: Double): Offset? {
                if (!isValidLatLng(lat, lng)) return null
                val nx = ((lng - minLng) / (maxLng - minLng)).toFloat() * w
                val ny = ((maxLat - lat) / (maxLat - minLat)).toFloat() * h
                val sx = (nx - center.x) * safeZoom + center.x + safePanX
                val sy = (ny - center.y) * safeZoom + center.y + safePanY
                return if (sx.isFinite() && sy.isFinite()) Offset(sx, sy) else null
            }

            fun drawBoundedLabel(text: String, topLeft: Offset, style: TextStyle) {
                if (text.isEmpty() || !topLeft.x.isFinite() || !topLeft.y.isFinite()) return
                if (topLeft.x !in -160f..(w + 60f) || topLeft.y !in -60f..(h + 40f)) return
                runCatching {
                    val layout = textMeasurer.measure(
                        text = text,
                        style = style,
                        softWrap = false,
                        maxLines = 1
                    )
                    drawText(textLayoutResult = layout, topLeft = topLeft)
                }
            }

            val gridColor = Color(0xFF1E293B)
            for (i in 1..10) {
                val x = (w / 10f) * i
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                val y = (h / 10f) * i
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            val hwyStart = toScreen(15.4730, 45.3420)
            val hwyMid = toScreen(15.4920, 45.3620)
            val hwyEnd = toScreen(15.4860, 45.4150)
            if (hwyStart != null && hwyMid != null && hwyEnd != null) {
                drawLine(Color(0xFF334155), hwyStart, hwyMid, strokeWidth = 4f * safeZoom)
                drawLine(Color(0xFF334155), hwyMid, hwyEnd, strokeWidth = 4f * safeZoom)
            }

            AlWadiDistricts.districts.forEach { d ->
                val p = toScreen(d.lat, d.lng) ?: return@forEach
                if (p.x in -60f..(w + 60f) && p.y in -40f..(h + 40f)) {
                    drawCircle(Color(0xFF475569), radius = 3.5f * safeZoom.coerceAtMost(2f), center = p)
                    if (safeZoom >= 0.9f) {
                        drawBoundedLabel(
                            text = d.name,
                            topLeft = Offset(p.x + 6f, p.y - 16f),
                            style = TextStyle(color = Color(0xFF94A3B8), fontSize = 10.sp)
                        )
                    }
                }
            }

            val safePlantLat = if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lat else 15.4920
            val safePlantLng = if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lng else 45.3620
            val plantPt = toScreen(safePlantLat, safePlantLng) ?: center
            val metersToPx = (w / ((maxLng - minLng).toFloat() * 107_000f)) * safeZoom
            val geofencePx = (geofenceMeters.coerceAtLeast(10) * metersToPx).coerceIn(18f, 160f)
            drawCircle(color = Color(0xFF0EA5E9).copy(alpha = 0.16f), radius = geofencePx, center = plantPt)
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = geofencePx,
                center = plantPt,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            )
            drawCircle(Color(0xFF0284C7), radius = 10f, center = plantPt)
            drawCircle(Color.White, radius = 4f, center = plantPt)
            drawBoundedLabel(
                text = "مصنع مياهنا",
                topLeft = Offset(plantPt.x - 30f, plantPt.y + 12f),
                style = TextStyle(color = Color(0xFF7DD3FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            )

            if (showCustomers) {
                customers.forEach { c ->
                    val cp = toScreen(c.lat, c.lng) ?: return@forEach
                    drawCircle(Color(0xFF10B981).copy(alpha = 0.85f), radius = 6f, center = cp)
                    drawCircle(Color.White, radius = 2.2f, center = cp)
                }
            }

            val validStopsPts = plannedStops.mapNotNull { s -> toScreen(s.lat, s.lng)?.let { s to it } }
            if (validStopsPts.isNotEmpty()) {
                val routePath = Path().apply {
                    moveTo(plantPt.x, plantPt.y)
                    validStopsPts.forEach { (_, sp) -> lineTo(sp.x, sp.y) }
                    lineTo(plantPt.x, plantPt.y)
                }
                drawPath(
                    path = routePath,
                    color = Color(0xFF38BDF8).copy(alpha = 0.65f),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                )
                validStopsPts.forEach { (s, sp) ->
                    val stopColor = if (s.status.wire == "delivered") Color(0xFF10B981) else Color(0xFFF59E0B)
                    drawCircle(stopColor, radius = 11f, center = sp)
                    drawBoundedLabel(
                        text = s.stopSequence.toString(),
                        topLeft = Offset(sp.x - 4f, sp.y - 8f),
                        style = TextStyle(color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }

            val validGpsScreenPts = gpsPoints.mapNotNull { pt -> toScreen(pt.lat, pt.lng) }
            if (validGpsScreenPts.size >= 2) {
                val gpsPath = Path().apply {
                    val first = validGpsScreenPts.first()
                    moveTo(first.x, first.y)
                    validGpsScreenPts.drop(1).forEach { p -> lineTo(p.x, p.y) }
                }
                drawPath(
                    path = gpsPath,
                    color = Color(0xFF22C55E),
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                )
            }

            detectedStops.forEach { stop ->
                val dp = toScreen(stop.lat, stop.lng) ?: return@forEach
                val c = if (stop.isUnplannedStop) Color(0xFFF43F5E) else Color(0xFF34D399)
                drawCircle(c.copy(alpha = 0.28f), radius = 16f, center = dp)
                drawCircle(c, radius = 6f, center = dp)
            }

            resolvedTrucks.forEach { t ->
                val tp = toScreen(t.lastLat, t.lastLng) ?: return@forEach
                val markerColor = when (t.status) {
                    TruckStatus.ON_TRIP -> Color(0xFF0EA5E9)
                    TruckStatus.LOADING -> Color(0xFFF59E0B)
                    TruckStatus.IDLE -> Color(0xFF10B981)
                    TruckStatus.MAINTENANCE -> Color(0xFFF43F5E)
                }
                val isSelected = selectedTruckId != null && t.id == selectedTruckId
                drawCircle(
                    color = markerColor.copy(alpha = if (isSelected) 0.38f else 0.22f),
                    radius = if (isSelected) 24f else 17f,
                    center = tp
                )
                if (isSelected) {
                    drawCircle(color = Color.White, radius = 15f, center = tp, style = Stroke(width = 2.5f))
                }
                drawCircle(markerColor, radius = if (isSelected) 11f else 9f, center = tp)
                drawCircle(Color.White, radius = 3.5f, center = tp)

                val badgeLabel = if (t.lastSpeed.isFinite() && t.lastSpeed >= 1.0) {
                    "${t.code} (${t.lastSpeed.safeRoundToInt()} كم/س)"
                } else {
                    t.code
                }
                drawBoundedLabel(
                    text = badgeLabel,
                    topLeft = Offset(tp.x + 12f, tp.y - 10f),
                    style = TextStyle(
                        color = if (isSelected) Color.White else Color(0xFFE2E8F0),
                        fontSize = if (isSelected) 11.sp else 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }

            pickedPoint?.let { (plat, plng) ->
                val pp = toScreen(plat, plng) ?: return@let
                drawCircle(Color(0xFFFB7185).copy(alpha = 0.25f), radius = 20f, center = pp)
                drawCircle(Color(0xFFE11D48), radius = 9f, center = pp)
                drawCircle(Color.White, radius = 3.5f, center = pp)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = Color(0xFF1E293B).copy(alpha = 0.9f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column {
                    IconButton(
                        onClick = { zoom = (zoom * 1.25f).coerceAtMost(4.5f) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "تكبير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.75f) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "تصغير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            zoom = 1.0f
                            panOffset = Offset.Zero
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "إعادة التمركز للمصنع", tint = Color(0xFF38BDF8))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(8.dp)) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE2E8F0))
    }
}

@Composable
fun LocationPickerDialog(
    title: String,
    initialLat: Double,
    initialLng: Double,
    plantLocation: PlantLocation,
    geofenceMeters: Int,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    val safeInitialLat = if (isValidLatLng(initialLat, initialLng)) initialLat
    else if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lat else 15.4920
    val safeInitialLng = if (isValidLatLng(initialLat, initialLng)) initialLng
    else if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lng else 45.3620
    var selectedLatLng by remember(safeInitialLat, safeInitialLng) {
        mutableStateOf(safeInitialLat to safeInitialLng)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }
                Text(
                    text = "اضغط على أي نقطة داخل خريطة مأرب/الوادي لتحديد الإحداثيات:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                MaribInteractiveMap(
                    plantLocation = plantLocation,
                    geofenceMeters = geofenceMeters,
                    focusLatLng = selectedLatLng,
                    pickedPoint = selectedLatLng,
                    onMapTapPick = { lat, lng -> selectedLatLng = lat to lng },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "الإحداثيات المختارة: ${formatLatLng(selectedLatLng.first, selectedLatLng.second, decimals = 6)}",
                    style = NumericTextStyle.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("إلغاء")
                    }
                    Button(
                        onClick = { onConfirm(selectedLatLng.first, selectedLatLng.second) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("confirm_location_picker_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اعتماد الموقع")
                    }
                }
            }
        }
    }
}

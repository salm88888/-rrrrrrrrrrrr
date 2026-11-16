package com.miyahna.water.ui.common

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.miyahna.water.R
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun isValidCoordinate(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

private object GoogleMapsRuntimeCache {
    private val initMutex = Mutex()
    @Volatile
    private var cachedReady: Boolean? = null
    @Volatile
    private var cachedUnavailableReason: String? = null

    suspend fun ensureReady(context: android.content.Context): Pair<Boolean, String?> {
        cachedReady?.let { return it to cachedUnavailableReason }
        return initMutex.withLock {
            cachedReady?.let { return@withLock it to cachedUnavailableReason }

            if (!isGoogleMapsApiKeyConfigured()) {
                val msg = context.getString(R.string.maps_key_missing_hint)
                cachedReady = false
                cachedUnavailableReason = msg
                return@withLock false to msg
            }

            val playStatus = runCatching {
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            }.getOrDefault(ConnectionResult.SERVICE_MISSING)

            if (playStatus != ConnectionResult.SUCCESS) {
                val msg = "خدمات Google Play غير متوفرة حالياً — تم التبديل للخريطة المحلية."
                Log.w("GoogleMaribMap", "Google Play Services unavailable (code=$playStatus); using offline vector map")
                cachedReady = false
                cachedUnavailableReason = msg
                return@withLock false to msg
            }

            val appCtx = context.applicationContext ?: context
            val initCode = runCatching {
                MapsInitializer.initialize(appCtx)
            }.getOrElse { err ->
                Log.e("GoogleMaribMap", "MapsInitializer failed: ${err.message}", err)
                ConnectionResult.INTERNAL_ERROR
            }

            if (initCode != ConnectionResult.SUCCESS) {
                val msg = "تعذر تهيئة خرائط Google على هذا الجهاز — تم التبديل للخريطة المحلية."
                Log.w("GoogleMaribMap", "MapsInitializer returned code=$initCode; using offline vector map")
                cachedReady = false
                cachedUnavailableReason = msg
                return@withLock false to msg
            }

            cachedReady = true
            cachedUnavailableReason = null
            true to null
        }
    }
}

/**
 * Native Google Maps Compose view plotting Marib / Al-Wadi fleet tankers,
 * registered customers, plant geofence, planned delivery sequence, live GPS breadcrumbs,
 * and tap-to-pick coordinate selection.
 */
@Composable
fun GoogleMaribMap(
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
    onMapsUnavailable: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var mapsInitialized by remember { mutableStateOf(false) }
    var isMapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val (ready, reason) = GoogleMapsRuntimeCache.ensureReady(context)
        if (ready) {
            mapsInitialized = true
        } else {
            onMapsUnavailable?.invoke(reason ?: context.getString(R.string.maps_key_missing_hint))
        }
    }

    val safePlantLat = if (isValidCoordinate(plantLocation.lat, plantLocation.lng)) plantLocation.lat else 15.4920
    val safePlantLng = if (isValidCoordinate(plantLocation.lat, plantLocation.lng)) plantLocation.lng else 45.3620
    val plantLatLng = remember(safePlantLat, safePlantLng) {
        LatLng(safePlantLat, safePlantLng)
    }

    val initialTarget = remember(focusLatLng, plantLatLng) {
        focusLatLng?.takeIf { isValidCoordinate(it.first, it.second) }
            ?.let { LatLng(it.first, it.second) }
            ?: plantLatLng
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialTarget, 13.2f)
    }

    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showLayerMenu by remember { mutableStateOf(false) }

    LaunchedEffect(focusLatLng, isMapLoaded) {
        val focus = focusLatLng
        if (isMapLoaded && focus != null && isValidCoordinate(focus.first, focus.second)) {
            val target = LatLng(focus.first, focus.second)
            try {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(target, 14.5f)
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("GoogleMaribMap", "Camera focus skipped: ${t.message}")
            }
        }
    }

    val mapProperties = remember(mapType) {
        MapProperties(
            mapType = mapType,
            isTrafficEnabled = false
        )
    }

    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            scrollGesturesEnabled = true,
            zoomGesturesEnabled = true,
            scrollGesturesEnabledDuringRotateOrZoom = true
        )
    }

    Box(modifier = modifier) {
        if (!mapsInitialized) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Box
        }

        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .testTag("google_map_canvas"),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = mapUiSettings,
            onMapLoaded = { isMapLoaded = true },
            onMapClick = { latLng ->
                if (onMapTapPick != null && isValidCoordinate(latLng.latitude, latLng.longitude)) {
                    val lat = (latLng.latitude * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                    val lng = (latLng.longitude * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                    onMapTapPick(lat, lng)
                }
            }
        ) {
            // Plant Geofence Circle
            Circle(
                center = plantLatLng,
                radius = geofenceMeters.coerceAtLeast(10).toDouble(),
                fillColor = Color(0x280EA5E9),
                strokeColor = Color(0xFF0284C7),
                strokeWidth = 3f
            )

            Marker(
                state = rememberSyncedMarkerState("plant", plantLatLng),
                title = plantLocation.name.ifBlank { "مصنع مياهنا" },
                snippet = "${plantLocation.city} • نطاق السياج ${geofenceMeters}م",
                icon = rememberSafeMarkerHue(isMapLoaded, BitmapDescriptorFactory.HUE_AZURE)
            )

            // Registered Customers
            if (showCustomers) {
                val validCustomers = remember(customers) {
                    customers.filter { isValidCoordinate(it.lat, it.lng) }.distinctBy { it.id }
                }
                validCustomers.forEachIndexed { idx, customer ->
                    key("cust_${customer.id}_$idx") {
                        val pos = LatLng(customer.lat, customer.lng)
                        Marker(
                            state = rememberSyncedMarkerState("cust_${customer.id}_$idx", pos),
                            title = customer.name,
                            snippet = "${customer.district} • ${customer.tankCapacityLiters} لتر",
                            icon = rememberSafeMarkerHue(isMapLoaded, BitmapDescriptorFactory.HUE_CYAN)
                        )
                    }
                }
            }

            // Planned Route & Stop Pins
            val validStops = remember(plannedStops) {
                plannedStops.filter { isValidCoordinate(it.lat, it.lng) }
            }
            if (validStops.isNotEmpty()) {
                val routePoints = buildList {
                    add(plantLatLng)
                    validStops.forEach { add(LatLng(it.lat, it.lng)) }
                    add(plantLatLng)
                }
                Polyline(
                    points = routePoints,
                    color = Color(0xFF38BDF8),
                    width = 7f,
                    pattern = listOf(Dash(22f), Gap(12f))
                )
                validStops.forEachIndexed { idx, stop ->
                    key("stop_${stop.orderId}_${stop.stopSequence}_$idx") {
                        val hue = if (stop.status.wire == "delivered") {
                            BitmapDescriptorFactory.HUE_GREEN
                        } else {
                            BitmapDescriptorFactory.HUE_YELLOW
                        }
                        Marker(
                            state = rememberSyncedMarkerState("stop_${stop.orderId}_${stop.stopSequence}_$idx", LatLng(stop.lat, stop.lng)),
                            title = "${stop.stopSequence}. ${stop.customerName}",
                            snippet = "${stop.district} • ${stop.requestedLiters} لتر",
                            icon = rememberSafeMarkerHue(isMapLoaded, hue)
                        )
                    }
                }
            }

            // Actual Recorded GPS Path
            val trackPoints = remember(gpsPoints) {
                gpsPoints.filter { isValidCoordinate(it.lat, it.lng) }.map { LatLng(it.lat, it.lng) }
            }
            if (trackPoints.size >= 2) {
                Polyline(
                    points = trackPoints,
                    color = Color(0xFF22C55E),
                    width = 10f
                )
            }

            // Prolonged / Unplanned Detected Stops
            val validDetectedStops = remember(detectedStops) {
                detectedStops.filter { isValidCoordinate(it.lat, it.lng) }
            }
            validDetectedStops.forEachIndexed { idx, stop ->
                key("det_${stop.id}_${stop.startTime}_$idx") {
                    val hue = if (stop.isUnplannedStop) {
                        BitmapDescriptorFactory.HUE_RED
                    } else {
                        BitmapDescriptorFactory.HUE_GREEN
                    }
                    val label = if (stop.isUnplannedStop) "توقف غير مخطط" else "نقطة توقف"
                    Marker(
                        state = rememberSyncedMarkerState("det_${stop.id}_${stop.startTime}_$idx", LatLng(stop.lat, stop.lng)),
                        title = label,
                        snippet = "المدة: ${stop.durationMinutes} دقيقة",
                        icon = rememberSafeMarkerHue(isMapLoaded, hue)
                    )
                }
            }

            // Fleet Tanker Markers
            val validTrucks = remember(resolvedTrucks) {
                resolvedTrucks.filter { isValidCoordinate(it.lastLat, it.lastLng) }.distinctBy { it.id }
            }
            validTrucks.forEachIndexed { idx, truck ->
                key("truck_${truck.id}_$idx") {
                    val truckPos = LatLng(truck.lastLat, truck.lastLng)
                    val hue = when (truck.status) {
                        TruckStatus.ON_TRIP -> BitmapDescriptorFactory.HUE_BLUE
                        TruckStatus.LOADING -> BitmapDescriptorFactory.HUE_ORANGE
                        TruckStatus.IDLE -> BitmapDescriptorFactory.HUE_GREEN
                        TruckStatus.MAINTENANCE -> BitmapDescriptorFactory.HUE_ROSE
                    }
                    val isSelected = selectedTruckId != null && truck.id == selectedTruckId
                    val speedText = if (truck.lastSpeed.isFinite() && truck.lastSpeed >= 1.0) {
                        " • ${truck.lastSpeed.safeRoundToInt()} كم/س"
                    } else ""
                    Marker(
                        state = rememberSyncedMarkerState("truck_${truck.id}_$idx", truckPos),
                        title = "${truck.code} (${truck.plateNumber})",
                        snippet = "${truck.driverName.ifBlank { "بدون سائق" }}$speedText • ${truck.currentWaterLiters} لتر",
                        alpha = if (selectedTruckId == null || isSelected) 1f else 0.78f,
                        icon = rememberSafeMarkerHue(isMapLoaded, hue),
                        onClick = {
                            onTruckSelected?.invoke(truck.id)
                            false
                        }
                    )
                }
            }

            // Picked Coordinate Marker
            pickedPoint?.takeIf { isValidCoordinate(it.first, it.second) }?.let { (plat, plng) ->
                Marker(
                    state = rememberSyncedMarkerState("picked_point", LatLng(plat, plng)),
                    title = "الموقع المحدد",
                    snippet = formatLatLng(plat, plng, 6),
                    icon = rememberSafeMarkerHue(isMapLoaded, BitmapDescriptorFactory.HUE_VIOLET)
                )
            }
        }

        // Map Type & Camera Zoom Controls (top-end)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column {
                    IconButton(
                        onClick = { showLayerMenu = !showLayerMenu },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("google_map_layers_button")
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "تبديل نوع الخريطة",
                            tint = Color(0xFF38BDF8)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isMapLoaded) {
                                coroutineScope.launch {
                                    runCatching { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "تكبير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (isMapLoaded) {
                                coroutineScope.launch {
                                    runCatching { cameraPositionState.animate(CameraUpdateFactory.zoomOut()) }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "تصغير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (isMapLoaded) {
                                coroutineScope.launch {
                                    runCatching {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(plantLatLng, 13.4f)
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "إعادة التمركز للمصنع",
                            tint = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            if (showLayerMenu) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.94f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            MapType.NORMAL to "عادي",
                            MapType.SATELLITE to "قمر صناعي",
                            MapType.HYBRID to "هجين"
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = mapType == type,
                                onClick = {
                                    mapType = type
                                    showLayerMenu = false
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF0EA5E9),
                                    selectedLabelColor = Color.White,
                                    labelColor = Color(0xFFE2E8F0)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSyncedMarkerState(key: Any?, position: LatLng): MarkerState {
    val state = remember(key) { MarkerState(position = position) }
    LaunchedEffect(position) {
        state.position = position
    }
    return state
}

@Composable
private fun rememberSafeMarkerHue(isMapLoaded: Boolean, hue: Float): BitmapDescriptor? {
    return remember(isMapLoaded, hue) {
        if (!isMapLoaded) null
        else runCatching { BitmapDescriptorFactory.defaultMarker(hue) }.getOrNull()
    }
}

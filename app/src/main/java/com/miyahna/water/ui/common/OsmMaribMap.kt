package com.miyahna.water.ui.common

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miyahna.water.R
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DetectedStop
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSGap
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.PlantLocation
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.Truck
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

enum class OsmTileLayer {
    STREETS,
    SATELLITE,
    TOPO
}

private object OsmRuntimeConfig {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext ?: context
            val conf = Configuration.getInstance()
            conf.userAgentValue = "${appCtx.packageName}/1.0"
            val baseDir = File(appCtx.cacheDir, "osmdroid").apply { mkdirs() }
            val tileCacheDir = File(baseDir, "tiles").apply { mkdirs() }
            conf.osmdroidBasePath = baseDir
            conf.osmdroidTileCache = tileCacheDir
            initialized = true
        }
    }
}

private val EsriWorldImageryTileSource = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    18,
    256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        return "${baseUrl}$z/$y/$x"
    }
}

private fun fitAllPointsOnMap(
    mapView: MapView,
    points: List<GeoPoint>,
    fallbackCenter: GeoPoint
) {
    val valid = points.filter { isValidLatLng(it.latitude, it.longitude) }
    if (valid.size <= 1) {
        val target = valid.firstOrNull() ?: fallbackCenter
        mapView.controller.animateTo(target, 14.2, 420L)
        return
    }
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLng = Double.MAX_VALUE
    var maxLng = -Double.MAX_VALUE
    valid.forEach { pt ->
        if (pt.latitude < minLat) minLat = pt.latitude
        if (pt.latitude > maxLat) maxLat = pt.latitude
        if (pt.longitude < minLng) minLng = pt.longitude
        if (pt.longitude > maxLng) maxLng = pt.longitude
    }
    if ((maxLat - minLat) < 0.002 && (maxLng - minLng) < 0.002) {
        val mid = GeoPoint((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)
        mapView.controller.animateTo(mid, 14.8, 420L)
        return
    }
    val box = BoundingBox(
        (maxLat + 0.004).coerceAtMost(85.0),
        (maxLng + 0.004).coerceAtMost(180.0),
        (minLat - 0.004).coerceAtLeast(-85.0),
        (minLng - 0.004).coerceAtLeast(-180.0)
    )
    mapView.post {
        runCatching { mapView.zoomToBoundingBox(box, true, 90, 17.0, 420L) }
    }
}

/**
 * Interactive real open-source OpenStreetMap (OSMDroid) view with:
 * - Multi-layer switcher (OSM Streets, Open Satellite Imagery, OpenTopoMap)
 * - Smooth pinch-zoom, double-tap, inertial pan, and quick focus controls
 * - Custom Factory icon at plantLocation, Tanker Truck icons at truck coordinates,
 *   and Customer Water Tank icons (Red when ordering, Gray in normal state).
 */
@Composable
fun OsmMaribMap(
    plantLocation: PlantLocation,
    geofenceMeters: Int,
    customers: List<Customer>,
    orders: List<Order>,
    resolvedTrucks: List<Truck>,
    selectedTruckId: String?,
    onTruckSelected: ((String) -> Unit)?,
    showCustomers: Boolean,
    focusLatLng: Pair<Double, Double>?,
    plannedStops: List<TripCustomerStop>,
    gpsPoints: List<TripGPSPoint>,
    detectedStops: List<DetectedStop>,
    gpsGaps: List<GPSGap> = emptyList(),
    gpsOffEvents: List<GPSEvent> = emptyList(),
    pickedPoint: Pair<Double, Double>?,
    onMapTapPick: ((Double, Double) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRobolectric = remember { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }

    remember(context) {
        OsmRuntimeConfig.ensureInitialized(context)
        true
    }

    var tileLayer by remember { mutableStateOf(OsmTileLayer.STREETS) }
    var showLayerMenu by remember { mutableStateOf(false) }

    val safePlantLat = if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lat else 15.4698538
    val safePlantLng = if (isValidLatLng(plantLocation.lat, plantLocation.lng)) plantLocation.lng else 45.3636985
    val plantGeo = remember(safePlantLat, safePlantLng) { GeoPoint(safePlantLat, safePlantLng) }

    val activeOrders = remember(orders) { MapMarkerIcons.activeOrdersFrom(orders) }
    val activeCustomerIds = remember(orders, plannedStops) {
        MapMarkerIcons.activeCustomerIds(orders, plannedStops)
    }

    val mapView = remember {
        MapView(context).apply {
            setUseDataConnection(!isRobolectric)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            setTileSource(TileSourceFactory.MAPNIK)
            val startTarget = focusLatLng?.takeIf { isValidLatLng(it.first, it.second) }
                ?.let { GeoPoint(it.first, it.second) }
                ?: plantGeo
            controller.setZoom(13.8)
            controller.setCenter(startTarget)
        }
    }

    // Intercept parent ScrollView/LazyColumn touch gestures while panning or zooming the map
    LaunchedEffect(mapView) {
        mapView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onDetach() }
        }
    }

    LaunchedEffect(tileLayer) {
        val source = when (tileLayer) {
            OsmTileLayer.STREETS -> TileSourceFactory.MAPNIK
            OsmTileLayer.SATELLITE -> EsriWorldImageryTileSource
            OsmTileLayer.TOPO -> TileSourceFactory.OpenTopo
        }
        mapView.setTileSource(source)
        mapView.invalidate()
    }

    LaunchedEffect(focusLatLng) {
        val focus = focusLatLng
        if (focus != null && isValidLatLng(focus.first, focus.second)) {
            mapView.controller.animateTo(GeoPoint(focus.first, focus.second), 15.0, 420L)
        }
    }

    val overlaySignature = remember(
        plantGeo,
        geofenceMeters,
        customers,
        activeCustomerIds,
        activeOrders,
        resolvedTrucks,
        selectedTruckId,
        showCustomers,
        plannedStops,
        gpsPoints,
        detectedStops,
        gpsGaps,
        gpsOffEvents,
        pickedPoint,
        onMapTapPick != null,
        onTruckSelected != null
    ) {
        listOf(
            plantGeo,
            geofenceMeters,
            customers.hashCode(),
            activeCustomerIds.hashCode(),
            activeOrders.hashCode(),
            resolvedTrucks.hashCode(),
            selectedTruckId,
            showCustomers,
            plannedStops.hashCode(),
            gpsPoints.hashCode(),
            detectedStops.hashCode(),
            gpsGaps.hashCode(),
            gpsOffEvents.hashCode(),
            pickedPoint,
            onMapTapPick != null,
            onTruckSelected != null
        )
    }

    val validTrucks = remember(resolvedTrucks) {
        resolvedTrucks.filter { isValidLatLng(it.lastLat, it.lastLng) }.distinctBy { it.id }
    }
    val focusedTruckGeo = remember(validTrucks, selectedTruckId) {
        val chosen = validTrucks.firstOrNull { it.id == selectedTruckId } ?: validTrucks.firstOrNull()
        chosen?.let { GeoPoint(it.lastLat, it.lastLng) }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            update = { view ->
                if (view.tag == overlaySignature) return@AndroidView
                view.tag = overlaySignature

                view.overlays.clear()

                // 1. Tap listener for coordinate picking without interfering with pan/zoom
                if (onMapTapPick != null) {
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p != null && isValidLatLng(p.latitude, p.longitude)) {
                                val lat = (p.latitude * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                                val lng = (p.longitude * 1_000_000.0).safeRoundToInt() / 1_000_000.0
                                onMapTapPick(lat, lng)
                                return true
                            }
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    })
                    view.overlays.add(eventsOverlay)
                }

                // 2. Plant geofence circle polygon
                val geofenceCircle = Polygon(view).apply {
                    points = Polygon.pointsAsCircle(plantGeo, geofenceMeters.coerceAtLeast(10).toDouble())
                    fillPaint.color = android.graphics.Color.argb(38, 14, 165, 233)
                    outlinePaint.color = android.graphics.Color.parseColor("#0284C7")
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                    infoWindow = null
                }
                view.overlays.add(geofenceCircle)

                // 3. Planned route polyline
                val validStops = plannedStops.filter { isValidLatLng(it.lat, it.lng) }
                if (validStops.isNotEmpty()) {
                    val routePoly = Polyline(view).apply {
                        setPoints(
                            buildList {
                                add(plantGeo)
                                validStops.forEach { add(GeoPoint(it.lat, it.lng)) }
                                add(plantGeo)
                            }
                        )
                        outlinePaint.color = android.graphics.Color.argb(190, 56, 189, 248)
                        outlinePaint.strokeWidth = 7f
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
                        infoWindow = null
                    }
                    view.overlays.add(routePoly)
                }

                // 4. Actual recorded GPS path polyline
                val trackPoints = gpsPoints
                    .filter { isValidLatLng(it.lat, it.lng) }
                    .map { GeoPoint(it.lat, it.lng) }
                if (trackPoints.size >= 2) {
                    val gpsPoly = Polyline(view).apply {
                        setPoints(trackPoints)
                        outlinePaint.color = android.graphics.Color.parseColor("#22C55E")
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        infoWindow = null
                    }
                    view.overlays.add(gpsPoly)
                }

                // 5. Customers: Gray Tank in normal state, Red Tank when customer has active order
                if (showCustomers) {
                    val validCustomers = customers.filter { isValidLatLng(it.lat, it.lng) }.distinctBy { it.id }
                    val (orderingCustomers, normalCustomers) = validCustomers.partition { cust ->
                        MapMarkerIcons.isCustomerOrdering(
                            customer = cust,
                            activeCustomerIds = activeCustomerIds,
                            activeOrders = activeOrders,
                            plannedStops = plannedStops
                        )
                    }
                    // Draw normal gray tanks first
                    normalCustomers.forEach { customer ->
                        val marker = Marker(view).apply {
                            position = GeoPoint(customer.lat, customer.lng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = MapMarkerIcons.customerTankDrawable(context, isOrdering = false)
                            title = customer.name
                            snippet = "${customer.district} • ${customer.tankCapacityLiters} لتر (وضع عادي)"
                        }
                        view.overlays.add(marker)
                    }
                    // Draw active-order red tanks above gray tanks
                    orderingCustomers.forEach { customer ->
                        val activeOrder = activeOrders.firstOrNull {
                            it.customerId == customer.id ||
                                (customer.phone.isNotBlank() && it.phone.trim() == customer.phone.trim())
                        }
                        val marker = Marker(view).apply {
                            position = GeoPoint(customer.lat, customer.lng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = MapMarkerIcons.customerTankDrawable(context, isOrdering = true)
                            title = "${customer.name} (لديه طلب)"
                            snippet = activeOrder?.let {
                                "${it.orderNumber} • ${it.requestedLiters} لتر • ${it.status.labelAr}"
                            } ?: "${customer.district} • طلب نشط"
                        }
                        view.overlays.add(marker)
                    }
                }

                // 6. Planned stops markers
                validStops.forEach { stop ->
                    val delivered = stop.status.wire == "delivered"
                    val marker = Marker(view).apply {
                        position = GeoPoint(stop.lat, stop.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.stopPinDrawable(context, stop.stopSequence, delivered)
                        title = "${stop.stopSequence}. ${stop.customerName}"
                        snippet = "${stop.district} • ${stop.requestedLiters} لتر • ${stop.status.labelAr}"
                    }
                    view.overlays.add(marker)
                }

                // 7. Key GPS Timing & Speed Checkpoint Markers along the route
                val speedCheckpoints = MapMarkerIcons.sampleSpeedCheckpoints(gpsPoints)
                speedCheckpoints.forEach { pt ->
                    val speedVal = pt.speedKmH.safeRoundToInt()
                    val marker = Marker(view).apply {
                        position = GeoPoint(pt.lat, pt.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.speedCheckpointDrawable(context, speedVal)
                        title = "نقطة #${pt.seq} • ${safeShortTime(pt.timestamp)}"
                        snippet = "السرعة: $speedVal كم/س • ${formatLatLng(pt.lat, pt.lng, 5)}"
                    }
                    view.overlays.add(marker)
                }

                // 8. Detected Stops Markers (Location + Duration + Timing)
                detectedStops.filter { isValidLatLng(it.lat, it.lng) }.forEachIndexed { idx, stop ->
                    val stopTitle = when {
                        stop.isUnplannedStop -> "توقف غير مجدول #${idx + 1}"
                        !stop.nearCustomerName.isNullOrBlank() -> "توقف: ${stop.nearCustomerName}"
                        else -> "توقف في نطاق المصنع #${idx + 1}"
                    }
                    val endLabel = stop.endTime?.let { safeShortTime(it) } ?: "مستمر"
                    val marker = Marker(view).apply {
                        position = GeoPoint(stop.lat, stop.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.detectedStopDrawable(context, stop.isUnplannedStop)
                        title = stopTitle
                        snippet = "المدة: ${stop.durationMinutes} د · من ${safeShortTime(stop.startTime)} إلى $endLabel"
                    }
                    view.overlays.add(marker)
                }

                // 9. GPS Off / Interruption Segments & Note Markers
                gpsGaps.forEach { gap ->
                    val fromOk = gap.fromLat != null && gap.fromLng != null && isValidLatLng(gap.fromLat, gap.fromLng)
                    val toOk = gap.toLat != null && gap.toLng != null && isValidLatLng(gap.toLat, gap.toLng)
                    if (fromOk && toOk) {
                        val gapPoly = Polyline(view).apply {
                            setPoints(listOf(GeoPoint(gap.fromLat!!, gap.fromLng!!), GeoPoint(gap.toLat!!, gap.toLng!!)))
                            outlinePaint.color = android.graphics.Color.parseColor("#DC2626")
                            outlinePaint.strokeWidth = 6f
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
                            infoWindow = null
                        }
                        view.overlays.add(gapPoly)
                    }
                    val markerLat = if (fromOk) gap.fromLat else if (toOk) gap.toLat else null
                    val markerLng = if (fromOk) gap.fromLng else if (toOk) gap.toLng else null
                    if (markerLat != null && markerLng != null) {
                        val durLabel = if (gap.durationSeconds >= 60) {
                            "${(gap.durationSeconds / 60)} د"
                        } else {
                            "${gap.durationSeconds} ث"
                        }
                        val marker = Marker(view).apply {
                            position = GeoPoint(markerLat, markerLng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = MapMarkerIcons.gpsOffPinDrawable(context)
                            title = "إيقاف / انقطاع GPS"
                            snippet = "${MapMarkerIcons.gpsGapReasonAr(gap.reason)} · $durLabel · ${safeShortTime(gap.startTime)} - ${safeShortTime(gap.endTime)}"
                        }
                        view.overlays.add(marker)
                    }
                }
                gpsOffEvents.filter { isValidLatLng(it.lat, it.lng) }.forEach { ev ->
                    val marker = Marker(view).apply {
                        position = GeoPoint(ev.lat, ev.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.gpsOffPinDrawable(context)
                        title = ev.customerName ?: "ملاحظة إيقاف GPS"
                        snippet = "${ev.eventType.labelAr} · ${safeShortTime(ev.timestamp)}"
                    }
                    view.overlays.add(marker)
                }

                // 8. Factory Marker at plantLocation
                val plantMarker = Marker(view).apply {
                    position = plantGeo
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = MapMarkerIcons.factoryDrawable(context)
                    title = plantLocation.name.ifBlank { "مصنع مياهنا" }
                    snippet = "${plantLocation.city} • نطاق السياج ${geofenceMeters}م"
                }
                view.overlays.add(plantMarker)

                // 9. Fleet Tanker Truck Markers at each truck's GPS location
                validTrucks.forEach { t ->
                    val isSelected = selectedTruckId != null && t.id == selectedTruckId
                    val speedText = if (t.lastSpeed.isFinite() && t.lastSpeed >= 1.0) {
                        " • ${t.lastSpeed.safeRoundToInt()} كم/س"
                    } else ""
                    val marker = Marker(view).apply {
                        position = GeoPoint(t.lastLat, t.lastLng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.tankerTruckDrawable(context, t.status, isSelected)
                        title = "وايت ${t.code} (${t.plateNumber})"
                        snippet = "${t.driverName.ifBlank { "بدون سائق" }}$speedText • ${t.status.labelAr}"
                        setOnMarkerClickListener { m, _ ->
                            onTruckSelected?.invoke(t.id)
                            m.showInfoWindow()
                            true
                        }
                    }
                    view.overlays.add(marker)
                }

                // 10. Picked Coordinate Marker
                pickedPoint?.takeIf { isValidLatLng(it.first, it.second) }?.let { (plat, plng) ->
                    val pickedMarker = Marker(view).apply {
                        position = GeoPoint(plat, plng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = MapMarkerIcons.pickedPinDrawable(context)
                        title = "الموقع المحدد"
                        snippet = formatLatLng(plat, plng, 6)
                    }
                    view.overlays.add(pickedMarker)
                }

                view.invalidate()
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("osm_map_view")
        )

        // Top-End Camera & Navigation Toolbar + Side Layer Selector
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (showLayerMenu) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.94f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple(OsmTileLayer.STREETS, stringResource(R.string.map_style_streets), "osm_layer_streets"),
                            Triple(OsmTileLayer.SATELLITE, stringResource(R.string.map_style_satellite), "osm_layer_satellite"),
                            Triple(OsmTileLayer.TOPO, stringResource(R.string.map_style_topo), "osm_layer_topo")
                        ).forEach { (layer, label, tag) ->
                            FilterChip(
                                selected = tileLayer == layer,
                                onClick = {
                                    tileLayer = layer
                                    showLayerMenu = false
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag(tag),
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

            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.90f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column {
                    IconButton(
                        onClick = { showLayerMenu = !showLayerMenu },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("osm_layers_button")
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "تبديل نوع الخريطة",
                            tint = Color(0xFF38BDF8)
                        )
                    }
                    IconButton(
                        onClick = { runCatching { mapView.controller.zoomIn() } },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("osm_zoom_in_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "تكبير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = { runCatching { mapView.controller.zoomOut() } },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("osm_zoom_out_button")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "تصغير الخريطة", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val allPoints = buildList {
                                add(plantGeo)
                                validTrucks.forEach { add(GeoPoint(it.lastLat, it.lastLng)) }
                                if (showCustomers) {
                                    customers.filter { isValidLatLng(it.lat, it.lng) }
                                        .forEach { add(GeoPoint(it.lat, it.lng)) }
                                }
                                plannedStops.filter { isValidLatLng(it.lat, it.lng) }
                                    .forEach { add(GeoPoint(it.lat, it.lng)) }
                                gpsPoints.filter { isValidLatLng(it.lat, it.lng) }
                                    .forEach { add(GeoPoint(it.lat, it.lng)) }
                                detectedStops.filter { isValidLatLng(it.lat, it.lng) }
                                    .forEach { add(GeoPoint(it.lat, it.lng)) }
                                gpsGaps.forEach { gap ->
                                    if (gap.fromLat != null && gap.fromLng != null && isValidLatLng(gap.fromLat, gap.fromLng)) {
                                        add(GeoPoint(gap.fromLat, gap.fromLng))
                                    }
                                }
                            }
                            fitAllPointsOnMap(mapView, allPoints, plantGeo)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("osm_fit_all_button")
                    ) {
                        Icon(
                            Icons.Default.CenterFocusStrong,
                            contentDescription = stringResource(R.string.map_fit_all),
                            tint = Color(0xFF10B981)
                        )
                    }
                    if (focusedTruckGeo != null) {
                        IconButton(
                            onClick = {
                                runCatching { mapView.controller.animateTo(focusedTruckGeo, 15.5, 420L) }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("osm_focus_truck_button")
                        ) {
                            Icon(
                                Icons.Default.LocalShipping,
                                contentDescription = stringResource(R.string.map_focus_truck),
                                tint = Color(0xFFF59E0B)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            runCatching { mapView.controller.animateTo(plantGeo, 14.5, 420L) }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("osm_focus_plant_button")
                    ) {
                        Icon(
                            Icons.Default.Factory,
                            contentDescription = stringResource(R.string.map_focus_plant),
                            tint = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }
    }
}

package com.miyahna.water.ui.driver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.DriverTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.common.CustomerOrderLookupSection
import com.miyahna.water.ui.common.DistrictSelectField
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppScreen(
    uiState: MiyahnaUiState,
    hasLocationPermission: Boolean,
    isDeviceGpsEnabled: Boolean = true,
    onRequestLocationPermission: () -> Unit,
    onEnableDeviceGps: () -> Unit = {},
    onRequestCloudSignIn: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val domain = uiState.domain
    val session = domain.driverSession
    val gpsMandatoryReady = hasLocationPermission && isDeviceGpsEnabled

    if (gpsMandatoryReady && uiState.driverTab != DriverTab.TRIP) {
        BackHandler { viewModel.setDriverTab(DriverTab.TRIP) }
    }

    val activeTrip = domain.trips.firstOrNull {
        it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED
    }
    val readyTrip = domain.trips.firstOrNull { it.status == TripStatus.APPROVED }
    val displayTrip: Trip? = activeTrip ?: readyTrip

    val unreadDriverNotifs = domain.notifications.count { it.targetRole == "driver" && !it.isRead }
    var showNotifsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Column {
                        Text(
                            text = "السائق: ${session?.name ?: domain.truck.driverName}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        val pendingTotal = domain.pendingSyncCount + uiState.unsyncedGpsPointsCount
                        Text(
                            text = if (domain.isOnline) {
                                "متصل · ${domain.truck.plateNumber}"
                            } else {
                                "بدون نت (تسجيل محلي) · $pendingTotal معلق"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (domain.isOnline) EmeraldAccent else AmberWarning
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showNotifsDialog = true }) {
                        BadgedBox(
                            badge = {
                                if (unreadDriverNotifs > 0) {
                                    Badge { Text(unreadDriverNotifs.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "إشعارات السائق")
                        }
                    }
                    IconButton(onClick = { viewModel.logoutDriver() }) {
                        Icon(Icons.Default.Logout, contentDescription = "تسجيل خروج السائق")
                    }
                    IconButton(onClick = { viewModel.switchRolePortal() }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "تبديل الدور")
                    }
                }
            )
        },
        bottomBar = {
            if (gpsMandatoryReady) {
                NavigationBar {
                    DriverTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            DriverTab.TRIP -> Icons.Default.Directions
                            DriverTab.FIELD_ORDER -> Icons.AutoMirrored.Filled.ListAlt
                            DriverTab.SYNC -> Icons.Default.Sync
                        }
                        NavigationBarItem(
                            selected = uiState.driverTab == tab,
                            onClick = { viewModel.setDriverTab(tab) },
                            icon = { Icon(icon, contentDescription = tab.labelAr) },
                            label = { Text(tab.labelAr) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!gpsMandatoryReady) {
                DriverMandatoryGpsGate(
                    hasLocationPermission = hasLocationPermission,
                    isDeviceGpsEnabled = isDeviceGpsEnabled,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onEnableDeviceGps = onEnableDeviceGps
                )
            } else {
                when (uiState.driverTab) {
                    DriverTab.TRIP -> DriverActiveTripTab(
                        uiState = uiState,
                        trip = displayTrip,
                        hasLocationPermission = hasLocationPermission,
                        isDeviceGpsEnabled = isDeviceGpsEnabled,
                        onRequestLocationPermission = onRequestLocationPermission,
                        onEnableDeviceGps = onEnableDeviceGps,
                        viewModel = viewModel
                    )
                    DriverTab.FIELD_ORDER -> DriverOrdersTab(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    DriverTab.SYNC -> DriverSyncTab(
                        uiState = uiState,
                        onRequestCloudSignIn = onRequestCloudSignIn,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    if (showNotifsDialog) {
        val list = domain.notifications.filter { it.targetRole == "driver" }
        AlertDialog(
            onDismissRequest = { showNotifsDialog = false },
            title = { Text("تنبيهات وتوجيهات الإدارة") },
            text = {
                if (list.isEmpty()) {
                    Text("لا توجد إشعارات جديدة")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(list, key = { idx, n -> "${n.id}_$idx" }) { _, n ->
                            Column {
                                Text(n.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    n.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.markAllNotificationsRead("driver")
                        showNotifsDialog = false
                    }
                ) { Text("تمت القراءة") }
            },
            dismissButton = {
                TextButton(onClick = { showNotifsDialog = false }) { Text("إغلاق") }
            }
        )
    }
}

@Composable
private fun DriverMandatoryGpsGate(
    hasLocationPermission: Boolean,
    isDeviceGpsEnabled: Boolean,
    onRequestLocationPermission: () -> Unit,
    onEnableDeviceGps: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("driver_mandatory_gps_gate"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, RoseError)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.GpsOff,
                    contentDescription = null,
                    tint = RoseError,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = stringResource(R.string.driver_gps_gate_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.driver_gps_gate_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("1. صلاحية الموقع للتطبيق", style = MaterialTheme.typography.bodyMedium)
                    StatusPill(
                        label = if (hasLocationPermission) "مفعّلة" else "مطلوبة",
                        containerColor = if (hasLocationPermission) EmeraldAccent.copy(alpha = 0.2f) else RoseError.copy(alpha = 0.2f),
                        contentColor = if (hasLocationPermission) EmeraldAccent else RoseError
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("2. تشغيل GPS في الجهاز", style = MaterialTheme.typography.bodyMedium)
                    StatusPill(
                        label = if (isDeviceGpsEnabled) "مفعّل" else "متوقف",
                        containerColor = if (isDeviceGpsEnabled) EmeraldAccent.copy(alpha = 0.2f) else RoseError.copy(alpha = 0.2f),
                        contentColor = if (isDeviceGpsEnabled) EmeraldAccent else RoseError
                    )
                }

                if (!hasLocationPermission) {
                    Button(
                        onClick = onRequestLocationPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("grant_location_permission_button")
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.grant_permission))
                    }
                }

                if (!isDeviceGpsEnabled) {
                    Button(
                        onClick = onEnableDeviceGps,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("enable_device_gps_button")
                    ) {
                        Icon(Icons.Default.GpsFixed, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.enable_device_gps_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverActiveTripTab(
    uiState: MiyahnaUiState,
    trip: Trip?,
    hasLocationPermission: Boolean,
    isDeviceGpsEnabled: Boolean,
    onRequestLocationPermission: () -> Unit,
    onEnableDeviceGps: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val activeDirectives = uiState.domain.directives.filter { it.status != DirectiveStatus.COMPLETED }
    var showModDialog by remember { mutableStateOf(false) }
    var showDeclineDialog by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var pendingAcceptTripId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasLocationPermission, isDeviceGpsEnabled, pendingAcceptTripId) {
        val targetId = pendingAcceptTripId
        if (hasLocationPermission && isDeviceGpsEnabled && targetId != null) {
            pendingAcceptTripId = null
            viewModel.respondToTrip(targetId, true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("driver_bg_gps_status_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (uiState.isBackgroundGpsActive) EmeraldAccent else AmberWarning
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.isGpsPowerSaveActive) Icons.Default.BatterySaver else Icons.Default.GpsFixed,
                            contentDescription = null,
                            tint = if (uiState.isBackgroundGpsActive) EmeraldAccent else AmberWarning
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (uiState.isBackgroundGpsActive) {
                                    stringResource(R.string.bg_gps_active_title)
                                } else {
                                    stringResource(R.string.bg_gps_inactive_title)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = buildString {
                                    append("نقاط المسار: ${trip?.gpsPoints?.size ?: 0}")
                                    if (uiState.unsyncedGpsPointsCount > 0) {
                                        append(" · بانتظار النت: ${uiState.unsyncedGpsPointsCount}")
                                    }
                                },
                                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusPill(
                        label = if (uiState.isGpsPowerSaveActive) "توفير طاقة" else if (uiState.isBackgroundGpsActive) "يعمل دائماً" else "متوقف",
                        containerColor = if (uiState.isBackgroundGpsActive) EmeraldAccent.copy(alpha = 0.2f) else AmberWarning.copy(alpha = 0.2f),
                        contentColor = if (uiState.isBackgroundGpsActive) EmeraldAccent else AmberWarning
                    )
                }
            }
        }

        if (activeDirectives.isNotEmpty()) {
            itemsIndexed(activeDirectives, key = { idx, dir -> "${dir.id}_$idx" }) { _, dir ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, if (dir.priority == "urgent") RoseError else AmberWarning)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "توجيه إداري: ${dir.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFFBBF24)
                        )
                        Text(dir.message, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (dir.status == DirectiveStatus.SENT) {
                                OutlinedButton(
                                    onClick = { viewModel.updateDirectiveStatus(dir.id, DirectiveStatus.ACKNOWLEDGED) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text("تأكيد الاستلام")
                                }
                            }
                            Button(
                                onClick = { viewModel.updateDirectiveStatus(dir.id, DirectiveStatus.COMPLETED, "تم التنفيذ") },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text("تم التنفيذ")
                            }
                        }
                    }
                }
            }
        }

        if (trip == null) {
            val latestAutoClosedTrip = uiState.domain.trips.firstOrNull {
                it.returnLocation?.detectedAutomatically == true &&
                    (it.status == TripStatus.COMPLETED_PENDING_REVIEW ||
                        it.status == TripStatus.PENDING_SYNC ||
                        it.status == TripStatus.RETURNED_TO_PLANT ||
                        it.status == TripStatus.CLOSED)
            }
            if (latestAutoClosedTrip != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("driver_auto_closed_trip_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, EmeraldAccent)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent)
                                Text(
                                    text = "تم تسجيل العودة وإغلاق الرحلة ${latestAutoClosedTrip.tripNumber} تلقائياً",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldAccent
                                )
                            }
                            Text(
                                text = "تم رصد وصول الوايت داخل محيط المصنع (${latestAutoClosedTrip.returnLocation?.distanceFromPlantMeters ?: uiState.domain.settings.plantGeofenceMeters}م) وإغلاق الرحلة من واجهة السائق تلقائياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                EmptyStateCard(
                    icon = Icons.Default.Directions,
                    title = "لا توجد رحلة معتمدة حالياً",
                    subtitle = "عندما يعتمد المدير رحلة ستظهر هنا للموافقة وتشغيل التتبع التلقائي في الخلفية."
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(trip.tripNumber, style = NumericTextStyle, color = MaterialTheme.colorScheme.primary)
                            StatusPill(
                                trip.status.labelAr,
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "${formatNumber(trip.initialLoadLiters)} لتر · ${trip.customerStops.size} محطات · ${trip.estimatedDistanceKm} كم",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (trip.createdByRole == "manager" && trip.driverAcceptanceStatus == "pending") {
                            Text(
                                text = "عند الضغط على «موافق على الرحلة» يخرج التطبيق تلقائياً ويستمر تتبع المسار في الخلفية حتى بدون إنترنت.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (!hasLocationPermission) {
                                            pendingAcceptTripId = trip.id
                                            onRequestLocationPermission()
                                        } else if (!isDeviceGpsEnabled) {
                                            pendingAcceptTripId = trip.id
                                            onEnableDeviceGps()
                                        } else {
                                            viewModel.respondToTrip(trip.id, true)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 50.dp)
                                        .testTag("driver_accept_trip_button")
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("موافق على الرحلة")
                                }
                                OutlinedButton(
                                    onClick = { showDeclineDialog = true },
                                    modifier = Modifier.heightIn(min = 50.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = RoseError)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("اعتذار")
                                }
                            }
                        } else if (trip.status == TripStatus.APPROVED) {
                            Button(
                                onClick = { viewModel.startTrip(trip.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("driver_start_trip_button")
                            ) {
                                Text("بدء تنفيذ الرحلة")
                            }
                            OutlinedButton(
                                onClick = { showModDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("driver_request_mod_button")
                            ) {
                                Icon(Icons.Default.EditNote, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("طلب تعديل المسار")
                            }
                        } else if (trip.status == TripStatus.IN_PROGRESS) {
                            Button(
                                onClick = { showFinishDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("driver_finish_trip_button")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.driver_finish_trip_button))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showPauseDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("driver_pause_trip_button")
                                ) {
                                    Text("إيقاف مؤقت")
                                }
                                OutlinedButton(
                                    onClick = { showModDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("driver_request_mod_button")
                                ) {
                                    Icon(Icons.Default.EditNote, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تعديل المسار")
                                }
                            }
                        } else if (trip.status == TripStatus.PAUSED) {
                            Button(
                                onClick = { viewModel.resumeTrip(trip.id, "استئناف التوزيع", actorRole = "driver") },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("driver_resume_trip_button")
                            ) {
                                Text("استئناف تنفيذ الرحلة")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showFinishDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("driver_finish_trip_button")
                                ) {
                                    Text("إنهاء الرحلة")
                                }
                                OutlinedButton(
                                    onClick = { showModDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("driver_request_mod_button")
                                ) {
                                    Text("تعديل المسار")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "محطات التوصيل (${trip.customerStops.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(trip.customerStops, key = { idx, stop -> "${stop.orderId}_${stop.stopSequence}_$idx" }) { _, stop ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${stop.stopSequence}. ${stop.customerName}", style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                stop.status.labelAr,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            "${stop.district} ${if (stop.addressDetails.isNotBlank()) "- ${stop.addressDetails}" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "الكمية: ${formatNumber(stop.requestedLiters)} لتر · الهاتف: ${stop.phone}",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize)
                        )
                        OutlinedButton(
                            onClick = {
                                if (!hasLocationPermission) {
                                    onRequestLocationPermission()
                                } else if (!isDeviceGpsEnabled) {
                                    onEnableDeviceGps()
                                } else {
                                    viewModel.captureDeliveryLocation(
                                        tripId = trip.id,
                                        tripNumber = trip.tripNumber,
                                        orderId = stop.orderId,
                                        customerId = stop.customerId,
                                        customerName = stop.customerName,
                                        deliveredLiters = stop.requestedLiters,
                                        notes = "توثيق موقع التسليم للمحطة ${stop.stopSequence}"
                                    )
                                }
                            },
                            enabled = !uiState.isCapturingDeliveryLocation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 6.dp)
                                .testTag("capture_stop_gps_${stop.orderId}")
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.delivery_capture_button))
                        }
                    }
                }
            }
        }
    }

    if (showModDialog && trip != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showModDialog = false },
            title = { Text("طلب تعديل مسار ${trip.tripNumber}") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("سبب التعديل المطلوب (مثل تغيير ترتيب أو ظرف طريق)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.requestTripModification(trip.id, trip.customerStops, reason)
                    showModDialog = false
                }) { Text("إرسال للمدير") }
            },
            dismissButton = { TextButton(onClick = { showModDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showDeclineDialog && trip != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDeclineDialog = false },
            title = { Text("سبب الاعتذار عن الرحلة") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("السبب *") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.respondToTrip(trip.id, false, reason)
                        showDeclineDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError)
                ) { Text("إرسال الرفض") }
            },
            dismissButton = { TextButton(onClick = { showDeclineDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showFinishDialog && trip != null) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text(stringResource(R.string.driver_finish_trip_confirm_title)) },
            text = { Text(stringResource(R.string.driver_finish_trip_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.finishTripByDriver(trip.id)
                        showFinishDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    modifier = Modifier.testTag("confirm_finish_trip_button")
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showFinishDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showPauseDialog && trip != null) {
        var pauseReason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPauseDialog = false },
            title = { Text("إيقاف مؤقت للرحلة ${trip.tripNumber}") },
            text = {
                OutlinedTextField(
                    value = pauseReason,
                    onValueChange = { pauseReason = it },
                    label = { Text("السبب (مثلاً: استراحة، وقود، ظرف طريق)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.pauseTrip(
                            tripId = trip.id,
                            reason = pauseReason.ifBlank { "توقف مؤقت خلال الرحلة" },
                            actorRole = "driver"
                        )
                        showPauseDialog = false
                    },
                    modifier = Modifier.testTag("confirm_driver_pause_trip_button")
                ) { Text("تأكيد الإيقاف المؤقت") }
            },
            dismissButton = { TextButton(onClick = { showPauseDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverOrdersTab(
    uiState: MiyahnaUiState,
    viewModel: MiyahnaViewModel
) {
    val domain = uiState.domain
    val existingOrders = remember(domain.orders) {
        domain.orders
            .filter { it.status != OrderStatus.CANCELLED }
            .sortedWith(
                compareByDescending<Order> { it.isUrgent }
                    .thenByDescending { it.createdAt }
            )
    }
    var showSendOrderDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("driver_orders_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الطلبات الموجودة (${existingOrders.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "تعرض هذه الشاشة الطلبات الموجودة فقط ويمكنك إرسال طلب جديد حتى بدون إنترنت.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = { showSendOrderDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("driver_open_send_order_dialog")
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إرسال طلب جديد")
                    }
                }
            }
        }

        if (existingOrders.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    title = "لا توجد طلبات موجودة حالياً",
                    subtitle = "عند تسجيل أو وصول طلبات جديدة ستظهر هنا مباشرة."
                )
            }
        } else {
            itemsIndexed(existingOrders, key = { idx, ord -> "${ord.id}_$idx" }) { _, order ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("driver_existing_order_card_${order.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        if (order.isUrgent) RoseError else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = order.orderNumber,
                                style = NumericTextStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (order.isUrgent) {
                                    StatusPill(
                                        label = "عاجل",
                                        containerColor = RoseError.copy(alpha = 0.2f),
                                        contentColor = RoseError
                                    )
                                }
                                StatusPill(
                                    label = order.status.labelAr,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Text(
                            text = order.customerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${order.district}${if (order.addressDetails.isNotBlank()) " - ${order.addressDetails}" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "الكمية: ${formatNumber(order.requestedLiters)} لتر · الهاتف: ${order.phone.ifBlank { "غير محدد" }}",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize)
                        )
                    }
                }
            }
        }
    }

    if (showSendOrderDialog) {
        val settings = domain.settings
        val customers = domain.customers
        var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var district by remember { mutableStateOf(AlWadiDistricts.DEFAULT_DISTRICT) }
        var address by remember { mutableStateOf("") }
        var tankCapacityText by remember { mutableStateOf("2000") }
        var liters by remember { mutableStateOf("2000") }
        var urgent by remember { mutableStateOf(false) }
        var lat by remember { mutableStateOf(settings.plantLocation.lat + 0.004) }
        var lng by remember { mutableStateOf(settings.plantLocation.lng + 0.004) }
        var showPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSendOrderDialog = false },
            title = { Text("إرسال طلب جديد للمدير") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CustomerOrderLookupSection(
                        customers = customers,
                        selectedCustomer = selectedCustomer,
                        typedName = name,
                        typedPhone = phone,
                        onImportCustomer = { customer ->
                            selectedCustomer = customer
                            name = customer.name
                            phone = customer.phone
                            district = customer.district
                            address = customer.addressDetails
                            tankCapacityText = customer.tankCapacityLiters.toString()
                            liters = customer.tankCapacityLiters.toString()
                            lat = customer.lat
                            lng = customer.lng
                        },
                        onSwitchToNewCustomer = {
                            selectedCustomer = null
                        }
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { newName ->
                            name = newName
                            if (selectedCustomer != null && !selectedCustomer!!.name.equals(newName.trim(), ignoreCase = true)) {
                                selectedCustomer = null
                            }
                        },
                        label = { Text("اسم العميل *") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("driver_order_customer_name_input")
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("رقم الجوال") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("driver_order_phone_input")
                    )
                    DistrictSelectField(
                        selectedDistrict = district,
                        districts = uiState.mergedDistricts,
                        onDistrictSelected = { chosen ->
                            district = chosen
                            val (dLat, dLng) = AlWadiDistricts.coordinatesForDistrict(chosen)
                            lat = dLat
                            lng = dLng
                        },
                        onAddCustomDistrict = viewModel::addCustomDistrict
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("تفاصيل الموقع") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tankCapacityText,
                            onValueChange = { tankCapacityText = it.filter(Char::isDigit) },
                            label = { Text("سعة الخزان") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = liters,
                            onValueChange = { liters = it.filter(Char::isDigit) },
                            label = { Text("الكمية (لتر)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تثبيت الإحداثيات (${formatLatLng(lat, lng, 4)})")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = urgent, onCheckedChange = { urgent = it })
                        Text("طلب عاجل")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            val reqLiters = liters.toIntOrNull() ?: 2000
                            val cap = tankCapacityText.toIntOrNull()
                                ?: selectedCustomer?.tankCapacityLiters
                                ?: reqLiters
                            viewModel.addOrder(
                                existingCustomerId = selectedCustomer?.id,
                                customerName = name,
                                phone = phone,
                                district = district,
                                addressDetails = address,
                                lat = lat,
                                lng = lng,
                                tankCapacity = cap,
                                requestedLiters = reqLiters,
                                pricePerLiter = null,
                                source = OrderSource.DRIVER_MANUAL,
                                isUrgent = urgent,
                                notes = "طلب ميداني من السائق"
                            )
                            showSendOrderDialog = false
                        }
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.testTag("driver_submit_field_order")
                ) {
                    Text("إرسال الطلب")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendOrderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )

        if (showPicker) {
            LocationPickerDialog(
                title = "تحديد موقع العميل",
                initialLat = lat,
                initialLng = lng,
                plantLocation = settings.plantLocation,
                geofenceMeters = settings.plantGeofenceMeters,
                onDismiss = { showPicker = false },
                onConfirm = { pLat, pLng ->
                    lat = pLat
                    lng = pLng
                    showPicker = false
                }
            )
        }
    }
}

@Composable
private fun DriverSyncTab(
    uiState: MiyahnaUiState,
    onRequestCloudSignIn: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val domain = uiState.domain
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("المزامنة وحالة المسار", style = MaterialTheme.typography.titleMedium)
                        Icon(
                            imageVector = if (domain.isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (domain.isOnline) EmeraldAccent else AmberWarning
                        )
                    }
                    Text(
                        text = "نقاط المسار المحفوظة محلياً بانتظار الإنترنت: ${uiState.unsyncedGpsPointsCount}",
                        style = NumericTextStyle,
                        color = if (uiState.unsyncedGpsPointsCount > 0) AmberWarning else EmeraldAccent,
                        modifier = Modifier.testTag("driver_unsynced_gps_count")
                    )
                    Text(
                        text = "العمليات المحلية المعلقة: ${domain.pendingSyncCount}",
                        style = NumericTextStyle,
                        color = if (domain.pendingSyncCount > 0) AmberWarning else EmeraldAccent
                    )
                    Text(
                        text = "يستمر تسجيل خط السير والطلبات محلياً أثناء انقطاع الإنترنت وبعد الخروج من التطبيق، وتُرسل تلقائياً للمدير فور عودة الشبكة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "آخر مزامنة سحابية: ${domain.lastCloudSyncAt ?: "لم تتم بعد"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    domain.cloudSyncError?.let {
                        Text("ملاحظة: $it", color = RoseError, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = domain.isOnline && !domain.isCloudSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("driver_sync_now_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (domain.isCloudSyncing) "جاري المزامنة..." else "مزامنة وإرسال المسار الآن")
                    }
                    if (domain.cloudUser == null) {
                        OutlinedButton(
                            onClick = onRequestCloudSignIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.cloud_signin_google))
                        }
                    } else {
                        Text(
                            "الحساب السحابي: ${domain.cloudUser.email}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldAccent
                        )
                    }
                }
            }
        }
    }
}

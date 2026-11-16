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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.DriverTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.common.DistrictSelectField
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppScreen(
    uiState: MiyahnaUiState,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onRequestCloudSignIn: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val domain = uiState.domain
    val session = domain.driverSession
    if (uiState.driverTab != DriverTab.TRIP) {
        BackHandler { viewModel.setDriverTab(DriverTab.TRIP) }
    }

    val activeTrip = domain.trips.firstOrNull { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
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
                        Text("السائق: ${session?.name ?: domain.truck.driverName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (domain.isOnline) "متصل · ${domain.truck.plateNumber}" else "غير متصل (حفظ محلي) · ${domain.pendingSyncCount} معلق",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showNotifsDialog = true }) {
                        BadgedBox(badge = { if (unreadDriverNotifs > 0) Badge { Text(unreadDriverNotifs.toString()) } }) {
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
            NavigationBar {
                DriverTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        DriverTab.TRIP -> Icons.Default.Directions
                        DriverTab.GPS_FUEL -> Icons.Default.LocalGasStation
                        DriverTab.FIELD_ORDER -> Icons.Default.AddCircleOutline
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
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasLocationPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    border = BorderStroke(1.dp, AmberWarning)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.location_permission_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(stringResource(R.string.location_permission_rationale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Button(onClick = onRequestLocationPermission, modifier = Modifier.testTag("grant_location_permission_button")) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (uiState.driverTab) {
                    DriverTab.TRIP -> DriverActiveTripTab(
                        uiState = uiState,
                        trip = displayTrip,
                        hasLocationPermission = hasLocationPermission,
                        onRequestLocationPermission = onRequestLocationPermission,
                        viewModel = viewModel
                    )
                    DriverTab.GPS_FUEL -> DriverFuelAndDeliveryTab(
                        uiState = uiState,
                        trip = displayTrip,
                        hasLocationPermission = hasLocationPermission,
                        onRequestLocationPermission = onRequestLocationPermission,
                        viewModel = viewModel
                    )
                    DriverTab.FIELD_ORDER -> DriverFieldOrderTab(uiState, viewModel)
                    DriverTab.SYNC -> DriverSyncTab(uiState, onRequestCloudSignIn, viewModel)
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
                if (list.isEmpty()) Text("لا توجد إشعارات جديدة")
                else LazyColumn(modifier = Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list, key = { idx, n -> "${n.id}_$idx" }) { _, n ->
                        Column {
                            Text(n.title, style = MaterialTheme.typography.titleMedium)
                            Text(n.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.markAllNotificationsRead("driver"); showNotifsDialog = false }) { Text("تمت القراءة") }
            },
            dismissButton = { TextButton(onClick = { showNotifsDialog = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun DriverActiveTripTab(
    uiState: MiyahnaUiState,
    trip: Trip?,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val activeDirectives = uiState.domain.directives.filter { it.status != DirectiveStatus.COMPLETED }
    var showModDialog by remember { mutableStateOf(false) }
    var showDeclineDialog by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var pendingAcceptTripId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasLocationPermission, pendingAcceptTripId) {
        val targetId = pendingAcceptTripId
        if (hasLocationPermission && targetId != null) {
            pendingAcceptTripId = null
            viewModel.respondToTrip(targetId, true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activeDirectives.isNotEmpty()) {
            itemsIndexed(activeDirectives, key = { idx, dir -> "${dir.id}_$idx" }) { _, dir ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, if (dir.priority == "urgent") RoseError else AmberWarning)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("توجيه إداري: ${dir.title}", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFBBF24))
                        Text(dir.message, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (dir.status == DirectiveStatus.SENT) {
                                OutlinedButton(onClick = { viewModel.updateDirectiveStatus(dir.id, DirectiveStatus.ACKNOWLEDGED) }, modifier = Modifier.weight(1f)) {
                                    Text("تأكيد الاستلام")
                                }
                            }
                            Button(onClick = { viewModel.updateDirectiveStatus(dir.id, DirectiveStatus.COMPLETED, "تم التنفيذ") }, modifier = Modifier.weight(1f)) {
                                Text("تم التنفيذ")
                            }
                        }
                    }
                }
            }
        }

        if (trip == null) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.Directions,
                    title = "لا توجد رحلة معتمدة حالياً",
                    subtitle = "عندما يعتمد المدير رحلة ستظهر هنا محطات التوصيل ومعلومات الشحنة."
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(trip.tripNumber, style = NumericTextStyle, color = MaterialTheme.colorScheme.primary)
                            StatusPill(trip.status.labelAr, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                        }
                        Text("الحمولة المعتمدة: ${formatNumber(trip.initialLoadLiters)} لتر · المحطات: ${trip.customerStops.size}", style = MaterialTheme.typography.bodyMedium)
                        Text("المسافة المقدرة: ${trip.estimatedDistanceKm} كم", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (trip.createdByRole == "manager" && trip.driverAcceptanceStatus == "pending") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (!hasLocationPermission) {
                                            pendingAcceptTripId = trip.id
                                            onRequestLocationPermission()
                                        } else {
                                            viewModel.respondToTrip(trip.id, true)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("driver_accept_trip_button")
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("موافق على الرحلة")
                                }
                                OutlinedButton(onClick = { showDeclineDialog = true }, modifier = Modifier.weight(1f)) {
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
                                    .height(48.dp)
                                    .testTag("driver_start_trip_button")
                            ) {
                                Text("بدء تنفيذ الرحلة")
                            }
                        }

                        if (trip.status == TripStatus.IN_PROGRESS) {
                            OutlinedButton(
                                onClick = { showPauseDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("driver_pause_trip_button")
                            ) {
                                Text("إيقاف مؤقت للرحلة")
                            }
                        } else if (trip.status == TripStatus.PAUSED) {
                            Button(
                                onClick = { viewModel.resumeTrip(trip.id, "استئناف التوزيع", actorRole = "driver") },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("driver_resume_trip_button")
                            ) {
                                Text("استئناف تنفيذ الرحلة")
                            }
                        }

                        if (trip.status == TripStatus.APPROVED || trip.status == TripStatus.IN_PROGRESS || trip.status == TripStatus.PAUSED) {
                            OutlinedButton(
                                onClick = { showModDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("driver_request_mod_button")
                            ) {
                                Icon(Icons.Default.EditNote, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("طلب تعديل مسار الرحلة من المدير")
                            }
                        }

                        if (trip.status == TripStatus.IN_PROGRESS || trip.status == TripStatus.PAUSED) {
                            Button(
                                onClick = { showFinishDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("driver_finish_trip_button")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.driver_finish_trip_button))
                            }
                        }
                    }
                }
            }

            item { Text("خط سير المحطات المعتمد (للقراءة فقط)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            itemsIndexed(trip.customerStops, key = { idx, stop -> "${stop.orderId}_${stop.stopSequence}_$idx" }) { _, stop ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${stop.stopSequence}. ${stop.customerName}", style = MaterialTheme.typography.titleMedium)
                            StatusPill(stop.status.labelAr, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary)
                        }
                        Text("${stop.district} ${if (stop.addressDetails.isNotBlank()) "- ${stop.addressDetails}" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("الكمية: ${formatNumber(stop.requestedLiters)} لتر · الهاتف: ${stop.phone}", style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize))
                        OutlinedButton(
                            onClick = {
                                if (!hasLocationPermission) {
                                    onRequestLocationPermission()
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
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("سبب التعديل المطلوب (مثل تغيير ترتيب أو ظرف طريق)") }, modifier = Modifier.fillMaxWidth())
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
            text = { OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("السبب *") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(
                    onClick = { viewModel.respondToTrip(trip.id, false, reason); showDeclineDialog = false },
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

@Composable
private fun DriverFuelAndDeliveryTab(
    uiState: MiyahnaUiState,
    trip: Trip?,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    viewModel: MiyahnaViewModel
) {
    val domain = uiState.domain
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("توثيق مواقع التسليم الميداني", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "يمكنك توثيق موقع العميل عند تسليم المياه لحفظ الإحداثيات في سجل التوصيل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        if (!hasLocationPermission) {
                            onRequestLocationPermission()
                        } else {
                            viewModel.captureDeliveryLocation(
                                tripId = trip?.id,
                                tripNumber = trip?.tripNumber,
                                notes = "تسجيل موقع تسليم من الوايت"
                            )
                        }
                    },
                    enabled = !uiState.isCapturingDeliveryLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("capture_delivery_location_button")
                ) {
                    if (uiState.isCapturingDeliveryLocation) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.height(18.dp).width(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.delivery_capture_in_progress))
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.delivery_capture_button))
                    }
                }
            }
        }

        MaribInteractiveMap(
            plantLocation = domain.settings.plantLocation,
            geofenceMeters = domain.settings.plantGeofenceMeters,
            truck = null,
            plannedStops = trip?.customerStops.orEmpty(),
            gpsPoints = emptyList(),
            detectedStops = emptyList(),
            modifier = Modifier.fillMaxWidth().height(250.dp)
        )

        Text(
            text = "سجل مواقع التسليم المحفوظة (${uiState.deliveryLocations.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        if (uiState.deliveryLocations.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Default.LocationOn,
                title = "لا توجد مواقع تسليم مسجلة بعد",
                subtitle = "اضغط على زر تسجيل موقع التسليم لالتقاط إحداثيات GPS الحالية وحفظها في قاعدة البيانات"
            )
        } else {
            uiState.deliveryLocations.take(20).forEach { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delivery_location_card_${log.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = log.customerName?.takeIf { it.isNotBlank() } ?: "موقع تسليم ميداني",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatLatLng(log.latitude, log.longitude, decimals = 5) +
                                    (log.accuracyMeters?.takeIf { it.isFinite() }?.let { " (±${it.safeRoundToInt()} م)" } ?: ""),
                                style = NumericTextStyle,
                                color = EmeraldAccent
                            )
                            val meta = buildString {
                                if (!log.tripNumber.isNullOrBlank()) {
                                    append(log.tripNumber)
                                    append(" · ")
                                }
                                if (log.deliveredLiters != null) {
                                    append("${formatNumber(log.deliveredLiters)} لتر · ")
                                }
                                append(log.capturedAtIso.take(19).replace('T', ' '))
                            }
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.deleteDeliveryLocationLog(log.id) },
                            modifier = Modifier.testTag("delete_delivery_location_${log.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = RoseError
                            )
                        }
                    }
                }
            }
        }

        DriverWeeklyFuelReportCard(
            trips = domain.trips,
            settings = domain.settings,
            driverId = domain.driverSession?.id,
            driverName = domain.driverSession?.name ?: domain.truck.driverName
        )
    }
}

@Composable
private fun DriverFieldOrderTab(uiState: MiyahnaUiState, viewModel: MiyahnaViewModel) {
    val settings = uiState.domain.settings
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var district by remember { mutableStateOf(AlWadiDistricts.DEFAULT_DISTRICT) }
    var address by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("2000") }
    var urgent by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf(settings.plantLocation.lat + 0.004) }
    var lng by remember { mutableStateOf(settings.plantLocation.lng + 0.004) }
    var showPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("تسجيل طلب أو عميل ميداني (يعمل دون إنترنت)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم العميل *") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("رقم الجوال") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth()) }
        item {
            DistrictSelectField(
                selectedDistrict = district,
                districts = uiState.mergedDistricts,
                onDistrictSelected = { chosen ->
                    district = chosen
                    val (dLat, dLng) = AlWadiDistricts.coordinatesForDistrict(chosen)
                    lat = dLat; lng = dLng
                },
                onAddCustomDistrict = viewModel::addCustomDistrict
            )
        }
        item { OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("تفاصيل الموقع") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = liters, onValueChange = { liters = it.filter(Char::isDigit) }, label = { Text("الكمية المطلوبة (لتر)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Map, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("تثبيت الإحداثيات (${formatLatLng(lat, lng, 5)})")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = urgent, onCheckedChange = { urgent = it })
                Text("طلب عاجل")
            }
        }
        item {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        viewModel.addOrder(null, name, phone, district, address, lat, lng, liters.toIntOrNull() ?: 2000, liters.toIntOrNull() ?: 2000, null, OrderSource.DRIVER_MANUAL, urgent, "طلب ميداني من السائق")
                        name = ""; phone = ""; address = ""; urgent = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("driver_submit_field_order")
            ) { Text("حفظ وإرسال الطلب الميداني") }
        }
    }

    if (showPicker) {
        LocationPickerDialog(
            title = "تحديد موقع العميل الميداني",
            initialLat = lat,
            initialLng = lng,
            plantLocation = settings.plantLocation,
            geofenceMeters = settings.plantGeofenceMeters,
            onDismiss = { showPicker = false },
            onConfirm = { pLat, pLng -> lat = pLat; lng = pLng; showPicker = false }
        )
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
                    Text("المزامنة وحالة التخزين المحلي", style = MaterialTheme.typography.titleMedium)
                    Text("العمليات المحلية بانتظار المزامنة: ${domain.pendingSyncCount}", style = NumericTextStyle, color = if (domain.pendingSyncCount > 0) AmberWarning else EmeraldAccent)
                    Text("تتم المزامنة التلقائية مع الإدارة فور توفر الإنترنت حتى بعد إغلاق التطبيق.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("آخر مزامنة سحابية: ${domain.lastCloudSyncAt ?: "لم تتم بعد"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    domain.cloudSyncError?.let { Text("ملاحظة: $it", color = RoseError, style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = domain.isOnline && !domain.isCloudSyncing,
                        modifier = Modifier.fillMaxWidth().testTag("driver_sync_now_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (domain.isCloudSyncing) "جاري المزامنة..." else "مزامنة الآن")
                    }
                    if (domain.cloudUser == null) {
                        OutlinedButton(onClick = onRequestCloudSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cloud_signin_google))
                        }
                    } else {
                        Text("الحساب السحابي: ${domain.cloudUser.email}", style = MaterialTheme.typography.labelMedium, color = EmeraldAccent)
                    }
                }
            }
        }
    }
}

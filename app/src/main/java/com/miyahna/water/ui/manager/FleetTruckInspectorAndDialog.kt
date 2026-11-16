package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.common.safeShortTime
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun SelectedTruckTelemetryCard(
    truck: Truck,
    activeTrip: Trip?,
    recentGpsEvents: List<GPSEvent> = emptyList(),
    onCenterOnMap: () -> Unit,
    onUpdateStatus: (TruckStatus) -> Unit,
    onOpenTripsTab: () -> Unit
) {
    val accent = truckStatusColor(truck.status)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fleet_selected_truck_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.6f))
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
                        text = "تفاصيل الصهريج المحدد: ${truck.code}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${truck.plateNumber} · ${truck.driverName} ${if (truck.driverPhone.isNotBlank()) "(${truck.driverPhone})" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onCenterOnMap,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("fleet_center_selected_button")
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.fleet_center_on_truck))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMiniStat(
                    label = "السرعة الحية",
                    value = "${truck.lastSpeed.safeRoundToInt()} كم/س",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMiniStat(
                    label = "المتبقي بالخزان",
                    value = "${formatNumber(truck.currentWaterLiters)} لتر",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMiniStat(
                    label = "الإحداثيات",
                    value = formatLatLng(truck.lastLat, truck.lastLng, 4),
                    modifier = Modifier.weight(1.3f)
                )
            }

            if (activeTrip != null) {
                Surface(
                    color = SkyPrimary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "رحلة نشطة: ${activeTrip.tripNumber}",
                                style = NumericTextStyle.copy(fontWeight = FontWeight.Bold),
                                color = SkyPrimary
                            )
                            Text(
                                text = "${activeTrip.customerStops.size} محطات · المسار: ${activeTrip.gpsPoints.size} نقطة GPS · توقفات: ${activeTrip.detectedStops.size} · انقطاع: ${activeTrip.gpsGaps.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onOpenTripsTab,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("fleet_open_trip_details_button")
                        ) {
                            Text(stringResource(R.string.fleet_open_trips))
                        }
                    }
                }
            }

            if (recentGpsEvents.isNotEmpty()) {
                val latestEvents = recentGpsEvents.takeLast(4).reversed()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "آخر التنقلات والتوقفات المرصودة (${recentGpsEvents.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        latestEvents.forEach { ev ->
                            val timeShort = safeShortTime(ev.timestamp)
                            val targetNote = ev.customerName?.let { " ($it)" } ?: ""
                            Text(
                                text = "• ${ev.eventType.labelAr}$targetNote · $timeShort · ${formatLatLng(ev.lat, ev.lng, 4)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = "تغيير الحالة التشغيلية:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TruckStatus.entries.forEach { st ->
                    val stColor = truckStatusColor(st)
                    FilterChip(
                        selected = truck.status == st,
                        onClick = { onUpdateStatus(st) },
                        label = { Text(st.labelAr) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (st) {
                                    TruckStatus.ON_TRIP -> Icons.Default.Directions
                                    TruckStatus.LOADING -> Icons.Default.WaterDrop
                                    TruckStatus.IDLE -> Icons.Default.Factory
                                    TruckStatus.MAINTENANCE -> Icons.Default.Build
                                },
                                contentDescription = null,
                                tint = stColor,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("fleet_set_status_${st.wire}")
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AddFleetTruckDialog(
    suggestedCode: String,
    drivers: List<DriverAccount>,
    onDismiss: () -> Unit,
    onConfirm: (code: String, plateNumber: String, model: String, maxCapacityLiters: Int, driverId: String?) -> Unit
) {
    var code by remember { mutableStateOf(suggestedCode) }
    var plate by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("وايت مياه صالح للشرب 6000L") }
    var capacity by remember { mutableStateOf("6000") }
    val approvedDrivers = remember(drivers) { drivers.filter { it.status == DriverAccountStatus.APPROVED } }
    var selectedDriver by remember { mutableStateOf<DriverAccount?>(null) }
    var driverMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة صهريج جديد للأسطول") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("رمز الصهريج (مثال: TRUCK-02)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_truck_code_input")
                )
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it },
                    label = { Text("رقم اللوحة *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_truck_plate_input")
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("النوع والموديل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it.filter(Char::isDigit) },
                    label = { Text("السعة القصوى (لتر)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (approvedDrivers.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { driverMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("add_truck_driver_picker_button")
                        ) {
                            Text(
                                text = selectedDriver?.let { "السائق: ${it.name}" } ?: "تعيين السائق: بدون سائق معيّن",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = driverMenuExpanded,
                            onDismissRequest = { driverMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("بدون سائق معيّن") },
                                onClick = {
                                    selectedDriver = null
                                    driverMenuExpanded = false
                                }
                            )
                            approvedDrivers.forEach { drv ->
                                DropdownMenuItem(
                                    text = { Text("${drv.name} (${drv.phone})") },
                                    onClick = {
                                        selectedDriver = drv
                                        driverMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cap = capacity.toIntOrNull()?.coerceAtLeast(1000) ?: 6000
                    onConfirm(code.trim(), plate.trim(), model.trim(), cap, selectedDriver?.id)
                },
                enabled = code.isNotBlank() && plate.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_truck_button")
            ) {
                Text("إضافة الصهريج")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

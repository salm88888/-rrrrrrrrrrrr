package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miyahna.water.data.ActionResult
import com.miyahna.water.data.cloud.CloudUserInfo
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Truck
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun DriverAndTruckScreen(
    truck: Truck,
    fleetTrucks: List<Truck> = listOf(truck),
    drivers: List<DriverAccount>,
    onUpdateTruck: (Truck) -> Unit,
    onOpenFleetDashboard: () -> Unit = {},
    onApproveDriver: (String) -> Unit,
    onRejectDriver: (String, String) -> Unit,
    onToggleSuspendDriver: (String) -> Unit,
    cloudUser: CloudUserInfo? = null,
    onAddDriverByManager: ((
        name: String,
        username: String,
        phone: String,
        password: String,
        googleEmail: String?,
        licenseNumber: String?,
        onResult: (ActionResult<*>) -> Unit
    ) -> Unit)? = null,
    onUpdateDriverGoogleEmail: ((driverId: String, email: String) -> Unit)? = null
) {
    var plate by remember(truck.plateNumber) { mutableStateOf(truck.plateNumber) }
    var model by remember(truck.model) { mutableStateOf(truck.model) }
    var capacity by remember(truck.maxCapacityLiters) { mutableStateOf(truck.maxCapacityLiters.toString()) }
    var rejectTarget by remember { mutableStateOf<DriverAccount?>(null) }
    var showAddDriverDialog by remember { mutableStateOf(false) }
    var editEmailTarget by remember { mutableStateOf<DriverAccount?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = if (cloudUser != null) EmeraldAccent else AmberWarning)
                        Text("ربط جوالين عبر السحابة (المدير والسائق)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = if (cloudUser != null) {
                            "جوال المدير متصل بـ ${cloudUser.email}. للربط مع جوال السائق: إما استخدام نفس حساب Google، أو إضافة بريد Gmail الخاص بالسائق في حسابه أدناه ليتم فتح القاعدة المشتركة له تلقائياً."
                        } else {
                            "اربط حساب Google في جوال المدير ثم أضف السائق مع بريد Gmail الخاص به أو استخدم نفس الحساب في الجوالين للمزامنة الفورية."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onAddDriverByManager != null) {
                        Button(
                            onClick = { showAddDriverDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("add_driver_by_manager_button")
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إضافة سائق جديد وربط بريده فوراً")
                        }
                    }
                }
            }
        }

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
                        Text("إعدادات الصهريج الرئيسي (${fleetTrucks.size} بالأسطول)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onOpenFleetDashboard) {
                            Text("خريطة الأسطول")
                        }
                    }
                    OutlinedTextField(value = plate, onValueChange = { plate = it }, label = { Text("رقم اللوحة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("نوع وموديل الوايت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = capacity, onValueChange = { capacity = it.filter(Char::isDigit) }, label = { Text("السعة القصوى (لتر)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            onUpdateTruck(truck.copy(plateNumber = plate, model = model, maxCapacityLiters = capacity.toIntOrNull() ?: 6000))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("حفظ بيانات الوايت") }
                }
            }
        }

        item { Text("حسابات السائقين وطلبات الاعتماد (${drivers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (drivers.isEmpty()) {
            item { EmptyStateCard(Icons.Default.People, "لا يوجد سائقون مسجلون", "أضف سائقاً مباشرة من الأعلى أو عند تسجيل سائق من بوابة السائقين سيظهر طلبه هنا") }
        } else {
            itemsIndexed(drivers, key = { idx, drv -> "${drv.id}_$idx" }) { _, drv ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(drv.name, style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = drv.status.labelAr,
                                containerColor = when (drv.status) {
                                    DriverAccountStatus.APPROVED -> EmeraldAccent.copy(alpha = 0.14f)
                                    DriverAccountStatus.PENDING -> AmberWarning.copy(alpha = 0.14f)
                                    else -> RoseError.copy(alpha = 0.14f)
                                },
                                contentColor = when (drv.status) {
                                    DriverAccountStatus.APPROVED -> EmeraldAccent
                                    DriverAccountStatus.PENDING -> AmberWarning
                                    else -> RoseError
                                }
                            )
                        }
                        Text("المستخدم: ${drv.username} · الهاتف: ${drv.phone}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (!drv.googleEmail.isNullOrBlank()) "بريد Google للمزامنة: ${drv.googleEmail}" else "بريد Google للمزامنة: غير مربوط بعد",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!drv.googleEmail.isNullOrBlank()) EmeraldAccent else AmberWarning
                        )
                        if (onUpdateDriverGoogleEmail != null) {
                            OutlinedButton(
                                onClick = { editEmailTarget = drv },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("إدارة بريد Google")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (drv.status == DriverAccountStatus.PENDING) {
                                Button(onClick = { onApproveDriver(drv.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("اعتماد السائق") }
                                OutlinedButton(onClick = { rejectTarget = drv }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("رفض") }
                            } else if (drv.status == DriverAccountStatus.APPROVED || drv.status == DriverAccountStatus.SUSPENDED) {
                                OutlinedButton(onClick = { onToggleSuspendDriver(drv.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (drv.status == DriverAccountStatus.SUSPENDED) "إعادة تفعيل الحساب" else "إيقاف مؤقت")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDriverDialog && onAddDriverByManager != null) {
        var name by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var googleEmail by remember { mutableStateOf("") }
        var license by remember { mutableStateOf("") }
        var errMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddDriverDialog = false },
            title = { Text("إضافة سائق معتمد وربط جواله") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it; errMsg = null }, label = { Text("اسم السائق") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it; errMsg = null }, label = { Text("اسم المستخدم للدخول") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it; errMsg = null }, label = { Text("رقم الجوال") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it; errMsg = null }, label = { Text("كلمة المرور (4 أحرف+)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = googleEmail, onValueChange = { googleEmail = it; errMsg = null }, label = { Text("بريد Google لجوال السائق (اختياري)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = license, onValueChange = { license = it }, label = { Text("رقم الرخصة (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (errMsg != null) {
                        Text(errMsg!!, color = RoseError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAddDriverByManager(
                        name,
                        username,
                        phone,
                        password,
                        googleEmail.ifBlank { null },
                        license.ifBlank { null }
                    ) { res ->
                        if (res.success) {
                            showAddDriverDialog = false
                        } else {
                            errMsg = res.error ?: "تعذر إضافة السائق"
                        }
                    }
                }) { Text("إضافة واعتماد") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDriverDialog = false }) { Text("إلغاء") }
            }
        )
    }

    editEmailTarget?.let { drv ->
        var emailInput by remember(drv.id) { mutableStateOf(drv.googleEmail.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editEmailTarget = null },
            title = { Text("إدارة بريد Google لـ ${drv.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "أدخل بريد Gmail الذي سيفتح به السائق تطبيق مياهنا على جواله للوصول إلى القاعدة المشتركة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("driver@gmail.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateDriverGoogleEmail?.invoke(drv.id, emailInput)
                    editEmailTarget = null
                }) { Text("حفظ البريد") }
            },
            dismissButton = {
                TextButton(onClick = { editEmailTarget = null }) { Text("إلغاء") }
            }
        )
    }

    rejectTarget?.let { drv ->
        var reason by remember(drv.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("رفض حساب ${drv.name}") },
            text = { OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("سبب الرفض") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(onClick = { onRejectDriver(drv.id, reason); rejectTarget = null }) { Text("تأكيد الرفض") }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun SystemSettingsScreen(
    settings: SystemSettings,
    cloudUser: CloudUserInfo?,
    onSaveSettings: (SystemSettings) -> Unit,
    onChangePin: (String, String, (ActionResult<Unit>) -> Unit) -> Unit,
    onCloudSignIn: () -> Unit,
    onCloudSignOut: () -> Unit,
    onClearOperationalData: () -> Unit
) {
    var sellPrice by remember(settings.defaultSellingPricePerLiter) { mutableStateOf(settings.defaultSellingPricePerLiter.toString()) }
    var prodCost by remember(settings.waterProductionCostPerLiter) { mutableStateOf(settings.waterProductionCostPerLiter.toString()) }
    var dieselPrice by remember(settings.dieselPricePerLiter) { mutableStateOf(settings.dieselPricePerLiter.toString()) }
    var driverWage by remember(settings.driverWagePerTrip) { mutableStateOf(settings.driverWagePerTrip.toString()) }
    var stopMinutes by remember(settings.stopDetectionMinutes) { mutableStateOf(settings.stopDetectionMinutes.toString()) }
    var geofenceMeters by remember(settings.plantGeofenceMeters) { mutableStateOf(settings.plantGeofenceMeters.toString()) }
    var gpsPowerSaveEnabled by remember(settings.gpsPowerSaveEnabled) { mutableStateOf(settings.gpsPowerSaveEnabled) }
    var gpsPowerSaveIntervalSec by remember(settings.gpsPowerSaveIntervalSeconds) { mutableStateOf(settings.gpsPowerSaveIntervalSeconds.toString()) }
    var gpsStationaryDwellSec by remember(settings.gpsStationaryDwellSeconds) { mutableStateOf(settings.gpsStationaryDwellSeconds.toString()) }

    var showPlantPicker by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
                    Text("التسعير وقواعد التتبع التلقائي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = sellPrice, onValueChange = { sellPrice = it }, label = { Text("سعر بيع اللتر") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = prodCost, onValueChange = { prodCost = it }, label = { Text("تكلفة إنتاج اللتر") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = dieselPrice, onValueChange = { dieselPrice = it }, label = { Text("سعر الديزل/لتر") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = driverWage, onValueChange = { driverWage = it }, label = { Text("حصة أجر السائق") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = stopMinutes, onValueChange = { stopMinutes = it.filter(Char::isDigit) }, label = { Text("دقائق التوقف المطوّل") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = geofenceMeters, onValueChange = { geofenceMeters = it.filter(Char::isDigit) }, label = { Text("نطاق المصنع (متر)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "وضع توفير طاقة GPS عند التوقف (Power Save)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "يقلل تردد تحديث الموقع عند توقف الشاحنة للحفاظ على البطارية مع ضمان بقاء التحديثات ضمن حد انقطاع الإشارة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = gpsPowerSaveEnabled,
                            onCheckedChange = { gpsPowerSaveEnabled = it },
                            modifier = Modifier.testTag("gps_power_save_switch")
                        )
                    }
                    if (gpsPowerSaveEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = gpsPowerSaveIntervalSec,
                                onValueChange = { gpsPowerSaveIntervalSec = it.filter(Char::isDigit) },
                                label = { Text("تردد التوقف (ثانية)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("gps_power_save_interval_input")
                            )
                            OutlinedTextField(
                                value = gpsStationaryDwellSec,
                                onValueChange = { gpsStationaryDwellSec = it.filter(Char::isDigit) },
                                label = { Text("مهلة السكون (ثانية)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("gps_stationary_dwell_input")
                            )
                        }
                    }
                    OutlinedButton(onClick = { showPlantPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إحداثيات المصنع: ${formatLatLng(settings.plantLocation.lat, settings.plantLocation.lng, 4)}")
                    }
                    Button(
                        onClick = {
                            onSaveSettings(
                                settings.copy(
                                    defaultSellingPricePerLiter = sellPrice.toDoubleOrNull() ?: settings.defaultSellingPricePerLiter,
                                    waterProductionCostPerLiter = prodCost.toDoubleOrNull() ?: settings.waterProductionCostPerLiter,
                                    dieselPricePerLiter = dieselPrice.toDoubleOrNull() ?: settings.dieselPricePerLiter,
                                    driverWagePerTrip = driverWage.toDoubleOrNull() ?: settings.driverWagePerTrip,
                                    stopDetectionMinutes = stopMinutes.toIntOrNull() ?: settings.stopDetectionMinutes,
                                    plantGeofenceMeters = geofenceMeters.toIntOrNull() ?: settings.plantGeofenceMeters,
                                    gpsPowerSaveEnabled = gpsPowerSaveEnabled,
                                    gpsPowerSaveIntervalSeconds = (gpsPowerSaveIntervalSec.toIntOrNull() ?: settings.gpsPowerSaveIntervalSeconds).coerceIn(20, 90),
                                    gpsStationaryDwellSeconds = (gpsStationaryDwellSec.toIntOrNull() ?: settings.gpsStationaryDwellSeconds).coerceIn(15, 300)
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
                    ) { Text("حفظ إعدادات المصنع") }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("السحابة والأمان", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (cloudUser != null) {
                        Text("متصل بالسحابة: ${cloudUser.email}", color = EmeraldAccent, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onCloudSignOut, modifier = Modifier.fillMaxWidth()) { Text("فصل حساب Google") }
                    } else {
                        Button(onClick = onCloudSignIn, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CloudDone, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ربط حساب Google للمزامنة السحابية")
                        }
                    }
                    OutlinedButton(onClick = { showPinDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تغيير رمز PIN للإدارة")
                    }
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصفير السجلات التشغيلية")
                    }
                }
            }
        }
    }

    if (showPlantPicker) {
        LocationPickerDialog(
            title = "اختيار موقع مصنع مياهنا",
            initialLat = settings.plantLocation.lat,
            initialLng = settings.plantLocation.lng,
            plantLocation = settings.plantLocation,
            geofenceMeters = settings.plantGeofenceMeters,
            onDismiss = { showPlantPicker = false },
            onConfirm = { lat, lng ->
                onSaveSettings(settings.copy(plantLocation = settings.plantLocation.copy(lat = lat, lng = lng)))
                showPlantPicker = false
            }
        )
    }

    if (showPinDialog) {
        var curPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("تغيير رمز PIN للإدارة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = curPin, onValueChange = { curPin = it }, label = { Text("الرمز الحالي") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("الرمز الجديد (4 أرقام+)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    if (err != null) Text(err!!, color = RoseError, style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onChangePin(curPin, newPin) { res ->
                        if (res.success) showPinDialog = false else err = res.error
                    }
                }) { Text("تحديث الرمز") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("تأكيد تصفير البيانات التشغيلية") },
            text = { Text("سيتم حذف الطلبات والرحلات والفواتير والتسويات والمصروفات مع الإبقاء على حسابات السائقين والإعدادات.") },
            confirmButton = {
                Button(
                    onClick = { onClearOperationalData(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError)
                ) { Text("تأكيد التصفير") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("إلغاء") } }
        )
    }
}

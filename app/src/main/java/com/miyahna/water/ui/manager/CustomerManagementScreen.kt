package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.R
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.ui.common.DistrictSelectField
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun CustomerManagementScreen(
    customers: List<Customer>,
    totalCustomersCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    districts: List<String>,
    settings: SystemSettings,
    onAddCustomer: (
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ) -> Unit,
    onUpdateCustomer: (
        customerId: String,
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ) -> Unit,
    onDeleteCustomer: (String) -> Unit,
    onAddCustomDistrict: (String) -> Unit
) {
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingCustomer by remember { mutableStateOf<Customer?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "قاعدة بيانات العملاء المركزية ($totalCustomersCount)",
                            style = MaterialTheme.typography.titleMedium,
                            color = SkyPrimary
                        )
                        Text(
                            text = "إجمالي الطلبات: ${customers.sumOf { it.totalOrdersCount }} · إجمالي المسلم: ${formatNumber(customers.sumOf { it.totalLitersDelivered })} لتر",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("بحث بالاسم أو العنوان أو الهاتف ($totalCustomersCount)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_search_input")
                )
            }
            if (customers.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.People,
                        title = if (searchQuery.isBlank()) "لا يوجد عملاء مسجلون بعد" else "لا توجد نتائج مطابقة للبحث",
                        subtitle = if (searchQuery.isBlank()) "أضف عميلاً لتثبيت موقعه الجغرافي وسعة خزانه" else "جرّب البحث بالاسم أو الحي أو رقم الهاتف"
                    )
                }
            } else {
                itemsIndexed(customers, key = { idx, cust -> "${cust.id}_$idx" }) { _, cust ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_card_${cust.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                                Text(cust.customerId, style = NumericTextStyle, color = SkyPrimary)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    StatusPill(
                                        label = cust.district,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = SkyPrimary
                                    )
                                    IconButton(
                                        onClick = { editingCustomer = cust },
                                        modifier = Modifier.testTag("edit_customer_${cust.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.edit),
                                            tint = SkyPrimary
                                        )
                                    }
                                    IconButton(
                                        onClick = { deletingCustomer = cust },
                                        modifier = Modifier.testTag("delete_customer_${cust.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Text(cust.name, style = MaterialTheme.typography.titleMedium)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                val phonesText = buildString {
                                    append(cust.phone.ifBlank { "غير مسجل" })
                                    if (!cust.altPhone.isNullOrBlank()) {
                                        append(" · بديل: ")
                                        append(cust.altPhone)
                                    }
                                }
                                Text(
                                    text = "$phonesText · الخزان: ${formatNumber(cust.tankCapacityLiters)} لتر",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (cust.addressDetails.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${cust.district} — ${cust.addressDetails}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "إحداثيات GPS: ${formatLatLng(cust.lat, cust.lng, 5)}",
                                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "إجمالي الطلبات: ${cust.totalOrdersCount} · المسلم: ${formatNumber(cust.totalLitersDelivered)} لتر",
                                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                color = EmeraldAccent
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_customer_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.customer_add_title))
        }
    }

    if (showAddDialog || editingCustomer != null) {
        val target = editingCustomer
        CustomerFormDialog(
            initialCustomer = target,
            districts = districts,
            settings = settings,
            onDismiss = {
                showAddDialog = false
                editingCustomer = null
            },
            onSave = { name, phone, altPhone, district, address, lat, lng, capacity, notes ->
                if (target == null) {
                    onAddCustomer(name, phone, altPhone, district, address, lat, lng, capacity, notes)
                } else {
                    onUpdateCustomer(target.id, name, phone, altPhone, district, address, lat, lng, capacity, notes)
                }
                showAddDialog = false
                editingCustomer = null
            },
            onAddCustomDistrict = onAddCustomDistrict
        )
    }

    deletingCustomer?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingCustomer = null },
            title = { Text(stringResource(R.string.customer_delete_title)) },
            text = {
                Text("${target.name} (${target.customerId})\n${stringResource(R.string.customer_delete_confirm)}")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCustomer(target.id)
                        deletingCustomer = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_customer_button")
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustomer = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CustomerFormDialog(
    initialCustomer: Customer?,
    districts: List<String>,
    settings: SystemSettings,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ) -> Unit,
    onAddCustomDistrict: (String) -> Unit
) {
    var name by remember(initialCustomer) { mutableStateOf(initialCustomer?.name ?: "") }
    var phone by remember(initialCustomer) { mutableStateOf(initialCustomer?.phone ?: "") }
    var altPhone by remember(initialCustomer) { mutableStateOf(initialCustomer?.altPhone ?: "") }
    var district by remember(initialCustomer) { mutableStateOf(initialCustomer?.district ?: AlWadiDistricts.DEFAULT_DISTRICT) }
    var address by remember(initialCustomer) { mutableStateOf(initialCustomer?.addressDetails ?: "") }
    var lat by remember(initialCustomer) { mutableStateOf(initialCustomer?.lat ?: (settings.plantLocation.lat + 0.003)) }
    var lng by remember(initialCustomer) { mutableStateOf(initialCustomer?.lng ?: (settings.plantLocation.lng + 0.003)) }
    var capacity by remember(initialCustomer) { mutableStateOf((initialCustomer?.tankCapacityLiters ?: 2000).toString()) }
    var notes by remember(initialCustomer) { mutableStateOf(initialCustomer?.notes ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (initialCustomer == null) {
                        stringResource(R.string.customer_add_title)
                    } else {
                        stringResource(R.string.customer_edit_title)
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.customer_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_name_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.customer_phone_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_phone_input")
                )
                OutlinedTextField(
                    value = altPhone,
                    onValueChange = { altPhone = it },
                    label = { Text(stringResource(R.string.customer_alt_phone_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_alt_phone_input")
                )
                DistrictSelectField(
                    selectedDistrict = district,
                    districts = districts,
                    onDistrictSelected = { chosen ->
                        district = chosen
                        val (dLat, dLng) = AlWadiDistricts.coordinatesForDistrict(chosen)
                        lat = dLat
                        lng = dLng
                    },
                    onAddCustomDistrict = onAddCustomDistrict
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.customer_address_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_address_input")
                )
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it.filter(Char::isDigit) },
                    label = { Text("سعة الخزان (لتر)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديد الموقع (${formatLatLng(lat, lng, 4)})")
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onSave(
                                    name,
                                    phone,
                                    altPhone.takeIf { it.isNotBlank() },
                                    district,
                                    address,
                                    lat,
                                    lng,
                                    capacity.toIntOrNull() ?: 2000,
                                    notes.takeIf { it.isNotBlank() }
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_customer_button")
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (showPicker) {
        LocationPickerDialog(
            title = "موقع العميل على خريطة مأرب",
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

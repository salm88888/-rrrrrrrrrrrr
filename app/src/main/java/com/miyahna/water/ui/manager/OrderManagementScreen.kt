package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.ui.common.DistrictSelectField
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun OrderManagementScreen(
    orders: List<Order>,
    customers: List<Customer>,
    districts: List<String>,
    settings: SystemSettings,
    onAddOrder: (
        existingCustomerId: String?,
        customerName: String,
        phone: String,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacity: Int,
        requestedLiters: Int,
        pricePerLiter: Double?,
        source: OrderSource,
        isUrgent: Boolean,
        notes: String?
    ) -> Unit,
    onCancelOrder: (String) -> Unit,
    onAddCustomDistrict: (String) -> Unit,
    showNewOrderDialogInitially: Boolean = false,
    onNewOrderDialogDismissed: () -> Unit = {}
) {
    var search by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf<String>("all") }
    var showNewDialog by remember { mutableStateOf(showNewOrderDialogInitially) }

    val filtered = remember(orders, search, selectedStatusFilter) {
        orders.filter { o ->
            val matchesFilter = when (selectedStatusFilter) {
                "waiting" -> o.status == OrderStatus.WAITING_CLUSTERING || o.status == OrderStatus.NEW || o.status == OrderStatus.CONFIRMED
                "in_delivery" -> o.status == OrderStatus.IN_DELIVERY || o.status == OrderStatus.APPROVED || o.status == OrderStatus.CLUSTERED
                "delivered" -> o.status == OrderStatus.DELIVERED
                "cancelled" -> o.status == OrderStatus.CANCELLED
                else -> true
            }
            val q = search.trim()
            val matchesSearch = q.isEmpty() ||
                o.customerName.contains(q, ignoreCase = true) ||
                o.phone.contains(q) ||
                o.district.contains(q, ignoreCase = true) ||
                o.orderNumber.contains(q, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "سجل جميع الطلبات (${orders.size}) · إجمالي الكميات: ${formatNumber(orders.filter { it.status != OrderStatus.CANCELLED }.sumOf { it.requestedLiters })} لتر",
                    style = MaterialTheme.typography.titleSmall,
                    color = SkyPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("بحث بالاسم، الهاتف، الحي أو رقم الطلب") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("orders_search_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedStatusFilter == "all",
                        onClick = { selectedStatusFilter = "all" },
                        label = { Text("الكل (${orders.size})") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = selectedStatusFilter == "waiting",
                        onClick = { selectedStatusFilter = "waiting" },
                        label = { Text("بانتظار التجميع (${orders.count { it.status == OrderStatus.WAITING_CLUSTERING || it.status == OrderStatus.NEW }})") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = selectedStatusFilter == "in_delivery",
                        onClick = { selectedStatusFilter = "in_delivery" },
                        label = { Text("مجدولة/توصيل (${orders.count { it.status == OrderStatus.IN_DELIVERY || it.status == OrderStatus.APPROVED || it.status == OrderStatus.CLUSTERED }})") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = selectedStatusFilter == "delivered",
                        onClick = { selectedStatusFilter = "delivered" },
                        label = { Text("تم التسليم (${orders.count { it.status == OrderStatus.DELIVERED }})") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = selectedStatusFilter == "cancelled",
                        onClick = { selectedStatusFilter = "cancelled" },
                        label = { Text("ملغي (${orders.count { it.status == OrderStatus.CANCELLED }})") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.LocalDrink,
                        title = "لا توجد طلبات مطابقة",
                        subtitle = "اضغط + لتسجيل طلب مياه جديد من عميل حالي أو جديد"
                    )
                }
            } else {
                itemsIndexed(filtered, key = { idx, order -> "${order.id}_$idx" }) { _, order ->
                    OrderItemCard(order = order, onCancel = { onCancelOrder(order.id) })
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_order_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "إضافة طلب مياه")
        }
    }

    if (showNewDialog) {
        NewOrderDialog(
            customers = customers,
            districts = districts,
            settings = settings,
            defaultSource = OrderSource.MANAGER_MANUAL,
            onAddCustomDistrict = onAddCustomDistrict,
            onDismiss = {
                showNewDialog = false
                onNewOrderDialogDismissed()
            },
            onSubmit = { custId, name, phone, district, addr, lat, lng, cap, liters, price, src, urgent, notes ->
                onAddOrder(custId, name, phone, district, addr, lat, lng, cap, liters, price, src, urgent, notes)
                showNewDialog = false
                onNewOrderDialogDismissed()
            }
        )
    }
}

@Composable
private fun OrderItemCard(order: Order, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (order.isUrgent) RoseError.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(order.orderNumber, style = NumericTextStyle, color = SkyPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (order.isUrgent) {
                        StatusPill("عاجل", RoseError.copy(alpha = 0.14f), RoseError)
                    }
                    StatusPill(
                        label = order.status.labelAr,
                        containerColor = when (order.status) {
                            OrderStatus.DELIVERED -> EmeraldAccent.copy(alpha = 0.14f)
                            OrderStatus.CANCELLED -> RoseError.copy(alpha = 0.14f)
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = when (order.status) {
                            OrderStatus.DELIVERED -> EmeraldAccent
                            OrderStatus.CANCELLED -> RoseError
                            else -> SkyPrimary
                        }
                    )
                }
            }
            Text("${order.customerName} · ${order.phone}", style = MaterialTheme.typography.titleMedium)
            Text("${order.district} ${if (order.addressDetails.isNotBlank()) "- ${order.addressDetails}" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatNumber(order.requestedLiters)} لتر · ${formatNumber(order.totalAmount.safeRoundToInt())} ر.ي",
                    style = NumericTextStyle,
                    color = EmeraldAccent
                )
                if (order.status == OrderStatus.WAITING_CLUSTERING || order.status == OrderStatus.NEW) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = "إلغاء الطلب", tint = RoseError)
                    }
                }
            }
            if (order.duplicateWarning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تنبيه تكرار محتمل مع ${order.duplicateMatchedOrderId ?: "طلب حديث"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning
                    )
                }
            }
        }
    }
}

@Composable
fun NewOrderDialog(
    customers: List<Customer>,
    districts: List<String>,
    settings: SystemSettings,
    defaultSource: OrderSource,
    onAddCustomDistrict: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (
        existingCustomerId: String?,
        customerName: String,
        phone: String,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacity: Int,
        requestedLiters: Int,
        pricePerLiter: Double?,
        source: OrderSource,
        isUrgent: Boolean,
        notes: String?
    ) -> Unit
) {
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var customerMenuOpen by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var district by remember { mutableStateOf(AlWadiDistricts.DEFAULT_DISTRICT) }
    var address by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(settings.plantLocation.lat + 0.004) }
    var lng by remember { mutableStateOf(settings.plantLocation.lng + 0.004) }
    var capacityText by remember { mutableStateOf("2000") }
    var litersText by remember { mutableStateOf("2000") }
    var urgent by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var showMapPicker by remember { mutableStateOf(false) }

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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("تسجيل طلب مياه جديد", style = MaterialTheme.typography.titleLarge)

                if (customers.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { customerMenuOpen = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text(selectedCustomer?.let { "العميل المختار: ${it.name}" } ?: "اختيار عميل مسجل (أو أدخل جديد بالأسفل)")
                        }
                        DropdownMenu(
                            expanded = customerMenuOpen,
                            onDismissRequest = { customerMenuOpen = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("-- عميل جديد --") },
                                onClick = {
                                    selectedCustomer = null
                                    customerMenuOpen = false
                                }
                            )
                            customers.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.name} (${c.district})") },
                                    onClick = {
                                        selectedCustomer = c
                                        name = c.name
                                        phone = c.phone
                                        district = c.district
                                        address = c.addressDetails
                                        lat = c.lat
                                        lng = c.lng
                                        capacityText = c.tankCapacityLiters.toString()
                                        litersText = c.tankCapacityLiters.toString()
                                        customerMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم العميل *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("order_customer_name_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("رقم الجوال") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("order_phone_input")
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
                    label = { Text("وصف العنوان / المعالم القريبة") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { showMapPicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديد الموقع على الخريطة (${formatLatLng(lat, lng, 4)})")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = capacityText,
                        onValueChange = { capacityText = it.filter(Char::isDigit) },
                        label = { Text("سعة الخزان (لتر)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = litersText,
                        onValueChange = { litersText = it.filter(Char::isDigit) },
                        label = { Text("الكمية المطلوبة (لتر)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("order_liters_input")
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = urgent, onCheckedChange = { urgent = it })
                    Text("طلب عاجل جداً (أولوية خروج فورية)")
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات (اختياري)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("إلغاء")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onSubmit(
                                    selectedCustomer?.id,
                                    name,
                                    phone,
                                    district,
                                    address,
                                    lat,
                                    lng,
                                    capacityText.toIntOrNull() ?: 2000,
                                    litersText.toIntOrNull() ?: 2000,
                                    null,
                                    defaultSource,
                                    urgent,
                                    notes
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("submit_new_order_button")
                    ) {
                        Text("حفظ الطلب")
                    }
                }
            }
        }
    }

    if (showMapPicker) {
        LocationPickerDialog(
            title = "اختيار موقع العميل",
            initialLat = lat,
            initialLng = lng,
            plantLocation = settings.plantLocation,
            geofenceMeters = settings.plantGeofenceMeters,
            onDismiss = { showMapPicker = false },
            onConfirm = { pLat, pLng ->
                lat = pLat
                lng = pLng
                showMapPicker = false
            }
        )
    }
}

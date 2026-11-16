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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.Expense
import com.miyahna.water.domain.ExpenseCategory
import com.miyahna.water.domain.Invoice
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.UnitEconomicsEngine
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.KpiMetricCard
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun InvoicingAndCashScreen(invoices: List<Invoice>) {
    val totalBilled = invoices.sumOf { it.totalAmount }
    val totalCollected = invoices.sumOf { it.paidAmount }
    val totalRemaining = invoices.sumOf { it.remainingAmount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiMetricCard("إجمالي الفواتير", "${formatNumber(totalBilled.safeRoundToInt())} ر.ي", icon = Icons.Default.ReceiptLong, modifier = Modifier.weight(1f))
                KpiMetricCard("المحصل نقداً", "${formatNumber(totalCollected.safeRoundToInt())} ر.ي", "متبقي ${formatNumber(totalRemaining.safeRoundToInt())}", Icons.Default.AccountBalanceWallet, EmeraldAccent, Modifier.weight(1f))
            }
        }
        if (invoices.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.ReceiptLong, "لا توجد فواتير صادرة بعد", "تصدر الفواتير تلقائياً عند توثيق التسليم من شاشة الرحلات")
            }
        } else {
            itemsIndexed(invoices, key = { idx, inv -> "${inv.id}_$idx" }) { _, inv ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(inv.invoiceNumber, style = NumericTextStyle, color = SkyPrimary)
                            StatusPill(
                                label = if (inv.isReconciled) "تمت التسوية" else "نقدي مع السائق",
                                containerColor = if (inv.isReconciled) EmeraldAccent.copy(alpha = 0.14f) else AmberWarning.copy(alpha = 0.14f),
                                contentColor = if (inv.isReconciled) EmeraldAccent else AmberWarning
                            )
                        }
                        Text("${inv.customerName} (${inv.district})", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${formatNumber(inv.deliveredLiters)} لتر · الإجمالي: ${formatNumber(inv.totalAmount.safeRoundToInt())} ر.ي · المدفوع: ${formatNumber(inv.paidAmount.safeRoundToInt())} ر.ي",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DriverReconciliationScreen(
    trips: List<Trip>,
    reconciliations: List<DriverReconciliation>,
    onSubmitReconciliation: (tripId: String, cashHandedOver: Double, deductedExpenses: Double, notes: String?) -> Unit
) {
    val unReconciledTrips = trips.filter {
        it.reconciledAt == null && (it.status == TripStatus.COMPLETED_PENDING_REVIEW || it.status == TripStatus.CLOSED || it.status == TripStatus.RETURNED_TO_PLANT)
    }
    var selectedTrip by remember { mutableStateOf<Trip?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (unReconciledTrips.isNotEmpty()) {
            item { Text("رحلات بانتظار تسوية العهدة النقدية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            itemsIndexed(unReconciledTrips, key = { idx, trip -> "${trip.id}_$idx" }) { _, trip ->
                val collected = trip.customerStops.sumOf { it.paidAmount }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, AmberWarning)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(trip.tripNumber, style = NumericTextStyle, color = SkyPrimary)
                            Text("السائق: ${trip.driverName} · محصل: ${formatNumber(collected.safeRoundToInt())} ر.ي", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { selectedTrip = trip }) { Text("إجراء التسوية") }
                    }
                }
            }
        }

        item { Text("سجل التسويات المعتمدة (${reconciliations.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (reconciliations.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.AccountBalanceWallet, "لا توجد تسويات بعد", "اختر رحلة مكتملة لتوثيق استلام النقدية من السائق")
            }
        } else {
            itemsIndexed(reconciliations, key = { idx, rec -> "${rec.id}_$idx" }) { _, rec ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rec.reconciliationNumber, style = NumericTextStyle)
                            StatusPill(
                                label = if (rec.status == "approved_reconciled") "متطابق ومعتمد" else "يوجد فارق",
                                containerColor = if (rec.status == "approved_reconciled") EmeraldAccent.copy(alpha = 0.14f) else RoseError.copy(alpha = 0.14f),
                                contentColor = if (rec.status == "approved_reconciled") EmeraldAccent else RoseError
                            )
                        }
                        Text("الرحلة ${rec.tripNumber} · ${rec.driverName}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "المستلم: ${formatNumber(rec.actualCashHandedOver.safeRoundToInt())} ر.ي · مصروفات مخصومة: ${formatNumber(rec.authorizedExpensesDeducted.safeRoundToInt())} ر.ي · الفارق: ${rec.differenceAmount.safeRoundToInt()} ر.ي",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize)
                        )
                    }
                }
            }
        }
    }

    selectedTrip?.let { trip ->
        val expectedCash = trip.customerStops.sumOf { it.paidAmount }
        var handedText by remember(trip.id) { mutableStateOf(expectedCash.safeRoundToInt().toString()) }
        var expenseText by remember(trip.id) { mutableStateOf("0") }
        var notes by remember(trip.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { selectedTrip = null },
            title = { Text("تسوية مالية — ${trip.tripNumber}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("النقدية المفترضة من الفواتير: ${formatNumber(expectedCash.safeRoundToInt())} ر.ي")
                    OutlinedTextField(value = handedText, onValueChange = { handedText = it.filter(Char::isDigit) }, label = { Text("المبلغ المسلم فعلياً (ر.ي)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = expenseText, onValueChange = { expenseText = it.filter(Char::isDigit) }, label = { Text("مصروفات مخصومة مصرح بها (ر.ي)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSubmitReconciliation(
                        trip.id,
                        handedText.toDoubleOrNull() ?: 0.0,
                        expenseText.toDoubleOrNull() ?: 0.0,
                        notes
                    )
                    selectedTrip = null
                }) { Text("اعتماد التسوية") }
            },
            dismissButton = { TextButton(onClick = { selectedTrip = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ExpensesAndFuelScreen(
    expenses: List<Expense>,
    trips: List<Trip>,
    onAddExpense: (ExpenseCategory, String, Double, Double?, String?, String?) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val totalExpenses = expenses.sumOf { it.amount }
    val dieselLiters = expenses.filter { it.category == ExpenseCategory.DIESEL }.sumOf { it.liters ?: 0.0 }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiMetricCard("إجمالي المصروفات", "${formatNumber(totalExpenses.safeRoundToInt())} ر.ي", icon = Icons.Default.LocalGasStation, accentColor = RoseError, modifier = Modifier.weight(1f))
                    KpiMetricCard("وقود ديزل مسجل", "${dieselLiters.safeRoundToInt()} لتر", "${expenses.size} قيد مصروف", Icons.Default.LocalGasStation, AmberWarning, Modifier.weight(1f))
                }
            }
            if (expenses.isEmpty()) {
                item { EmptyStateCard(Icons.Default.LocalGasStation, "لا توجد مصروفات مسجلة", "اضغط + لتوثيق تعبئة ديزل أو صيانة أو أجر سائق") }
            } else {
                itemsIndexed(expenses, key = { idx, exp -> "${exp.id}_$idx" }) { _, exp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${exp.expenseNumber} · ${exp.category.labelAr}", style = MaterialTheme.typography.titleMedium)
                                Text(exp.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(exp.date + (exp.tripNumber?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${formatNumber(exp.amount.safeRoundToInt())} ر.ي", style = NumericTextStyle, color = RoseError)
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
                .testTag("add_expense_fab")
        ) { Icon(Icons.Default.Add, contentDescription = "تسجيل مصروف") }
    }

    if (showAddDialog) {
        var cat by remember { mutableStateOf(ExpenseCategory.DIESEL) }
        var catExpanded by remember { mutableStateOf(false) }
        var desc by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var liters by remember { mutableStateOf("") }
        var receipt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("تسجيل مصروف تشغيلي جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { catExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("التصنيف: ${cat.labelAr}")
                        }
                        DropdownMenu(
                            expanded = catExpanded,
                            onDismissRequest = { catExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            ExpenseCategory.entries.forEach { c ->
                                DropdownMenuItem(text = { Text(c.labelAr) }, onClick = { cat = c; catExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("البيان / الوصف") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) }, label = { Text("المبلغ (ر.ي) *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    if (cat == ExpenseCategory.DIESEL) {
                        OutlinedTextField(value = liters, onValueChange = { liters = it }, label = { Text("كمية اللترات") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = receipt, onValueChange = { receipt = it }, label = { Text("رقم السند (اختياري)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        onAddExpense(cat, desc, amt, liters.toDoubleOrNull(), trips.firstOrNull()?.id, receipt)
                        showAddDialog = false
                    }
                }) { Text("حفظ المصروف") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ProfitabilityAnalyticsScreen(
    trips: List<Trip>,
    invoices: List<Invoice>,
    customers: List<Customer>,
    settings: SystemSettings
) {
    var tab by remember { mutableIntStateOf(0) }
    val summary = UnitEconomicsEngine.calculateProfitabilitySummary(trips, invoices, settings)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("ملخص الوحدات") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("حسب الرحلة") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("حسب العميل") })
            }
        }
        when (tab) {
            0 -> {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KpiMetricCard("إجمالي الإيرادات", "${formatNumber(summary.totalRevenue.safeRoundToInt())} ر.ي", icon = Icons.Default.TrendingUp, accentColor = SkyPrimary, modifier = Modifier.weight(1f))
                        KpiMetricCard("صافي الربح", "${formatNumber(summary.totalOperatingProfit.safeRoundToInt())} ر.ي", "هامش ${summary.overallMarginPercent}%", Icons.Default.TrendingUp, EmeraldAccent, Modifier.weight(1f))
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("اقتصاديات الوحدة (Unit Economics)", style = MaterialTheme.typography.titleMedium)
                            MetricRow("تكلفة توصيل 1,000 لتر", "${formatNumber(summary.costPer1000Liters)} ر.ي")
                            MetricRow("تكلفة نقل المنزل الواحد", "${formatNumber(summary.costPerHouse)} ر.ي")
                            MetricRow("إجمالي تكلفة اللتر الواصل", "${summary.costPerLiterDelivered} ر.ي")
                            MetricRow("صافي ربح اللتر الواحد", "${summary.netProfitPerLiter} ر.ي")
                        }
                    }
                }
            }
            1 -> {
                itemsIndexed(trips, key = { idx, t -> "${t.id}_$idx" }) { _, t ->
                    val rev = t.actualRevenue ?: t.expectedRevenue
                    val prof = t.actualProfit ?: t.expectedProfit
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(t.tripNumber, style = NumericTextStyle, color = SkyPrimary)
                            Text("إيراد: ${formatNumber(rev.safeRoundToInt())} ر.ي · ربح: +${formatNumber(prof.safeRoundToInt())} ر.ي (${t.profitMarginPercent}%)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            2 -> {
                itemsIndexed(customers, key = { idx, c -> "${c.id}_$idx" }) { _, c ->
                    val rev = c.totalLitersDelivered * settings.defaultSellingPricePerLiter
                    val prod = c.totalLitersDelivered * settings.waterProductionCostPerLiter
                    val contrib = rev - prod - (c.totalOrdersCount * 1200.0)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("${c.name} (${c.district})", style = MaterialTheme.typography.titleMedium)
                            Text("${formatNumber(c.totalLitersDelivered)} لتر · صافي المساهمة: +${formatNumber(contrib.safeRoundToInt())} ر.ي", style = NumericTextStyle, color = EmeraldAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NumericTextStyle)
    }
}

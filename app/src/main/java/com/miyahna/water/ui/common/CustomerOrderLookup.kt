package com.miyahna.water.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miyahna.water.domain.Customer
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.SkyPrimary

/**
 * Shared customer import/new-registration banner + searchable picker + live auto-suggestions
 * used in both Manager NewOrderDialog and Driver Send Field Order dialog.
 */
@Composable
fun CustomerOrderLookupSection(
    customers: List<Customer>,
    selectedCustomer: Customer?,
    typedName: String,
    typedPhone: String,
    onImportCustomer: (Customer) -> Unit,
    onSwitchToNewCustomer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }

    val liveSuggestions = remember(customers, selectedCustomer, typedName, typedPhone) {
        if (selectedCustomer != null || customers.isEmpty()) {
            emptyList()
        } else {
            val qName = typedName.trim()
            val qPhone = typedPhone.trim()
            if (qName.length < 2 && qPhone.length < 3) {
                emptyList()
            } else {
                customers.filter { c ->
                    (qName.length >= 2 && c.name.contains(qName, ignoreCase = true)) ||
                        (qPhone.length >= 3 && (c.phone.contains(qPhone) || (c.altPhone?.contains(qPhone) == true)))
                }.take(3)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedCustomer != null) {
            Surface(
                color = EmeraldAccent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("imported_customer_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تم استيراد العميل: ${selectedCustomer.name}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldAccent
                        )
                        Text(
                            text = "${selectedCustomer.customerId} · ${selectedCustomer.district} · خزان ${selectedCustomer.tankCapacityLiters} لتر",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onSwitchToNewCustomer,
                        modifier = Modifier.testTag("switch_to_new_customer_button")
                    ) {
                        Text("عميل جديد")
                    }
                }
            }
        } else {
            Surface(
                color = SkyPrimary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_customer_info_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = SkyPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "عميل جديد — سيتم حفظ بياناته تلقائياً في قاعدة بيانات العملاء",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (customers.isNotEmpty()) {
            OutlinedButton(
                onClick = { showSearchDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_customer_import_picker_button")
            ) {
                Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (selectedCustomer != null) {
                        "تغيير العميل المستورد (${customers.size})"
                    } else {
                        "استيراد اسم عميل من قاعدة البيانات (${customers.size})"
                    }
                )
            }
        }

        if (liveSuggestions.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "عملاء مطابقون في قاعدة البيانات (اضغط للاستيراد):",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    liveSuggestions.forEach { match ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onImportCustomer(match) }
                                .testTag("customer_suggestion_${match.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = match.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${match.phone.ifBlank { "بدون هاتف" }} · ${match.district}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "استيراد",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSearchDialog) {
        CustomerImportPickerDialog(
            customers = customers,
            onDismiss = { showSearchDialog = false },
            onSelectCustomer = { chosen ->
                onImportCustomer(chosen)
                showSearchDialog = false
            },
            onSelectNewCustomer = {
                onSwitchToNewCustomer()
                showSearchDialog = false
            }
        )
    }
}

@Composable
private fun CustomerImportPickerDialog(
    customers: List<Customer>,
    onDismiss: () -> Unit,
    onSelectCustomer: (Customer) -> Unit,
    onSelectNewCustomer: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(customers, search) {
        val q = search.trim()
        if (q.isEmpty()) customers
        else customers.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.phone.contains(q) ||
                (it.altPhone?.contains(q) == true) ||
                it.district.contains(q, ignoreCase = true) ||
                it.customerId.contains(q, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "استيراد عميل من قاعدة البيانات",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("ابحث بالاسم أو الجوال أو الحي") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_import_search_input")
                )

                OutlinedButton(
                    onClick = onSelectNewCustomer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تسجيل عميل جديد بدلاً من ذلك")
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            text = "لا توجد نتائج مطابقة في قاعدة العملاء",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        filtered.forEach { customer ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectCustomer(customer) }
                                    .testTag("import_customer_item_${customer.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = customer.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${customer.phone.ifBlank { "بدون هاتف" }} · ${customer.district}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "سعة الخزان: ${customer.tankCapacityLiters} لتر · طلبات: ${customer.totalOrdersCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SkyPrimary
                                        )
                                    }
                                    Text(
                                        text = "استيراد",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = EmeraldAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

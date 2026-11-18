package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.UnitEconomicsEngine
import com.miyahna.water.ui.ManagerTab
import com.miyahna.water.ui.MiyahnaUiState
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
fun DashboardOverviewScreen(
    uiState: MiyahnaUiState,
    onNavigateTab: (ManagerTab) -> Unit,
    onOpenNewOrder: () -> Unit,
    onRequestCloudSignIn: () -> Unit = {},
    onSyncNow: () -> Unit = {}
) {
    val domain = uiState.domain
    val waitingOrders = domain.orders.filter {
        it.status == OrderStatus.WAITING_CLUSTERING || it.status == OrderStatus.NEW || it.status == OrderStatus.CONFIRMED
    }
    val activeTrip = domain.trips.firstOrNull { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
    val pendingReviewTrips = domain.trips.filter {
        it.status == TripStatus.COMPLETED_PENDING_REVIEW || it.status == TripStatus.RETURNED_TO_PLANT || it.status == TripStatus.PENDING_APPROVAL
    }
    val pendingDriversCount = domain.driverAccounts.count { it.status == DriverAccountStatus.PENDING }
    val economics = UnitEconomicsEngine.calculateProfitabilitySummary(
        trips = domain.trips,
        invoices = domain.invoices,
        settings = domain.settings
    )
    val totalCashCollected = domain.invoices.sumOf { it.paidAmount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Clear 1-tap Firebase Cloud Sync Strip
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (domain.cloudUser != null) {
                        EmeraldAccent.copy(alpha = 0.08f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (domain.cloudUser != null) EmeraldAccent.copy(alpha = 0.45f) else SkyPrimary.copy(alpha = 0.45f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (domain.cloudUser != null) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = if (domain.cloudUser != null) EmeraldAccent else SkyPrimary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (domain.cloudUser != null) {
                                "Firebase متصل (${domain.cloudUser.email})"
                            } else {
                                "Firebase غير مربوط — اربط حساب Google للمزامنة"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (domain.cloudSyncError != null) {
                            Text(
                                text = domain.cloudSyncError,
                                style = MaterialTheme.typography.labelSmall,
                                color = RoseError
                            )
                        } else if (domain.cloudUser != null) {
                            Text(
                                text = if (domain.isRealtimeFleetConnected) "تحديث مباشر نشط" else "جاهز للمزامنة",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldAccent
                            )
                        }
                    }
                    if (domain.cloudUser != null) {
                        TextButton(
                            onClick = onSyncNow,
                            enabled = domain.isOnline && !domain.isCloudSyncing,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("overview_sync_now_button")
                        ) {
                            Text(if (domain.isCloudSyncing) "جاري..." else "مزامنة")
                        }
                    } else {
                        Button(
                            onClick = onRequestCloudSignIn,
                            enabled = domain.isOnline,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("overview_connect_firebase_button")
                        ) {
                            Text("ربط")
                        }
                    }
                }
            }
        }

        // Primary 1-tap actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenNewOrder,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("overview_new_order_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("طلب جديد")
                }
                OutlinedButton(
                    onClick = { onNavigateTab(ManagerTab.CLUSTERING) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("overview_clustering_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تجهيز رحلة (${uiState.clusters.size})")
                }
            }
        }

        // Clean 1-tap shortcuts to frequent admin views
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { onNavigateTab(ManagerTab.CUSTOMERS) },
                    label = { Text("العملاء (${domain.customers.size})") },
                    leadingIcon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = false,
                    onClick = { onNavigateTab(ManagerTab.INVOICES) },
                    label = { Text("الفواتير") },
                    leadingIcon = { Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = false,
                    onClick = { onNavigateTab(ManagerTab.DRIVERS) },
                    label = {
                        Text(if (pendingDriversCount > 0) "السائقين ($pendingDriversCount جديد)" else "السائقين")
                    },
                    leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = false,
                    onClick = { onNavigateTab(ManagerTab.SETTINGS) },
                    label = { Text("الإعدادات") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }

        // 2x2 Clean KPIs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiMetricCard(
                    title = "المياه المسلمة",
                    value = "${formatNumber(economics.totalDeliveredWater)} لتر",
                    subtitle = "${economics.totalDeliveredHouses} منزل",
                    icon = Icons.Default.WaterDrop,
                    accentColor = SkyPrimary,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "صافي الربح",
                    value = "${formatNumber(economics.totalOperatingProfit.safeRoundToInt())} ر.ي",
                    subtitle = "هامش ${economics.overallMarginPercent}%",
                    icon = Icons.Default.TrendingUp,
                    accentColor = EmeraldAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiMetricCard(
                    title = "التحصيل النقدي",
                    value = "${formatNumber(totalCashCollected.safeRoundToInt())} ر.ي",
                    subtitle = "${domain.invoices.size} فاتورة",
                    icon = Icons.Default.Payments,
                    accentColor = AmberWarning,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "طلبات بالانتظار",
                    value = "${waitingOrders.size}",
                    subtitle = "${formatNumber(waitingOrders.sumOf { it.requestedLiters })} لتر",
                    icon = Icons.Default.LocalShipping,
                    accentColor = if (waitingOrders.any { it.isUrgent }) RoseError else SkyPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Concise Live Fleet Card
        item {
            val fleetSummary = uiState.fleetSummary
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("الأسطول (${fleetSummary.totalCount} صهريج)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        StatusPill(
                            label = if (domain.isRealtimeFleetConnected) "بث مباشر" else domain.truck.status.labelAr,
                            containerColor = if (domain.isRealtimeFleetConnected) EmeraldAccent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (domain.isRealtimeFleetConnected) EmeraldAccent else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "في رحلة: ${fleetSummary.onTripCount} · متاح: ${fleetSummary.idleCount} · تعبئة: ${fleetSummary.loadingCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeTrip != null) {
                        Text(
                            text = "الرحلة النشطة: ${activeTrip.tripNumber} (${activeTrip.actualDistanceKm ?: 0.0} كم)",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            color = EmeraldAccent
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onNavigateTab(ManagerTab.FLEET_LIVE) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("overview_open_fleet_dashboard_button")
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("خريطة الأسطول")
                        }
                        if (activeTrip != null) {
                            OutlinedButton(
                                onClick = { onNavigateTab(ManagerTab.TRIPS) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("متابعة الرحلة")
                            }
                        }
                    }
                }
            }
        }

        if (pendingReviewTrips.isNotEmpty()) {
            item {
                Text(
                    text = "رحلات بانتظار المراجعة (${pendingReviewTrips.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(pendingReviewTrips, key = { idx, trip -> "${trip.id}_$idx" }) { _, trip ->
                Card(
                    onClick = { onNavigateTab(ManagerTab.TRIPS) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(trip.tripNumber, style = NumericTextStyle)
                            Text(
                                text = "${trip.customerStops.size} محطات · ${formatNumber(trip.initialLoadLiters)} لتر",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            label = trip.status.labelAr,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = AmberWarning
                        )
                    }
                }
            }
        }
    }
}

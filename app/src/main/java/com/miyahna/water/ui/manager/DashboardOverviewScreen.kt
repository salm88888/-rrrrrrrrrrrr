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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.UnitEconomicsEngine
import com.miyahna.water.ui.ManagerTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.common.KpiMetricCard
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
fun DashboardOverviewScreen(
    uiState: MiyahnaUiState,
    onNavigateTab: (ManagerTab) -> Unit,
    onOpenNewOrder: () -> Unit
) {
    val domain = uiState.domain
    val waitingOrders = domain.orders.filter {
        it.status == OrderStatus.WAITING_CLUSTERING || it.status == OrderStatus.NEW || it.status == OrderStatus.CONFIRMED
    }
    val activeTrip = domain.trips.firstOrNull { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
    val pendingReviewTrips = domain.trips.filter {
        it.status == TripStatus.COMPLETED_PENDING_REVIEW || it.status == TripStatus.RETURNED_TO_PLANT || it.status == TripStatus.PENDING_APPROVAL
    }
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
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenNewOrder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("overview_new_order_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("طلب مياه جديد")
                }
                OutlinedButton(
                    onClick = { onNavigateTab(ManagerTab.CLUSTERING) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("overview_clustering_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("التجميع (${uiState.clusters.size})")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiMetricCard(
                    title = "إجمالي المياه المسلمة",
                    value = "${formatNumber(economics.totalDeliveredWater)} لتر",
                    subtitle = "${economics.totalDeliveredHouses} منزل مستفيد",
                    icon = Icons.Default.WaterDrop,
                    accentColor = SkyPrimary,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "صافي الربح التشغيلي",
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
                    subtitle = "${domain.invoices.size} فاتورة صادرة",
                    icon = Icons.Default.Payments,
                    accentColor = AmberWarning,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "طلبات بانتظار التجميع",
                    value = "${waitingOrders.size}",
                    subtitle = "${formatNumber(waitingOrders.sumOf { it.requestedLiters })} لتر مطلوب",
                    icon = Icons.Default.LocalShipping,
                    accentColor = if (waitingOrders.any { it.isUrgent }) RoseError else SkyPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Live Fleet & Active Trip Banner
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
                        Text("حالة الأسطول والميدان (${fleetSummary.totalCount} صهريج)", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            label = if (domain.isRealtimeFleetConnected) "بث مباشر" else domain.truck.status.labelAr,
                            containerColor = if (domain.isRealtimeFleetConnected) EmeraldAccent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (domain.isRealtimeFleetConnected) EmeraldAccent else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "في رحلة: ${fleetSummary.onTripCount} · تعبئة: ${fleetSummary.loadingCount} · متاح: ${fleetSummary.idleCount} · صيانة: ${fleetSummary.maintenanceCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${domain.truck.model} · لوحة: ${domain.truck.plateNumber} · السائق: ${domain.truck.driverName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeTrip != null) {
                        Text(
                            text = "الرحلة النشطة: ${activeTrip.tripNumber} | المسافة الفعلية: ${activeTrip.actualDistanceKm ?: 0.0} كم | نقاط GPS: ${activeTrip.gpsPoints.size}",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            color = EmeraldAccent
                        )
                    } else {
                        Text(
                            text = "آخر إحداثيات: ${formatLatLng(domain.truck.lastLat, domain.truck.lastLng, 4)} · عداد: ${formatNumber(domain.truck.odometerKm.safeRoundToInt())} كم",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                .testTag("overview_open_fleet_dashboard_button")
                        ) {
                            Text("لوحة الأسطول المباشرة")
                        }
                        if (activeTrip != null) {
                            OutlinedButton(
                                onClick = { onNavigateTab(ManagerTab.TRIPS) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("تفاصيل الرحلة")
                            }
                        }
                    }
                }
            }
        }

        if (pendingReviewTrips.isNotEmpty()) {
            item {
                Text(
                    text = "رحلات تتطلب قرار أو مراجعة المدير (${pendingReviewTrips.size})",
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

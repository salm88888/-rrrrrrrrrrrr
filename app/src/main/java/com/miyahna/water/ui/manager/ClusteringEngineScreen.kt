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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.ClusterProposal
import com.miyahna.water.domain.DepartureRecommendation
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun ClusteringEngineScreen(
    clusters: List<ClusterProposal>,
    maxTruckCapacity: Int,
    onApproveAndCreateTrip: (ClusterProposal, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = "محرك التجميع الذكي للطلبات (Clustering & Route Optimization)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "يجمّع الطلبات المتقاربة جغرافياً ضمن سعة الوايت (${formatNumber(maxTruckCapacity)} لتر) ويرتب المحطات تلقائياً بأقصر مسار.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (clusters.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.AutoAwesome,
                    title = "لا توجد طلبات بانتظار التجميع حالياً",
                    subtitle = "عندما تضاف طلبات جديدة ستظهر هنا مجموعات الرحلات المقترحة مع حساب الجدوى والربحية"
                )
            }
        } else {
            itemsIndexed(clusters, key = { idx, prop -> "${prop.id}_$idx" }) { _, prop ->
                val (badgeBg, badgeFg) = when (prop.recommendation) {
                    DepartureRecommendation.URGENT_OVERRIDE -> RoseError.copy(alpha = 0.14f) to RoseError
                    DepartureRecommendation.GO_NOW -> EmeraldAccent.copy(alpha = 0.14f) to EmeraldAccent
                    DepartureRecommendation.FEASIBLE_PARTIAL -> MaterialTheme.colorScheme.primaryContainer to SkyPrimary
                    DepartureRecommendation.WAIT_MORE -> MaterialTheme.colorScheme.tertiaryContainer to AmberWarning
                }
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
                            Text(
                                text = prop.districtsCovered.joinToString(" + "),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            StatusPill(prop.recommendation.labelAr, badgeBg, badgeFg)
                        }

                        Text(
                            text = prop.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("امتلاء الوايت", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "${formatNumber(prop.totalLiters)} / ${formatNumber(maxTruckCapacity)} لتر (${prop.capacityUtilization}%)",
                                    style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize)
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (prop.capacityUtilization.toFloat() / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("المسافة التقديرية: ${prop.estimatedDistanceKm} كم", style = MaterialTheme.typography.bodyMedium)
                            Text("الديزل: ${prop.estimatedFuelLiters} لتر", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "الإيراد: ${formatNumber(prop.expectedRevenue.safeRoundToInt())} ر.ي",
                                style = NumericTextStyle,
                                color = SkyPrimary
                            )
                            Text(
                                text = "الربح المتوقع: +${formatNumber(prop.expectedProfit.safeRoundToInt())} ر.ي (${prop.profitMarginPercent}%)",
                                style = NumericTextStyle,
                                color = EmeraldAccent
                            )
                        }

                        // Stop sequence preview
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Route, contentDescription = null, tint = SkyPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ترتيب المحطات المقترح (${prop.customerStops.size}):", style = MaterialTheme.typography.labelMedium)
                            }
                            prop.customerStops.forEach { stop ->
                                Text(
                                    text = "${stop.stopSequence}. ${stop.customerName} — ${stop.district} (${formatNumber(stop.requestedLiters)} لتر)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onApproveAndCreateTrip(prop, false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("حفظ كمخطط")
                            }
                            Button(
                                onClick = { onApproveAndCreateTrip(prop, true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("approve_cluster_${prop.id}")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("اعتماد وإطلاق")
                            }
                        }
                    }
                }
            }
        }
    }
}

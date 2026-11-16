package com.miyahna.water.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miyahna.water.domain.FuelEfficiencyStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.UnitEconomicsEngine
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun DriverWeeklyFuelReportCard(
    trips: List<Trip>,
    settings: SystemSettings,
    driverId: String?,
    driverName: String?,
    modifier: Modifier = Modifier
) {
    val report = remember(trips, settings, driverId, driverName) {
        UnitEconomicsEngine.buildDriverWeeklyFuelReport(trips, settings, driverId, driverName)
    }
    val textMeasurer = rememberTextMeasurer()

    val (badgeBg, badgeFg) = when (report.efficiencyStatus) {
        FuelEfficiencyStatus.EFFICIENT -> EmeraldAccent.copy(alpha = 0.18f) to Color(0xFF34D399)
        FuelEfficiencyStatus.NORMAL -> SkyPrimary.copy(alpha = 0.22f) to Color(0xFF38BDF8)
        FuelEfficiencyStatus.HIGH -> AmberWarning.copy(alpha = 0.22f) to Color(0xFFFBBF24)
        FuelEfficiencyStatus.EMPTY -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = Color(0xFFFBBF24))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("استهلاك الوقود الأسبوعي", style = MaterialTheme.typography.titleMedium)
                        Text(report.rangeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusPill(report.efficiencyStatus.labelAr, badgeBg, badgeFg)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatBox(
                    label = "إجمالي الوقود",
                    value = "${report.totalFuelLiters} لتر",
                    sub = "≈ ${formatNumber(report.estimatedFuelCost)} ر.ي",
                    modifier = Modifier.weight(1f)
                )
                MiniStatBox(
                    label = "المسافة المنجزة",
                    value = "${report.totalDistanceKm} كم",
                    sub = "${report.tripRows.size} رحلات",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatBox(
                    label = "المعدل/رحلة",
                    value = if (report.tripRows.isNotEmpty()) "${report.avgFuelPerTrip} لتر" else "—",
                    sub = "متوسط ${report.avgDistancePerTrip} كم",
                    modifier = Modifier.weight(1f)
                )
                MiniStatBox(
                    label = "مؤشر الكفاءة",
                    value = "${report.avgLitersPerKm} ل/كم",
                    sub = "مرجعي: ${report.referenceRatePerKm}",
                    modifier = Modifier.weight(1f)
                )
            }

            // 7-day Saturday-Friday Canvas Chart
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "التوزيع اليومي للوقود (السبت – الجمعة)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(86.dp)
                    ) {
                        if (size.width < 40f || size.height < 40f) return@Canvas
                        val slotW = size.width / 7f
                        val barW = (slotW * 0.45f).coerceIn(6f, 28f)
                        val maxFuel = if (report.maxDailyFuel.isFinite() && report.maxDailyFuel > 0.0) {
                            report.maxDailyFuel
                        } else {
                            1.0
                        }
                        val labelReserved = 24f
                        val maxBarH = (size.height - labelReserved - 6f).coerceAtLeast(8f)
                        report.dailyBuckets.forEachIndexed { idx, bucket ->
                            val x = idx * slotW + (slotW - barW) / 2f
                            val fuel = if (bucket.fuelLiters.isFinite() && bucket.fuelLiters > 0.0) {
                                bucket.fuelLiters
                            } else {
                                0.0
                            }
                            val barH = if (fuel > 0.0) {
                                ((fuel / maxFuel).toFloat() * maxBarH).coerceIn(8f, maxBarH)
                            } else 4f
                            val y = (size.height - labelReserved - barH).coerceAtLeast(0f)
                            val fillColor = when {
                                fuel <= 0.0 -> Color(0xFF334155)
                                bucket.isToday -> Color(0xFFFBBF24)
                                else -> Color(0xFFF59E0B)
                            }
                            drawRoundRect(
                                color = fillColor,
                                topLeft = Offset(x, y),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(6f, 6f)
                            )
                            runCatching {
                                val measured = textMeasurer.measure(
                                    text = bucket.dayShort,
                                    style = TextStyle(
                                        color = if (bucket.isToday) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    softWrap = false,
                                    maxLines = 1
                                )
                                val maxLabelX = (size.width - measured.size.width).coerceAtLeast(0f)
                                val maxLabelY = (size.height - measured.size.height).coerceAtLeast(0f)
                                val labelX = (idx * slotW + (slotW - measured.size.width) / 2f).coerceIn(0f, maxLabelX)
                                val labelY = (size.height - measured.size.height - 2f).coerceIn(0f, maxLabelY)
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(labelX, labelY)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStatBox(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, style = NumericTextStyle.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.domain.FleetFilterCriteria
import com.miyahna.water.domain.FleetSortOption
import com.miyahna.water.domain.FleetStatusSummary
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.common.formatNumber
import com.miyahna.water.ui.common.safeRoundToInt
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.NumericTextStyle
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

fun truckStatusColor(status: TruckStatus): Color = when (status) {
    TruckStatus.ON_TRIP -> SkyPrimary
    TruckStatus.LOADING -> AmberWarning
    TruckStatus.IDLE -> EmeraldAccent
    TruckStatus.MAINTENANCE -> RoseError
}

@Composable
fun FleetHeaderBanner(
    summary: FleetStatusSummary,
    isRealtimeConnected: Boolean,
    hasCloudUser: Boolean,
    isOnline: Boolean,
    onAddTruckClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onRequestCloudSignIn: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                        text = stringResource(R.string.fleet_dashboard_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusPill(
                            label = if (isRealtimeConnected) {
                                stringResource(R.string.fleet_live_stream_badge)
                            } else {
                                stringResource(R.string.fleet_local_mode_badge)
                            },
                            containerColor = if (isRealtimeConnected) EmeraldAccent.copy(alpha = 0.14f) else AmberWarning.copy(alpha = 0.14f),
                            contentColor = if (isRealtimeConnected) EmeraldAccent else AmberWarning
                        )
                        if (summary.movingCount > 0) {
                            StatusPill(
                                label = "${summary.movingCount} متحركة",
                                containerColor = SkyPrimary.copy(alpha = 0.14f),
                                contentColor = SkyPrimary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onSyncNowClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("fleet_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = stringResource(R.string.fleet_sync_now),
                        tint = SkyPrimary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAddTruckClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("fleet_add_truck_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.fleet_add_truck))
                }

                if (!hasCloudUser && isOnline) {
                    OutlinedButton(
                        onClick = onRequestCloudSignIn,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("fleet_connect_cloud_button")
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ربط البث السحابي", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WaterDrop,
                                contentDescription = null,
                                tint = SkyPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${formatNumber(summary.totalCurrentWaterLiters)} / ${formatNumber(summary.totalMaxCapacityLiters)} لتر",
                                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FleetStatusKpiStrip(
    summary: FleetStatusSummary,
    selectedStatus: TruckStatus?,
    onSelectStatus: (TruckStatus?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FleetStatusFilterPillCard(
            title = "الكل",
            count = summary.totalCount,
            accent = SkyPrimary,
            selected = selectedStatus == null,
            testTag = "fleet_kpi_all",
            onClick = { onSelectStatus(null) }
        )
        FleetStatusFilterPillCard(
            title = "في رحلة",
            count = summary.onTripCount,
            accent = SkyPrimary,
            selected = selectedStatus == TruckStatus.ON_TRIP,
            testTag = "fleet_kpi_on_trip",
            onClick = { onSelectStatus(if (selectedStatus == TruckStatus.ON_TRIP) null else TruckStatus.ON_TRIP) }
        )
        FleetStatusFilterPillCard(
            title = "تعبئة",
            count = summary.loadingCount,
            accent = AmberWarning,
            selected = selectedStatus == TruckStatus.LOADING,
            testTag = "fleet_kpi_loading",
            onClick = { onSelectStatus(if (selectedStatus == TruckStatus.LOADING) null else TruckStatus.LOADING) }
        )
        FleetStatusFilterPillCard(
            title = "متاح",
            count = summary.idleCount,
            accent = EmeraldAccent,
            selected = selectedStatus == TruckStatus.IDLE,
            testTag = "fleet_kpi_idle",
            onClick = { onSelectStatus(if (selectedStatus == TruckStatus.IDLE) null else TruckStatus.IDLE) }
        )
        FleetStatusFilterPillCard(
            title = "صيانة",
            count = summary.maintenanceCount,
            accent = RoseError,
            selected = selectedStatus == TruckStatus.MAINTENANCE,
            testTag = "fleet_kpi_maintenance",
            onClick = { onSelectStatus(if (selectedStatus == TruckStatus.MAINTENANCE) null else TruckStatus.MAINTENANCE) }
        )
    }
}

@Composable
private fun FleetStatusFilterPillCard(
    title: String,
    count: Int,
    accent: Color,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatNumber(count),
                style = NumericTextStyle.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    fontWeight = FontWeight.Bold
                ),
                color = accent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun FleetFilterControlsPanel(
    criteria: FleetFilterCriteria,
    totalCount: Int,
    filteredCount: Int,
    onQueryChange: (String) -> Unit,
    onToggleMovingOnly: () -> Unit,
    onToggleActiveTripOnly: () -> Unit,
    onSortChange: (FleetSortOption) -> Unit,
    onClearFilters: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = criteria.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.fleet_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (criteria.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fleet_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = criteria.movingOnly,
                    onClick = onToggleMovingOnly,
                    label = { Text(stringResource(R.string.fleet_filter_moving_only)) },
                    leadingIcon = {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("fleet_moving_only_chip")
                )
                FilterChip(
                    selected = criteria.activeTripOnly,
                    onClick = onToggleActiveTripOnly,
                    label = { Text(stringResource(R.string.fleet_filter_active_trip)) },
                    leadingIcon = {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("fleet_active_trip_chip")
                )
                FleetSortOption.entries.forEach { sortOpt ->
                    FilterChip(
                        selected = criteria.sortOption == sortOpt,
                        onClick = { onSortChange(sortOpt) },
                        label = { Text("ترتيب: ${sortOpt.labelAr}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("fleet_sort_${sortOpt.name.lowercase()}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "عرض $filteredCount من أصل $totalCount صهريج",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (criteria.hasActiveFilters) {
                    TextButton(
                        onClick = onClearFilters,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("fleet_clear_filters_button")
                    ) {
                        Text(stringResource(R.string.fleet_clear_filters))
                    }
                }
            }
        }
    }
}

@Composable
fun FleetTruckListCard(
    truck: Truck,
    activeTrip: Trip?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accent = truckStatusColor(truck.status)
    val loadRatio = if (truck.maxCapacityLiters > 0) {
        (truck.currentWaterLiters.toFloat() / truck.maxCapacityLiters.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fleet_truck_card_${truck.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) accent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = accent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalShipping,
                            contentDescription = truck.code,
                            tint = accent,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "${truck.code} · ${truck.plateNumber}",
                            style = NumericTextStyle.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = truck.model,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StatusPill(
                    label = truck.status.labelAr,
                    containerColor = accent.copy(alpha = 0.14f),
                    contentColor = accent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = truck.driverName.ifBlank { "غير معين" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(15.dp), tint = SkyPrimary)
                    Text(
                        text = "${truck.lastSpeed.safeRoundToInt()} كم/س",
                        style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize),
                        color = if (truck.lastSpeed.isFinite() && truck.lastSpeed >= 2.0) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "الحمولة: ${formatNumber(truck.currentWaterLiters)} / ${formatNumber(truck.maxCapacityLiters)} لتر",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeTrip != null) {
                        Text(
                            text = "رحلة ${activeTrip.tripNumber} (${activeTrip.gpsPoints.size} نقطة)",
                            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = SkyPrimary
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { loadRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GPS: ${formatLatLng(truck.lastLat, truck.lastLng, 4)}",
                    style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (truck.lastUpdateTimestamp.isNotBlank()) {
                    Text(
                        text = "آخر تحديث: ${truck.lastUpdateTimestamp.take(19).replace('T', ' ')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

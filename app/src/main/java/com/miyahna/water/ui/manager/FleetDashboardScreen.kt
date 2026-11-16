package com.miyahna.water.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.domain.FleetSortOption
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.common.EmptyStateCard
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.common.isValidLatLng

@Composable
fun FleetDashboardScreen(
    uiState: MiyahnaUiState,
    windowWidthSizeClass: WindowWidthSizeClass,
    onSearchQueryChange: (String) -> Unit,
    onStatusFilterChange: (TruckStatus?) -> Unit,
    onToggleMovingOnly: () -> Unit,
    onToggleActiveTripOnly: () -> Unit,
    onSortOptionChange: (FleetSortOption) -> Unit,
    onClearFilters: () -> Unit,
    onSelectTruck: (String?) -> Unit,
    onAddFleetTruck: (code: String, plateNumber: String, model: String, maxCapacityLiters: Int, driverId: String?) -> Unit,
    onUpdateTruckStatus: (String, TruckStatus) -> Unit,
    onSyncNow: () -> Unit,
    onOpenTripsTab: () -> Unit,
    onRequestCloudSignIn: () -> Unit
) {
    val domain = uiState.domain
    val allTrucks = uiState.allFleetTrucks
    val filteredTrucks = uiState.filteredFleetTrucks
    val summary = uiState.fleetSummary
    val criteria = uiState.fleetFilterCriteria

    var showCustomersLayer by remember { mutableStateOf(true) }
    var showAddTruckDialog by remember { mutableStateOf(false) }
    var focusLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val activeTripsByTruckId = remember(domain.trips) {
        domain.trips
            .filter { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
            .associateBy { it.truckId }
    }

    val selectedTruck: Truck? = remember(uiState.selectedFleetTruckId, filteredTrucks, allTrucks) {
        filteredTrucks.firstOrNull { it.id == uiState.selectedFleetTruckId }
            ?: allTrucks.firstOrNull { it.id == uiState.selectedFleetTruckId }
            ?: filteredTrucks.firstOrNull()
    }

    val selectedTrip: Trip? = remember(selectedTruck, activeTripsByTruckId, domain.trips) {
        if (selectedTruck == null) {
            null
        } else {
            activeTripsByTruckId[selectedTruck.id]
                ?: domain.trips.firstOrNull { it.id == selectedTruck.currentTripId }
        }
    }

    val handleTruckSelect: (String) -> Unit = { id ->
        onSelectTruck(id)
        allTrucks.firstOrNull { it.id == id }?.let { chosen ->
            if (isValidLatLng(chosen.lastLat, chosen.lastLng)) {
                focusLatLng = chosen.lastLat to chosen.lastLng
            }
        }
    }

    val isExpanded = windowWidthSizeClass != WindowWidthSizeClass.Compact

    if (isExpanded) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Pane: KPIs, Search/Filter Controls & Filtered Trucks List
            LazyColumn(
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    FleetHeaderBanner(
                        summary = summary,
                        isRealtimeConnected = domain.isRealtimeFleetConnected,
                        hasCloudUser = domain.cloudUser != null,
                        isOnline = domain.isOnline,
                        onAddTruckClick = { showAddTruckDialog = true },
                        onSyncNowClick = onSyncNow,
                        onRequestCloudSignIn = onRequestCloudSignIn
                    )
                }
                item {
                    FleetStatusKpiStrip(
                        summary = summary,
                        selectedStatus = criteria.statusFilter,
                        onSelectStatus = onStatusFilterChange
                    )
                }
                item {
                    FleetFilterControlsPanel(
                        criteria = criteria,
                        totalCount = allTrucks.size,
                        filteredCount = filteredTrucks.size,
                        onQueryChange = onSearchQueryChange,
                        onToggleMovingOnly = onToggleMovingOnly,
                        onToggleActiveTripOnly = onToggleActiveTripOnly,
                        onSortChange = onSortOptionChange,
                        onClearFilters = onClearFilters
                    )
                }

                if (filteredTrucks.isEmpty()) {
                    item {
                        FleetEmptyFilterCard(
                            hasActiveFilters = criteria.hasActiveFilters,
                            onClearFilters = onClearFilters
                        )
                    }
                } else {
                    itemsIndexed(filteredTrucks, key = { idx, truck -> "${truck.id}_$idx" }) { _, truck ->
                        FleetTruckListCard(
                            truck = truck,
                            activeTrip = activeTripsByTruckId[truck.id],
                            isSelected = selectedTruck?.id == truck.id,
                            onClick = { handleTruckSelect(truck.id) }
                        )
                    }
                }
            }

            // Right Pane: Interactive Map + Selected Truck Telemetry Inspector
            Column(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FleetMapCard(
                    uiState = uiState,
                    filteredTrucks = filteredTrucks,
                    selectedTruck = selectedTruck,
                    selectedTrip = selectedTrip,
                    showCustomersLayer = showCustomersLayer,
                    focusLatLng = focusLatLng,
                    onToggleCustomersLayer = { showCustomersLayer = !showCustomersLayer },
                    onCenterPlant = {
                        focusLatLng = domain.settings.plantLocation.lat to domain.settings.plantLocation.lng
                    },
                    onTruckSelected = handleTruckSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                if (selectedTruck != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectedTruckTelemetryCard(
                            truck = selectedTruck,
                            activeTrip = selectedTrip,
                            recentGpsEvents = domain.gpsEvents,
                            onCenterOnMap = {
                                if (isValidLatLng(selectedTruck.lastLat, selectedTruck.lastLng)) {
                                    focusLatLng = selectedTruck.lastLat to selectedTruck.lastLng
                                }
                            },
                            onUpdateStatus = { st -> onUpdateTruckStatus(selectedTruck.id, st) },
                            onOpenTripsTab = onOpenTripsTab
                        )
                    }
                }
            }
        }
    } else {
        // Compact Mobile Vertical Layout (keeps MapView mounted during scroll to prevent GL surface disposal)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FleetHeaderBanner(
                summary = summary,
                isRealtimeConnected = domain.isRealtimeFleetConnected,
                hasCloudUser = domain.cloudUser != null,
                isOnline = domain.isOnline,
                onAddTruckClick = { showAddTruckDialog = true },
                onSyncNowClick = onSyncNow,
                onRequestCloudSignIn = onRequestCloudSignIn
            )

            FleetStatusKpiStrip(
                summary = summary,
                selectedStatus = criteria.statusFilter,
                onSelectStatus = onStatusFilterChange
            )

            FleetMapCard(
                uiState = uiState,
                filteredTrucks = filteredTrucks,
                selectedTruck = selectedTruck,
                selectedTrip = selectedTrip,
                showCustomersLayer = showCustomersLayer,
                focusLatLng = focusLatLng,
                onToggleCustomersLayer = { showCustomersLayer = !showCustomersLayer },
                onCenterPlant = {
                    focusLatLng = domain.settings.plantLocation.lat to domain.settings.plantLocation.lng
                },
                onTruckSelected = handleTruckSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
            )

            FleetFilterControlsPanel(
                criteria = criteria,
                totalCount = allTrucks.size,
                filteredCount = filteredTrucks.size,
                onQueryChange = onSearchQueryChange,
                onToggleMovingOnly = onToggleMovingOnly,
                onToggleActiveTripOnly = onToggleActiveTripOnly,
                onSortChange = onSortOptionChange,
                onClearFilters = onClearFilters
            )

            if (selectedTruck != null) {
                SelectedTruckTelemetryCard(
                    truck = selectedTruck,
                    activeTrip = selectedTrip,
                    recentGpsEvents = domain.gpsEvents,
                    onCenterOnMap = {
                        if (isValidLatLng(selectedTruck.lastLat, selectedTruck.lastLng)) {
                            focusLatLng = selectedTruck.lastLat to selectedTruck.lastLng
                        }
                    },
                    onUpdateStatus = { st -> onUpdateTruckStatus(selectedTruck.id, st) },
                    onOpenTripsTab = onOpenTripsTab
                )
            }

            if (filteredTrucks.isEmpty()) {
                FleetEmptyFilterCard(
                    hasActiveFilters = criteria.hasActiveFilters,
                    onClearFilters = onClearFilters
                )
            } else {
                filteredTrucks.forEach { truck ->
                    FleetTruckListCard(
                        truck = truck,
                        activeTrip = activeTripsByTruckId[truck.id],
                        isSelected = selectedTruck?.id == truck.id,
                        onClick = { handleTruckSelect(truck.id) }
                    )
                }
            }
        }
    }

    if (showAddTruckDialog) {
        val nextIndex = allTrucks.size + 1
        val suggestedCode = "TRUCK-${nextIndex.toString().padStart(2, '0')}"
        AddFleetTruckDialog(
            suggestedCode = suggestedCode,
            drivers = domain.driverAccounts,
            onDismiss = { showAddTruckDialog = false },
            onConfirm = { code, plate, model, cap, driverId ->
                onAddFleetTruck(code, plate, model, cap, driverId)
                showAddTruckDialog = false
            }
        )
    }
}

@Composable
private fun FleetMapCard(
    uiState: MiyahnaUiState,
    filteredTrucks: List<Truck>,
    selectedTruck: Truck?,
    selectedTrip: Trip?,
    showCustomersLayer: Boolean,
    focusLatLng: Pair<Double, Double>?,
    onToggleCustomersLayer: () -> Unit,
    onCenterPlant: () -> Unit,
    onTruckSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val domain = uiState.domain
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showCustomersLayer,
                    onClick = onToggleCustomersLayer,
                    label = { Text(stringResource(R.string.fleet_show_customers)) },
                    leadingIcon = {
                        Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("fleet_toggle_customers_chip")
                )
                FilterChip(
                    selected = false,
                    onClick = onCenterPlant,
                    label = { Text(stringResource(R.string.fleet_center_on_plant)) },
                    leadingIcon = {
                        Icon(Icons.Default.Factory, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("fleet_center_plant_chip")
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                MaribInteractiveMap(
                    plantLocation = domain.settings.plantLocation,
                    geofenceMeters = domain.settings.plantGeofenceMeters,
                    customers = domain.customers,
                    truck = domain.truck,
                    trucks = filteredTrucks,
                    selectedTruckId = selectedTruck?.id,
                    onTruckSelected = onTruckSelected,
                    showCustomers = showCustomersLayer,
                    focusLatLng = focusLatLng,
                    plannedStops = selectedTrip?.customerStops.orEmpty(),
                    gpsPoints = selectedTrip?.gpsPoints.orEmpty(),
                    detectedStops = selectedTrip?.detectedStops.orEmpty(),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("fleet_interactive_map")
                )
            }
        }
    }
}

@Composable
private fun FleetEmptyFilterCard(
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EmptyStateCard(
            icon = Icons.Default.LocalShipping,
            title = stringResource(R.string.fleet_no_matching_trucks),
            subtitle = stringResource(R.string.fleet_no_matching_trucks_sub)
        )
        if (hasActiveFilters) {
            Button(
                onClick = onClearFilters,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("fleet_empty_clear_filters_button")
            ) {
                Text(stringResource(R.string.fleet_clear_filters))
            }
        }
    }
}

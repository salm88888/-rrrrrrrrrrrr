package com.miyahna.water.ui.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.ui.ManagerTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

private enum class ManagerSection(
    val labelAr: String,
    val icon: ImageVector,
    val defaultTab: ManagerTab,
    val tabs: List<ManagerTab>
) {
    HOME(
        labelAr = "الرئيسية",
        icon = Icons.Default.Dashboard,
        defaultTab = ManagerTab.OVERVIEW,
        tabs = listOf(ManagerTab.OVERVIEW)
    ),
    OPERATIONS(
        labelAr = "العمليات",
        icon = Icons.Default.Directions,
        defaultTab = ManagerTab.ORDERS,
        tabs = listOf(ManagerTab.ORDERS, ManagerTab.CLUSTERING, ManagerTab.TRIPS)
    ),
    MAP_FLEET(
        labelAr = "الخريطة",
        icon = Icons.Default.Map,
        defaultTab = ManagerTab.FLEET_LIVE,
        tabs = listOf(ManagerTab.FLEET_LIVE, ManagerTab.MAP)
    ),
    ADMIN(
        labelAr = "الإدارة",
        icon = Icons.Default.AdminPanelSettings,
        defaultTab = ManagerTab.CUSTOMERS,
        tabs = listOf(
            ManagerTab.CUSTOMERS,
            ManagerTab.INVOICES,
            ManagerTab.RECONCILIATION,
            ManagerTab.EXPENSES,
            ManagerTab.PROFITABILITY,
            ManagerTab.DRIVERS,
            ManagerTab.SETTINGS
        )
    )
}

private fun sectionForTab(tab: ManagerTab): ManagerSection = when (tab) {
    ManagerTab.OVERVIEW -> ManagerSection.HOME
    ManagerTab.ORDERS, ManagerTab.CLUSTERING, ManagerTab.TRIPS -> ManagerSection.OPERATIONS
    ManagerTab.FLEET_LIVE, ManagerTab.MAP -> ManagerSection.MAP_FLEET
    ManagerTab.CUSTOMERS,
    ManagerTab.INVOICES,
    ManagerTab.RECONCILIATION,
    ManagerTab.EXPENSES,
    ManagerTab.PROFITABILITY,
    ManagerTab.DRIVERS,
    ManagerTab.SETTINGS -> ManagerSection.ADMIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerShellScreen(
    uiState: MiyahnaUiState,
    windowWidthSizeClass: WindowWidthSizeClass,
    viewModel: MiyahnaViewModel,
    onRequestCloudSignIn: () -> Unit
) {
    val domain = uiState.domain
    val useRail = windowWidthSizeClass != WindowWidthSizeClass.Compact
    val activeSection = sectionForTab(uiState.managerTab)

    var showNewOrderModal by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showDirectiveDialog by remember { mutableStateOf(false) }

    if (uiState.managerTab != ManagerTab.OVERVIEW) {
        BackHandler { viewModel.setManagerTab(ManagerTab.OVERVIEW) }
    }

    val unreadManagerNotifs = domain.notifications.count { it.targetRole == "manager" && !it.isRead }
    val waitingOrdersCount = domain.orders.count {
        it.status == OrderStatus.WAITING_CLUSTERING || it.status == OrderStatus.NEW || it.status == OrderStatus.CONFIRMED
    }
    val activeTripsCount = domain.trips.count {
        it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED || it.status == TripStatus.PENDING_APPROVAL || it.status == TripStatus.COMPLETED_PENDING_REVIEW
    }
    val pendingDriversCount = domain.driverAccounts.count { it.status == DriverAccountStatus.PENDING }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Column {
                        Text(
                            text = uiState.managerTab.labelAr,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                !domain.isOnline -> "غير متصل بالشبكة"
                                domain.cloudUser != null -> "Firebase متصل · ${domain.cloudUser.email}"
                                else -> "متصل · اضغط أيقونة السحابة لربط Firebase"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                !domain.isOnline -> RoseError
                                domain.cloudUser != null -> EmeraldAccent
                                else -> AmberWarning
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (domain.cloudUser == null) onRequestCloudSignIn() else viewModel.syncNow()
                        },
                        modifier = Modifier.testTag("manager_firebase_status_button")
                    ) {
                        Icon(
                            imageVector = if (domain.cloudUser != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = "حالة ومزامنة Firebase",
                            tint = if (domain.cloudUser != null) EmeraldAccent else AmberWarning
                        )
                    }
                    IconButton(onClick = { showDirectiveDialog = true }) {
                        Icon(Icons.Default.Send, contentDescription = "إرسال توجيه للسائق", tint = SkyPrimary)
                    }
                    IconButton(onClick = { showNotificationsDialog = true }) {
                        BadgedBox(
                            badge = {
                                if (unreadManagerNotifs > 0) {
                                    Badge { Text(unreadManagerNotifs.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "الإشعارات")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.lockManagerSession() },
                        modifier = Modifier.testTag("manager_lock_session_button")
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "قفل برمز PIN")
                    }
                    IconButton(onClick = { viewModel.switchRolePortal() }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "تبديل الدور")
                    }
                }
            )
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar(
                    windowInsets = WindowInsets.navigationBars,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    ManagerSection.entries.forEach { section ->
                        val badgeCount = when (section) {
                            ManagerSection.OPERATIONS -> waitingOrdersCount + activeTripsCount
                            ManagerSection.ADMIN -> pendingDriversCount
                            else -> 0
                        }
                        NavigationBarItem(
                            selected = activeSection == section,
                            onClick = { viewModel.setManagerTab(section.defaultTab) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (badgeCount > 0) {
                                            Badge { Text(badgeCount.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(section.icon, contentDescription = section.labelAr)
                                }
                            },
                            label = { Text(section.labelAr, maxLines = 1) },
                            modifier = Modifier.testTag("manager_nav_${section.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (useRail) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ManagerSection.entries.forEach { section ->
                        NavigationRailItem(
                            selected = activeSection == section,
                            onClick = { viewModel.setManagerTab(section.defaultTab) },
                            icon = { Icon(section.icon, contentDescription = section.labelAr) },
                            label = { Text(section.labelAr, maxLines = 1) }
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (activeSection.tabs.size > 1) {
                    ManagerSubTabStrip(
                        tabs = activeSection.tabs,
                        selectedTab = uiState.managerTab,
                        ordersCount = domain.orders.size,
                        clustersCount = uiState.clusters.size,
                        tripsCount = domain.trips.size,
                        pendingDriversCount = pendingDriversCount,
                        onSelectTab = viewModel::setManagerTab
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (uiState.managerTab) {
                        ManagerTab.OVERVIEW -> DashboardOverviewScreen(
                            uiState = uiState,
                            onNavigateTab = { viewModel.setManagerTab(it) },
                            onOpenNewOrder = {
                                showNewOrderModal = true
                                viewModel.setManagerTab(ManagerTab.ORDERS)
                            },
                            onRequestCloudSignIn = onRequestCloudSignIn,
                            onSyncNow = viewModel::syncNow
                        )
                        ManagerTab.FLEET_LIVE -> FleetDashboardScreen(
                            uiState = uiState,
                            windowWidthSizeClass = windowWidthSizeClass,
                            onSearchQueryChange = viewModel::setFleetSearchQuery,
                            onStatusFilterChange = viewModel::setFleetStatusFilter,
                            onToggleMovingOnly = viewModel::toggleFleetMovingOnly,
                            onToggleActiveTripOnly = viewModel::toggleFleetActiveTripOnly,
                            onSortOptionChange = viewModel::setFleetSortOption,
                            onClearFilters = viewModel::clearFleetFilters,
                            onSelectTruck = viewModel::selectFleetTruck,
                            onAddFleetTruck = viewModel::addFleetTruck,
                            onUpdateTruckStatus = viewModel::updateFleetTruckStatus,
                            onSyncNow = viewModel::syncNow,
                            onOpenTripsTab = { viewModel.setManagerTab(ManagerTab.TRIPS) },
                            onRequestCloudSignIn = onRequestCloudSignIn
                        )
                        ManagerTab.ORDERS -> OrderManagementScreen(
                            orders = domain.orders,
                            customers = domain.customers,
                            districts = uiState.mergedDistricts,
                            settings = domain.settings,
                            onAddOrder = viewModel::addOrder,
                            onCancelOrder = viewModel::cancelOrder,
                            onAddCustomDistrict = viewModel::addCustomDistrict,
                            showNewOrderDialogInitially = showNewOrderModal,
                            onNewOrderDialogDismissed = { showNewOrderModal = false }
                        )
                        ManagerTab.CLUSTERING -> ClusteringEngineScreen(
                            clusters = uiState.clusters,
                            maxTruckCapacity = domain.truck.maxCapacityLiters,
                            onApproveAndCreateTrip = viewModel::createTripFromCluster
                        )
                        ManagerTab.TRIPS -> TripsManagementScreen(
                            trips = domain.trips,
                            deliveryLocations = uiState.deliveryLocations,
                            settings = domain.settings,
                            gpsEvents = domain.gpsEvents,
                            tripStateTransitions = domain.tripStateTransitions,
                            onApproveTrip = viewModel::approveTrip,
                            onRejectTrip = viewModel::rejectTrip,
                            onPauseTrip = { id, reason -> viewModel.pauseTrip(id, reason, actorRole = "manager") },
                            onResumeTrip = { id, reason -> viewModel.resumeTrip(id, reason, actorRole = "manager") },
                            onStopTracking = viewModel::stopTripTracking,
                            onCloseTrip = viewModel::closeTrip,
                            onDeleteTrip = viewModel::deleteTrip,
                            onLogDeliveryForStop = viewModel::logManagerDelivery,
                            onReviewModRequest = viewModel::reviewModificationRequest,
                            onMarkTransitionReviewed = viewModel::markTransitionReviewed
                        )
                        ManagerTab.MAP -> MaribMapScreen(
                            settings = domain.settings,
                            truck = domain.truck,
                            fleetTrucks = uiState.allFleetTrucks,
                            customers = domain.customers,
                            orders = domain.orders,
                            trips = domain.trips,
                            onUpdatePlantLocation = { lat, lng ->
                                viewModel.updateSettings(
                                    domain.settings.copy(
                                        plantLocation = domain.settings.plantLocation.copy(lat = lat, lng = lng)
                                    )
                                )
                            }
                        )
                        ManagerTab.CUSTOMERS -> CustomerManagementScreen(
                            customers = uiState.filteredCustomers,
                            totalCustomersCount = domain.customers.size,
                            searchQuery = uiState.customerSearchQuery,
                            onSearchQueryChange = viewModel::setCustomerSearchQuery,
                            districts = uiState.mergedDistricts,
                            settings = domain.settings,
                            onAddCustomer = viewModel::addCustomer,
                            onUpdateCustomer = { id, name, phone, alt, dist, addr, lat, lng, cap, notes ->
                                viewModel.updateCustomer(id, name, phone, alt, dist, addr, lat, lng, cap, notes)
                            },
                            onDeleteCustomer = viewModel::deleteCustomer,
                            onAddCustomDistrict = viewModel::addCustomDistrict
                        )
                        ManagerTab.INVOICES -> InvoicingAndCashScreen(invoices = domain.invoices)
                        ManagerTab.RECONCILIATION -> DriverReconciliationScreen(
                            trips = domain.trips,
                            reconciliations = domain.reconciliations,
                            onSubmitReconciliation = { id, cash, exp, notes ->
                                viewModel.submitReconciliation(id, cash, exp, notes)
                            }
                        )
                        ManagerTab.EXPENSES -> ExpensesAndFuelScreen(
                            expenses = domain.expenses,
                            trips = domain.trips,
                            onAddExpense = viewModel::addExpense
                        )
                        ManagerTab.PROFITABILITY -> ProfitabilityAnalyticsScreen(
                            trips = domain.trips,
                            invoices = domain.invoices,
                            customers = domain.customers,
                            settings = domain.settings
                        )
                        ManagerTab.DRIVERS -> DriverAndTruckScreen(
                            truck = domain.truck,
                            fleetTrucks = uiState.allFleetTrucks,
                            drivers = domain.driverAccounts,
                            onUpdateTruck = viewModel::updateTruck,
                            onOpenFleetDashboard = { viewModel.setManagerTab(ManagerTab.FLEET_LIVE) },
                            onApproveDriver = viewModel::approveDriver,
                            onRejectDriver = viewModel::rejectDriver,
                            onToggleSuspendDriver = viewModel::toggleSuspendDriver,
                            cloudUser = domain.cloudUser,
                            onAddDriverByManager = viewModel::createDriverByManager,
                            onUpdateDriverGoogleEmail = viewModel::updateDriverGoogleEmail
                        )
                        ManagerTab.SETTINGS -> SystemSettingsScreen(
                            settings = domain.settings,
                            cloudUser = domain.cloudUser,
                            onSaveSettings = viewModel::updateSettings,
                            onChangePin = viewModel::changeManagerPin,
                            onCloudSignIn = onRequestCloudSignIn,
                            onCloudSignOut = viewModel::signOutCloud,
                            onClearOperationalData = viewModel::clearOperationalData
                        )
                    }
                }
            }
        }
    }

    if (showNotificationsDialog) {
        val managerNotifs = domain.notifications.filter { it.targetRole == "manager" }
        AlertDialog(
            onDismissRequest = { showNotificationsDialog = false },
            title = { Text("مركز إشعارات الإدارة (${managerNotifs.size})") },
            text = {
                if (managerNotifs.isEmpty()) {
                    Text("لا توجد إشعارات حالياً")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(managerNotifs, key = { idx, n -> "${n.id}_$idx" }) { _, n ->
                            Column {
                                Text(n.title, style = MaterialTheme.typography.titleMedium)
                                Text(n.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.markAllNotificationsRead("manager")
                    showNotificationsDialog = false
                }) { Text("تمت القراءة") }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationsDialog = false }) { Text("إغلاق") }
            }
        )
    }

    if (showDirectiveDialog) {
        var title by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var urgent by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDirectiveDialog = false },
            title = { Text("إرسال توجيه ميداني للسائق") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("عنوان التوجيه") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = msg, onValueChange = { msg = it }, label = { Text("تفاصيل التوجيه") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = urgent, onCheckedChange = { urgent = it })
                        Text("توجيه عاجل")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank() && msg.isNotBlank()) {
                        viewModel.sendDirective(title, msg, urgent)
                        showDirectiveDialog = false
                    }
                }) { Text("إرسال") }
            },
            dismissButton = { TextButton(onClick = { showDirectiveDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun ManagerSubTabStrip(
    tabs: List<ManagerTab>,
    selectedTab: ManagerTab,
    ordersCount: Int,
    clustersCount: Int,
    tripsCount: Int,
    pendingDriversCount: Int,
    onSelectTab: (ManagerTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val labelText = when (tab) {
                        ManagerTab.ORDERS -> "الطلبات ($ordersCount)"
                        ManagerTab.CLUSTERING -> "التجميع ($clustersCount)"
                        ManagerTab.TRIPS -> "الرحلات ($tripsCount)"
                        ManagerTab.DRIVERS -> if (pendingDriversCount > 0) "السائقين ($pendingDriversCount)" else tab.labelAr
                        else -> tab.labelAr
                    }
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(labelText, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = iconForManagerTab(tab),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyPrimary.copy(alpha = 0.14f),
                            selectedLabelColor = SkyPrimary,
                            selectedLeadingIconColor = SkyPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("manager_subtab_${tab.name.lowercase()}")
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        }
    }
}

private fun iconForManagerTab(tab: ManagerTab): ImageVector = when (tab) {
    ManagerTab.OVERVIEW -> Icons.Default.Dashboard
    ManagerTab.FLEET_LIVE -> Icons.Default.MyLocation
    ManagerTab.ORDERS -> Icons.Default.ShoppingCart
    ManagerTab.CLUSTERING -> Icons.Default.AutoAwesome
    ManagerTab.TRIPS -> Icons.Default.Directions
    ManagerTab.MAP -> Icons.Default.Map
    ManagerTab.CUSTOMERS -> Icons.Default.People
    ManagerTab.INVOICES -> Icons.Default.ReceiptLong
    ManagerTab.RECONCILIATION -> Icons.Default.AccountBalanceWallet
    ManagerTab.EXPENSES -> Icons.Default.LocalGasStation
    ManagerTab.PROFITABILITY -> Icons.Default.TrendingUp
    ManagerTab.DRIVERS -> Icons.Default.LocalShipping
    ManagerTab.SETTINGS -> Icons.Default.Settings
}

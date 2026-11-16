package com.miyahna.water.ui.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.miyahna.water.ui.ManagerTab
import com.miyahna.water.ui.MiyahnaUiState
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.theme.SkyPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerShellScreen(
    uiState: MiyahnaUiState,
    windowWidthSizeClass: WindowWidthSizeClass,
    viewModel: MiyahnaViewModel,
    onRequestCloudSignIn: () -> Unit
) {
    val domain = uiState.domain
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val useRail = windowWidthSizeClass != WindowWidthSizeClass.Compact

    var showNewOrderModal by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showDirectiveDialog by remember { mutableStateOf(false) }

    if (uiState.managerTab != ManagerTab.OVERVIEW) {
        BackHandler { viewModel.setManagerTab(ManagerTab.OVERVIEW) }
    }

    val unreadManagerNotifs = domain.notifications.count { it.targetRole == "manager" && !it.isRead }

    val navContent: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        if (!useRail) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("manager_menu_button")
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "القائمة الجانبية")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(uiState.managerTab.labelAr, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (domain.isOnline) "متصل بالشبكة" else "وضع عدم الاتصال (محلي)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
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
                        IconButton(onClick = { viewModel.syncNow() }) {
                            Icon(Icons.Default.Sync, contentDescription = "مزامنة")
                        }
                        IconButton(onClick = { viewModel.switchRolePortal() }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "تبديل الدور")
                        }
                    }
                )
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (useRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            ManagerTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = uiState.managerTab == tab,
                                    onClick = { viewModel.setManagerTab(tab) },
                                    icon = { Icon(iconForManagerTab(tab), contentDescription = tab.labelAr) },
                                    label = { Text(tab.labelAr, maxLines = 1) }
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (uiState.managerTab) {
                        ManagerTab.OVERVIEW -> DashboardOverviewScreen(
                            uiState = uiState,
                            onNavigateTab = { viewModel.setManagerTab(it) },
                            onOpenNewOrder = {
                                showNewOrderModal = true
                                viewModel.setManagerTab(ManagerTab.ORDERS)
                            }
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
                            onLogDeliveryForStop = viewModel::logManagerDelivery,
                            onReviewModRequest = viewModel::reviewModificationRequest,
                            onMarkTransitionReviewed = viewModel::markTransitionReviewed
                        )
                        ManagerTab.MAP -> MaribMapScreen(
                            settings = domain.settings,
                            truck = domain.truck,
                            fleetTrucks = uiState.allFleetTrucks,
                            customers = domain.customers,
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
                            onUpdateCustomer = viewModel::updateCustomer,
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
                            onToggleSuspendDriver = viewModel::toggleSuspendDriver
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

    if (useRail) {
        navContent()
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "إدارة عمليات مياهنا",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(6.dp))
                        ManagerTab.entries.forEach { tab ->
                            NavigationDrawerItem(
                                selected = uiState.managerTab == tab,
                                onClick = {
                                    viewModel.setManagerTab(tab)
                                    scope.launch { drawerState.close() }
                                },
                                icon = { Icon(iconForManagerTab(tab), contentDescription = null) },
                                label = { Text(tab.labelAr) },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            content = navContent
        )
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

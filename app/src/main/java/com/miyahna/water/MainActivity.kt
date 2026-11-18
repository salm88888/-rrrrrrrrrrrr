package com.miyahna.water

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.miyahna.water.data.location.DeviceLocationHelper
import com.miyahna.water.data.notifications.MiyahnaNotificationHelper
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.ui.MiyahnaViewModel
import com.miyahna.water.ui.auth.DriverAuthScreen
import com.miyahna.water.ui.auth.RoleSelectionPortalScreen
import com.miyahna.water.ui.driver.DriverAppScreen
import com.miyahna.water.ui.manager.ManagerShellScreen
import com.miyahna.water.ui.theme.MiyahnaTheme
import kotlinx.serialization.Serializable

@Serializable
object PortalRoute

@Serializable
object ManagerRoute

@Serializable
object DriverAuthRoute

@Serializable
object DriverAppRoute

private enum class RootDestination {
    PORTAL,
    MANAGER,
    DRIVER_AUTH,
    DRIVER_APP
}

private data class NotificationDeepLink(
    val targetRole: String?,
    val actionTab: String?,
    val nonce: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    private val deepLinkState = mutableStateOf<NotificationDeepLink?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureNotificationIntent(intent)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val vm: MiyahnaViewModel = viewModel()
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val activityContext = this
            val pendingDeepLink by deepLinkState

            LaunchedEffect(Unit) {
                vm.toastMessage.collect { msg ->
                    snackbarHostState.showSnackbar(msg)
                }
            }

            LaunchedEffect(Unit) {
                vm.minimizeAppRequest.collect {
                    kotlinx.coroutines.delay(250)
                    if (!activityContext.isFinishing && !activityContext.isDestroyed) {
                        runCatching { activityContext.moveTaskToBack(true) }
                    }
                }
            }

            var attemptedAutoCloudSignIn by remember { mutableStateOf(false) }
            LaunchedEffect(uiState.domain.isLoaded, uiState.domain.isOnline, uiState.domain.cloudUser) {
                if (uiState.domain.isLoaded &&
                    uiState.domain.isOnline &&
                    uiState.domain.cloudUser == null &&
                    !attemptedAutoCloudSignIn
                ) {
                    attemptedAutoCloudSignIn = true
                    vm.attemptCloudAutoSignIn(activityContext)
                }
            }

            LaunchedEffect(pendingDeepLink, uiState.domain.isLoaded) {
                val link = pendingDeepLink ?: return@LaunchedEffect
                if (uiState.domain.isLoaded) {
                    vm.openTabFromNotification(link.targetRole, link.actionTab)
                    deepLinkState.value = null
                }
            }

            val useDriverDarkTheme =
                uiState.domain.savedRole == AppRole.DRIVER && uiState.domain.driverSession != null

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MiyahnaTheme(darkDriverTheme = useDriverDarkTheme) {
                    MiyahnaRootHost(
                        uiState = uiState,
                        windowWidthSizeClass = windowSizeClass.widthSizeClass,
                        snackbarHostState = snackbarHostState,
                        viewModel = vm,
                        onCloudSignIn = { vm.signInCloudWithGoogle(activityContext) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)
    }

    private fun captureNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MiyahnaNotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false) == true) {
            deepLinkState.value = NotificationDeepLink(
                targetRole = intent.getStringExtra(MiyahnaNotificationHelper.EXTRA_TARGET_ROLE),
                actionTab = intent.getStringExtra(MiyahnaNotificationHelper.EXTRA_ACTION_TAB)
            )
        }
    }
}

@Composable
private fun MiyahnaRootHost(
    uiState: com.miyahna.water.ui.MiyahnaUiState,
    windowWidthSizeClass: androidx.compose.material3.windowsizeclass.WindowWidthSizeClass,
    snackbarHostState: SnackbarHostState,
    viewModel: MiyahnaViewModel,
    onCloudSignIn: () -> Unit
) {
    val domain = uiState.domain
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()

    fun checkLocationPermissionNow(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun checkNotificationPermissionNow(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    var hasLocationPermission by remember { mutableStateOf(checkLocationPermissionNow()) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermissionNow()) }
    var isDeviceGpsEnabled by remember {
        mutableStateOf(DeviceLocationHelper.isDeviceGpsEnabled(context)).also {
            viewModel.setDeviceGpsEnabled(it.value)
        }
    }
    var promptedNotificationsOnce by remember { mutableStateOf(false) }

    fun refreshLocationAndGpsState() {
        hasLocationPermission = checkLocationPermissionNow()
        hasNotificationPermission = checkNotificationPermissionNow()
        val gpsNow = DeviceLocationHelper.isDeviceGpsEnabled(context)
        isDeviceGpsEnabled = gpsNow
        viewModel.setDeviceGpsEnabled(gpsNow)
        if (domain.isLoaded &&
            domain.savedRole == AppRole.DRIVER &&
            domain.driverSession != null &&
            hasLocationPermission
        ) {
            viewModel.ensureDriverBackgroundGpsRunning()
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshLocationAndGpsState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val providerReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshLocationAndGpsState()
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, providerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(providerReceiver) }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        refreshLocationAndGpsState()
    }

    val requestEnableDeviceGps: () -> Unit = {
        val activity = context as? Activity
        if (activity != null) {
            DeviceLocationHelper.promptEnableDeviceGps(
                activity = activity,
                resolutionLauncher = locationSettingsLauncher,
                onAlreadyEnabled = { refreshLocationAndGpsState() }
            )
        } else {
            DeviceLocationHelper.openLocationSettings(context)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            checkLocationPermissionNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
                checkNotificationPermissionNow()
        }
        val gpsNow = DeviceLocationHelper.isDeviceGpsEnabled(context)
        isDeviceGpsEnabled = gpsNow
        viewModel.setDeviceGpsEnabled(gpsNow)
        if (hasLocationPermission && !gpsNow) {
            requestEnableDeviceGps()
        }
        if (hasLocationPermission && domain.savedRole == AppRole.DRIVER && domain.driverSession != null) {
            viewModel.ensureDriverBackgroundGpsRunning()
        }
    }

    val requestLocationAndNotificationPermissions: () -> Unit = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        runCatching { permissionLauncher.launch(perms) }
    }

    if (!domain.isLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Request POST_NOTIFICATIONS cleanly on Android 13+ for both Manager & Driver roles
    LaunchedEffect(domain.isLoaded, domain.savedRole, domain.driverSession, hasNotificationPermission) {
        val inActiveRole = domain.savedRole == AppRole.MANAGER ||
            (domain.savedRole == AppRole.DRIVER && domain.driverSession != null)
        if (inActiveRole &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission &&
            !promptedNotificationsOnce
        ) {
            promptedNotificationsOnce = true
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Keep background GPS active whenever the app is in Driver mode
    LaunchedEffect(domain.isLoaded, domain.savedRole, domain.driverSession, hasLocationPermission, isDeviceGpsEnabled) {
        if (domain.isLoaded && domain.savedRole == AppRole.DRIVER && domain.driverSession != null && hasLocationPermission) {
            viewModel.ensureDriverBackgroundGpsRunning()
        }
    }

    // Keep Navigation Compose backstack synchronized with role/session transitions without tearing down unchanged routes
    val targetDestination = when (domain.savedRole) {
        null -> RootDestination.PORTAL
        AppRole.MANAGER -> RootDestination.MANAGER
        AppRole.DRIVER -> if (domain.driverSession != null) RootDestination.DRIVER_APP else RootDestination.DRIVER_AUTH
    }

    LaunchedEffect(targetDestination) {
        val currentDest = navController.currentBackStackEntry?.destination
        val alreadyOnTarget = when (targetDestination) {
            RootDestination.PORTAL -> currentDest?.hasRoute<PortalRoute>() == true
            RootDestination.MANAGER -> currentDest?.hasRoute<ManagerRoute>() == true
            RootDestination.DRIVER_AUTH -> currentDest?.hasRoute<DriverAuthRoute>() == true
            RootDestination.DRIVER_APP -> currentDest?.hasRoute<DriverAppRoute>() == true
        }
        if (!alreadyOnTarget) {
            val targetRoute: Any = when (targetDestination) {
                RootDestination.PORTAL -> PortalRoute
                RootDestination.MANAGER -> ManagerRoute
                RootDestination.DRIVER_AUTH -> DriverAuthRoute
                RootDestination.DRIVER_APP -> DriverAppRoute
            }
            val startDestId = runCatching { navController.graph.findStartDestination().id }.getOrNull()
            runCatching {
                navController.navigate(targetRoute) {
                    if (startDestId != null) {
                        popUpTo(startDestId) { inclusive = true }
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    val initialRoute: Any = remember {
        when (targetDestination) {
            RootDestination.PORTAL -> PortalRoute
            RootDestination.MANAGER -> ManagerRoute
            RootDestination.DRIVER_AUTH -> DriverAuthRoute
            RootDestination.DRIVER_APP -> DriverAppRoute
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = initialRoute,
            modifier = Modifier.fillMaxSize()
        ) {
            composable<PortalRoute> {
                RoleSelectionPortalScreen(
                    isOnline = domain.isOnline,
                    cloudUser = domain.cloudUser,
                    pendingDriversCount = domain.driverAccounts.count { it.status == DriverAccountStatus.PENDING },
                    onVerifyManagerPin = viewModel::verifyAndEnterManager,
                    onSelectDriver = viewModel::enterDriverPortal,
                    onCloudSignIn = onCloudSignIn,
                    onCloudSignOut = viewModel::signOutCloud,
                    isManagerUnlocked = domain.isManagerUnlocked,
                    hasSavedDriverSession = domain.driverSession != null,
                    onEnterManagerDirectly = viewModel::enterManagerDirectly
                )
            }
            composable<ManagerRoute> {
                ManagerShellScreen(
                    uiState = uiState,
                    windowWidthSizeClass = windowWidthSizeClass,
                    viewModel = viewModel,
                    onRequestCloudSignIn = onCloudSignIn
                )
            }
            composable<DriverAuthRoute> {
                DriverAuthScreen(
                    onBackToPortal = viewModel::switchRolePortal,
                    onLogin = viewModel::loginDriver,
                    onRegister = viewModel::registerDriver,
                    isOnline = domain.isOnline,
                    cloudUser = domain.cloudUser,
                    onCloudSignIn = onCloudSignIn
                )
            }
            composable<DriverAppRoute> {
                DriverAppScreen(
                    uiState = uiState,
                    hasLocationPermission = hasLocationPermission,
                    isDeviceGpsEnabled = isDeviceGpsEnabled,
                    onRequestLocationPermission = requestLocationAndNotificationPermissions,
                    onEnableDeviceGps = requestEnableDeviceGps,
                    onRequestCloudSignIn = onCloudSignIn,
                    viewModel = viewModel
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

package com.miyahna.water.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.miyahna.water.data.cloud.CloudSnapshot
import com.miyahna.water.data.cloud.CloudUserInfo
import com.miyahna.water.data.cloud.FirebaseSyncManager
import com.miyahna.water.data.cloud.toFriendlyFirestoreMessage
import com.miyahna.water.data.local.AppStateSnapshotEntity
import com.miyahna.water.data.local.AuthSessionPrefs
import com.miyahna.water.data.local.CustomerDao
import com.miyahna.water.data.local.DeliveryLocationDao
import com.miyahna.water.data.local.MiyahnaStateDao
import com.miyahna.water.data.local.PendingOfflineOpEntity
import com.miyahna.water.data.local.TripGpsPointDao
import com.miyahna.water.data.local.TripStateTransitionDao
import com.miyahna.water.data.local.toDomain
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.data.location.DeliveryLocationCaptureService
import com.miyahna.water.data.location.DeviceLocationHelper
import com.miyahna.water.data.location.LocationTrackingPrefs
import com.miyahna.water.data.location.LocationTrackingService
import com.miyahna.water.data.notifications.MiyahnaNotificationHelper
import com.miyahna.water.data.sync.TripSyncWorker
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.ClusterProposal
import com.miyahna.water.domain.ClusteringEngine
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DeliveryLocationLog
import com.miyahna.water.domain.DepartureRecommendation
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.DriverSession
import com.miyahna.water.domain.Expense
import com.miyahna.water.domain.ExpenseCategory
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.GPSEventType
import com.miyahna.water.domain.GPSGap
import com.miyahna.water.domain.GeoLocationPoint
import com.miyahna.water.domain.GpsTrackingEngine
import com.miyahna.water.domain.Invoice
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.ModificationRequest
import com.miyahna.water.domain.NotificationPriority
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.ReturnLocationInfo
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripAuditEntry
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.TripSyncStatus
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

data class MiyahnaDomainState(
    val isLoaded: Boolean = false,
    val isOnline: Boolean = true,
    val savedRole: AppRole? = null,
    val isManagerUnlocked: Boolean = false,
    val driverSession: DriverSession? = null,
    val settings: SystemSettings = SystemSettings(),
    val truck: Truck = Truck(),
    val fleetTrucks: List<Truck> = emptyList(),
    val driverAccounts: List<DriverAccount> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val orders: List<Order> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val reconciliations: List<DriverReconciliation> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val gpsEvents: List<GPSEvent> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val directives: List<ManagerDirective> = emptyList(),
    val tripStateTransitions: List<TripStateTransition> = emptyList(),
    val pendingSyncCount: Int = 0,
    val cloudUser: CloudUserInfo? = null,
    val isCloudSyncing: Boolean = false,
    val isRealtimeFleetConnected: Boolean = false,
    val lastCloudSyncAt: String? = null,
    val cloudSyncError: String? = null
)

data class ActionResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val warning: Boolean = false
)

class MiyahnaRepository(
    private val context: Context,
    private val stateDao: MiyahnaStateDao,
    private val customerDao: CustomerDao,
    private val deliveryLocationDao: DeliveryLocationDao,
    private val tripGpsPointDao: TripGpsPointDao,
    private val tripStateTransitionDao: TripStateTransitionDao,
    private val cloudSync: FirebaseSyncManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(MiyahnaDomainState())
    val state: StateFlow<MiyahnaDomainState> = _state.asStateFlow()

    private val _isCapturingDeliveryLocation = MutableStateFlow(false)
    val isCapturingDeliveryLocation: StateFlow<Boolean> = _isCapturingDeliveryLocation.asStateFlow()

    private val _isBackgroundGpsActive = MutableStateFlow(false)
    val isBackgroundGpsActive: StateFlow<Boolean> = _isBackgroundGpsActive.asStateFlow()

    private val _isGpsPowerSaveActive = MutableStateFlow(false)
    val isGpsPowerSaveActive: StateFlow<Boolean> = _isGpsPowerSaveActive.asStateFlow()

    private val _isDeviceGpsEnabled = MutableStateFlow(DeviceLocationHelper.isDeviceGpsEnabled(context))
    val isDeviceGpsEnabled: StateFlow<Boolean> = _isDeviceGpsEnabled.asStateFlow()

    private val _deliveryCaptureMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val deliveryCaptureMessages: SharedFlow<String> = _deliveryCaptureMessages.asSharedFlow()

    @Volatile
    private var lastStandbyPointSavedMs: Long = 0L

    @Volatile
    private var lastLiveTelemetryPushMs: Long = 0L

    @Volatile
    private var lastStandbyMovingState: Boolean? = null

    private val transitionSeq = AtomicLong(0L)
    private val idSeq = AtomicLong(1000L)
    private val autoPausedTripIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingDeletedTripIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var gpsHardwareDisabledAtMs: Long? = null

    @Volatile
    private var gpsHardwareDisabledIso: String? = null

    @Volatile
    private var gpsHardwareDisabledLatLng: Pair<Double, Double>? = null

    private fun nextId(prefix: String): String = "$prefix-${System.currentTimeMillis()}-${idSeq.incrementAndGet()}"

    private val notificationHelper = MiyahnaNotificationHelper(context)

    private var realtimeSettingsJob: Job? = null
    private var realtimeTrucksJob: Job? = null
    private var realtimeTripsJob: Job? = null
    private var realtimeCustomersJob: Job? = null
    private var realtimeOrdersJob: Job? = null
    private var realtimeNotificationsJob: Job? = null
    private var realtimeDirectivesJob: Job? = null
    private var realtimeGpsEventsJob: Job? = null
    private var realtimeDriverAccountsJob: Job? = null
    private var realtimeTripStateLogsJob: Job? = null

    private val initJob: Job

    init {
        observeConnectivity()
        initJob = scope.launch {
            runCatching {
                loadInitialState()
            }.onFailure {
                _state.update { s -> s.copy(isLoaded = true) }
            }
        }
    }

    suspend fun awaitInitialized() {
        initJob.join()
    }

    fun checkAndDispatchPendingSystemNotifications() {
        notificationHelper.notifyNewUnreadNotifications(_state.value)
    }

    fun setBackgroundGpsActive(active: Boolean) {
        _isBackgroundGpsActive.value = active
    }

    fun setGpsPowerSaveActive(active: Boolean) {
        _isGpsPowerSaveActive.value = active
    }

    fun setDeviceGpsEnabled(enabled: Boolean) {
        val previous = _isDeviceGpsEnabled.getAndSetIfChanged(enabled)
        if (previous == enabled) return

        val snap = _state.value
        val activeTrip = snap.trips.firstOrNull {
            it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED
        } ?: return

        val now = nowIso()
        val nowMs = System.currentTimeMillis()
        val lastPt = activeTrip.gpsPoints.lastOrNull()
        val lastLat = lastPt?.lat ?: snap.truck.lastLat.takeIf { it != 0.0 }
        val lastLng = lastPt?.lng ?: snap.truck.lastLng.takeIf { it != 0.0 }

        if (!enabled) {
            gpsHardwareDisabledAtMs = nowMs
            gpsHardwareDisabledIso = now
            gpsHardwareDisabledLatLng = if (lastLat != null && lastLng != null) lastLat to lastLng else null
            val offEvent = if (lastLat != null && lastLng != null) {
                GPSEvent(
                    id = nextId("ev-gpsoff"),
                    tripId = activeTrip.id,
                    tripNumber = activeTrip.tripNumber,
                    timestamp = now,
                    eventType = GPSEventType.STOPPED,
                    lat = lastLat,
                    lng = lastLng,
                    speed = 0.0,
                    customerName = "تم إيقاف GPS من هاتف السائق",
                    source = "device_gps_off",
                    sequenceNumber = snap.gpsEvents.size + 1
                )
            } else null
            val offGap = GPSGap(
                id = "gap-hw-${activeTrip.id}-$nowMs",
                tripId = activeTrip.id,
                startTime = now,
                endTime = now,
                durationSeconds = 0,
                reason = "gps_hardware_disabled",
                fromLat = lastLat,
                fromLng = lastLng,
                toLat = lastLat,
                toLng = lastLng
            )
            scope.launch {
                mutateState(syncToCloudAfter = snap.isOnline) { s ->
                    s.copy(
                        trips = s.trips.map { t ->
                            if (t.id == activeTrip.id) {
                                t.copy(
                                    gpsGaps = t.gpsGaps + offGap,
                                    auditLogs = t.auditLogs + TripAuditEntry(
                                        id = nextId("aud"),
                                        timestamp = now,
                                        actorRole = "driver",
                                        actorName = snap.driverSession?.name ?: t.driverName,
                                        action = "إيقاف GPS",
                                        details = "تم إيقاف خدمة الموقع (GPS) على جهاز السائق"
                                    )
                                )
                            } else t
                        },
                        gpsEvents = if (offEvent != null) (s.gpsEvents + offEvent).takeLast(150) else s.gpsEvents
                    )
                }
            }
        } else {
            val disabledMs = gpsHardwareDisabledAtMs
            val disabledIso = gpsHardwareDisabledIso
            val disabledCoord = gpsHardwareDisabledLatLng
            gpsHardwareDisabledAtMs = null
            gpsHardwareDisabledIso = null
            gpsHardwareDisabledLatLng = null
            if (disabledMs != null && disabledIso != null) {
                val durSec = ((nowMs - disabledMs) / 1000L).toInt().coerceAtLeast(1)
                scope.launch {
                    mutateState(syncToCloudAfter = snap.isOnline) { s ->
                        s.copy(
                            trips = s.trips.map { t ->
                                if (t.id == activeTrip.id) {
                                    val updatedGaps = t.gpsGaps.map { g ->
                                        if (g.reason == "gps_hardware_disabled" && g.startTime == disabledIso) {
                                            g.copy(
                                                endTime = now,
                                                durationSeconds = durSec,
                                                toLat = lastLat ?: disabledCoord?.first,
                                                toLng = lastLng ?: disabledCoord?.second
                                            )
                                        } else g
                                    }
                                    t.copy(
                                        gpsGaps = updatedGaps,
                                        auditLogs = t.auditLogs + TripAuditEntry(
                                            id = nextId("aud"),
                                            timestamp = now,
                                            actorRole = "driver",
                                            actorName = snap.driverSession?.name ?: t.driverName,
                                            action = "تشغيل GPS",
                                            details = "عودة خدمة الموقع (GPS) بعد توقف $durSec ثانية"
                                        )
                                    )
                                } else t
                            }
                        )
                    }
                }
            }
        }
    }

    private fun MutableStateFlow<Boolean>.getAndSetIfChanged(next: Boolean): Boolean {
        val prev = value
        if (prev != next) value = next
        return prev
    }

    fun observeUnsyncedGpsPointsCount(): Flow<Int> =
        tripGpsPointDao.observeUnsyncedPointsCount()

    fun observeTripStateTransitions(tripId: String? = null): Flow<List<TripStateTransition>> {
        val source = if (tripId.isNullOrBlank()) {
            tripStateTransitionDao.observeAllTransitions()
        } else {
            tripStateTransitionDao.observeTransitionsForTrip(tripId)
        }
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun shouldKeepDriverBackgroundGpsRunning(): Boolean {
        val snap = _state.value
        val inDriverMode = snap.savedRole == AppRole.DRIVER && snap.driverSession != null
        val hasAcceptedOrActiveTrip = snap.trips.any {
            it.status == TripStatus.IN_PROGRESS ||
                it.status == TripStatus.PAUSED ||
                (it.status == TripStatus.APPROVED && it.driverAcceptanceStatus == "accepted")
        }
        return inDriverMode || hasAcceptedOrActiveTrip || LocationTrackingPrefs.isPersistentTrackingEnabled(context)
    }

    fun ensureDriverBackgroundGpsRunning(
        acceptedTripId: String? = null,
        forceStart: Boolean = false
    ) {
        val snap = _state.value
        val inDriverMode = snap.savedRole == AppRole.DRIVER && snap.driverSession != null
        val activeTripId = acceptedTripId
            ?: snap.trips.firstOrNull {
                it.status == TripStatus.IN_PROGRESS ||
                    it.status == TripStatus.PAUSED ||
                    (it.status == TripStatus.APPROVED && it.driverAcceptanceStatus == "accepted")
            }?.id
        if ((inDriverMode || forceStart || activeTripId != null) && hasLocationPermission()) {
            LocationTrackingPrefs.setPersistentTracking(
                context = context,
                enabled = true,
                acceptedTripId = activeTripId,
                intervalSeconds = snap.settings.gpsSampleIntervalSeconds,
                powerSaveEnabled = snap.settings.gpsPowerSaveEnabled,
                powerSaveIntervalSeconds = snap.settings.gpsPowerSaveIntervalSeconds,
                stationaryDwellSeconds = snap.settings.gpsStationaryDwellSeconds
            )
            runCatching {
                LocationTrackingService.start(
                    context = context,
                    intervalSeconds = snap.settings.gpsSampleIntervalSeconds,
                    acceptedTripId = activeTripId,
                    powerSaveEnabled = snap.settings.gpsPowerSaveEnabled,
                    powerSaveIntervalSeconds = snap.settings.gpsPowerSaveIntervalSeconds,
                    stationaryDwellSeconds = snap.settings.gpsStationaryDwellSeconds
                )
            }
        }
    }

    private fun stopTrackingServiceIfNotDriver() {
        val snap = _state.value
        val inDriverMode = snap.savedRole == AppRole.DRIVER && snap.driverSession != null
        if (!inDriverMode) {
            LocationTrackingPrefs.clear(context)
            runCatching { LocationTrackingService.stop(context) }
        }
    }

    private fun checkNetworkOnlineNow(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun observeConnectivity() {
        val initialOnline = checkNetworkOnlineNow()
        _state.update { it.copy(isOnline = initialOnline) }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _state.update { it.copy(isOnline = true) }
                    scope.launch {
                        flushPendingOfflineQueue()
                        if (_state.value.cloudUser != null) {
                            syncWithCloud()
                            startCloudRealtimeListeners()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    stopCloudRealtimeListeners()
                    _state.update { it.copy(isOnline = false, isRealtimeFleetConnected = false) }
                }
            })
        }
    }

    private fun syncFleetMemberEmails(accounts: List<DriverAccount>) {
        cloudSync.setFleetMemberEmails(accounts.mapNotNull { it.googleEmail })
    }

    private fun startCloudRealtimeListeners() {
        val current = _state.value
        if (current.cloudUser == null || !current.isOnline) return
        if (realtimeSettingsJob?.isActive == true &&
            realtimeTrucksJob?.isActive == true &&
            realtimeTripsJob?.isActive == true &&
            realtimeCustomersJob?.isActive == true &&
            realtimeOrdersJob?.isActive == true &&
            realtimeNotificationsJob?.isActive == true &&
            realtimeDirectivesJob?.isActive == true &&
            realtimeGpsEventsJob?.isActive == true &&
            realtimeDriverAccountsJob?.isActive == true &&
            realtimeTripStateLogsJob?.isActive == true
        ) return

        stopCloudRealtimeListeners()
        _state.update { it.copy(isRealtimeFleetConnected = true) }

        realtimeSettingsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeSettings().collect { cloudSettings ->
                    if (cloudSettings == null) return@collect
                    val normalized = migrateLegacyPlantSettings(cloudSettings)
                    mutateState(syncToCloudAfter = false) { s ->
                        if (s.settings == normalized) s
                        else s.copy(settings = normalized, isRealtimeFleetConnected = true)
                    }
                    runCatching { LocationTrackingService.updatePowerSaveConfig(context, normalized) }
                }
            }
        }

        realtimeTrucksJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeTrucks().collect { cloudTrucks ->
                    if (cloudTrucks.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val mergedFleet = mergeTruckLists(
                            localTrucks = syncPrimaryTruckIntoFleet(s.truck, s.fleetTrucks),
                            cloudTrucks = cloudTrucks,
                            preferLocalPrimaryId = if (s.savedRole == AppRole.DRIVER) s.truck.id else null
                        )
                        val updatedPrimary = mergedFleet.firstOrNull { it.id == s.truck.id } ?: s.truck
                        s.copy(
                            truck = updatedPrimary,
                            fleetTrucks = mergedFleet,
                            isRealtimeFleetConnected = true,
                            cloudSyncError = null
                        )
                    }
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(cloudSyncError = toFriendlyFirestoreMessage(err, "تعذر استقبال تحديثات الأسطول المباشرة"))
                }
            }
        }

        realtimeTripsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeTrips().collect { cloudTrips ->
                    if (cloudTrips.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val filteredCloud = cloudTrips.filterNot { it.id in pendingDeletedTripIds }
                        val localById = s.trips.associateBy { it.id }
                        val cloudById = filteredCloud.associateBy { it.id }
                        val allIds = (s.trips.map { it.id } + filteredCloud.map { it.id })
                            .distinct()
                            .filterNot { it in pendingDeletedTripIds }
                        val mergedTrips = allIds.mapNotNull { id ->
                            mergeTrip(localById[id], cloudById[id], s.settings)
                        }
                        s.copy(
                            trips = mergedTrips,
                            isRealtimeFleetConnected = true,
                            cloudSyncError = null
                        )
                    }
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(cloudSyncError = toFriendlyFirestoreMessage(err, "تعذر استقبال تحديثات الرحلات المباشرة"))
                }
            }
        }

        realtimeCustomersJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeCustomers().collect { cloudCustomers ->
                    if (cloudCustomers.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val offlineLocal = s.customers.filter { it.isOfflineCreated }
                        val merged = (offlineLocal + cloudCustomers).distinctBy { it.id }
                        s.copy(customers = merged, isRealtimeFleetConnected = true)
                    }
                }
            }
        }

        realtimeOrdersJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeOrders().collect { cloudOrders ->
                    if (cloudOrders.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val cloudById = cloudOrders.associateBy { it.id }
                        val merged = (cloudOrders + s.orders.filterNot { it.id in cloudById }).distinctBy { it.id }
                        s.copy(orders = merged, isRealtimeFleetConnected = true)
                    }
                }
            }
        }

        realtimeNotificationsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeNotifications().collect { cloudNotifs ->
                    if (cloudNotifs.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val merged = mergeNotifications(s.notifications, cloudNotifs)
                        s.copy(notifications = merged)
                    }
                }
            }
        }

        realtimeDirectivesJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeDirectives().collect { cloudDirectives ->
                    if (cloudDirectives.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val cloudById = cloudDirectives.associateBy { it.id }
                        val merged = (cloudDirectives + s.directives.filterNot { it.id in cloudById }).distinctBy { it.id }
                        s.copy(directives = merged)
                    }
                }
            }
        }

        realtimeGpsEventsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeGpsEvents().collect { cloudEvents ->
                    if (cloudEvents.isEmpty()) return@collect
                    mutateState(syncToCloudAfter = false) { s ->
                        val merged = (s.gpsEvents + cloudEvents)
                            .distinctBy { it.id }
                            .sortedBy { it.timestamp }
                            .takeLast(150)
                        s.copy(gpsEvents = merged, isRealtimeFleetConnected = true)
                    }
                }
            }
        }

        realtimeDriverAccountsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeDriverAccounts().collect { cloudDrivers ->
                    if (cloudDrivers.isEmpty()) return@collect
                    syncFleetMemberEmails(cloudDrivers + _state.value.driverAccounts)
                    mutateState(syncToCloudAfter = false) { s ->
                        val merged = mergeDriverAccounts(s.driverAccounts, cloudDrivers)
                        s.copy(driverAccounts = merged, isRealtimeFleetConnected = true)
                    }
                }
            }
        }

        realtimeTripStateLogsJob = scope.launch {
            runCatching {
                cloudSync.observeRealTimeTripStateLogs().collect { cloudLogs ->
                    if (cloudLogs.isEmpty()) return@collect
                    tripStateTransitionDao.insertTransitions(cloudLogs.map { it.toEntity(isSynced = true) })
                    mutateState(syncToCloudAfter = false) { s ->
                        val merged = mergeTransitions(s.tripStateTransitions, cloudLogs).take(500)
                        s.copy(tripStateTransitions = merged, isRealtimeFleetConnected = true)
                    }
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(cloudSyncError = toFriendlyFirestoreMessage(err, "تعذر استقبال سجل تحولات الرحلات"))
                }
            }
        }
    }

    private fun stopCloudRealtimeListeners() {
        realtimeSettingsJob?.cancel()
        realtimeSettingsJob = null
        realtimeTrucksJob?.cancel()
        realtimeTrucksJob = null
        realtimeTripsJob?.cancel()
        realtimeTripsJob = null
        realtimeCustomersJob?.cancel()
        realtimeCustomersJob = null
        realtimeOrdersJob?.cancel()
        realtimeOrdersJob = null
        realtimeNotificationsJob?.cancel()
        realtimeNotificationsJob = null
        realtimeDirectivesJob?.cancel()
        realtimeDirectivesJob = null
        realtimeGpsEventsJob?.cancel()
        realtimeGpsEventsJob = null
        realtimeDriverAccountsJob?.cancel()
        realtimeDriverAccountsJob = null
        realtimeTripStateLogsJob?.cancel()
        realtimeTripStateLogsJob = null
        _state.update { it.copy(isRealtimeFleetConnected = false) }
    }

    private fun mergeNotifications(
        localList: List<AppNotification>,
        cloudList: List<AppNotification>
    ): List<AppNotification> {
        val merged = linkedMapOf<String, AppNotification>()
        (localList + cloudList).forEach { item ->
            val prev = merged[item.id]
            merged[item.id] = if (prev == null) item else item.copy(isRead = prev.isRead || item.isRead)
        }
        return merged.values.sortedByDescending { it.createdAt }.take(250)
    }

    private fun mergeDriverAccounts(
        localList: List<DriverAccount>,
        cloudList: List<DriverAccount>
    ): List<DriverAccount> {
        val localById = localList.associateBy { it.id }
        val cloudById = cloudList.associateBy { it.id }
        val allIds = (cloudList.map { it.id } + localList.map { it.id }).distinct()
        return allIds.mapNotNull { id ->
            val local = localById[id]
            val cloud = cloudById[id]
            when {
                local == null -> cloud
                cloud == null -> local
                else -> cloud.copy(
                    googleEmail = cloud.googleEmail ?: local.googleEmail,
                    lastLoginAt = listOfNotNull(cloud.lastLoginAt, local.lastLoginAt).maxOrNull()
                )
            }
        }
    }

    private fun syncPrimaryTruckIntoFleet(primary: Truck, fleet: List<Truck>): List<Truck> {
        val withoutPrimary = fleet.filterNot { it.id == primary.id }
        return listOf(primary) + withoutPrimary
    }

    private fun mergeTruckLists(
        localTrucks: List<Truck>,
        cloudTrucks: List<Truck>,
        preferLocalPrimaryId: String? = null
    ): List<Truck> {
        val localById = localTrucks.associateBy { it.id }
        val cloudById = cloudTrucks.associateBy { it.id }
        val allIds = (localTrucks.map { it.id } + cloudTrucks.map { it.id }).distinct()
        return allIds.mapNotNull { id ->
            val local = localById[id]
            val cloud = cloudById[id]
            when {
                local == null -> cloud
                cloud == null -> local
                id == preferLocalPrimaryId -> {
                    val useLocalTelemetry = local.lastUpdateTimestamp >= cloud.lastUpdateTimestamp
                    cloud.copy(
                        status = if (useLocalTelemetry) local.status else cloud.status,
                        currentTripId = if (useLocalTelemetry) local.currentTripId else cloud.currentTripId,
                        currentWaterLiters = if (useLocalTelemetry) local.currentWaterLiters else cloud.currentWaterLiters,
                        lastLat = if (useLocalTelemetry) local.lastLat else cloud.lastLat,
                        lastLng = if (useLocalTelemetry) local.lastLng else cloud.lastLng,
                        lastSpeed = if (useLocalTelemetry) local.lastSpeed else cloud.lastSpeed,
                        lastUpdateTimestamp = if (useLocalTelemetry) local.lastUpdateTimestamp else cloud.lastUpdateTimestamp,
                        lastEvent = if (useLocalTelemetry) local.lastEvent else cloud.lastEvent,
                        odometerKm = max(local.odometerKm, cloud.odometerKm)
                    )
                }
                cloud.lastUpdateTimestamp >= local.lastUpdateTimestamp -> cloud
                else -> local
            }
        }
    }

    private suspend fun loadInitialState() {
        mutex.withLock {
            val snap = stateDao.getSnapshot()
            val pendingOps = stateDao.getPendingOps()
            pendingOps.filter { it.type == "trip_delete" }.forEach { op ->
                if (op.payloadJson.isNotBlank()) pendingDeletedTripIds.add(op.payloadJson)
            }
            val pendingCount = pendingOps.size
            val user = cloudSync.currentCloudUser()
            if (snap != null) {
                val snapCustomers = runCatching { json.decodeFromString<List<Customer>>(snap.customersJson) }.getOrDefault(emptyList())
                val roomCustomers = customerDao.getAllCustomersOnce().map { it.toDomain() }
                val resolvedCustomers = if (roomCustomers.isNotEmpty()) {
                    roomCustomers
                } else {
                    if (snapCustomers.isNotEmpty()) {
                        customerDao.upsertCustomers(snapCustomers.map { it.toEntity() })
                    }
                    snapCustomers
                }
                val rawRestoredSettings = runCatching { json.decodeFromString<SystemSettings>(snap.settingsJson) }.getOrDefault(SystemSettings())
                val restoredSettings = migrateLegacyPlantSettings(rawRestoredSettings)
                val rawTrips = runCatching { json.decodeFromString<List<Trip>>(snap.tripsJson) }.getOrDefault(emptyList())
                val unsyncedTripIds = tripGpsPointDao.getUnsyncedPointsOnce().map { it.tripId }.toSet()
                val restoredTrips = rawTrips.map { trip ->
                    val shouldHydrate = trip.status == TripStatus.IN_PROGRESS ||
                        trip.status == TripStatus.PAUSED ||
                        trip.status == TripStatus.PENDING_SYNC ||
                        trip.syncStatus == TripSyncStatus.PENDING_SYNC ||
                        trip.id in unsyncedTripIds
                    if (shouldHydrate) {
                        val roomPts = tripGpsPointDao.getPointsForTripOnce(trip.id).map { it.toDomain() }
                        val mergedPts = (trip.gpsPoints + roomPts).distinctBy { it.id }
                        if (mergedPts.size > trip.gpsPoints.size) {
                            val analysis = GpsTrackingEngine.analyzeGpsTrack(
                                tripId = trip.id,
                                rawPoints = mergedPts,
                                approvedStops = trip.customerStops,
                                settings = restoredSettings
                            )
                            trip.copy(
                                gpsPoints = analysis.points,
                                detectedStops = analysis.detectedStops,
                                gpsGaps = analysis.gpsGaps,
                                actualDistanceKm = analysis.totalDistanceKm,
                                movingMinutes = analysis.movingMinutes,
                                stoppedMinutes = analysis.stoppedMinutes
                            )
                        } else trip
                    } else trip
                }

                val rawRestoredTruck = runCatching { json.decodeFromString<Truck>(snap.truckJson) }.getOrDefault(Truck())
                val restoredTruck = if (
                    kotlin.math.abs(rawRestoredTruck.lastLat - 15.4920) < 0.0001 &&
                    kotlin.math.abs(rawRestoredTruck.lastLng - 45.3620) < 0.0001 &&
                    rawRestoredTruck.currentTripId == null
                ) {
                    rawRestoredTruck.copy(lastLat = 15.4698538, lastLng = 45.3636985)
                } else {
                    rawRestoredTruck
                }
                val rawFleetTrucks = runCatching { json.decodeFromString<List<Truck>>(snap.fleetTrucksJson) }.getOrDefault(emptyList())
                val restoredFleetTrucks = syncPrimaryTruckIntoFleet(restoredTruck, rawFleetTrucks)
                val restoredTransitions = tripStateTransitionDao.getAllTransitionsOnce().map { it.toDomain() }

                val prefsSavedRole = AuthSessionPrefs.getSavedRole(context)
                val prefsDriverSession = AuthSessionPrefs.getDriverSession(context)
                val snapRole = snap.savedRole?.let { r -> AppRole.entries.firstOrNull { it.wire == r } }
                val resolvedRole = if (AuthSessionPrefs.hasExplicitRole(context)) {
                    prefsSavedRole
                } else {
                    snapRole ?: prefsSavedRole
                }
                val snapDriverSession = snap.driverSessionJson
                    ?.let { runCatching { json.decodeFromString<DriverSession>(it) }.getOrNull() }
                val resolvedDriverSession = if (AuthSessionPrefs.hasExplicitDriverSession(context)) {
                    prefsDriverSession
                } else {
                    snapDriverSession ?: prefsDriverSession
                }
                val unlockedManager = AuthSessionPrefs.isManagerUnlocked(context) || resolvedRole == AppRole.MANAGER
                if (!AuthSessionPrefs.hasExplicitRole(context) && resolvedRole != null) {
                    AuthSessionPrefs.setSavedRole(context, resolvedRole)
                }
                if (!AuthSessionPrefs.hasExplicitDriverSession(context) && resolvedDriverSession != null) {
                    AuthSessionPrefs.setDriverSession(context, resolvedDriverSession)
                }
                if (unlockedManager) {
                    AuthSessionPrefs.setManagerUnlocked(context, true)
                }

                val restored = MiyahnaDomainState(
                    isLoaded = true,
                    isOnline = _state.value.isOnline,
                    savedRole = resolvedRole,
                    isManagerUnlocked = unlockedManager,
                    driverSession = resolvedDriverSession,
                    settings = restoredSettings,
                    truck = restoredTruck,
                    fleetTrucks = restoredFleetTrucks,
                    driverAccounts = runCatching { json.decodeFromString<List<DriverAccount>>(snap.driverAccountsJson) }.getOrDefault(emptyList()),
                    customers = resolvedCustomers,
                    orders = runCatching { json.decodeFromString<List<Order>>(snap.ordersJson) }.getOrDefault(emptyList()),
                    trips = restoredTrips,
                    invoices = runCatching { json.decodeFromString<List<Invoice>>(snap.invoicesJson) }.getOrDefault(emptyList()),
                    reconciliations = runCatching { json.decodeFromString<List<DriverReconciliation>>(snap.reconciliationsJson) }.getOrDefault(emptyList()),
                    expenses = runCatching { json.decodeFromString<List<Expense>>(snap.expensesJson) }.getOrDefault(emptyList()),
                    gpsEvents = runCatching { json.decodeFromString<List<GPSEvent>>(snap.gpsEventsJson) }.getOrDefault(emptyList()),
                    notifications = runCatching { json.decodeFromString<List<AppNotification>>(snap.notificationsJson) }.getOrDefault(emptyList()),
                    directives = runCatching { json.decodeFromString<List<ManagerDirective>>(snap.directivesJson) }.getOrDefault(emptyList()),
                    tripStateTransitions = restoredTransitions,
                    pendingSyncCount = pendingCount,
                    cloudUser = user
                )
                _state.value = restored
            } else {
                val defaultTruck = Truck(lastUpdateTimestamp = nowIso())
                val restoredTransitions = tripStateTransitionDao.getAllTransitionsOnce().map { it.toDomain() }
                val prefsSavedRole = AuthSessionPrefs.getSavedRole(context)
                val prefsDriverSession = AuthSessionPrefs.getDriverSession(context)
                val unlockedManager = AuthSessionPrefs.isManagerUnlocked(context) || prefsSavedRole == AppRole.MANAGER
                val initial = MiyahnaDomainState(
                    isLoaded = true,
                    isOnline = _state.value.isOnline,
                    savedRole = prefsSavedRole,
                    isManagerUnlocked = unlockedManager,
                    driverSession = prefsDriverSession,
                    truck = defaultTruck,
                    fleetTrucks = listOf(defaultTruck),
                    tripStateTransitions = restoredTransitions,
                    pendingSyncCount = pendingCount,
                    cloudUser = user
                )
                _state.value = initial
                persistStateLocked(initial)
            }
        }
        val current = _state.value
        syncFleetMemberEmails(current.driverAccounts)
        notificationHelper.markNotificationsSeen(current.notifications.filter { it.isRead }.map { it.id })
        notificationHelper.notifyNewUnreadNotifications(current)
        val hasPendingSyncWork = current.pendingSyncCount > 0 ||
            current.trips.any { it.syncStatus == TripSyncStatus.PENDING_SYNC || it.status == TripStatus.PENDING_SYNC } ||
            tripGpsPointDao.getUnsyncedPointsOnce().isNotEmpty() ||
            tripStateTransitionDao.getUnsyncedTransitionsOnce().isNotEmpty()
        if (hasPendingSyncWork && !current.isOnline) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        if (current.cloudUser != null && current.isOnline) {
            syncWithCloud()
            startCloudRealtimeListeners()
        } else if (hasPendingSyncWork && current.isOnline) {
            flushPendingOfflineQueue()
        }
        // Keep LocationTrackingService running in Driver mode even when process restarts in background
        ensureDriverBackgroundGpsRunning()
    }

    private suspend fun persistStateLocked(s: MiyahnaDomainState) {
        stateDao.upsertSnapshot(
            AppStateSnapshotEntity(
                key = "primary",
                settingsJson = json.encodeToString(s.settings),
                truckJson = json.encodeToString(s.truck),
                driverAccountsJson = json.encodeToString(s.driverAccounts),
                customersJson = json.encodeToString(s.customers),
                ordersJson = json.encodeToString(s.orders),
                tripsJson = json.encodeToString(s.trips),
                invoicesJson = json.encodeToString(s.invoices),
                reconciliationsJson = json.encodeToString(s.reconciliations),
                expensesJson = json.encodeToString(s.expenses),
                gpsEventsJson = json.encodeToString(s.gpsEvents.takeLast(150)),
                notificationsJson = json.encodeToString(s.notifications.take(250)),
                directivesJson = json.encodeToString(s.directives),
                savedRole = s.savedRole?.wire,
                driverSessionJson = s.driverSession?.let { json.encodeToString(it) },
                updatedAtEpochMs = System.currentTimeMillis(),
                fleetTrucksJson = json.encodeToString(syncPrimaryTruckIntoFleet(s.truck, s.fleetTrucks))
            )
        )
    }

    private suspend fun mutateState(
        queueOfflineType: String? = null,
        queueOfflinePayload: String? = null,
        syncToCloudAfter: Boolean = true,
        transform: (MiyahnaDomainState) -> MiyahnaDomainState
    ) {
        val snapshotToSync: MiyahnaDomainState
        var enqueuedOffline = false
        mutex.withLock {
            val previous = _state.value
            val next = transform(previous)
            if (queueOfflineType != null && queueOfflinePayload != null && !next.isOnline) {
                stateDao.insertPendingOp(
                    PendingOfflineOpEntity(
                        id = nextId("op"),
                        type = queueOfflineType,
                        payloadJson = queueOfflinePayload,
                        createdAt = nowIso()
                    )
                )
                enqueuedOffline = true
            }
            if (next.customers !== previous.customers && next.customers != previous.customers) {
                syncCustomersTableLocked(previous.customers, next.customers)
            }
            val pendingCount = stateDao.getPendingOps().size
            val normalizedFleet = syncPrimaryTruckIntoFleet(next.truck, next.fleetTrucks)
            snapshotToSync = next.copy(
                fleetTrucks = normalizedFleet,
                pendingSyncCount = pendingCount
            )
            _state.value = snapshotToSync
            persistStateLocked(snapshotToSync)
        }
        val hasPendingTrip = snapshotToSync.trips.any {
            it.syncStatus == TripSyncStatus.PENDING_SYNC || it.status == TripStatus.PENDING_SYNC
        }
        val onlineNow = checkNetworkOnlineNow() && snapshotToSync.isOnline
        if (enqueuedOffline || !onlineNow || hasPendingTrip) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        notificationHelper.notifyNewUnreadNotifications(snapshotToSync)
        if (syncToCloudAfter && onlineNow && snapshotToSync.cloudUser != null) {
            scope.launch {
                val res = pushCloudSnapshotInternal(snapshotToSync)
                if (res.isFailure) {
                    runCatching { TripSyncWorker.enqueueWhenOnline(context) }
                }
            }
        }
    }

    private suspend fun syncCustomersTableLocked(previous: List<Customer>, next: List<Customer>) {
        if (next.isEmpty()) {
            customerDao.clearAllCustomers()
            return
        }
        val nextIds = next.mapTo(HashSet()) { it.id }
        previous.forEach { oldCustomer ->
            if (oldCustomer.id !in nextIds) {
                customerDao.deleteCustomerById(oldCustomer.id)
            }
        }
        customerDao.upsertCustomers(next.map { it.toEntity() })
    }

    // --- Role & Security ---

    fun verifyManagerPin(pin: String): Boolean {
        val expected = _state.value.settings.managerPin.ifBlank { "20262027" }
        val ok = pin.trim() == expected
        if (ok) {
            AuthSessionPrefs.setManagerUnlocked(context, true)
            AuthSessionPrefs.setSavedRole(context, AppRole.MANAGER)
            _state.update { it.copy(isManagerUnlocked = true, savedRole = AppRole.MANAGER) }
        }
        return ok
    }

    suspend fun selectRole(role: AppRole?) {
        AuthSessionPrefs.setSavedRole(context, role)
        if (role == AppRole.MANAGER) {
            AuthSessionPrefs.setManagerUnlocked(context, true)
        }
        if (role != AppRole.DRIVER) {
            LocationTrackingPrefs.clear(context)
        }
        _state.update {
            it.copy(
                savedRole = role,
                isManagerUnlocked = if (role == AppRole.MANAGER) true else it.isManagerUnlocked
            )
        }
        mutateState(syncToCloudAfter = false) {
            it.copy(
                savedRole = role,
                isManagerUnlocked = if (role == AppRole.MANAGER) true else it.isManagerUnlocked
            )
        }
        if (role != AppRole.DRIVER) {
            stopTrackingServiceIfNotDriver()
        } else {
            ensureDriverBackgroundGpsRunning()
        }
    }

    suspend fun lockManagerSession() {
        AuthSessionPrefs.setManagerUnlocked(context, false)
        AuthSessionPrefs.setSavedRole(context, null)
        _state.update { it.copy(isManagerUnlocked = false, savedRole = null) }
        mutateState(syncToCloudAfter = false) { it.copy(isManagerUnlocked = false, savedRole = null) }
    }

    suspend fun changeManagerPin(currentPin: String, newPin: String): ActionResult<Unit> {
        if (!verifyManagerPin(currentPin)) {
            return ActionResult(false, error = "رمز PIN الحالي غير صحيح")
        }
        if (newPin.trim().length < 4) {
            return ActionResult(false, error = "يجب أن يكون رمز PIN مكوناً من 4 أرقام على الأقل")
        }
        mutateState { s -> s.copy(settings = s.settings.copy(managerPin = newPin.trim())) }
        return ActionResult(true)
    }

    // --- Driver Account Management ---

    suspend fun registerDriverAccount(
        name: String,
        username: String,
        phone: String,
        password: String,
        licenseNumber: String?
    ): ActionResult<DriverAccount> {
        val cleanUser = username.trim()
        val cleanPhone = phone.trim()
        if (name.isBlank() || cleanUser.isBlank() || cleanPhone.isBlank() || password.length < 4) {
            return ActionResult(false, error = "يرجى إكمال الحقول وكلمة مرور من 4 أحرف على الأقل")
        }
        val exists = _state.value.driverAccounts.any {
            it.username.equals(cleanUser, ignoreCase = true) || it.phone == cleanPhone
        }
        if (exists) {
            return ActionResult(false, error = "اسم المستخدم أو رقم الهاتف مسجل مسبقاً")
        }

        val linkedGoogleEmail = _state.value.cloudUser?.email?.trim()?.lowercase()?.takeIf { it.contains("@") }
        val acc = DriverAccount(
            id = nextId("drv"),
            name = name.trim(),
            username = cleanUser,
            phone = cleanPhone,
            password = password,
            googleEmail = linkedGoogleEmail,
            licenseNumber = licenseNumber?.trim()?.takeIf { it.isNotEmpty() },
            status = DriverAccountStatus.PENDING,
            createdAt = nowIso(),
            createdBy = "driver_self"
        )
        val notif = buildNotification(
            targetRole = AppRole.MANAGER.wire,
            actorRole = "driver",
            actorName = acc.name,
            category = "driver_account_requested",
            priority = NotificationPriority.ACTION_REQUIRED,
            title = "طلب انضمام سائق جديد: ${acc.name}",
            message = "الهاتف: ${acc.phone}${linkedGoogleEmail?.let { " · Google: $it" } ?: ""} — بانتظار اعتماد المدير",
            actionTab = "drivers"
        )
        mutateState { s ->
            val updatedAccounts = listOf(acc) + s.driverAccounts
            syncFleetMemberEmails(updatedAccounts)
            s.copy(
                driverAccounts = updatedAccounts,
                notifications = listOf(notif) + s.notifications
            )
        }
        return ActionResult(true, data = acc)
    }

    suspend fun createDriverAccountByManager(
        name: String,
        username: String,
        phone: String,
        password: String,
        googleEmail: String?,
        licenseNumber: String?
    ): ActionResult<DriverAccount> {
        val cleanName = name.trim()
        val cleanUser = username.trim()
        val cleanPhone = phone.trim()
        val cleanEmail = googleEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleanName.isBlank() || cleanUser.isBlank() || cleanPhone.isBlank() || password.length < 4) {
            return ActionResult(false, error = "يرجى إكمال الاسم واسم المستخدم والهاتف وكلمة مرور (4 أحرف+)")
        }
        if (cleanEmail != null && !cleanEmail.contains("@")) {
            return ActionResult(false, error = "صيغة بريد Google غير صحيحة")
        }
        val exists = _state.value.driverAccounts.any {
            it.username.equals(cleanUser, ignoreCase = true) || it.phone == cleanPhone
        }
        if (exists) {
            return ActionResult(false, error = "اسم المستخدم أو رقم الهاتف مسجل مسبقاً")
        }
        val now = nowIso()
        val acc = DriverAccount(
            id = nextId("drv"),
            name = cleanName,
            username = cleanUser,
            phone = cleanPhone,
            password = password,
            googleEmail = cleanEmail,
            licenseNumber = licenseNumber?.trim()?.takeIf { it.isNotEmpty() },
            status = DriverAccountStatus.APPROVED,
            createdAt = now,
            createdBy = "manager",
            approvedAt = now,
            approvedBy = "المدير العام"
        )
        mutateState { s ->
            val updatedAccounts = listOf(acc) + s.driverAccounts
            syncFleetMemberEmails(updatedAccounts)
            val updatedTruck = if (s.truck.driverId.isBlank()) {
                s.truck.copy(driverId = acc.id, driverName = acc.name, driverPhone = acc.phone)
            } else s.truck
            s.copy(
                driverAccounts = updatedAccounts,
                truck = updatedTruck
            )
        }
        return ActionResult(true, data = acc)
    }

    suspend fun updateDriverGoogleEmail(driverId: String, email: String): ActionResult<Unit> {
        val cleanEmail = email.trim().lowercase().takeIf { it.isNotEmpty() }
        if (cleanEmail != null && !cleanEmail.contains("@")) {
            return ActionResult(false, error = "يرجى إدخال بريد Google صحيح مثل driver@gmail.com")
        }
        mutateState { s ->
            val updatedAccounts = s.driverAccounts.map {
                if (it.id == driverId) it.copy(googleEmail = cleanEmail) else it
            }
            syncFleetMemberEmails(updatedAccounts)
            s.copy(driverAccounts = updatedAccounts)
        }
        return ActionResult(true)
    }

    suspend fun loginDriver(identifier: String, password: String): ActionResult<DriverSession> {
        val clean = identifier.trim()
        val acc = _state.value.driverAccounts.firstOrNull {
            it.username.equals(clean, ignoreCase = true) || it.phone == clean
        } ?: return ActionResult(false, error = "لم يتم العثور على حساب سائق بهذا الاسم أو الهاتف")

        if (acc.password != password) {
            return ActionResult(false, error = "كلمة المرور غير صحيحة")
        }
        when (acc.status) {
            DriverAccountStatus.PENDING -> return ActionResult(false, error = "حسابك قيد المراجعة وبانتظار اعتماد المدير")
            DriverAccountStatus.REJECTED -> return ActionResult(false, error = "تم رفض طلب الحساب${acc.rejectionReason?.let { ": $it" } ?: ""}")
            DriverAccountStatus.SUSPENDED -> return ActionResult(false, error = "تم إيقاف هذا الحساب مؤقتاً من الإدارة")
            DriverAccountStatus.APPROVED -> Unit
        }

        val session = DriverSession(
            id = acc.id,
            name = acc.name,
            username = acc.username,
            phone = acc.phone,
            licenseNumber = acc.licenseNumber,
            loggedInAt = nowIso(),
            isDemo = acc.isDemo
        )
        AuthSessionPrefs.setDriverSession(context, session)
        AuthSessionPrefs.setSavedRole(context, AppRole.DRIVER)
        _state.update { s ->
            s.copy(
                driverSession = session,
                savedRole = AppRole.DRIVER
            )
        }
        mutateState { s ->
            s.copy(
                driverSession = session,
                savedRole = AppRole.DRIVER,
                driverAccounts = s.driverAccounts.map {
                    if (it.id == acc.id) it.copy(lastLoginAt = session.loggedInAt) else it
                },
                truck = if (s.truck.driverId.isBlank()) {
                    s.truck.copy(driverId = acc.id, driverName = acc.name, driverPhone = acc.phone)
                } else s.truck
            )
        }
        ensureDriverBackgroundGpsRunning()
        return ActionResult(true, data = session)
    }

    suspend fun logoutDriver() {
        LocationTrackingPrefs.clear(context)
        AuthSessionPrefs.setDriverSession(context, null)
        AuthSessionPrefs.setSavedRole(context, null)
        _state.update { it.copy(driverSession = null, savedRole = null) }
        mutateState(syncToCloudAfter = false) { it.copy(driverSession = null, savedRole = null) }
        runCatching { LocationTrackingService.stop(context) }
    }

    suspend fun approveDriverAccount(driverId: String) {
        val target = _state.value.driverAccounts.firstOrNull { it.id == driverId } ?: return
        val notif = buildNotification(
            targetRole = "driver",
            actorRole = "manager",
            actorName = "المدير العام",
            category = "driver_account_approved",
            priority = NotificationPriority.INFO,
            title = "تم اعتماد حساب السائق ${target.name}",
            message = "يمكنك الآن تسجيل الدخول وبدء استلام الرحلات المعتمدة"
        )
        mutateState { s ->
            val updatedAccounts = s.driverAccounts.map {
                if (it.id == driverId) {
                    it.copy(
                        status = DriverAccountStatus.APPROVED,
                        approvedAt = nowIso(),
                        approvedBy = "المدير العام",
                        rejectedAt = null,
                        rejectionReason = null
                    )
                } else it
            }
            syncFleetMemberEmails(updatedAccounts)
            val updatedTruck = if (s.truck.driverId.isBlank() || s.truck.driverId == driverId) {
                s.truck.copy(driverId = target.id, driverName = target.name, driverPhone = target.phone)
            } else s.truck
            s.copy(
                driverAccounts = updatedAccounts,
                truck = updatedTruck,
                notifications = listOf(notif) + s.notifications
            )
        }
    }

    suspend fun rejectDriverAccount(driverId: String, reason: String) {
        val target = _state.value.driverAccounts.firstOrNull { it.id == driverId } ?: return
        val notif = buildNotification(
            targetRole = "driver",
            actorRole = "manager",
            actorName = "المدير العام",
            category = "driver_account_rejected",
            priority = NotificationPriority.URGENT,
            title = "تم رفض حساب السائق ${target.name}",
            message = reason.ifBlank { "يرجى مراجعة الإدارة" }
        )
        mutateState { s ->
            s.copy(
                driverAccounts = s.driverAccounts.map {
                    if (it.id == driverId) {
                        it.copy(
                            status = DriverAccountStatus.REJECTED,
                            rejectedAt = nowIso(),
                            rejectionReason = reason.trim()
                        )
                    } else it
                },
                notifications = listOf(notif) + s.notifications
            )
        }
    }

    suspend fun toggleSuspendDriverAccount(driverId: String) {
        mutateState { s ->
            s.copy(
                driverAccounts = s.driverAccounts.map {
                    if (it.id == driverId) {
                        val next = if (it.status == DriverAccountStatus.SUSPENDED) {
                            DriverAccountStatus.APPROVED
                        } else {
                            DriverAccountStatus.SUSPENDED
                        }
                        it.copy(status = next)
                    } else it
                }
            )
        }
    }

    // --- Cloud Sign-In & Sync ---

    suspend fun signInCloudWithGoogle(activityContext: Context): ActionResult<CloudUserInfo> {
        val res = cloudSync.signInWithGoogle(activityContext)
        return res.fold(
            onSuccess = { user ->
                _state.update { it.copy(cloudUser = user, cloudSyncError = null) }
                syncWithCloud()
                startCloudRealtimeListeners()
                ActionResult(true, data = user)
            },
            onFailure = { err ->
                _state.update { it.copy(cloudSyncError = err.message) }
                ActionResult(false, error = err.message ?: "تعذر ربط حساب Google")
            }
        )
    }

    suspend fun attemptCloudAutoSignIn(activityContext: Context): ActionResult<CloudUserInfo> {
        initJob.join()
        val current = _state.value
        current.cloudUser?.let { return ActionResult(true, data = it) }
        if (!current.isOnline) return ActionResult(false, error = "غير متصل بالإنترنت")
        val res = cloudSync.attemptAutoSignIn(activityContext)
        return res.fold(
            onSuccess = { user ->
                _state.update { it.copy(cloudUser = user, cloudSyncError = null) }
                syncWithCloud()
                startCloudRealtimeListeners()
                ActionResult(true, data = user)
            },
            onFailure = { err ->
                ActionResult(false, error = err.message)
            }
        )
    }

    fun signOutCloud() {
        stopCloudRealtimeListeners()
        scope.launch { cloudSync.signOut() }
        _state.update { it.copy(cloudUser = null, lastCloudSyncAt = null, cloudSyncError = null, isRealtimeFleetConnected = false) }
    }

    suspend fun flushPendingOfflineQueue(): Boolean {
        initJob.join()
        val onlineNow = checkNetworkOnlineNow() || _state.value.isOnline
        if (!onlineNow) return false

        val resolvedUser = _state.value.cloudUser ?: cloudSync.currentCloudUser()
        _state.update { it.copy(isOnline = true, cloudUser = resolvedUser ?: it.cloudUser) }

        val pendingOps = stateDao.getPendingOps()
        val unsyncedPointEntities = tripGpsPointDao.getUnsyncedPointsOnce()
        val unsyncedTransitions = tripStateTransitionDao.getUnsyncedTransitionsOnce().map { it.toDomain() }
        val snapBefore = _state.value
        val hasPendingTripsInitial = snapBefore.trips.any {
            it.syncStatus == TripSyncStatus.PENDING_SYNC || it.status == TripStatus.PENDING_SYNC
        }
        if (pendingOps.isEmpty() && !hasPendingTripsInitial && unsyncedPointEntities.isEmpty() && unsyncedTransitions.isEmpty()) {
            return true
        }

        // Never drop offline queue items or mark Room GPS points synced before Google/Firebase Cloud Auth is active
        if (resolvedUser == null) {
            return false
        }

        // Hydrate document metadata after process restart so non-admin driver pushes update Manager-owned docs safely
        if (!cloudSync.hasPopulatedMeta() && !resolvedUser.isAdmin) {
            val pulled = cloudSync.pullCloudSnapshot()
            if (pulled.isFailure) {
                return false
            }
            val cloud = pulled.getOrThrow()
            if (cloud.hasAnyDocs) {
                mergeCloudIntoLocal(cloud)
            }
        }

        val snap = _state.value
        val unsyncedByTrip = unsyncedPointEntities.groupBy { it.tripId }
        val now = nowIso()
        val nowEpoch = System.currentTimeMillis()
        val syncTransitionLogs = mutableListOf<TripStateTransition>()

        val updatedTrips = snap.trips.map { trip ->
            val roomPtsForTrip = if (trip.id in unsyncedByTrip || trip.syncStatus == TripSyncStatus.PENDING_SYNC || trip.status == TripStatus.PENDING_SYNC) {
                tripGpsPointDao.getPointsForTripOnce(trip.id).map { it.toDomain() }
            } else emptyList()

            val rehydratedTrip = if (roomPtsForTrip.isNotEmpty()) {
                val combinedPts = (trip.gpsPoints + roomPtsForTrip).distinctBy { it.id }
                val analysis = GpsTrackingEngine.analyzeGpsTrack(
                    tripId = trip.id,
                    rawPoints = combinedPts,
                    approvedStops = trip.customerStops,
                    settings = snap.settings
                )
                val safeDist = analysis.totalDistanceKm.takeIf { it.isFinite() } ?: 0.0
                val safeCons = snap.settings.truckDieselConsumptionPerKm.takeIf { it.isFinite() } ?: 0.0
                val safeDiesel = snap.settings.dieselPricePerLiter.takeIf { it.isFinite() } ?: 0.0
                val actualFuel = ((safeDist * safeCons) * 100.0).let { if (it.isFinite()) it.roundToInt() else 0 } / 100.0
                val fuelCost = (actualFuel * safeDiesel).let { if (it.isFinite()) it.roundToInt().toDouble() else 0.0 }
                val actualCost = trip.productionCost + fuelCost + trip.driverWageShare + trip.maintenanceShare
                trip.copy(
                    gpsPoints = analysis.points,
                    detectedStops = analysis.detectedStops,
                    gpsGaps = analysis.gpsGaps,
                    actualDistanceKm = analysis.totalDistanceKm,
                    actualFuelLiters = actualFuel,
                    fuelCost = fuelCost,
                    actualCost = actualCost,
                    movingMinutes = analysis.movingMinutes,
                    stoppedMinutes = analysis.stoppedMinutes
                )
            } else trip

            when {
                rehydratedTrip.status == TripStatus.PENDING_SYNC -> {
                    val seq = transitionSeq.incrementAndGet()
                    syncTransitionLogs.add(
                        TripStateTransition(
                            id = "tlog-$nowEpoch-$seq",
                            tripId = rehydratedTrip.id,
                            tripNumber = rehydratedTrip.tripNumber,
                            truckId = rehydratedTrip.truckId,
                            driverId = rehydratedTrip.driverId,
                            driverName = rehydratedTrip.driverName,
                            fromState = TripStatus.PENDING_SYNC.wire,
                            toState = TripStatus.COMPLETED_PENDING_REVIEW.wire,
                            triggerSource = "offline_queue_synced",
                            actorRole = "system",
                            actorName = "المزامنة التلقائية",
                            details = "مزامنة الرحلة من الطابور المحلي إلى لوحة المدير",
                            timestamp = now,
                            timestampEpochMs = nowEpoch,
                            reviewedByManager = false,
                            isSyncedToCloud = false
                        )
                    )
                    rehydratedTrip.copy(
                        status = TripStatus.COMPLETED_PENDING_REVIEW,
                        syncStatus = TripSyncStatus.SYNCED,
                        lastSyncedAt = now
                    )
                }
                rehydratedTrip.syncStatus == TripSyncStatus.PENDING_SYNC || rehydratedTrip.id in unsyncedByTrip ->
                    rehydratedTrip.copy(syncStatus = TripSyncStatus.SYNCED, lastSyncedAt = now)
                else -> rehydratedTrip
            }
        }

        val latestUnsyncedPt = unsyncedPointEntities.maxByOrNull { it.capturedAtEpochMs }
        val updatedTruck = if (latestUnsyncedPt != null && latestUnsyncedPt.timestamp > snap.truck.lastUpdateTimestamp) {
            snap.truck.copy(
                lastLat = latestUnsyncedPt.lat,
                lastLng = latestUnsyncedPt.lng,
                lastSpeed = latestUnsyncedPt.speedKmH,
                lastUpdateTimestamp = latestUnsyncedPt.timestamp
            )
        } else snap.truck

        val pendingTripsCount = snap.trips.count {
            it.syncStatus == TripSyncStatus.PENDING_SYNC || it.status == TripStatus.PENDING_SYNC
        }
        if (syncTransitionLogs.isNotEmpty()) {
            tripStateTransitionDao.insertTransitions(syncTransitionLogs.map { it.toEntity(isSynced = false) })
        }
        val allTransitionsToPush = (unsyncedTransitions + syncTransitionLogs).distinctBy { it.id }

        val updatedCustomers = snap.customers.map { it.copy(isOfflineCreated = false) }
        val notif = buildNotification(
            targetRole = "manager",
            actorRole = "system",
            actorName = "المزامنة التلقائية",
            category = "sync_completed",
            priority = if (pendingTripsCount > 0) NotificationPriority.ACTION_REQUIRED else NotificationPriority.INFO,
            title = "تمت مزامنة مسار الرحلة وإرسالها للمدير",
            message = "تمت مزامنة $pendingTripsCount رحلة و${unsyncedPointEntities.size} نقطة GPS و${allTransitionsToPush.size} تحول حالة.",
            tripId = updatedTrips.firstOrNull { it.id in unsyncedByTrip }?.id,
            tripNumber = updatedTrips.firstOrNull { it.id in unsyncedByTrip }?.tripNumber,
            actionTab = "trips"
        )
        val mergedTransitions = mergeTransitions(allTransitionsToPush, snap.tripStateTransitions).take(500)
        val prepared = snap.copy(
            isOnline = true,
            cloudUser = resolvedUser,
            truck = updatedTruck,
            trips = updatedTrips,
            customers = updatedCustomers,
            tripStateTransitions = mergedTransitions,
            notifications = listOf(notif) + snap.notifications,
            pendingSyncCount = 0
        )

        val pendingTripDeletes = pendingOps.filter { it.type == "trip_delete" && it.payloadJson.isNotBlank() }
        if (pendingTripDeletes.isNotEmpty()) {
            pendingTripDeletes.forEach { op ->
                val delRes = cloudSync.deleteTripDocument(op.payloadJson)
                if (delRes.isFailure) return false
            }
            pendingTripDeletes.forEach { op ->
                pendingDeletedTripIds.remove(op.payloadJson)
            }
        }

        val pushed = pushCloudSnapshotInternal(prepared)
        if (pushed.isFailure) {
            return false
        }
        if (allTransitionsToPush.isNotEmpty()) {
            val syncedRes = cloudSync.syncPendingTripStateLogs(allTransitionsToPush)
            if (syncedRes.isFailure) {
                return false
            }
        }

        val syncedTransitionIds = allTransitionsToPush.map { it.id }.toSet()
        val finalTransitions = mergedTransitions.map {
            if (it.id in syncedTransitionIds) it.copy(isSyncedToCloud = true) else it
        }
        val finalPrepared = prepared.copy(tripStateTransitions = finalTransitions)
        val flushedOpIds = pendingOps.map { it.id }
        val flushedPointIds = unsyncedPointEntities.map { it.id }

        mutex.withLock {
            _state.value = finalPrepared
            persistStateLocked(finalPrepared)
            flushedOpIds.chunked(400).forEach { chunk ->
                stateDao.deletePendingOpsByIds(chunk)
            }
            flushedPointIds.chunked(400).forEach { chunk ->
                tripGpsPointDao.markPointsSynced(chunk)
            }
            if (syncedTransitionIds.isNotEmpty()) {
                tripStateTransitionDao.markAllTransitionsSynced()
            }
        }
        return true
    }

    suspend fun syncWithCloud(): ActionResult<Unit> {
        initJob.join()
        val current = _state.value
        val onlineNow = checkNetworkOnlineNow() || current.isOnline
        if (!onlineNow) return ActionResult(false, error = "لا يوجد اتصال بالإنترنت")
        if (current.cloudUser == null) return ActionResult(false, error = "يرجى ربط حساب Google أولاً")

        _state.update { it.copy(isOnline = true, isCloudSyncing = true, cloudSyncError = null) }
        flushPendingOfflineQueue()

        val pulled = cloudSync.pullCloudSnapshot()
        if (pulled.isFailure) {
            val err = pulled.exceptionOrNull()
            val msg = err?.let { toFriendlyFirestoreMessage(it, "تعذر قراءة السحابة") } ?: "تعذر قراءة السحابة"
            _state.update { it.copy(isCloudSyncing = false, cloudSyncError = msg) }
            return ActionResult(false, error = msg)
        }

        val cloud = pulled.getOrThrow()
        if (cloud.hasAnyDocs) {
            mergeCloudIntoLocal(cloud)
        }
        val pushed = pushCloudSnapshotInternal(_state.value)
        if (pushed.isFailure) {
            val err = pushed.exceptionOrNull()
            val msg = err?.let { toFriendlyFirestoreMessage(it, "تعذر رفع البيانات للسحابة") } ?: "تعذر رفع البيانات للسحابة"
            _state.update { it.copy(isCloudSyncing = false, cloudSyncError = msg) }
            return ActionResult(false, error = msg)
        }
        _state.update { it.copy(isCloudSyncing = false, lastCloudSyncAt = nowIso(), cloudSyncError = null) }
        return ActionResult(true)
    }

    private fun stopStatusRank(status: StopStatus): Int = when (status) {
        StopStatus.PENDING -> 0
        StopStatus.ARRIVED -> 1
        StopStatus.UNLOADING -> 2
        StopStatus.SKIPPED -> 3
        StopStatus.DELIVERED -> 4
    }

    private fun mergeTrip(local: Trip?, cloud: Trip?, settings: SystemSettings): Trip? =
        mergeTripModels(local, cloud, settings)

    private suspend fun mergeCloudIntoLocal(cloud: CloudSnapshot) {
        syncFleetMemberEmails(cloud.driverAccounts + _state.value.driverAccounts)
        mutateState(syncToCloudAfter = false) { s ->
            val resolvedSettings = migrateLegacyPlantSettings(cloud.settings ?: s.settings)
            val filteredCloudTrips = cloud.trips.filterNot { it.id in pendingDeletedTripIds }
            val localTripsById = s.trips.associateBy { it.id }
            val cloudTripsById = filteredCloudTrips.associateBy { it.id }
            val allTripIds = (s.trips.map { it.id } + filteredCloudTrips.map { it.id })
                .distinct()
                .filterNot { it in pendingDeletedTripIds }
            val mergedTrips = allTripIds.mapNotNull { id ->
                mergeTrip(localTripsById[id], cloudTripsById[id], resolvedSettings)
            }
            val mergedFleet = mergeTruckLists(
                localTrucks = syncPrimaryTruckIntoFleet(s.truck, s.fleetTrucks),
                cloudTrucks = cloud.trucks.ifEmpty { listOfNotNull(cloud.truck) },
                preferLocalPrimaryId = if (s.savedRole == AppRole.DRIVER) s.truck.id else null
            )
            val updatedPrimary = mergedFleet.firstOrNull { it.id == s.truck.id } ?: cloud.truck ?: s.truck
            val offlineLocalCustomers = s.customers.filter { it.isOfflineCreated }
            val mergedTransitions = mergeTransitions(s.tripStateTransitions, cloud.tripStateLogs).take(500)
            if (cloud.tripStateLogs.isNotEmpty()) {
                scope.launch {
                    tripStateTransitionDao.insertTransitions(cloud.tripStateLogs.map { it.toEntity(isSynced = true) })
                }
            }
            s.copy(
                settings = resolvedSettings,
                truck = updatedPrimary,
                fleetTrucks = mergedFleet,
                driverAccounts = mergeDriverAccounts(s.driverAccounts, cloud.driverAccounts),
                customers = (offlineLocalCustomers + cloud.customers + s.customers).distinctBy { it.id },
                orders = (cloud.orders + s.orders).distinctBy { it.id },
                trips = mergedTrips,
                invoices = (cloud.invoices + s.invoices).distinctBy { it.id },
                reconciliations = (cloud.reconciliations + s.reconciliations).distinctBy { it.id },
                expenses = (cloud.expenses + s.expenses).distinctBy { it.id },
                gpsEvents = (s.gpsEvents + cloud.gpsEvents).distinctBy { it.id }.sortedBy { it.timestamp }.takeLast(150),
                notifications = mergeNotifications(s.notifications, cloud.notifications),
                directives = (cloud.directives + s.directives).distinctBy { it.id },
                tripStateTransitions = mergedTransitions
            )
        }
        runCatching { LocationTrackingService.updatePowerSaveConfig(context, _state.value.settings) }
    }

    private suspend fun pushCloudSnapshotInternal(s: MiyahnaDomainState): Result<Int> {
        syncFleetMemberEmails(s.driverAccounts)
        val res = cloudSync.pushFullSnapshot(
            settings = s.settings,
            truck = s.truck,
            fleetTrucks = s.fleetTrucks,
            driverAccounts = s.driverAccounts,
            customers = s.customers,
            orders = s.orders,
            trips = s.trips,
            invoices = s.invoices,
            reconciliations = s.reconciliations,
            expenses = s.expenses,
            gpsEvents = s.gpsEvents,
            notifications = s.notifications,
            directives = s.directives,
            tripStateLogs = s.tripStateTransitions,
            driverOnlyMode = s.savedRole == AppRole.DRIVER
        )
        res.onSuccess {
            val syncedIds = s.tripStateTransitions.filterNot { it.isSyncedToCloud }.map { it.id }
            if (syncedIds.isNotEmpty()) {
                runCatching { tripStateTransitionDao.markTransitionsSynced(syncedIds) }
            }
            _state.update { cur ->
                cur.copy(
                    tripStateTransitions = cur.tripStateTransitions.map { it.copy(isSyncedToCloud = true) },
                    lastCloudSyncAt = nowIso(),
                    cloudSyncError = null
                )
            }
        }.onFailure { err ->
            _state.update { it.copy(cloudSyncError = err.message) }
        }
        return res
    }

    // --- Districts, Customers & Orders ---

    suspend fun addCustomDistrict(districtName: String) {
        val clean = districtName.trim()
        if (clean.isBlank() || clean == "__other__" || clean == "أخرى") return
        if (clean in AlWadiDistricts.districtNames || clean in _state.value.settings.customDistricts) return
        mutateState(queueOfflineType = "district", queueOfflinePayload = clean) { s ->
            s.copy(
                settings = s.settings.copy(
                    customDistricts = (s.settings.customDistricts + clean).distinct()
                )
            )
        }
    }

    fun observeCustomers(query: String = ""): Flow<List<Customer>> {
        val clean = query.trim()
        val source = if (clean.isEmpty()) {
            customerDao.observeAllCustomers()
        } else {
            customerDao.searchCustomers(clean)
        }
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    suspend fun findCustomerById(id: String): Customer? =
        customerDao.getCustomerById(id)?.toDomain()

    suspend fun findCustomerByPhone(phone: String): Customer? =
        customerDao.getCustomerByPhone(phone.trim())?.toDomain()

    suspend fun addCustomer(
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ): Customer {
        val cleanDistrict = district.trim().ifEmpty { AlWadiDistricts.DEFAULT_DISTRICT }
        addCustomDistrict(cleanDistrict)
        val seq = max(_state.value.customers.size, customerDao.getCustomersCount()) + 1
        val isOffline = !_state.value.isOnline
        val customer = Customer(
            id = nextId("cust"),
            customerId = "CUST-${seq.toString().padStart(4, '0')}",
            name = name.trim().ifEmpty { "عميل" },
            phone = phone.trim(),
            altPhone = altPhone?.trim()?.takeIf { it.isNotEmpty() },
            district = cleanDistrict,
            addressDetails = addressDetails.trim(),
            lat = lat,
            lng = lng,
            tankCapacityLiters = tankCapacityLiters.coerceAtLeast(500),
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowIso(),
            isActive = true,
            isOfflineCreated = isOffline
        )
        val notif = buildNotification(
            targetRole = "manager",
            actorRole = _state.value.savedRole?.wire ?: "manager",
            actorName = _state.value.driverSession?.name ?: "الإدارة",
            category = "customer_added",
            priority = NotificationPriority.INFO,
            title = "إضافة عميل جديد: ${customer.name}",
            message = "${customer.district} — سعة الخزان ${customer.tankCapacityLiters} لتر",
            actionTab = "customers"
        )
        mutateState(
            queueOfflineType = if (isOffline) "customer" else null,
            queueOfflinePayload = if (isOffline) json.encodeToString(customer) else null
        ) { s ->
            s.copy(
                customers = listOf(customer) + s.customers,
                notifications = listOf(notif) + s.notifications
            )
        }
        return customer
    }

    suspend fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?,
        isActive: Boolean? = null
    ): ActionResult<Customer> {
        val existing = customerDao.getCustomerById(customerId)?.toDomain()
            ?: _state.value.customers.firstOrNull { it.id == customerId }
            ?: return ActionResult(false, error = "العميل غير موجود")

        val cleanDistrict = district.trim().ifEmpty { AlWadiDistricts.DEFAULT_DISTRICT }
        addCustomDistrict(cleanDistrict)
        val isOffline = !_state.value.isOnline
        val updated = existing.copy(
            name = name.trim().ifEmpty { existing.name },
            phone = phone.trim(),
            altPhone = altPhone?.trim()?.takeIf { it.isNotEmpty() },
            district = cleanDistrict,
            addressDetails = addressDetails.trim(),
            lat = lat,
            lng = lng,
            tankCapacityLiters = tankCapacityLiters.coerceAtLeast(500),
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            isActive = isActive ?: existing.isActive,
            isOfflineCreated = existing.isOfflineCreated || isOffline
        )

        mutateState(
            queueOfflineType = if (isOffline) "customer_update" else null,
            queueOfflinePayload = if (isOffline) json.encodeToString(updated) else null
        ) { s ->
            s.copy(
                customers = s.customers.map { if (it.id == customerId) updated else it }
            )
        }
        return ActionResult(true, data = updated)
    }

    suspend fun deleteCustomer(customerId: String): ActionResult<Unit> {
        val exists = customerDao.getCustomerById(customerId) != null ||
            _state.value.customers.any { it.id == customerId }
        if (!exists) return ActionResult(false, error = "العميل غير موجود")

        val isOffline = !_state.value.isOnline
        mutateState(
            queueOfflineType = if (isOffline) "customer_delete" else null,
            queueOfflinePayload = if (isOffline) customerId else null
        ) { s ->
            s.copy(customers = s.customers.filterNot { it.id == customerId })
        }
        if (!isOffline && _state.value.cloudUser != null) {
            scope.launch { cloudSync.deleteCustomerDocument(customerId) }
        }
        return ActionResult(true)
    }

    private fun findMatchingCustomer(
        customers: List<Customer>,
        existingCustomerId: String?,
        customerName: String,
        phone: String
    ): Customer? {
        if (!existingCustomerId.isNullOrBlank()) {
            customers.firstOrNull { it.id == existingCustomerId }?.let { return it }
        }
        val cleanPhone = phone.trim()
        val cleanName = customerName.trim()
        if (cleanPhone.isNotEmpty()) {
            customers.firstOrNull {
                it.phone.trim() == cleanPhone || it.altPhone?.trim() == cleanPhone
            }?.let { return it }
        }
        if (cleanName.isNotEmpty()) {
            customers.firstOrNull {
                it.name.trim().equals(cleanName, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    suspend fun addOrder(
        existingCustomerId: String?,
        customerName: String,
        phone: String,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacity: Int,
        requestedLiters: Int,
        pricePerLiter: Double?,
        source: OrderSource,
        isUrgent: Boolean,
        notes: String?
    ): ActionResult<Order> {
        val snap = _state.value
        val cleanDistrict = district.trim().ifEmpty { AlWadiDistricts.DEFAULT_DISTRICT }
        val cleanName = customerName.trim().ifEmpty { "عميل" }
        val cleanPhone = phone.trim()
        addCustomDistrict(cleanDistrict)

        val offlineNow = !checkNetworkOnlineNow() || !snap.isOnline
        val roomCustomers = customerDao.getAllCustomersOnce().map { it.toDomain() }
        val allKnownCustomers = (snap.customers + roomCustomers).distinctBy { it.id }

        val resolvedCustomer = findMatchingCustomer(
            customers = allKnownCustomers,
            existingCustomerId = existingCustomerId,
            customerName = cleanName,
            phone = cleanPhone
        )

        val newCustomerToInsert: Customer?
        val updatedExistingCustomer: Customer?
        val customerId: String
        val finalCustomerName: String
        val finalPhone: String
        val finalDistrict: String
        val finalAddress: String
        val finalLat: Double
        val finalLng: Double
        val finalTankCapacity: Int

        if (resolvedCustomer == null) {
            val seq = max(allKnownCustomers.size, customerDao.getCustomersCount()) + 1
            val created = Customer(
                id = nextId("cust"),
                customerId = "CUST-${seq.toString().padStart(4, '0')}",
                name = cleanName,
                phone = cleanPhone,
                altPhone = null,
                district = cleanDistrict,
                addressDetails = addressDetails.trim(),
                lat = lat,
                lng = lng,
                tankCapacityLiters = tankCapacity.coerceAtLeast(500),
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = nowIso(),
                isActive = true,
                totalOrdersCount = 1,
                isOfflineCreated = offlineNow
            )
            newCustomerToInsert = created
            updatedExistingCustomer = null
            customerId = created.id
            finalCustomerName = created.name
            finalPhone = created.phone
            finalDistrict = created.district
            finalAddress = created.addressDetails
            finalLat = created.lat
            finalLng = created.lng
            finalTankCapacity = created.tankCapacityLiters
        } else {
            newCustomerToInsert = null
            updatedExistingCustomer = resolvedCustomer.copy(
                phone = resolvedCustomer.phone.ifBlank { cleanPhone },
                totalOrdersCount = resolvedCustomer.totalOrdersCount + 1
            )
            customerId = resolvedCustomer.id
            finalCustomerName = cleanName.ifBlank { resolvedCustomer.name }
            finalPhone = cleanPhone.ifBlank { resolvedCustomer.phone }
            finalDistrict = cleanDistrict.ifBlank { resolvedCustomer.district }
            finalAddress = addressDetails.trim().ifBlank { resolvedCustomer.addressDetails }
            finalLat = lat
            finalLng = lng
            finalTankCapacity = tankCapacity.coerceAtLeast(500)
        }

        // 6-hour duplicate warning check
        val sixHoursAgoMs = System.currentTimeMillis() - (6 * 3600 * 1000L)
        val matchedDup = snap.orders.firstOrNull { existing ->
            existing.status != OrderStatus.CANCELLED &&
                (existing.customerId == customerId || (finalPhone.isNotBlank() && existing.phone == finalPhone)) &&
                (runCatching { Instant.parse(existing.createdAt).toEpochMilli() }.getOrDefault(0L) >= sixHoursAgoMs)
        }

        val unitPrice = pricePerLiter ?: snap.settings.defaultSellingPricePerLiter
        val seq = snap.orders.size + 1
        val order = Order(
            id = nextId("ord"),
            orderNumber = "ORD-${LocalDate.now().year}-${seq.toString().padStart(4, '0')}",
            customerId = customerId,
            customerName = finalCustomerName,
            phone = finalPhone,
            district = finalDistrict,
            addressDetails = finalAddress,
            lat = finalLat,
            lng = finalLng,
            tankCapacity = finalTankCapacity,
            requestedLiters = requestedLiters.coerceIn(250, 6000),
            pricePerLiter = unitPrice,
            totalAmount = requestedLiters.coerceIn(250, 6000) * unitPrice,
            source = source,
            createdAt = nowIso(),
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            status = OrderStatus.WAITING_CLUSTERING,
            isUrgent = isUrgent,
            duplicateWarning = matchedDup != null,
            duplicateMatchedOrderId = matchedDup?.orderNumber
        )

        val orderNotif = buildNotification(
            targetRole = "manager",
            actorRole = if (source == OrderSource.DRIVER_MANUAL) "driver" else "manager",
            actorName = snap.driverSession?.name ?: "الإدارة",
            category = "order_added",
            priority = if (isUrgent) NotificationPriority.URGENT else NotificationPriority.INFO,
            title = if (isUrgent) "طلب عاجل جديد: ${order.customerName}" else "طلب مياه جديد: ${order.customerName}",
            message = "${order.requestedLiters} لتر في ${order.district} (${if (order.totalAmount.isFinite()) order.totalAmount.roundToInt() else 0} ر.ي)",
            orderId = order.id,
            actionTab = "orders"
        )
        val customerNotif = newCustomerToInsert?.let { cust ->
            buildNotification(
                targetRole = "manager",
                actorRole = if (source == OrderSource.DRIVER_MANUAL) "driver" else "manager",
                actorName = snap.driverSession?.name ?: "الإدارة",
                category = "customer_added",
                priority = NotificationPriority.INFO,
                title = "حفظ عميل جديد في قاعدة العملاء: ${cust.name}",
                message = "${cust.district} — سعة الخزان ${cust.tankCapacityLiters} لتر",
                actionTab = "customers"
            )
        }

        mutateState(
            queueOfflineType = if (offlineNow) "order" else null,
            queueOfflinePayload = if (offlineNow) json.encodeToString(order) else null
        ) { s ->
            val updatedCustomers = when {
                newCustomerToInsert != null -> (listOf(newCustomerToInsert) + s.customers).distinctBy { it.id }
                updatedExistingCustomer != null -> s.customers.map {
                    if (it.id == updatedExistingCustomer.id) updatedExistingCustomer else it
                }
                else -> s.customers
            }
            val newNotifs = listOfNotNull(orderNotif, customerNotif)
            s.copy(
                isOnline = !offlineNow,
                customers = updatedCustomers,
                orders = listOf(order) + s.orders,
                notifications = newNotifs + s.notifications
            )
        }
        if (offlineNow) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        return ActionResult(success = true, data = order, warning = matchedDup != null)
    }

    suspend fun cancelOrder(orderId: String) {
        mutateState { s ->
            s.copy(
                orders = s.orders.map {
                    if (it.id == orderId) it.copy(status = OrderStatus.CANCELLED) else it
                }
            )
        }
    }

    // --- Trip Clustering, Approval & Lifecycle ---

    suspend fun createTripFromProposal(
        proposal: ClusterProposal,
        approveImmediately: Boolean = true,
        notes: String? = null
    ): Trip {
        val snap = _state.value
        val seq = snap.trips.size + 1
        val now = nowIso()
        val tripId = nextId("trip")
        val tripNumber = "TRIP-${LocalDate.now().year}-${seq.toString().padStart(4, '0')}"

        val assignedDriverId = snap.truck.driverId.ifBlank {
            snap.driverAccounts.firstOrNull { it.status == DriverAccountStatus.APPROVED }?.id.orEmpty()
        }
        val assignedDriverName = snap.truck.driverName.takeIf { it != "غير معين" }
            ?: snap.driverAccounts.firstOrNull { it.id == assignedDriverId }?.name
            ?: "السائق"

        val status = if (approveImmediately) TripStatus.APPROVED else TripStatus.PENDING_APPROVAL
        val orderStatus = if (approveImmediately) OrderStatus.APPROVED else OrderStatus.CLUSTERED

        val trip = Trip(
            id = tripId,
            tripNumber = tripNumber,
            date = LocalDate.now(ZoneId.systemDefault()).toString(),
            truckId = snap.truck.id,
            driverId = assignedDriverId,
            driverName = assignedDriverName,
            truckPlate = snap.truck.plateNumber,
            initialLoadLiters = proposal.totalLiters,
            deliveredLiters = 0,
            remainingLiters = proposal.totalLiters,
            status = status,
            syncStatus = if (snap.isOnline) TripSyncStatus.SYNCED else TripSyncStatus.LOCAL_ONLY,
            createdByRole = "manager",
            driverAcceptanceStatus = if (approveImmediately) "pending" else null,
            customerStops = proposal.customerStops,
            orderIds = proposal.orders.map { it.id },
            proposedAt = now,
            approvedAt = if (approveImmediately) now else null,
            approvedBy = if (approveImmediately) "المدير العام" else null,
            estimatedDistanceKm = proposal.estimatedDistanceKm,
            estimatedFuelLiters = proposal.estimatedFuelLiters,
            fuelCost = proposal.fuelCost,
            productionCost = proposal.productionCost,
            driverWageShare = proposal.driverWageShare,
            maintenanceShare = proposal.maintenanceShare,
            totalCost = proposal.totalCost,
            expectedRevenue = proposal.expectedRevenue,
            expectedProfit = proposal.expectedProfit,
            profitMarginPercent = proposal.profitMarginPercent,
            capacityUtilizationPercent = proposal.capacityUtilization,
            departureRecommendation = proposal.recommendation,
            recommendationReason = proposal.reason,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() } ?: proposal.reason,
            auditLogs = listOf(
                TripAuditEntry(
                    id = nextId("aud"),
                    timestamp = now,
                    actorRole = "manager",
                    actorName = "المدير العام",
                    action = if (approveImmediately) "اعتماد وتكوين رحلة ذكية" else "تجهيز مخطط رحلة",
                    details = "${proposal.orders.size} طلبات · ${proposal.totalLiters} لتر"
                )
            )
        )

        val notif = buildNotification(
            targetRole = "driver",
            actorRole = "manager",
            actorName = "المدير العام",
            category = "trip_approved",
            priority = NotificationPriority.ACTION_REQUIRED,
            title = "رحلة جديدة مسندة: ${trip.tripNumber}",
            message = "${trip.customerStops.size} محطات (${trip.initialLoadLiters} لتر) — يرجى التأكيد",
            tripId = trip.id,
            tripNumber = trip.tripNumber,
            actionTab = "trip"
        )

        mutateState { s ->
            s.copy(
                trips = listOf(trip) + s.trips,
                orders = s.orders.map { ord ->
                    if (ord.id in trip.orderIds) ord.copy(status = orderStatus, tripId = trip.id) else ord
                },
                truck = if (approveImmediately) {
                    s.truck.copy(
                        status = TruckStatus.LOADING,
                        currentTripId = trip.id,
                        currentWaterLiters = trip.initialLoadLiters
                    )
                } else s.truck,
                notifications = listOf(notif) + s.notifications
            )
        }
        recordTripStateTransition(
            trip = trip,
            fromState = "none",
            toState = status.wire,
            triggerSource = "trip_created",
            actorRole = "manager",
            actorName = "المدير العام",
            details = "${proposal.orders.size} طلبات · ${proposal.totalLiters} لتر"
        )
        return trip
    }

    suspend fun approveTrip(tripId: String, notes: String? = null): ActionResult<Trip> {
        val target = _state.value.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val now = nowIso()
        val updated = target.copy(
            status = TripStatus.APPROVED,
            approvedAt = now,
            approvedBy = "المدير العام",
            notes = notes?.takeIf { it.isNotBlank() } ?: target.notes,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = "manager",
                actorName = "المدير العام",
                action = "اعتماد الرحلة للانطلاق"
            )
        )
        mutateState { s ->
            s.copy(
                trips = s.trips.map { if (it.id == tripId) updated else it },
                orders = s.orders.map { if (it.id in target.orderIds) it.copy(status = OrderStatus.APPROVED, tripId = tripId) else it },
                truck = s.truck.copy(status = TruckStatus.LOADING, currentTripId = tripId, currentWaterLiters = target.initialLoadLiters)
            )
        }
        recordTripStateTransition(
            trip = updated,
            fromState = target.status.wire,
            toState = TripStatus.APPROVED.wire,
            triggerSource = "manager_approved",
            actorRole = "manager",
            actorName = "المدير العام",
            reason = notes
        )
        return ActionResult(true, data = updated)
    }

    suspend fun rejectTrip(tripId: String, reason: String) {
        val target = _state.value.trips.firstOrNull { it.id == tripId } ?: return
        val now = nowIso()
        mutateState { s ->
            s.copy(
                trips = s.trips.map {
                    if (it.id == tripId) {
                        it.copy(
                            status = TripStatus.REJECTED,
                            rejectedAt = now,
                            rejectedBy = "المدير العام",
                            rejectionReason = reason
                        )
                    } else it
                },
                orders = s.orders.map {
                    if (it.id in target.orderIds) it.copy(status = OrderStatus.WAITING_CLUSTERING, tripId = null) else it
                }
            )
        }
        recordTripStateTransition(
            trip = target,
            fromState = target.status.wire,
            toState = TripStatus.REJECTED.wire,
            triggerSource = "manager_rejected",
            actorRole = "manager",
            actorName = "المدير العام",
            reason = reason
        )
    }

    suspend fun respondToManagerTrip(tripId: String, accepted: Boolean, reason: String = ""): ActionResult<Unit> {
        if (!accepted && reason.isBlank()) {
            return ActionResult(false, error = "يرجى ذكر سبب عدم الموافقة ليظهر للمدير")
        }
        val target = _state.value.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val driverName = _state.value.driverSession?.name ?: _state.value.truck.driverName
        val now = nowIso()
        val notif = buildNotification(
            targetRole = "manager",
            actorRole = "driver",
            actorName = driverName,
            category = if (accepted) "trip_driver_accepted" else "trip_driver_declined",
            priority = if (accepted) NotificationPriority.ACTION_REQUIRED else NotificationPriority.URGENT,
            title = if (accepted) "وافق السائق على الرحلة ${target.tripNumber}" else "رفض السائق الرحلة ${target.tripNumber}",
            message = if (accepted) "السائق $driverName وافق ويعمل التطبيق في الخلفية لمتابعة المسار والتوقفات" else reason,
            tripId = target.id,
            tripNumber = target.tripNumber,
            actionTab = "trips"
        )
        val offlineNow = !checkNetworkOnlineNow() || !_state.value.isOnline
        mutateState(
            queueOfflineType = if (offlineNow) "trip_response" else null,
            queueOfflinePayload = if (offlineNow) "$tripId:$accepted" else null,
            syncToCloudAfter = true
        ) { s ->
            s.copy(
                isOnline = !offlineNow,
                trips = s.trips.map {
                    if (it.id == tripId) {
                        val auditEntry = TripAuditEntry(
                            id = nextId("aud"),
                            timestamp = now,
                            actorRole = "driver",
                            actorName = driverName,
                            action = if (accepted) "قبول الرحلة والانتقال التلقائي للخلفية" else "اعتذار السائق عن الرحلة",
                            details = if (accepted) "بدء التشغيل الخلفي لمتابعة التنقلات والتوقفات" else reason.trim()
                        )
                        it.copy(
                            driverAcceptanceStatus = if (accepted) "accepted" else "declined",
                            driverRespondedAt = now,
                            driverResponseReason = reason.takeIf { r -> r.isNotBlank() },
                            auditLogs = it.auditLogs + auditEntry
                        )
                    } else it
                },
                notifications = listOf(notif) + s.notifications
            )
        }
        recordTripStateTransition(
            trip = target,
            fromState = target.status.wire,
            toState = if (accepted) "accepted" else "declined",
            triggerSource = if (accepted) "driver_accepted" else "driver_declined",
            actorRole = "driver",
            actorName = driverName,
            reason = reason.takeIf { it.isNotBlank() },
            details = if (accepted) "تم قبول الرحلة من السائق" else "اعتذر السائق عن الرحلة"
        )
        if (accepted) {
            ensureDriverBackgroundGpsRunning(acceptedTripId = tripId, forceStart = true)
        }
        if (offlineNow) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        return ActionResult(true)
    }

    suspend fun startTrip(
        tripId: String,
        lat: Double? = null,
        lng: Double? = null,
        triggerSource: String = "manual_start"
    ): ActionResult<Trip> {
        val snap = _state.value
        val target = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        if (target.status != TripStatus.APPROVED) {
            return ActionResult(false, error = "يجب اعتماد الرحلة من المدير قبل البدء")
        }
        if (target.createdByRole == "manager" && target.driverAcceptanceStatus == "pending") {
            return ActionResult(false, error = "يرجى تأكيد موافقتك على الرحلة أولاً")
        }
        if (target.createdByRole == "manager" && target.driverAcceptanceStatus == "declined") {
            return ActionResult(false, error = "تم رفض هذه الرحلة من قِبل السائق")
        }

        val startLat = lat ?: snap.settings.plantLocation.lat
        val startLng = lng ?: snap.settings.plantLocation.lng
        val now = nowIso()
        val actorRole = if (triggerSource == "auto_departure_geofence") "system" else (snap.savedRole?.wire ?: "driver")
        val actorName = if (triggerSource == "auto_departure_geofence") "التتبع التلقائي" else (snap.driverSession?.name ?: snap.truck.driverName)
        val startPt = TripGPSPoint(
            id = nextId("pt-start-$tripId"),
            tripId = tripId,
            lat = startLat,
            lng = startLng,
            accuracy = 10.0,
            speedKmH = 0.0,
            timestamp = now,
            seq = 1,
            distanceFromPrevKm = 0.0,
            source = "gps_android_fg"
        )
        val startEv = GPSEvent(
            id = nextId("ev-start"),
            tripId = tripId,
            tripNumber = target.tripNumber,
            timestamp = now,
            eventType = GPSEventType.START,
            lat = startLat,
            lng = startLng,
            speed = 0.0,
            sequenceNumber = snap.gpsEvents.size + 1
        )

        val onlineNow = checkNetworkOnlineNow() && snap.isOnline
        val updated = target.copy(
            status = TripStatus.IN_PROGRESS,
            startedAt = now,
            startLocation = GeoLocationPoint(startLat, startLng),
            gpsPoints = (target.gpsPoints + startPt),
            syncStatus = if (onlineNow) TripSyncStatus.SYNCED else TripSyncStatus.PENDING_SYNC,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = actorRole,
                actorName = actorName,
                action = if (triggerSource == "auto_departure_geofence") "انطلاق تلقائي عند مغادرة المصنع" else "بدء الرحلة وتفعيل المسار"
            )
        )

        tripGpsPointDao.insertPoint(startPt.toEntity(isSynced = onlineNow && snap.cloudUser != null))

        mutateState(
            queueOfflineType = if (!onlineNow) "trip_start" else null,
            queueOfflinePayload = if (!onlineNow) tripId else null
        ) { s ->
            s.copy(
                isOnline = onlineNow,
                trips = s.trips.map { if (it.id == tripId) updated else it },
                orders = s.orders.map { if (it.id in target.orderIds) it.copy(status = OrderStatus.IN_DELIVERY) else it },
                truck = s.truck.copy(
                    status = TruckStatus.ON_TRIP,
                    currentTripId = tripId,
                    lastLat = startLat,
                    lastLng = startLng,
                    lastUpdateTimestamp = now,
                    lastEvent = GPSEventType.START
                ),
                gpsEvents = s.gpsEvents + startEv
            )
        }
        autoPausedTripIds.remove(tripId)
        recordTripStateTransition(
            trip = updated,
            fromState = if (target.driverAcceptanceStatus == "accepted" && triggerSource != "auto_departure_geofence") "accepted" else target.status.wire,
            toState = TripStatus.IN_PROGRESS.wire,
            triggerSource = triggerSource,
            actorRole = actorRole,
            actorName = actorName,
            details = if (startLat.isFinite() && startLng.isFinite()) {
                "انطلاق الرحلة من (${"%.4f, %.4f".format(Locale.US, startLat, startLng)})"
            } else {
                "انطلاق الرحلة"
            },
            lat = startLat,
            lng = startLng,
            speedKmH = 0.0
        )
        ensureDriverBackgroundGpsRunning()
        return ActionResult(true, data = updated)
    }

    suspend fun pauseTrip(
        tripId: String,
        reason: String? = null,
        actorRole: String = "driver"
    ): ActionResult<Trip> {
        val snap = _state.value
        val target = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        if (target.status != TripStatus.IN_PROGRESS) {
            return ActionResult(false, error = "يمكن إيقاف الرحلة مؤقتاً فقط أثناء تنفيذها")
        }
        val now = nowIso()
        val cleanActorRole = actorRole.takeIf { it in listOf("manager", "driver", "system") } ?: "driver"
        val actorName = when (cleanActorRole) {
            "manager" -> "المدير العام"
            "driver" -> snap.driverSession?.name ?: snap.truck.driverName
            else -> "النظام"
        }
        val lastPt = target.gpsPoints.lastOrNull()
        val updated = target.copy(
            status = TripStatus.PAUSED,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = cleanActorRole,
                actorName = actorName,
                action = "إيقاف مؤقت للرحلة",
                details = reason?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
        mutateState(
            queueOfflineType = if (!snap.isOnline) "trip_pause" else null,
            queueOfflinePayload = if (!snap.isOnline) tripId else null
        ) { s ->
            s.copy(
                trips = s.trips.map { if (it.id == tripId) updated else it },
                truck = s.truck.copy(
                    status = TruckStatus.ON_TRIP,
                    currentTripId = tripId,
                    lastEvent = GPSEventType.STOPPED,
                    lastUpdateTimestamp = now
                )
            )
        }
        autoPausedTripIds.remove(tripId)
        recordTripStateTransition(
            trip = updated,
            fromState = TripStatus.IN_PROGRESS.wire,
            toState = TripStatus.PAUSED.wire,
            triggerSource = if (cleanActorRole == "manager") "manager_paused" else "driver_paused",
            actorRole = cleanActorRole,
            actorName = actorName,
            reason = reason,
            lat = lastPt?.lat ?: snap.truck.lastLat,
            lng = lastPt?.lng ?: snap.truck.lastLng,
            speedKmH = lastPt?.speedKmH ?: snap.truck.lastSpeed
        )
        return ActionResult(true, data = updated)
    }

    suspend fun resumeTrip(
        tripId: String,
        reason: String? = null,
        actorRole: String = "driver"
    ): ActionResult<Trip> {
        val snap = _state.value
        val target = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        if (target.status != TripStatus.PAUSED) {
            return ActionResult(false, error = "الرحلة ليست متوقفة مؤقتاً")
        }
        val now = nowIso()
        val cleanActorRole = actorRole.takeIf { it in listOf("manager", "driver", "system") } ?: "driver"
        val actorName = when (cleanActorRole) {
            "manager" -> "المدير العام"
            "driver" -> snap.driverSession?.name ?: snap.truck.driverName
            else -> "النظام"
        }
        val lastPt = target.gpsPoints.lastOrNull()
        val updated = target.copy(
            status = TripStatus.IN_PROGRESS,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = cleanActorRole,
                actorName = actorName,
                action = "استئناف تنفيذ الرحلة",
                details = reason?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
        mutateState(
            queueOfflineType = if (!snap.isOnline) "trip_resume" else null,
            queueOfflinePayload = if (!snap.isOnline) tripId else null
        ) { s ->
            s.copy(
                trips = s.trips.map { if (it.id == tripId) updated else it },
                truck = s.truck.copy(
                    status = TruckStatus.ON_TRIP,
                    currentTripId = tripId,
                    lastEvent = GPSEventType.MOVING,
                    lastUpdateTimestamp = now
                )
            )
        }
        autoPausedTripIds.remove(tripId)
        recordTripStateTransition(
            trip = updated,
            fromState = TripStatus.PAUSED.wire,
            toState = TripStatus.IN_PROGRESS.wire,
            triggerSource = if (cleanActorRole == "manager") "manager_resumed" else "driver_resumed",
            actorRole = cleanActorRole,
            actorName = actorName,
            reason = reason,
            lat = lastPt?.lat ?: snap.truck.lastLat,
            lng = lastPt?.lng ?: snap.truck.lastLng,
            speedKmH = lastPt?.speedKmH ?: snap.truck.lastSpeed
        )
        return ActionResult(true, data = updated)
    }

    suspend fun finishTripByDriver(tripId: String): ActionResult<Trip> {
        val snap = _state.value
        val target = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        if (target.status != TripStatus.IN_PROGRESS && target.status != TripStatus.PAUSED) {
            return ActionResult(false, error = "الرحلة ليست قيد التنفيذ")
        }
        val onlineNow = checkNetworkOnlineNow() || snap.isOnline
        val now = nowIso()
        val roomPts = tripGpsPointDao.getPointsForTripOnce(tripId).map { it.toDomain() }
        val combinedPts = (target.gpsPoints + roomPts).distinctBy { it.id }
        val analysis = GpsTrackingEngine.analyzeGpsTrack(
            tripId = tripId,
            rawPoints = combinedPts,
            approvedStops = target.customerStops,
            settings = snap.settings
        )
        val safeDist = analysis.totalDistanceKm.takeIf { it.isFinite() } ?: 0.0
        val safeCons = snap.settings.truckDieselConsumptionPerKm.takeIf { it.isFinite() } ?: 0.0
        val safeDiesel = snap.settings.dieselPricePerLiter.takeIf { it.isFinite() } ?: 0.0
        val actualFuel = ((safeDist * safeCons) * 100.0).let { if (it.isFinite()) it.roundToInt() else 0 } / 100.0
        val fuelCost = (actualFuel * safeDiesel).let { if (it.isFinite()) it.roundToInt().toDouble() else 0.0 }
        val actualCost = target.productionCost + fuelCost + target.driverWageShare + target.maintenanceShare
        val driverName = snap.driverSession?.name ?: snap.truck.driverName

        val updated = target.copy(
            gpsPoints = analysis.points,
            detectedStops = analysis.detectedStops,
            gpsGaps = analysis.gpsGaps,
            actualDistanceKm = analysis.totalDistanceKm,
            actualFuelLiters = actualFuel,
            fuelCost = fuelCost,
            actualCost = actualCost,
            movingMinutes = analysis.movingMinutes,
            stoppedMinutes = analysis.stoppedMinutes,
            status = if (onlineNow) TripStatus.COMPLETED_PENDING_REVIEW else TripStatus.PENDING_SYNC,
            syncStatus = if (onlineNow) TripSyncStatus.SYNCED else TripSyncStatus.PENDING_SYNC,
            completedAt = now,
            lastSyncedAt = if (onlineNow) now else target.lastSyncedAt,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = "driver",
                actorName = driverName,
                action = if (onlineNow) "إنهاء الرحلة وإرسال المسار للمدير" else "إنهاء الرحلة دون إنترنت (بانتظار المزامنة)",
                details = "${analysis.points.size} نقطة GPS · ${analysis.totalDistanceKm} كم"
            )
        )
        val notif = buildNotification(
            targetRole = "manager",
            actorRole = "driver",
            actorName = driverName,
            category = "trip_completed_driver",
            priority = NotificationPriority.ACTION_REQUIRED,
            title = "أنهى السائق الرحلة ${target.tripNumber}",
            message = "تم تسجيل ${analysis.points.size} نقطة GPS لمسار الرحلة (${analysis.totalDistanceKm} كم) — بانتظار المراجعة",
            tripId = target.id,
            tripNumber = target.tripNumber,
            actionTab = "trips"
        )
        mutateState(
            queueOfflineType = if (!onlineNow) "trip_finish" else null,
            queueOfflinePayload = if (!onlineNow) tripId else null
        ) { s ->
            s.copy(
                isOnline = onlineNow,
                trips = s.trips.map { if (it.id == tripId) updated else it },
                truck = s.truck.copy(
                    status = TruckStatus.IDLE,
                    currentTripId = null,
                    lastEvent = GPSEventType.END,
                    lastUpdateTimestamp = now
                ),
                notifications = listOf(notif) + s.notifications
            )
        }
        autoPausedTripIds.remove(tripId)
        val lastPt = analysis.points.lastOrNull()
        recordTripStateTransition(
            trip = updated,
            fromState = target.status.wire,
            toState = updated.status.wire,
            triggerSource = "driver_completed",
            actorRole = "driver",
            actorName = driverName,
            details = "${analysis.points.size} نقطة · ${analysis.totalDistanceKm} كم",
            lat = lastPt?.lat ?: snap.truck.lastLat,
            lng = lastPt?.lng ?: snap.truck.lastLng,
            speedKmH = lastPt?.speedKmH ?: 0.0
        )
        if (!onlineNow) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        return ActionResult(true, data = updated)
    }

    suspend fun stopTripTrackingByManager(tripId: String, reason: String? = null): ActionResult<Trip> {
        val target = _state.value.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val now = nowIso()
        val updated = target.copy(
            status = TripStatus.COMPLETED_PENDING_REVIEW,
            returnedToPlantAt = target.returnedToPlantAt ?: now,
            completedAt = now,
            auditLogs = target.auditLogs + TripAuditEntry(
                id = nextId("aud"),
                timestamp = now,
                actorRole = "manager",
                actorName = "المدير العام",
                action = "إيقاف التتبع وإنهاء الرحلة إدارياً",
                details = reason
            )
        )
        mutateState { s ->
            s.copy(
                trips = s.trips.map { if (it.id == tripId) updated else it },
                truck = s.truck.copy(
                    status = TruckStatus.IDLE,
                    currentTripId = null,
                    lastEvent = GPSEventType.END,
                    lastUpdateTimestamp = now
                )
            )
        }
        autoPausedTripIds.remove(tripId)
        val lastPt = target.gpsPoints.lastOrNull()
        recordTripStateTransition(
            trip = updated,
            fromState = target.status.wire,
            toState = TripStatus.COMPLETED_PENDING_REVIEW.wire,
            triggerSource = "manager_stopped",
            actorRole = "manager",
            actorName = "المدير العام",
            reason = reason,
            lat = lastPt?.lat,
            lng = lastPt?.lng,
            speedKmH = lastPt?.speedKmH
        )
        stopTrackingServiceIfNotDriver()
        return ActionResult(true, data = updated)
    }

    suspend fun requestTripModification(
        tripId: String,
        proposedStops: List<TripCustomerStop>,
        reason: String
    ): ActionResult<Unit> {
        if (reason.isBlank()) return ActionResult(false, error = "يرجى كتابة سبب طلب التعديل")
        val target = _state.value.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val driverName = _state.value.driverSession?.name ?: _state.value.truck.driverName
        val req = ModificationRequest(
            id = nextId("mod"),
            requestedAt = nowIso(),
            requestedBy = driverName,
            reason = reason.trim(),
            proposedStops = proposedStops,
            proposedOrderIds = proposedStops.map { it.orderId },
            proposedTotalLiters = proposedStops.sumOf { it.requestedLiters }
        )
        val notif = buildNotification(
            targetRole = "manager",
            actorRole = "driver",
            actorName = driverName,
            category = "mod_requested",
            priority = NotificationPriority.ACTION_REQUIRED,
            title = "طلب تعديل على الرحلة ${target.tripNumber}",
            message = reason.trim(),
            tripId = tripId,
            tripNumber = target.tripNumber,
            actionTab = "trips"
        )
        mutateState { s ->
            s.copy(
                trips = s.trips.map {
                    if (it.id == tripId) it.copy(modificationRequests = it.modificationRequests + req) else it
                },
                notifications = listOf(notif) + s.notifications
            )
        }
        return ActionResult(true)
    }

    suspend fun reviewModificationRequest(
        tripId: String,
        requestId: String,
        approved: Boolean,
        reviewNotes: String? = null
    ) {
        val target = _state.value.trips.firstOrNull { it.id == tripId } ?: return
        val mod = target.modificationRequests.firstOrNull { it.id == requestId } ?: return
        val now = nowIso()
        val notif = buildNotification(
            targetRole = "driver",
            actorRole = "manager",
            actorName = "المدير العام",
            category = if (approved) "mod_approved" else "mod_rejected",
            priority = NotificationPriority.ACTION_REQUIRED,
            title = if (approved) "اعتماد تعديل الرحلة ${target.tripNumber}" else "رفض تعديل الرحلة ${target.tripNumber}",
            message = reviewNotes?.takeIf { it.isNotBlank() } ?: mod.reason,
            tripId = tripId,
            tripNumber = target.tripNumber,
            actionTab = "trip"
        )
        mutateState { s ->
            s.copy(
                trips = s.trips.map { t ->
                    if (t.id != tripId) t
                    else {
                        val updatedReqs = t.modificationRequests.map { r ->
                            if (r.id == requestId) {
                                r.copy(
                                    status = if (approved) "approved" else "rejected",
                                    reviewedBy = "المدير العام",
                                    reviewedAt = now,
                                    reviewNotes = reviewNotes
                                )
                            } else r
                        }
                        if (approved) {
                            t.copy(
                                customerStops = mod.proposedStops,
                                orderIds = mod.proposedOrderIds,
                                initialLoadLiters = mod.proposedTotalLiters,
                                modificationRequests = updatedReqs
                            )
                        } else {
                            t.copy(modificationRequests = updatedReqs)
                        }
                    }
                },
                notifications = listOf(notif) + s.notifications
            )
        }
    }

    suspend fun recordLiveGpsFix(
        lat: Double,
        lng: Double,
        speedKmH: Double,
        accuracy: Double?,
        source: String = "gps_android_fg"
    ) {
        initJob.join()
        val snap = _state.value
        val onlineNow = checkNetworkOnlineNow()
        val now = nowIso()
        val nowEpoch = System.currentTimeMillis()

        // 1. Auto-departure check for an approved ready trip
        val readyTrip = snap.trips.firstOrNull {
            it.status == TripStatus.APPROVED &&
                (it.createdByRole != "manager" || it.driverAcceptanceStatus != "declined")
        }
        val activeTrip = snap.trips.firstOrNull {
            it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED
        }

        if (activeTrip == null && readyTrip != null) {
            val dep = GpsTrackingEngine.evaluatePlantDepartureTrigger(
                lat = lat,
                lng = lng,
                accuracy = accuracy,
                speedKmH = speedKmH,
                settings = snap.settings
            )
            val acceptedMovingBeyondPlant = readyTrip.driverAcceptanceStatus == "accepted" &&
                dep.isReliableAccuracy &&
                speedKmH >= snap.settings.minMovementSpeedKmH &&
                dep.distanceFromPlantMeters >= dep.geofenceRadius
            if (dep.outsideTriggerZone || acceptedMovingBeyondPlant) {
                startTrip(readyTrip.id, lat, lng, triggerSource = "auto_departure_geofence")
                return
            }
        }

        if (activeTrip == null) {
            val distFromLastMeters = if (snap.truck.lastLat == 0.0 && snap.truck.lastLng == 0.0) {
                Double.MAX_VALUE
            } else {
                GpsTrackingEngine.haversineDistanceMeters(snap.truck.lastLat, snap.truck.lastLng, lat, lng)
            }
            if (distFromLastMeters >= 15.0 || (nowEpoch - lastStandbyPointSavedMs) >= 60_000L) {
                val standbyPt = TripGPSPoint(
                    id = nextId("pt-bg"),
                    tripId = "driver-bg-${LocalDate.now()}",
                    lat = lat,
                    lng = lng,
                    accuracy = accuracy,
                    speedKmH = speedKmH,
                    timestamp = now,
                    seq = 0,
                    source = "${source}_bg_shift"
                )
                tripGpsPointDao.insertPoint(
                    standbyPt.toEntity(
                        capturedAtEpochMs = nowEpoch,
                        isSynced = false
                    )
                )
                lastStandbyPointSavedMs = nowEpoch
            }

            val isMovingNow = speedKmH >= snap.settings.minMovementSpeedKmH
            val stateChanged = lastStandbyMovingState != null && lastStandbyMovingState != isMovingNow
            lastStandbyMovingState = isMovingNow

            val standbyEvent = if (stateChanged) {
                GPSEvent(
                    id = nextId("ev-bg"),
                    tripId = "standby-${LocalDate.now()}",
                    tripNumber = "حركة خارج رحلة",
                    timestamp = now,
                    eventType = if (isMovingNow) GPSEventType.MOVING else GPSEventType.STOPPED,
                    lat = lat,
                    lng = lng,
                    speed = speedKmH,
                    accuracy = accuracy,
                    source = source.take(32),
                    sequenceNumber = snap.gpsEvents.size + 1
                )
            } else null

            val updatedStandbyTruck = snap.truck.copy(
                lastLat = lat,
                lastLng = lng,
                lastSpeed = speedKmH,
                lastUpdateTimestamp = now,
                lastEvent = if (isMovingNow) GPSEventType.MOVING else GPSEventType.STOPPED
            )

            mutateState(syncToCloudAfter = false) { s ->
                s.copy(
                    isOnline = onlineNow,
                    truck = s.truck.copy(
                        lastLat = lat,
                        lastLng = lng,
                        lastSpeed = speedKmH,
                        lastUpdateTimestamp = now,
                        lastEvent = if (isMovingNow) GPSEventType.MOVING else GPSEventType.STOPPED
                    ),
                    gpsEvents = if (standbyEvent != null) (s.gpsEvents + standbyEvent).takeLast(150) else s.gpsEvents
                )
            }

            if (onlineNow && snap.cloudUser != null &&
                (stateChanged || distFromLastMeters >= 30.0 || (nowEpoch - lastLiveTelemetryPushMs) >= 15_000L)
            ) {
                lastLiveTelemetryPushMs = nowEpoch
                scope.launch {
                    val pushRes = cloudSync.upsertLiveTelemetry(
                        truck = updatedStandbyTruck,
                        activeTrip = null,
                        gpsEvent = standbyEvent
                    )
                    if (pushRes.isSuccess) {
                        val standbyIds = runCatching {
                            tripGpsPointDao.getUnsyncedPointsOnce()
                                .filter { it.tripId.startsWith("driver-bg-") }
                                .map { it.id }
                        }.getOrDefault(emptyList())
                        if (standbyIds.isNotEmpty()) {
                            runCatching { tripGpsPointDao.markPointsSynced(standbyIds) }
                        }
                    } else {
                        lastLiveTelemetryPushMs = 0L
                        runCatching { TripSyncWorker.enqueueWhenOnline(context) }
                    }
                }
            } else if (!onlineNow || snap.cloudUser == null) {
                runCatching { TripSyncWorker.enqueueWhenOnline(context) }
            }
            return
        }

        val newPt = TripGPSPoint(
            id = nextId("pt-${activeTrip.id}"),
            tripId = activeTrip.id,
            lat = lat,
            lng = lng,
            accuracy = accuracy,
            speedKmH = speedKmH,
            timestamp = now,
            seq = activeTrip.gpsPoints.size + 1,
            source = source
        )
        val combinedPts = activeTrip.gpsPoints + newPt
        val analysis = GpsTrackingEngine.analyzeGpsTrack(
            tripId = activeTrip.id,
            rawPoints = combinedPts,
            approvedStops = activeTrip.customerStops,
            settings = snap.settings
        )
        val persistedPoint = analysis.points.lastOrNull() ?: newPt
        tripGpsPointDao.insertPoint(
            persistedPoint.toEntity(
                capturedAtEpochMs = nowEpoch,
                isSynced = false
            )
        )

        val readiness = GpsTrackingEngine.evaluateTripCompletionReadiness(
            trip = activeTrip,
            settings = snap.settings,
            points = analysis.points,
            candidateReturnLocation = lat to lng,
            candidateReturnAccuracy = accuracy
        )

        val safeDist = analysis.totalDistanceKm.takeIf { it.isFinite() } ?: 0.0
        val safeCons = snap.settings.truckDieselConsumptionPerKm.takeIf { it.isFinite() } ?: 0.0
        val safeDiesel = snap.settings.dieselPricePerLiter.takeIf { it.isFinite() } ?: 0.0
        val actualFuel = ((safeDist * safeCons) * 100.0).let { if (it.isFinite()) it.roundToInt() else 0 } / 100.0
        val fuelCost = (actualFuel * safeDiesel).let { if (it.isFinite()) it.roundToInt().toDouble() else 0.0 }
        val actualCost = activeTrip.productionCost + fuelCost + activeTrip.driverWageShare + activeTrip.maintenanceShare

        val newStopDetected = analysis.detectedStops.size > activeTrip.detectedStops.size
        val newGapDetected = analysis.gpsGaps.size > activeTrip.gpsGaps.size

        val autoPauseTriggered = !readiness.qualified &&
            activeTrip.status == TripStatus.IN_PROGRESS &&
            (newStopDetected || newGapDetected)
        val autoResumeTriggered = !readiness.qualified &&
            activeTrip.status == TripStatus.PAUSED &&
            autoPausedTripIds.contains(activeTrip.id) &&
            speedKmH >= snap.settings.minMovementSpeedKmH

        val nextStatus = when {
            readiness.qualified -> if (onlineNow) TripStatus.COMPLETED_PENDING_REVIEW else TripStatus.PENDING_SYNC
            autoPauseTriggered -> TripStatus.PAUSED
            autoResumeTriggered -> TripStatus.IN_PROGRESS
            else -> activeTrip.status
        }

        val updatedTrip = activeTrip.copy(
            gpsPoints = analysis.points,
            detectedStops = analysis.detectedStops,
            gpsGaps = analysis.gpsGaps,
            actualDistanceKm = analysis.totalDistanceKm,
            actualFuelLiters = actualFuel,
            fuelCost = fuelCost,
            actualCost = actualCost,
            movingMinutes = analysis.movingMinutes,
            stoppedMinutes = analysis.stoppedMinutes,
            status = nextStatus,
            syncStatus = if (onlineNow) TripSyncStatus.SYNCED else TripSyncStatus.PENDING_SYNC,
            returnedToPlantAt = if (readiness.qualified) now else activeTrip.returnedToPlantAt,
            completedAt = if (readiness.qualified) now else activeTrip.completedAt,
            returnLocation = if (readiness.qualified) {
                ReturnLocationInfo(
                    lat = lat,
                    lng = lng,
                    distanceFromPlantMeters = readiness.currentDistanceFromPlantMeters,
                    detectedAutomatically = true
                )
            } else activeTrip.returnLocation,
            auditLogs = if (readiness.qualified) {
                activeTrip.auditLogs + TripAuditEntry(
                    id = nextId("aud"),
                    timestamp = now,
                    actorRole = "system",
                    actorName = "التتبع التلقائي",
                    action = "تسجيل عودة وإغلاق الرحلة تلقائياً في محيط المصنع (${readiness.currentDistanceFromPlantMeters}م)",
                    details = "المسافة المسجلة ${analysis.totalDistanceKm} كم"
                )
            } else activeTrip.auditLogs
        )

        val updatedActiveTruck = snap.truck.copy(
            status = if (readiness.qualified) TruckStatus.IDLE else TruckStatus.ON_TRIP,
            currentTripId = if (readiness.qualified) null else activeTrip.id,
            lastLat = lat,
            lastLng = lng,
            lastSpeed = speedKmH,
            lastUpdateTimestamp = now,
            lastEvent = when {
                readiness.qualified -> GPSEventType.RETURN_PLANT
                autoPauseTriggered || nextStatus == TripStatus.PAUSED -> GPSEventType.STOPPED
                else -> GPSEventType.MOVING
            },
            odometerKm = snap.truck.odometerKm + (analysis.points.lastOrNull()?.distanceFromPrevKm ?: 0.0)
        )

        val tripStopOrGapEvent = when {
            newStopDetected -> {
                val latestStop = updatedTrip.detectedStops.lastOrNull()
                GPSEvent(
                    id = nextId("ev-stop"),
                    tripId = activeTrip.id,
                    tripNumber = activeTrip.tripNumber,
                    timestamp = now,
                    eventType = GPSEventType.STOPPED,
                    lat = latestStop?.lat ?: lat,
                    lng = latestStop?.lng ?: lng,
                    speed = speedKmH,
                    accuracy = accuracy,
                    customerId = latestStop?.nearCustomerId,
                    customerName = latestStop?.nearCustomerName ?: if (latestStop?.isUnplannedStop == true) "توقف غير مجدول" else null,
                    source = source.take(32),
                    sequenceNumber = snap.gpsEvents.size + 1
                )
            }
            autoResumeTriggered -> GPSEvent(
                id = nextId("ev-resume"),
                tripId = activeTrip.id,
                tripNumber = activeTrip.tripNumber,
                timestamp = now,
                eventType = GPSEventType.MOVING,
                lat = lat,
                lng = lng,
                speed = speedKmH,
                accuracy = accuracy,
                source = source.take(32),
                sequenceNumber = snap.gpsEvents.size + 1
            )
            else -> null
        }

        val pointPayload = if (!onlineNow) json.encodeToString(persistedPoint) else null
        mutateState(
            queueOfflineType = if (!onlineNow) "gps_point" else null,
            queueOfflinePayload = pointPayload,
            syncToCloudAfter = readiness.qualified
        ) { s ->
            val extraNotifs = if (readiness.qualified) {
                listOf(
                    buildNotification(
                        targetRole = "manager",
                        actorRole = "system",
                        actorName = "التتبع التلقائي",
                        category = "trip_returned_plant",
                        priority = NotificationPriority.ACTION_REQUIRED,
                        title = "عاد الوايت للمصنع: ${activeTrip.tripNumber}",
                        message = "تم تسجيل العودة على بُعد ${readiness.currentDistanceFromPlantMeters}م (${analysis.totalDistanceKm} كم) — بانتظار المراجعة",
                        tripId = activeTrip.id,
                        tripNumber = activeTrip.tripNumber,
                        actionTab = "trips"
                    ),
                    buildNotification(
                        targetRole = "driver",
                        actorRole = "system",
                        actorName = "التتبع التلقائي",
                        category = "trip_auto_closed_driver",
                        priority = NotificationPriority.INFO,
                        title = "تم إغلاق الرحلة تلقائياً: ${activeTrip.tripNumber}",
                        message = "تم تسجيل عودتك إلى محيط المصنع (${readiness.currentDistanceFromPlantMeters}م) وإغلاق الرحلة من واجهة السائق تلقائياً.",
                        tripId = activeTrip.id,
                        tripNumber = activeTrip.tripNumber,
                        actionTab = "trip"
                    )
                )
            } else emptyList()

            s.copy(
                isOnline = onlineNow,
                trips = s.trips.map { if (it.id == activeTrip.id) updatedTrip else it },
                truck = updatedActiveTruck,
                gpsEvents = if (tripStopOrGapEvent != null) (s.gpsEvents + tripStopOrGapEvent).takeLast(150) else s.gpsEvents,
                notifications = extraNotifs + s.notifications
            )
        }

        when {
            readiness.qualified -> {
                autoPausedTripIds.remove(activeTrip.id)
                LocationTrackingPrefs.clearAcceptedTripId(context)
                recordTripStateTransition(
                    trip = updatedTrip,
                    fromState = activeTrip.status.wire,
                    toState = nextStatus.wire,
                    triggerSource = "auto_plant_geofence",
                    actorRole = "system",
                    actorName = "التتبع التلقائي",
                    details = "عودة تلقائية لسياج المصنع (${analysis.totalDistanceKm} كم)",
                    lat = lat,
                    lng = lng,
                    speedKmH = speedKmH
                )
            }
            autoPauseTriggered -> {
                autoPausedTripIds.add(activeTrip.id)
                val latestStop = updatedTrip.detectedStops.lastOrNull()
                val latestGap = updatedTrip.gpsGaps.lastOrNull()
                val trigger = if (newStopDetected) "auto_stop_paused" else "auto_gps_gap"
                val reason = when {
                    newStopDetected && latestStop?.isUnplannedStop == true ->
                        "توقف غير مجدول لمدة ${latestStop.durationMinutes} دقيقة"
                    newStopDetected ->
                        "توقف مرصود${latestStop?.nearCustomerName?.let { " قرب $it" } ?: ""}"
                    else ->
                        "انقطاع إشارة GPS لمدة ${latestGap?.durationSeconds ?: 0} ثانية"
                }
                recordTripStateTransition(
                    trip = updatedTrip,
                    fromState = TripStatus.IN_PROGRESS.wire,
                    toState = TripStatus.PAUSED.wire,
                    triggerSource = trigger,
                    actorRole = "system",
                    actorName = "التتبع التلقائي",
                    reason = reason,
                    lat = latestStop?.lat ?: lat,
                    lng = latestStop?.lng ?: lng,
                    speedKmH = speedKmH
                )
            }
            autoResumeTriggered -> {
                autoPausedTripIds.remove(activeTrip.id)
                recordTripStateTransition(
                    trip = updatedTrip,
                    fromState = TripStatus.PAUSED.wire,
                    toState = TripStatus.IN_PROGRESS.wire,
                    triggerSource = "auto_movement_resumed",
                    actorRole = "system",
                    actorName = "التتبع التلقائي",
                    details = if (speedKmH.isFinite()) {
                        "استئناف الحركة بسرعة ${"%.1f".format(Locale.US, speedKmH)} كم/س"
                    } else {
                        "استئناف الحركة"
                    },
                    lat = lat,
                    lng = lng,
                    speedKmH = speedKmH
                )
            }
        }

        if (!readiness.qualified && onlineNow && snap.cloudUser != null &&
            (newStopDetected || newGapDetected || autoPauseTriggered || autoResumeTriggered || (nowEpoch - lastLiveTelemetryPushMs) >= 15_000L)
        ) {
            lastLiveTelemetryPushMs = nowEpoch
            scope.launch {
                val pushRes = cloudSync.upsertLiveTelemetry(
                    truck = updatedActiveTruck,
                    activeTrip = updatedTrip,
                    gpsEvent = tripStopOrGapEvent
                )
                if (pushRes.isSuccess) {
                    val syncedIds = updatedTrip.gpsPoints.map { it.id }
                    if (syncedIds.isNotEmpty()) {
                        runCatching { tripGpsPointDao.markPointsSynced(syncedIds) }
                    }
                } else {
                    lastLiveTelemetryPushMs = 0L
                    runCatching { TripSyncWorker.enqueueWhenOnline(context) }
                }
            }
        } else if (!onlineNow || snap.cloudUser == null) {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }

        if (readiness.qualified) {
            if (!onlineNow) {
                runCatching { TripSyncWorker.enqueueWhenOnline(context) }
            }
            // Keep GPS running continuously in Driver mode even after trip returns to plant
            stopTrackingServiceIfNotDriver()
        }
    }

    // --- Manager-Exclusive Delivery Logging, Invoicing, Reconciliation, Expenses & Trip Close ---

    suspend fun logManagerDeliveryForStop(
        tripId: String,
        orderId: String,
        deliveredLiters: Int,
        paidAmount: Double
    ): ActionResult<Invoice> {
        val snap = _state.value
        val trip = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val stop = trip.customerStops.firstOrNull { it.orderId == orderId }
            ?: return ActionResult(false, error = "المحطة غير موجودة")

        val total = deliveredLiters * stop.pricePerLiter
        val remaining = max(0.0, total - paidAmount)
        val now = nowIso()
        val invNum = "INV-${LocalDate.now().year}-${(snap.invoices.size + 1).toString().padStart(4, '0')}"
        val orderNum = snap.orders.firstOrNull { it.id == orderId }?.orderNumber ?: orderId

        val invoice = Invoice(
            id = nextId("inv"),
            invoiceNumber = invNum,
            orderId = orderId,
            orderNumber = orderNum,
            tripId = tripId,
            tripNumber = trip.tripNumber,
            customerId = stop.customerId,
            customerName = stop.customerName,
            phone = stop.phone,
            district = stop.district,
            requestedLiters = stop.requestedLiters,
            deliveredLiters = deliveredLiters,
            pricePerLiter = stop.pricePerLiter,
            totalAmount = total,
            paidAmount = paidAmount,
            remainingAmount = remaining,
            createdAt = now,
            driverName = trip.driverName,
            isReconciled = false
        )

        val updatedStops = trip.customerStops.map {
            if (it.orderId == orderId) {
                it.copy(
                    deliveredLiters = deliveredLiters,
                    totalAmount = total,
                    paidAmount = paidAmount,
                    remainingAmount = remaining,
                    status = StopStatus.DELIVERED,
                    departureTime = now,
                    invoiceId = invoice.id
                )
            } else it
        }
        val totalDelivered = updatedStops.sumOf { it.deliveredLiters }
        val actualRev = updatedStops.sumOf { it.totalAmount }
        val cost = trip.actualCost ?: trip.totalCost
        val updatedTrip = trip.copy(
            customerStops = updatedStops,
            deliveredLiters = totalDelivered,
            remainingLiters = max(0, trip.initialLoadLiters - totalDelivered),
            actualRevenue = actualRev,
            actualProfit = actualRev - cost
        )

        mutateState { s ->
            s.copy(
                trips = s.trips.map { if (it.id == tripId) updatedTrip else it },
                orders = s.orders.map { if (it.id == orderId) it.copy(status = OrderStatus.DELIVERED) else it },
                customers = s.customers.map {
                    if (it.id == stop.customerId) {
                        it.copy(
                            totalOrdersCount = it.totalOrdersCount + 1,
                            totalLitersDelivered = it.totalLitersDelivered + deliveredLiters
                        )
                    } else it
                },
                invoices = listOf(invoice) + s.invoices
            )
        }
        return ActionResult(true, data = invoice)
    }

    suspend fun closeTripAfterReview(tripId: String, reviewNotes: String? = null) {
        val target = _state.value.trips.firstOrNull { it.id == tripId } ?: return
        val now = nowIso()
        mutateState { s ->
            s.copy(
                trips = s.trips.map {
                    if (it.id == tripId) {
                        it.copy(
                            status = TripStatus.CLOSED,
                            closedAt = now,
                            closedBy = "المدير العام",
                            auditLogs = it.auditLogs + TripAuditEntry(
                                id = nextId("aud"),
                                timestamp = now,
                                actorRole = "manager",
                                actorName = "المدير العام",
                                action = "اعتماد المراجعة وإغلاق الرحلة نهائياً",
                                details = reviewNotes
                            )
                        )
                    } else it
                }
            )
        }
        autoPausedTripIds.remove(tripId)
        recordTripStateTransition(
            trip = target,
            fromState = target.status.wire,
            toState = TripStatus.CLOSED.wire,
            triggerSource = "manager_closed",
            actorRole = "manager",
            actorName = "المدير العام",
            reason = reviewNotes
        )
    }

    suspend fun deleteTrip(tripId: String): ActionResult<Unit> {
        initJob.join()
        val target = _state.value.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")

        val canDelete = target.status == TripStatus.CLOSED ||
            target.status == TripStatus.REJECTED ||
            target.status == TripStatus.COMPLETED_PENDING_REVIEW ||
            target.status == TripStatus.RETURNED_TO_PLANT ||
            target.status == TripStatus.SYNCED ||
            target.status == TripStatus.PENDING_SYNC
        if (!canDelete) {
            return ActionResult(false, error = "لا يمكن حذف رحلة ما زالت قيد التنفيذ — أكمل المراجعة أو أوقف الرحلة أولاً")
        }

        val onlineNow = checkNetworkOnlineNow() && _state.value.isOnline
        pendingDeletedTripIds.add(tripId)
        autoPausedTripIds.remove(tripId)
        tripGpsPointDao.deletePointsForTrip(tripId)
        tripStateTransitionDao.deleteTransitionsForTrip(tripId)

        mutateState(
            queueOfflineType = if (!onlineNow) "trip_delete" else null,
            queueOfflinePayload = if (!onlineNow) tripId else null,
            syncToCloudAfter = false
        ) { s ->
            s.copy(
                trips = s.trips.filterNot { it.id == tripId },
                orders = s.orders.map { ord ->
                    if (ord.tripId == tripId && ord.status != OrderStatus.DELIVERED && ord.status != OrderStatus.CANCELLED) {
                        ord.copy(tripId = null, status = OrderStatus.WAITING_CLUSTERING)
                    } else ord
                },
                gpsEvents = s.gpsEvents.filterNot { it.tripId == tripId },
                tripStateTransitions = s.tripStateTransitions.filterNot { it.tripId == tripId },
                truck = if (s.truck.currentTripId == tripId) {
                    s.truck.copy(status = TruckStatus.IDLE, currentTripId = null)
                } else s.truck
            )
        }

        if (onlineNow && _state.value.cloudUser != null) {
            val res = cloudSync.deleteTripDocument(tripId)
            if (res.isSuccess) {
                pendingDeletedTripIds.remove(tripId)
            } else {
                stateDao.insertPendingOp(
                    PendingOfflineOpEntity(
                        id = nextId("op"),
                        type = "trip_delete",
                        payloadJson = tripId,
                        createdAt = nowIso()
                    )
                )
                runCatching { TripSyncWorker.enqueueWhenOnline(context) }
            }
        }
        return ActionResult(true)
    }

    suspend fun markTransitionReviewed(logId: String, note: String? = null): ActionResult<Unit> {
        val now = nowIso()
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() } ?: "تمت المراجعة من المدير"
        tripStateTransitionDao.markTransitionReviewed(logId, now, cleanNote)
        mutateState(syncToCloudAfter = false) { s ->
            s.copy(
                tripStateTransitions = s.tripStateTransitions.map {
                    if (it.id == logId) {
                        it.copy(
                            reviewedByManager = true,
                            reviewedAt = now,
                            reviewNote = cleanNote
                        )
                    } else it
                }
            )
        }
        val snap = _state.value
        if (snap.isOnline && snap.cloudUser != null) {
            val cloudRes = cloudSync.markTripStateTransitionReviewed(logId, "المدير العام", cleanNote)
            if (cloudRes.isFailure) {
                val errMsg = cloudRes.exceptionOrNull()?.let {
                    toFriendlyFirestoreMessage(it, "تم الحفظ محلياً فقط — تعذر تحديث السحابة")
                } ?: "تعذر تحديث حالة المراجعة في السحابة"
                return ActionResult(false, error = errMsg)
            }
        }
        return ActionResult(true)
    }

    suspend fun submitDriverReconciliation(
        tripId: String,
        actualCashHandedOver: Double,
        authorizedExpensesDeducted: Double,
        notes: String?
    ): ActionResult<DriverReconciliation> {
        val snap = _state.value
        val trip = snap.trips.firstOrNull { it.id == tripId }
            ?: return ActionResult(false, error = "الرحلة غير موجودة")
        val tripInvoices = snap.invoices.filter { it.tripId == tripId }
        val totalInvoiced = tripInvoices.sumOf { it.totalAmount }.takeIf { it > 0.0 }
            ?: trip.customerStops.sumOf { it.totalAmount }
        val totalCash = tripInvoices.sumOf { it.paidAmount }.takeIf { it > 0.0 }
            ?: trip.customerStops.sumOf { it.paidAmount }
        val expectedNet = totalCash - authorizedExpensesDeducted
        val diff = actualCashHandedOver - expectedNet
        val status = if (kotlin.math.abs(diff) < 1.0) "approved_reconciled" else "discrepancy"

        val rec = DriverReconciliation(
            id = nextId("rec"),
            reconciliationNumber = "REC-${LocalDate.now().year}-${(snap.reconciliations.size + 1).toString().padStart(4, '0')}",
            tripId = tripId,
            tripNumber = trip.tripNumber,
            driverId = trip.driverId,
            driverName = trip.driverName,
            date = LocalDate.now().toString(),
            totalInvoicedAmount = totalInvoiced,
            totalCashCollected = totalCash,
            actualCashHandedOver = actualCashHandedOver,
            authorizedExpensesDeducted = authorizedExpensesDeducted,
            differenceAmount = diff,
            status = status,
            notes = notes?.takeIf { it.isNotBlank() },
            approvedBy = "المدير العام",
            createdAt = nowIso()
        )
        mutateState { s ->
            s.copy(
                reconciliations = listOf(rec) + s.reconciliations,
                trips = s.trips.map {
                    if (it.id == tripId) it.copy(reconciledAt = rec.createdAt, status = TripStatus.CLOSED) else it
                },
                invoices = s.invoices.map {
                    if (it.tripId == tripId) it.copy(isReconciled = true) else it
                }
            )
        }
        if (trip.status != TripStatus.CLOSED) {
            recordTripStateTransition(
                trip = trip,
                fromState = trip.status.wire,
                toState = TripStatus.CLOSED.wire,
                triggerSource = "reconciliation_closed",
                actorRole = "manager",
                actorName = "المدير العام",
                reason = notes
            )
        }
        return ActionResult(true, data = rec)
    }

    suspend fun addExpense(
        category: ExpenseCategory,
        description: String,
        amount: Double,
        liters: Double?,
        tripId: String?,
        receiptNumber: String?
    ): Expense {
        val snap = _state.value
        val linkedTrip = tripId?.let { id -> snap.trips.firstOrNull { it.id == id } }
        val exp = Expense(
            id = nextId("exp"),
            expenseNumber = "EXP-${LocalDate.now().year}-${(snap.expenses.size + 1).toString().padStart(4, '0')}",
            date = LocalDate.now().toString(),
            category = category,
            description = description.trim().ifEmpty { category.labelAr },
            amount = amount,
            liters = liters,
            tripId = linkedTrip?.id,
            tripNumber = linkedTrip?.tripNumber,
            receiptNumber = receiptNumber?.trim()?.takeIf { it.isNotEmpty() },
            createdBy = "المدير العام"
        )
        mutateState { s -> s.copy(expenses = listOf(exp) + s.expenses) }
        return exp
    }

    // --- Notifications & Directives ---

    suspend fun markNotificationRead(id: String) {
        notificationHelper.markNotificationsSeen(listOf(id))
        notificationHelper.cancelNotification(id)
        mutateState { s ->
            s.copy(notifications = s.notifications.map { if (it.id == id) it.copy(isRead = true) else it })
        }
    }

    suspend fun markAllNotificationsRead(targetRole: String) {
        val ids = _state.value.notifications.filter { it.targetRole == targetRole }.map { it.id }
        notificationHelper.markNotificationsSeen(ids)
        ids.forEach { notificationHelper.cancelNotification(it) }
        mutateState { s ->
            s.copy(
                notifications = s.notifications.map {
                    if (it.targetRole == targetRole) it.copy(isRead = true) else it
                }
            )
        }
    }

    suspend fun sendManagerDirective(
        title: String,
        message: String,
        urgent: Boolean,
        tripId: String? = null
    ) {
        val trip = tripId?.let { id -> _state.value.trips.firstOrNull { it.id == id } }
        val dir = ManagerDirective(
            id = nextId("dir"),
            createdAt = nowIso(),
            createdBy = "المدير العام",
            title = title.trim(),
            message = message.trim(),
            priority = if (urgent) "urgent" else "normal",
            tripId = trip?.id,
            tripNumber = trip?.tripNumber,
            status = DirectiveStatus.SENT
        )
        val notif = buildNotification(
            targetRole = "driver",
            actorRole = "manager",
            actorName = "المدير العام",
            category = "directive",
            priority = if (urgent) NotificationPriority.URGENT else NotificationPriority.ACTION_REQUIRED,
            title = "توجيه إداري: ${dir.title}",
            message = dir.message,
            directiveId = dir.id,
            tripId = dir.tripId,
            tripNumber = dir.tripNumber
        )
        mutateState { s ->
            s.copy(
                directives = listOf(dir) + s.directives,
                notifications = listOf(notif) + s.notifications
            )
        }
    }

    suspend fun updateDirectiveStatus(directiveId: String, status: DirectiveStatus, reply: String? = null) {
        val now = nowIso()
        mutateState { s ->
            s.copy(
                directives = s.directives.map {
                    if (it.id == directiveId) {
                        it.copy(
                            status = status,
                            acknowledgedAt = if (status == DirectiveStatus.ACKNOWLEDGED && it.acknowledgedAt == null) now else it.acknowledgedAt,
                            completedAt = if (status == DirectiveStatus.COMPLETED) now else it.completedAt,
                            driverReply = reply?.takeIf { r -> r.isNotBlank() } ?: it.driverReply
                        )
                    } else it
                }
            )
        }
    }

    // --- Settings, Truck & Clear Operational Data ---

    suspend fun updateSettings(updated: SystemSettings) {
        mutateState { s -> s.copy(settings = updated) }
        runCatching { LocationTrackingService.updatePowerSaveConfig(context, updated) }
    }

    suspend fun updateTruck(updated: Truck) {
        mutateState { s ->
            val updatedPrimary = if (updated.id == s.truck.id) updated else s.truck
            val updatedFleet = syncPrimaryTruckIntoFleet(
                primary = updatedPrimary,
                fleet = s.fleetTrucks.map { if (it.id == updated.id) updated else it }
            )
            s.copy(truck = updatedPrimary, fleetTrucks = updatedFleet)
        }
    }

    suspend fun addFleetTruck(
        code: String,
        plateNumber: String,
        model: String,
        maxCapacityLiters: Int,
        driverId: String? = null
    ): ActionResult<Truck> {
        val cleanPlate = plateNumber.trim()
        if (cleanPlate.isBlank()) {
            return ActionResult(false, error = "يرجى إدخال رقم لوحة الشاحنة")
        }
        val snap = _state.value
        val seq = snap.fleetTrucks.size + 1
        val resolvedCode = code.trim().ifBlank { "TRUCK-${seq.toString().padStart(2, '0')}" }
        val assignedDriver = driverId?.let { id -> snap.driverAccounts.firstOrNull { it.id == id } }
        val newTruck = Truck(
            id = nextId("truck"),
            code = resolvedCode,
            plateNumber = cleanPlate,
            model = model.trim().ifBlank { "وايت مياه صالح للشرب ${maxCapacityLiters}L" },
            maxCapacityLiters = maxCapacityLiters.coerceIn(1000, 30000),
            currentWaterLiters = 0,
            driverId = assignedDriver?.id.orEmpty(),
            driverName = assignedDriver?.name ?: "غير معين",
            driverPhone = assignedDriver?.phone.orEmpty(),
            status = TruckStatus.IDLE,
            lastLat = snap.settings.plantLocation.lat,
            lastLng = snap.settings.plantLocation.lng,
            lastSpeed = 0.0,
            lastUpdateTimestamp = nowIso(),
            lastEvent = GPSEventType.RETURN_PLANT
        )
        mutateState { s ->
            s.copy(fleetTrucks = s.fleetTrucks + newTruck)
        }
        return ActionResult(true, data = newTruck)
    }

    suspend fun updateFleetTruckStatus(truckId: String, status: TruckStatus) {
        val now = nowIso()
        mutateState { s ->
            val nextFleet = s.fleetTrucks.map {
                if (it.id == truckId) it.copy(status = status, lastUpdateTimestamp = now) else it
            }
            val nextPrimary = if (s.truck.id == truckId) {
                s.truck.copy(status = status, lastUpdateTimestamp = now)
            } else s.truck
            s.copy(truck = nextPrimary, fleetTrucks = nextFleet)
        }
    }

    // --- Water Truck Delivery Location GPS Capture & Room Logs ---

    fun observeDeliveryLocations(tripId: String? = null): Flow<List<DeliveryLocationLog>> {
        val source = if (tripId.isNullOrBlank()) {
            deliveryLocationDao.observeAllDeliveryLocations()
        } else {
            deliveryLocationDao.observeDeliveryLocationsForTrip(tripId)
        }
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    fun triggerDeliveryLocationCapture(
        tripId: String? = null,
        tripNumber: String? = null,
        orderId: String? = null,
        customerId: String? = null,
        customerName: String? = null,
        deliveredLiters: Int? = null,
        notes: String? = null
    ) {
        _isCapturingDeliveryLocation.value = true
        val launched = DeliveryLocationCaptureService.captureCurrentLocation(
            context = context,
            tripId = tripId,
            tripNumber = tripNumber,
            orderId = orderId,
            customerId = customerId,
            customerName = customerName,
            deliveredLiters = deliveredLiters,
            notes = notes
        )
        if (!launched) {
            _isCapturingDeliveryLocation.value = false
            _deliveryCaptureMessages.tryEmit("تعذر تشغيل خدمة تحديد الموقع بالخلفية")
        }
    }

    fun setDeliveryLocationCapturing(capturing: Boolean) {
        _isCapturingDeliveryLocation.value = capturing
    }

    fun reportDeliveryLocationCaptureError(message: String) {
        _deliveryCaptureMessages.tryEmit(message)
    }

    suspend fun logCapturedDeliveryLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float? = null,
        altitudeMeters: Double? = null,
        speedKmH: Double = 0.0,
        tripId: String? = null,
        tripNumber: String? = null,
        orderId: String? = null,
        customerId: String? = null,
        customerName: String? = null,
        deliveredLiters: Int? = null,
        captureSource: String = "delivery_gps_service",
        notes: String? = null
    ): DeliveryLocationLog {
        val snap = _state.value
        val activeTrip = tripId?.let { id -> snap.trips.firstOrNull { it.id == id } }
            ?: snap.trips.firstOrNull { it.status == TripStatus.IN_PROGRESS }
        val resolvedTripId = tripId ?: activeTrip?.id
        val resolvedTripNumber = tripNumber ?: activeTrip?.tripNumber
        val nowEpoch = System.currentTimeMillis()
        val now = nowIso()

        val log = DeliveryLocationLog(
            id = nextId("dloc"),
            tripId = resolvedTripId,
            tripNumber = resolvedTripNumber,
            orderId = orderId,
            customerId = customerId,
            customerName = customerName,
            truckId = snap.truck.id,
            truckPlate = snap.truck.plateNumber,
            driverId = snap.driverSession?.id ?: snap.truck.driverId,
            driverName = snap.driverSession?.name ?: snap.truck.driverName,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            speedKmH = speedKmH,
            deliveredLiters = deliveredLiters,
            captureSource = captureSource,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            capturedAtIso = now,
            capturedAtEpochMs = nowEpoch,
            isSynced = snap.isOnline && snap.cloudUser != null
        )

        deliveryLocationDao.insertDeliveryLocation(log.toEntity())

        val arrivalEvent = GPSEvent(
            id = nextId("ev-dloc"),
            tripId = resolvedTripId ?: "delivery-$nowEpoch",
            tripNumber = (resolvedTripNumber ?: "تسليم ميداني").take(64),
            timestamp = now,
            eventType = GPSEventType.ARRIVAL,
            lat = latitude,
            lng = longitude,
            speed = speedKmH,
            accuracy = accuracyMeters?.toDouble(),
            customerId = customerId,
            customerName = customerName?.take(150),
            source = captureSource.take(32),
            sequenceNumber = snap.gpsEvents.size + 1
        )

        mutateState(
            queueOfflineType = if (!snap.isOnline) "delivery_location" else null,
            queueOfflinePayload = if (!snap.isOnline) json.encodeToString(log) else null
        ) { s ->
            s.copy(
                truck = s.truck.copy(
                    lastLat = latitude,
                    lastLng = longitude,
                    lastSpeed = speedKmH,
                    lastUpdateTimestamp = now,
                    lastEvent = GPSEventType.ARRIVAL
                ),
                gpsEvents = (s.gpsEvents + arrivalEvent).takeLast(150)
            )
        }

        val coordsFormatted = if (latitude.isFinite() && longitude.isFinite()) {
            "%.4f, %.4f".format(Locale.US, latitude, longitude)
        } else {
            "—"
        }
        val label = customerName?.takeIf { it.isNotBlank() } ?: "موقع التسليم"
        _deliveryCaptureMessages.tryEmit("تم تسجيل $label ($coordsFormatted)")
        return log
    }

    suspend fun deleteDeliveryLocationLog(id: String) {
        deliveryLocationDao.deleteDeliveryLocationById(id)
    }

    suspend fun clearOperationalData() {
        val now = nowIso()
        stateDao.clearAllPendingOps()
        deliveryLocationDao.clearAllDeliveryLocations()
        tripGpsPointDao.clearAllPoints()
        tripStateTransitionDao.clearAllTransitions()
        autoPausedTripIds.clear()
        mutateState { s ->
            s.copy(
                customers = emptyList(),
                orders = emptyList(),
                trips = emptyList(),
                invoices = emptyList(),
                reconciliations = emptyList(),
                expenses = emptyList(),
                gpsEvents = emptyList(),
                notifications = emptyList(),
                directives = emptyList(),
                tripStateTransitions = emptyList(),
                pendingSyncCount = 0,
                truck = s.truck.copy(
                    status = TruckStatus.IDLE,
                    currentTripId = null,
                    currentWaterLiters = 0,
                    lastLat = s.settings.plantLocation.lat,
                    lastLng = s.settings.plantLocation.lng,
                    lastSpeed = 0.0,
                    lastEvent = GPSEventType.RETURN_PLANT,
                    lastUpdateTimestamp = now
                ),
                settings = s.settings.copy(
                    demoDataActive = false,
                    demoDataClearedAt = now,
                    demoResetVersion = s.settings.demoResetVersion + 1
                )
            )
        }
        stopTrackingServiceIfNotDriver()
    }

    private fun buildNotification(
        targetRole: String,
        actorRole: String,
        actorName: String,
        category: String,
        priority: NotificationPriority,
        title: String,
        message: String,
        tripId: String? = null,
        tripNumber: String? = null,
        orderId: String? = null,
        directiveId: String? = null,
        actionTab: String? = null
    ) = AppNotification(
        id = nextId("notif"),
        createdAt = nowIso(),
        targetRole = targetRole,
        actorRole = actorRole,
        actorName = actorName,
        category = category,
        priority = priority,
        title = title,
        message = message,
        tripId = tripId,
        tripNumber = tripNumber,
        orderId = orderId,
        directiveId = directiveId,
        isRead = false,
        actionTab = actionTab
    )

    private suspend fun recordTripStateTransition(
        trip: Trip,
        fromState: String,
        toState: String,
        triggerSource: String,
        actorRole: String,
        actorName: String,
        reason: String? = null,
        details: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        speedKmH: Double? = null
    ): TripStateTransition {
        val nowEpoch = System.currentTimeMillis()
        val now = nowIso()
        val seq = transitionSeq.incrementAndGet()
        val entry = TripStateTransition(
            id = "tlog-$nowEpoch-$seq",
            tripId = trip.id,
            tripNumber = trip.tripNumber,
            truckId = trip.truckId,
            driverId = trip.driverId,
            driverName = trip.driverName,
            fromState = fromState,
            toState = toState,
            triggerSource = triggerSource,
            actorRole = actorRole.takeIf { it in listOf("manager", "driver", "system") } ?: "system",
            actorName = actorName.ifBlank { "النظام" }.take(120),
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
            details = details?.trim()?.takeIf { it.isNotEmpty() },
            lat = lat,
            lng = lng,
            speedKmH = speedKmH,
            timestamp = now,
            timestampEpochMs = nowEpoch,
            reviewedByManager = false,
            isSyncedToCloud = false
        )
        tripStateTransitionDao.insertTransition(entry.toEntity(isSynced = false))
        _state.update { s ->
            s.copy(tripStateTransitions = mergeTransitions(listOf(entry), s.tripStateTransitions).take(500))
        }
        val snap = _state.value
        val onlineNow = snap.isOnline || checkNetworkOnlineNow()
        if (onlineNow && snap.cloudUser != null) {
            scope.launch {
                val res = cloudSync.logTripStateTransition(entry)
                if (res.isSuccess) {
                    tripStateTransitionDao.markTransitionSynced(entry.id)
                    _state.update { cur ->
                        cur.copy(
                            tripStateTransitions = cur.tripStateTransitions.map {
                                if (it.id == entry.id) it.copy(isSyncedToCloud = true) else it
                            }
                        )
                    }
                } else {
                    runCatching { TripSyncWorker.enqueueWhenOnline(context) }
                }
            }
        } else {
            runCatching { TripSyncWorker.enqueueWhenOnline(context) }
        }
        return entry
    }

    private fun mergeTransitions(
        primary: List<TripStateTransition>,
        secondary: List<TripStateTransition>
    ): List<TripStateTransition> {
        val merged = linkedMapOf<String, TripStateTransition>()
        (secondary + primary).forEach { item ->
            val existing = merged[item.id]
            merged[item.id] = if (existing == null) {
                item
            } else {
                item.copy(
                    reviewedByManager = existing.reviewedByManager || item.reviewedByManager,
                    reviewedAt = item.reviewedAt ?: existing.reviewedAt,
                    reviewNote = item.reviewNote ?: existing.reviewNote,
                    isSyncedToCloud = existing.isSyncedToCloud || item.isSyncedToCloud
                )
            }
        }
        return merged.values.sortedByDescending { it.timestampEpochMs }
    }

    private fun migrateLegacyPlantSettings(settings: SystemSettings): SystemSettings {
        val isLegacyCoords = kotlin.math.abs(settings.plantLocation.lat - 15.4920) < 0.0001 &&
            kotlin.math.abs(settings.plantLocation.lng - 45.3620) < 0.0001
        val nextPlant = if (isLegacyCoords) {
            settings.plantLocation.copy(
                city = "مديرية الوادي - مأرب",
                lat = 15.4698538,
                lng = 45.3636985
            )
        } else settings.plantLocation
        val nextGeofence = if (isLegacyCoords && settings.plantGeofenceMeters == 120) {
            300
        } else settings.plantGeofenceMeters
        return if (nextPlant != settings.plantLocation || nextGeofence != settings.plantGeofenceMeters) {
            settings.copy(plantLocation = nextPlant, plantGeofenceMeters = nextGeofence)
        } else settings
    }

    private fun nowIso(): String = Instant.now().toString()
}

internal fun tripStatusRank(status: TripStatus): Int = when (status) {
    TripStatus.DRAFT -> 0
    TripStatus.PENDING_APPROVAL -> 1
    TripStatus.APPROVED -> 2
    TripStatus.IN_PROGRESS, TripStatus.PAUSED -> 3
    TripStatus.RETURNED_TO_PLANT -> 4
    TripStatus.PENDING_SYNC -> 5
    TripStatus.SYNCED -> 6
    TripStatus.COMPLETED_PENDING_REVIEW -> 7
    TripStatus.CLOSED -> 8
    TripStatus.REJECTED -> 9
}

internal fun mergeTripModels(local: Trip?, cloud: Trip?, settings: SystemSettings): Trip? {
    if (local == null) return cloud
    if (cloud == null) return local

    val stopRank: (StopStatus) -> Int = { st ->
        when (st) {
            StopStatus.PENDING -> 0
            StopStatus.ARRIVED -> 1
            StopStatus.UNLOADING -> 2
            StopStatus.SKIPPED -> 3
            StopStatus.DELIVERED -> 4
        }
    }

    val mergedPts = (local.gpsPoints + cloud.gpsPoints)
        .distinctBy { it.id }
        .sortedWith(compareBy<TripGPSPoint> { it.timestamp }.thenBy { it.seq })

    val localStopMap = local.customerStops.associateBy { it.orderId }
    val cloudStopMap = cloud.customerStops.associateBy { it.orderId }
    val allStopIds = (local.customerStops.map { it.orderId } + cloud.customerStops.map { it.orderId }).distinct()
    val mergedStops = allStopIds.mapNotNull { ordId ->
        val ls = localStopMap[ordId]
        val cs = cloudStopMap[ordId]
        when {
            ls == null -> cs
            cs == null -> ls
            stopRank(cs.status) > stopRank(ls.status) -> cs
            stopRank(ls.status) > stopRank(cs.status) -> ls
            cs.deliveredLiters >= ls.deliveredLiters && cs.paidAmount >= ls.paidAmount -> cs
            else -> ls
        }
    }

    val mergedModRequests = (cloud.modificationRequests + local.modificationRequests)
        .groupBy { it.id }
        .values
        .mapNotNull { group ->
            group.firstOrNull { it.status != "pending" } ?: group.firstOrNull()
        }

    val mergedAuditLogs = (local.auditLogs + cloud.auditLogs)
        .distinctBy { it.id }
        .sortedBy { it.timestamp }

    val (resolvedAcceptance, resolvedRespondedAt, resolvedResponseReason) = when {
        cloud.driverAcceptanceStatus != null && cloud.driverAcceptanceStatus != "pending" &&
            (local.driverAcceptanceStatus == null || local.driverAcceptanceStatus == "pending") ->
            Triple(cloud.driverAcceptanceStatus, cloud.driverRespondedAt, cloud.driverResponseReason)
        local.driverAcceptanceStatus != null && local.driverAcceptanceStatus != "pending" &&
            (cloud.driverAcceptanceStatus == null || cloud.driverAcceptanceStatus == "pending") ->
            Triple(local.driverAcceptanceStatus, local.driverRespondedAt, local.driverResponseReason)
        (cloud.driverRespondedAt ?: "") > (local.driverRespondedAt ?: "") ->
            Triple(cloud.driverAcceptanceStatus, cloud.driverRespondedAt, cloud.driverResponseReason)
        else ->
            Triple(
                local.driverAcceptanceStatus ?: cloud.driverAcceptanceStatus,
                local.driverRespondedAt ?: cloud.driverRespondedAt,
                local.driverResponseReason ?: cloud.driverResponseReason
            )
    }

    val preferLocalRoute = local.syncStatus == TripSyncStatus.PENDING_SYNC ||
        local.status == TripStatus.PENDING_SYNC ||
        local.gpsPoints.size > cloud.gpsPoints.size

    val chosen = when {
        tripStatusRank(cloud.status) > tripStatusRank(local.status) -> cloud
        tripStatusRank(local.status) > tripStatusRank(cloud.status) -> local
        (cloud.status == TripStatus.PAUSED || cloud.status == TripStatus.IN_PROGRESS) &&
            (local.status == TripStatus.PAUSED || local.status == TripStatus.IN_PROGRESS) &&
            cloud.status != local.status -> {
            val cloudTs = cloud.auditLogs.lastOrNull()?.timestamp ?: cloud.gpsPoints.lastOrNull()?.timestamp ?: ""
            val localTs = local.auditLogs.lastOrNull()?.timestamp ?: local.gpsPoints.lastOrNull()?.timestamp ?: ""
            if (cloudTs > localTs) cloud else local
        }
        preferLocalRoute && tripStatusRank(cloud.status) < tripStatusRank(TripStatus.CLOSED) -> local
        (cloud.completedAt ?: cloud.returnedToPlantAt ?: cloud.startedAt ?: "") >
            (local.completedAt ?: local.returnedToPlantAt ?: local.startedAt ?: "") -> cloud
        else -> local
    }

    val deliveredTotal = mergedStops.sumOf { it.deliveredLiters }
    val actualRev = mergedStops.sumOf { it.totalAmount }.takeIf { deliveredTotal > 0 }
    val baseMerged = chosen.copy(
        driverAcceptanceStatus = resolvedAcceptance,
        driverRespondedAt = resolvedRespondedAt,
        driverResponseReason = resolvedResponseReason,
        customerStops = mergedStops,
        deliveredLiters = max(chosen.deliveredLiters, deliveredTotal),
        remainingLiters = max(0, chosen.initialLoadLiters - max(chosen.deliveredLiters, deliveredTotal)),
        actualRevenue = chosen.actualRevenue ?: actualRev,
        modificationRequests = mergedModRequests,
        auditLogs = mergedAuditLogs,
        gpsPoints = mergedPts,
        detectedStops = (chosen.detectedStops + local.detectedStops + cloud.detectedStops).distinctBy { it.id },
        gpsGaps = (chosen.gpsGaps + local.gpsGaps + cloud.gpsGaps).distinctBy { it.id }
    )

    return if (mergedPts.isNotEmpty()) {
        val analysis = GpsTrackingEngine.analyzeGpsTrack(
            tripId = baseMerged.id,
            rawPoints = mergedPts,
            approvedStops = baseMerged.customerStops,
            settings = settings
        )
        baseMerged.copy(
            gpsPoints = analysis.points,
            detectedStops = analysis.detectedStops,
            gpsGaps = analysis.gpsGaps,
            actualDistanceKm = analysis.totalDistanceKm,
            movingMinutes = analysis.movingMinutes,
            stoppedMinutes = analysis.stoppedMinutes
        )
    } else {
        baseMerged
    }
}

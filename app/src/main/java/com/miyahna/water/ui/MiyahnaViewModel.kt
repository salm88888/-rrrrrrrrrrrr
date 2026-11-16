package com.miyahna.water.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miyahna.water.MiyahnaApplication
import com.miyahna.water.data.ActionResult
import com.miyahna.water.data.MiyahnaDomainState
import com.miyahna.water.domain.AlWadiDistricts
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.ClusterProposal
import com.miyahna.water.domain.ClusteringEngine
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DeliveryLocationLog
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.ExpenseCategory
import com.miyahna.water.domain.FleetFilterCriteria
import com.miyahna.water.domain.FleetFilterEngine
import com.miyahna.water.domain.FleetSortOption
import com.miyahna.water.domain.FleetStatusSummary
import com.miyahna.water.domain.OrderSource
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.Truck
import com.miyahna.water.domain.TruckStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ManagerTab(val labelAr: String) {
    OVERVIEW("نظرة عامة"),
    FLEET_LIVE("لوحة الأسطول المباشرة"),
    ORDERS("الطلبات"),
    CLUSTERING("التجميع الذكي"),
    TRIPS("الرحلات والتتبع"),
    MAP("خريطة مأرب"),
    CUSTOMERS("العملاء"),
    INVOICES("الفواتير والنقدية"),
    RECONCILIATION("تسوية السائقين"),
    EXPENSES("المصروفات والديزل"),
    PROFITABILITY("الربحية والتحليلات"),
    DRIVERS("السائقين والوايت"),
    SETTINGS("إعدادات النظام")
}

enum class DriverTab(val labelAr: String) {
    TRIP("الرحلة"),
    GPS_FUEL("الوقود والتسليم"),
    FIELD_ORDER("طلب ميداني"),
    SYNC("المزامنة")
}

data class MiyahnaUiState(
    val domain: MiyahnaDomainState = MiyahnaDomainState(),
    val managerTab: ManagerTab = ManagerTab.OVERVIEW,
    val driverTab: DriverTab = DriverTab.TRIP,
    val clusters: List<ClusterProposal> = emptyList(),
    val mergedDistricts: List<String> = AlWadiDistricts.districtNames,
    val customerSearchQuery: String = "",
    val filteredCustomers: List<Customer> = emptyList(),
    val deliveryLocations: List<DeliveryLocationLog> = emptyList(),
    val isCapturingDeliveryLocation: Boolean = false,
    val isBackgroundGpsActive: Boolean = false,
    val isGpsPowerSaveActive: Boolean = false,
    val unsyncedGpsPointsCount: Int = 0,
    val fleetFilterCriteria: FleetFilterCriteria = FleetFilterCriteria(),
    val allFleetTrucks: List<Truck> = emptyList(),
    val filteredFleetTrucks: List<Truck> = emptyList(),
    val fleetSummary: FleetStatusSummary = FleetStatusSummary(),
    val selectedFleetTruckId: String? = null
)

private data class TrackingIndicatorsState(
    val deliveryLocations: List<DeliveryLocationLog> = emptyList(),
    val isCapturingDeliveryLocation: Boolean = false,
    val isBackgroundGpsActive: Boolean = false,
    val isGpsPowerSaveActive: Boolean = false,
    val unsyncedGpsPointsCount: Int = 0
)

private data class FleetControlState(
    val criteria: FleetFilterCriteria = FleetFilterCriteria(),
    val selectedTruckId: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MiyahnaViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as MiyahnaApplication).repository

    private val _managerTab = MutableStateFlow(ManagerTab.OVERVIEW)
    private val _driverTab = MutableStateFlow(DriverTab.TRIP)
    private val _customerSearchQuery = MutableStateFlow("")
    private val _fleetFilterCriteria = MutableStateFlow(FleetFilterCriteria())
    private val _selectedFleetTruckId = MutableStateFlow<String?>(null)
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessage = _toastMessage.asSharedFlow()
    private val _minimizeAppRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val minimizeAppRequest = _minimizeAppRequest.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.deliveryCaptureMessages.collect { msg ->
                emitToast(msg)
            }
        }
    }

    private val roomCustomersFlow = _customerSearchQuery.flatMapLatest { query ->
        repo.observeCustomers(query)
    }

    private val trackingIndicatorsFlow = combine(
        repo.observeDeliveryLocations(),
        repo.isCapturingDeliveryLocation,
        repo.isBackgroundGpsActive,
        repo.isGpsPowerSaveActive,
        repo.observeUnsyncedGpsPointsCount()
    ) { logs, capturing, bgActive, powerSaveActive, unsyncedPts ->
        TrackingIndicatorsState(
            deliveryLocations = logs,
            isCapturingDeliveryLocation = capturing,
            isBackgroundGpsActive = bgActive,
            isGpsPowerSaveActive = powerSaveActive,
            unsyncedGpsPointsCount = unsyncedPts
        )
    }

    private val fleetControlFlow = combine(
        _fleetFilterCriteria,
        _selectedFleetTruckId
    ) { criteria, selectedId ->
        FleetControlState(criteria = criteria, selectedTruckId = selectedId)
    }

    private val baseUiFlow = combine(
        repo.state,
        _managerTab,
        _driverTab,
        _customerSearchQuery,
        roomCustomersFlow
    ) { domain, mTab, dTab, customerQuery, roomCustomers ->
        val clusters = runCatching {
            ClusteringEngine.generateSmartClusters(
                orders = domain.orders,
                settings = domain.settings,
                maxTruckCapacity = domain.truck.maxCapacityLiters
            )
        }.getOrDefault(emptyList())
        val districts = runCatching {
            AlWadiDistricts.mergeDistrictLists(
                customDistricts = domain.settings.customDistricts,
                customerDistricts = domain.customers.map { it.district }
            )
        }.getOrDefault(AlWadiDistricts.districtNames)
        MiyahnaUiState(
            domain = domain,
            managerTab = mTab,
            driverTab = dTab,
            clusters = clusters,
            mergedDistricts = districts,
            customerSearchQuery = customerQuery,
            filteredCustomers = roomCustomers
        )
    }

    val uiState: StateFlow<MiyahnaUiState> = combine(
        baseUiFlow,
        trackingIndicatorsFlow,
        fleetControlFlow
    ) { base, tracking, fleetControl ->
        val allTrucks = runCatching {
            FleetFilterEngine.resolveAllTrucks(
                primaryTruck = base.domain.truck,
                fleetTrucks = base.domain.fleetTrucks,
                trips = base.domain.trips
            )
        }.getOrDefault(listOf(base.domain.truck))
        val filteredTrucks = runCatching {
            FleetFilterEngine.filterAndSort(allTrucks, fleetControl.criteria)
        }.getOrDefault(allTrucks)
        val summary = runCatching {
            FleetFilterEngine.computeSummary(allTrucks)
        }.getOrDefault(FleetStatusSummary())
        base.copy(
            deliveryLocations = tracking.deliveryLocations,
            isCapturingDeliveryLocation = tracking.isCapturingDeliveryLocation,
            isBackgroundGpsActive = tracking.isBackgroundGpsActive,
            isGpsPowerSaveActive = tracking.isGpsPowerSaveActive,
            unsyncedGpsPointsCount = tracking.unsyncedGpsPointsCount,
            fleetFilterCriteria = fleetControl.criteria,
            allFleetTrucks = allTrucks,
            filteredFleetTrucks = filteredTrucks,
            fleetSummary = summary,
            selectedFleetTruckId = fleetControl.selectedTruckId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MiyahnaUiState()
    )

    fun setManagerTab(tab: ManagerTab) {
        _managerTab.value = tab
    }

    fun setDriverTab(tab: DriverTab) {
        _driverTab.value = tab
    }

    fun openTabFromNotification(targetRole: String?, actionTab: String?) {
        when (targetRole) {
            AppRole.DRIVER.wire -> {
                _driverTab.value = when (actionTab) {
                    "sync" -> DriverTab.SYNC
                    "field_order" -> DriverTab.FIELD_ORDER
                    else -> DriverTab.TRIP
                }
            }
            AppRole.MANAGER.wire -> {
                _managerTab.value = when (actionTab) {
                    "trips" -> ManagerTab.TRIPS
                    "orders" -> ManagerTab.ORDERS
                    "drivers" -> ManagerTab.DRIVERS
                    "customers" -> ManagerTab.CUSTOMERS
                    "fleet" -> ManagerTab.FLEET_LIVE
                    else -> ManagerTab.OVERVIEW
                }
            }
        }
    }

    fun setCustomerSearchQuery(query: String) {
        _customerSearchQuery.value = query
    }

    fun setFleetSearchQuery(query: String) {
        _fleetFilterCriteria.update { it.copy(query = query) }
    }

    fun setFleetStatusFilter(status: TruckStatus?) {
        _fleetFilterCriteria.update { it.copy(statusFilter = status) }
    }

    fun toggleFleetMovingOnly() {
        _fleetFilterCriteria.update { it.copy(movingOnly = !it.movingOnly) }
    }

    fun toggleFleetActiveTripOnly() {
        _fleetFilterCriteria.update { it.copy(activeTripOnly = !it.activeTripOnly) }
    }

    fun setFleetSortOption(option: FleetSortOption) {
        _fleetFilterCriteria.update { it.copy(sortOption = option) }
    }

    fun clearFleetFilters() {
        _fleetFilterCriteria.value = FleetFilterCriteria()
    }

    fun selectFleetTruck(truckId: String?) {
        _selectedFleetTruckId.value = truckId
    }

    private fun emitToast(msg: String) {
        _toastMessage.tryEmit(msg)
    }

    fun verifyAndEnterManager(pin: String): Boolean {
        val ok = repo.verifyManagerPin(pin)
        if (ok) {
            viewModelScope.launch { repo.selectRole(AppRole.MANAGER) }
        }
        return ok
    }

    fun enterDriverPortal() {
        viewModelScope.launch { repo.selectRole(AppRole.DRIVER) }
    }

    fun switchRolePortal() {
        viewModelScope.launch { repo.selectRole(null) }
    }

    fun loginDriver(identifier: String, password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            val res = repo.loginDriver(identifier, password)
            if (!res.success) {
                onError(res.error ?: "فشل تسجيل الدخول")
            } else {
                emitToast("مرحباً ${res.data?.name ?: ""}")
            }
        }
    }

    fun registerDriver(
        name: String,
        username: String,
        phone: String,
        password: String,
        licenseNumber: String?,
        onResult: (ActionResult<*>) -> Unit
    ) {
        viewModelScope.launch {
            val res = repo.registerDriverAccount(name, username, phone, password, licenseNumber)
            onResult(res)
        }
    }

    fun logoutDriver() {
        viewModelScope.launch { repo.logoutDriver() }
    }

    fun approveDriver(driverId: String) {
        viewModelScope.launch {
            repo.approveDriverAccount(driverId)
            emitToast("تم اعتماد حساب السائق")
        }
    }

    fun rejectDriver(driverId: String, reason: String) {
        viewModelScope.launch {
            repo.rejectDriverAccount(driverId, reason)
            emitToast("تم رفض حساب السائق")
        }
    }

    fun toggleSuspendDriver(driverId: String) {
        viewModelScope.launch { repo.toggleSuspendDriverAccount(driverId) }
    }

    fun signInCloudWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            val res = repo.signInCloudWithGoogle(activityContext)
            if (res.success) {
                emitToast("تم ربط السحابة بحساب ${res.data?.email}")
            } else {
                emitToast(res.error ?: "تعذر ربط حساب Google")
            }
        }
    }

    fun signOutCloud() {
        repo.signOutCloud()
        emitToast("تم فصل المزامنة السحابية")
    }

    fun syncNow() {
        viewModelScope.launch {
            val res = repo.syncWithCloud()
            if (res.success) {
                emitToast("تمت المزامنة بنجاح")
            } else {
                val flushed = repo.flushPendingOfflineQueue()
                emitToast(if (flushed) "تمت مزامنة الطابور المحلي" else (res.error ?: "تعذرت المزامنة"))
            }
        }
    }

    fun addCustomer(
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ) {
        viewModelScope.launch {
            repo.addCustomer(name, phone, altPhone, district, addressDetails, lat, lng, tankCapacityLiters, notes)
            emitToast("تم حفظ بيانات العميل")
        }
    }

    fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        altPhone: String?,
        district: String,
        addressDetails: String,
        lat: Double,
        lng: Double,
        tankCapacityLiters: Int,
        notes: String?
    ) {
        viewModelScope.launch {
            val res = repo.updateCustomer(
                customerId = customerId,
                name = name,
                phone = phone,
                altPhone = altPhone,
                district = district,
                addressDetails = addressDetails,
                lat = lat,
                lng = lng,
                tankCapacityLiters = tankCapacityLiters,
                notes = notes
            )
            emitToast(if (res.success) "تم تحديث بيانات العميل" else (res.error ?: "تعذر تحديث العميل"))
        }
    }

    fun deleteCustomer(customerId: String) {
        viewModelScope.launch {
            val res = repo.deleteCustomer(customerId)
            emitToast(if (res.success) "تم حذف العميل" else (res.error ?: "تعذر حذف العميل"))
        }
    }

    fun addOrder(
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
    ) {
        viewModelScope.launch {
            val res = repo.addOrder(
                existingCustomerId, customerName, phone, district, addressDetails,
                lat, lng, tankCapacity, requestedLiters, pricePerLiter, source, isUrgent, notes
            )
            if (res.warning) {
                emitToast("تم تسجيل الطلب (تنبيه: يوجد طلب حديث لنفس العميل خلال 6 ساعات)")
            } else {
                emitToast("تم تسجيل الطلب بنجاح")
            }
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch { repo.cancelOrder(orderId) }
    }

    fun createTripFromCluster(proposal: ClusterProposal, approveImmediately: Boolean = true) {
        viewModelScope.launch {
            val trip = repo.createTripFromProposal(proposal, approveImmediately)
            emitToast("تم إنشاء الرحلة ${trip.tripNumber}")
            _managerTab.value = ManagerTab.TRIPS
        }
    }

    fun approveTrip(tripId: String) {
        viewModelScope.launch {
            val res = repo.approveTrip(tripId)
            emitToast(if (res.success) "تم اعتماد الرحلة للخروج" else (res.error ?: "تعذر الاعتماد"))
        }
    }

    fun rejectTrip(tripId: String, reason: String) {
        viewModelScope.launch {
            repo.rejectTrip(tripId, reason)
            emitToast("تم رفض الرحلة وإعادة الطلبات للتجميع")
        }
    }

    fun stopTripTracking(tripId: String, reason: String?) {
        viewModelScope.launch {
            val res = repo.stopTripTrackingByManager(tripId, reason)
            emitToast(if (res.success) "تم إيقاف التتبع وإنهاء الرحلة" else (res.error ?: "حدث خطأ"))
        }
    }

    fun closeTrip(tripId: String, notes: String?) {
        viewModelScope.launch {
            repo.closeTripAfterReview(tripId, notes)
            emitToast("تم اعتماد المراجعة وإغلاق الرحلة")
        }
    }

    fun respondToTrip(tripId: String, accepted: Boolean, reason: String = "") {
        if (accepted) {
            // Start the location Foreground Service immediately while the Activity is in the foreground
            repo.ensureDriverBackgroundGpsRunning(acceptedTripId = tripId, forceStart = true)
        }
        viewModelScope.launch {
            val res = repo.respondToManagerTrip(tripId, accepted, reason)
            if (res.success && accepted) {
                repo.ensureDriverBackgroundGpsRunning(acceptedTripId = tripId, forceStart = true)
                emitToast("تم قبول الرحلة — يستمر التطبيق بالعمل في الخلفية")
                _minimizeAppRequest.tryEmit(Unit)
            } else {
                emitToast(if (res.success) "تم إرسال ردك للإدارة" else (res.error ?: "تعذر الإرسال"))
            }
        }
    }

    fun startTrip(tripId: String) {
        repo.ensureDriverBackgroundGpsRunning(acceptedTripId = tripId, forceStart = true)
        viewModelScope.launch {
            val res = repo.startTrip(tripId)
            emitToast(if (res.success) "تم بدء الرحلة بنجاح" else (res.error ?: "تعذر بدء الرحلة"))
        }
    }

    fun pauseTrip(tripId: String, reason: String? = null, actorRole: String = "driver") {
        viewModelScope.launch {
            val res = repo.pauseTrip(tripId = tripId, reason = reason, actorRole = actorRole)
            emitToast(if (res.success) "تم إيقاف الرحلة مؤقتاً" else (res.error ?: "تعذر الإيقاف المؤقت"))
        }
    }

    fun resumeTrip(tripId: String, reason: String? = null, actorRole: String = "driver") {
        if (actorRole == "driver") {
            repo.ensureDriverBackgroundGpsRunning(acceptedTripId = tripId, forceStart = true)
        }
        viewModelScope.launch {
            val res = repo.resumeTrip(tripId = tripId, reason = reason, actorRole = actorRole)
            emitToast(if (res.success) "تم استئناف الرحلة" else (res.error ?: "تعذر استئناف الرحلة"))
        }
    }

    fun markTransitionReviewed(logId: String, note: String? = null) {
        viewModelScope.launch {
            val res = repo.markTransitionReviewed(logId = logId, note = note)
            emitToast(if (res.success) "تم اعتماد مراجعة سجل الحالة" else (res.error ?: "تعذر اعتماد المراجعة"))
        }
    }

    fun finishTripByDriver(tripId: String) {
        viewModelScope.launch {
            val res = repo.finishTripByDriver(tripId)
            if (res.success) {
                val online = uiState.value.domain.isOnline
                emitToast(
                    if (online) "تم إنهاء الرحلة وإرسال التقرير للمدير"
                    else "تم حفظ بيانات الرحلة محلياً — سيتم الإرسال للمدير عند توفر الإنترنت"
                )
            } else {
                emitToast(res.error ?: "تعذر إنهاء الرحلة")
            }
        }
    }

    fun ensureDriverBackgroundGpsRunning() {
        repo.ensureDriverBackgroundGpsRunning()
    }

    fun requestTripModification(tripId: String, stops: List<TripCustomerStop>, reason: String) {
        viewModelScope.launch {
            val res = repo.requestTripModification(tripId, stops, reason)
            emitToast(if (res.success) "تم إرسال طلب التعديل للمدير" else (res.error ?: "تعذر الإرسال"))
        }
    }

    fun reviewModificationRequest(tripId: String, requestId: String, approved: Boolean, notes: String? = null) {
        viewModelScope.launch {
            repo.reviewModificationRequest(tripId, requestId, approved, notes)
            emitToast(if (approved) "تم اعتماد تعديل المسار" else "تم رفض طلب التعديل")
        }
    }

    fun logManagerDelivery(tripId: String, orderId: String, liters: Int, paidAmount: Double) {
        viewModelScope.launch {
            val res = repo.logManagerDeliveryForStop(tripId, orderId, liters, paidAmount)
            emitToast(if (res.success) "تم توثيق التسليم وإصدار الفاتورة ${res.data?.invoiceNumber}" else (res.error ?: "تعذر التوثيق"))
        }
    }

    fun submitReconciliation(
        tripId: String,
        cashHandedOver: Double,
        deductedExpenses: Double,
        notes: String?,
        onDone: (ActionResult<DriverReconciliation>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repo.submitDriverReconciliation(tripId, cashHandedOver, deductedExpenses, notes)
            emitToast(if (res.success) "تم حفظ التسوية المالية" else (res.error ?: "تعذر الحفظ"))
            onDone(res)
        }
    }

    fun addExpense(
        category: ExpenseCategory,
        description: String,
        amount: Double,
        liters: Double?,
        tripId: String?,
        receiptNumber: String?
    ) {
        viewModelScope.launch {
            repo.addExpense(category, description, amount, liters, tripId, receiptNumber)
            emitToast("تم تسجيل المصروف")
        }
    }

    fun sendDirective(title: String, message: String, urgent: Boolean, tripId: String? = null) {
        viewModelScope.launch {
            repo.sendManagerDirective(title, message, urgent, tripId)
            emitToast("تم إرسال التوجيه للسائق")
        }
    }

    fun updateDirectiveStatus(id: String, status: DirectiveStatus, reply: String? = null) {
        viewModelScope.launch { repo.updateDirectiveStatus(id, status, reply) }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch { repo.markNotificationRead(id) }
    }

    fun markAllNotificationsRead(role: String) {
        viewModelScope.launch { repo.markAllNotificationsRead(role) }
    }

    fun updateSettings(settings: SystemSettings) {
        viewModelScope.launch {
            repo.updateSettings(settings)
            emitToast("تم حفظ إعدادات النظام")
        }
    }

    fun updateTruck(truck: Truck) {
        viewModelScope.launch {
            repo.updateTruck(truck)
            emitToast("تم تحديث بيانات الوايت")
        }
    }

    fun addFleetTruck(
        code: String,
        plateNumber: String,
        model: String,
        maxCapacityLiters: Int,
        driverId: String? = null
    ) {
        viewModelScope.launch {
            val res = repo.addFleetTruck(code, plateNumber, model, maxCapacityLiters, driverId)
            if (res.success) {
                emitToast("تمت إضافة الوايت ${res.data?.code} للأسطول")
            } else {
                emitToast(res.error ?: "تعذر إضافة الوايت")
            }
        }
    }

    fun updateFleetTruckStatus(truckId: String, status: TruckStatus) {
        viewModelScope.launch {
            repo.updateFleetTruckStatus(truckId, status)
            emitToast("تم تحديث حالة الوايت إلى ${status.labelAr}")
        }
    }

    fun changeManagerPin(current: String, next: String, onResult: (ActionResult<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = repo.changeManagerPin(current, next)
            onResult(res)
        }
    }

    fun clearOperationalData() {
        viewModelScope.launch {
            repo.clearOperationalData()
            emitToast("تم تصفير البيانات التشغيلية بنجاح")
        }
    }

    fun addCustomDistrict(name: String) {
        viewModelScope.launch { repo.addCustomDistrict(name) }
    }

    fun captureDeliveryLocation(
        tripId: String? = null,
        tripNumber: String? = null,
        orderId: String? = null,
        customerId: String? = null,
        customerName: String? = null,
        deliveredLiters: Int? = null,
        notes: String? = null
    ) {
        repo.triggerDeliveryLocationCapture(
            tripId = tripId,
            tripNumber = tripNumber,
            orderId = orderId,
            customerId = customerId,
            customerName = customerName,
            deliveredLiters = deliveredLiters,
            notes = notes
        )
    }

    fun deleteDeliveryLocationLog(id: String) {
        viewModelScope.launch {
            repo.deleteDeliveryLocationLog(id)
            emitToast("تم حذف سجل الموقع")
        }
    }
}

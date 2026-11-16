package com.miyahna.water.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrderStatus(val wire: String, val labelAr: String) {
    @SerialName("new") NEW("new", "طلب جديد"),
    @SerialName("under_review") UNDER_REVIEW("under_review", "قيد المراجعة"),
    @SerialName("confirmed") CONFIRMED("confirmed", "مؤكد"),
    @SerialName("waiting_clustering") WAITING_CLUSTERING("waiting_clustering", "بانتظار التجميع"),
    @SerialName("clustered") CLUSTERED("clustered", "مجمّع في رحلة"),
    @SerialName("approved") APPROVED("approved", "معتمد"),
    @SerialName("in_delivery") IN_DELIVERY("in_delivery", "قيد التوصيل"),
    @SerialName("delivered") DELIVERED("delivered", "تم التسليم"),
    @SerialName("cancelled") CANCELLED("cancelled", "ملغي"),
    @SerialName("postponed") POSTPONED("postponed", "مؤجل");

    companion object {
        fun fromWire(value: String?): OrderStatus =
            entries.firstOrNull { it.wire == value } ?: WAITING_CLUSTERING
    }
}

@Serializable
enum class OrderSource(val wire: String, val labelAr: String) {
    @SerialName("phone") PHONE("phone", "اتصال هاتفي"),
    @SerialName("whatsapp") WHATSAPP("whatsapp", "واتساب"),
    @SerialName("manager_manual") MANAGER_MANUAL("manager_manual", "إدخال الإدارة"),
    @SerialName("driver_manual") DRIVER_MANUAL("driver_manual", "إدخال السائق ميدانياً"),
    @SerialName("customer_app") CUSTOMER_APP("customer_app", "تطبيق العميل");

    companion object {
        fun fromWire(value: String?): OrderSource =
            entries.firstOrNull { it.wire == value } ?: MANAGER_MANUAL
    }
}

/**
 * Exact 10 Trip Lifecycle States matching the domain workflow:
 * draft -> pending_approval -> rejected -> approved -> in_progress ->
 * returned_to_plant -> pending_sync -> synced -> completed_pending_review -> closed
 */
@Serializable
enum class TripStatus(val wire: String, val labelAr: String) {
    @SerialName("draft") DRAFT("draft", "مسودة"),
    @SerialName("pending_approval") PENDING_APPROVAL("pending_approval", "بانتظار اعتماد المدير"),
    @SerialName("rejected") REJECTED("rejected", "مرفوضة"),
    @SerialName("approved") APPROVED("approved", "معتمدة للخروج"),
    @SerialName("in_progress") IN_PROGRESS("in_progress", "جارية بالتتبع"),
    @SerialName("paused") PAUSED("paused", "متوقفة مؤقتاً"),
    @SerialName("returned_to_plant") RETURNED_TO_PLANT("returned_to_plant", "عاد للمصنع"),
    @SerialName("pending_sync") PENDING_SYNC("pending_sync", "بانتظار المزامنة"),
    @SerialName("synced") SYNCED("synced", "تمت المزامنة"),
    @SerialName("completed_pending_review") COMPLETED_PENDING_REVIEW("completed_pending_review", "مكتملة بانتظار المراجعة"),
    @SerialName("closed") CLOSED("closed", "مغلقة ومُراجعة");

    companion object {
        fun fromWire(value: String?): TripStatus =
            entries.firstOrNull { it.wire == value } ?: DRAFT
    }
}

@Serializable
enum class TripSyncStatus(val wire: String, val labelAr: String) {
    @SerialName("local_only") LOCAL_ONLY("local_only", "محلي فقط"),
    @SerialName("pending_sync") PENDING_SYNC("pending_sync", "بانتظار المزامنة"),
    @SerialName("syncing") SYNCING("syncing", "جاري المزامنة"),
    @SerialName("synced") SYNCED("synced", "تمت المزامنة"),
    @SerialName("sync_error") SYNC_ERROR("sync_error", "خطأ مزامنة");

    companion object {
        fun fromWire(value: String?): TripSyncStatus =
            entries.firstOrNull { it.wire == value } ?: LOCAL_ONLY
    }
}

@Serializable
enum class StopStatus(val wire: String, val labelAr: String) {
    @SerialName("pending") PENDING("pending", "قيد الانتظار"),
    @SerialName("arrived") ARRIVED("arrived", "وصل للموقع"),
    @SerialName("unloading") UNLOADING("unloading", "جاري التفريغ"),
    @SerialName("delivered") DELIVERED("delivered", "تم التسليم"),
    @SerialName("skipped") SKIPPED("skipped", "تم التخطي");

    companion object {
        fun fromWire(value: String?): StopStatus =
            entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

@Serializable
enum class GPSEventType(val wire: String, val labelAr: String) {
    @SerialName("START") START("START", "انطلاق الرحلة"),
    @SerialName("MOVING") MOVING("MOVING", "قيد الحركة"),
    @SerialName("STOPPED") STOPPED("STOPPED", "توقف"),
    @SerialName("ARRIVAL") ARRIVAL("ARRIVAL", "وصول"),
    @SerialName("DEPARTURE") DEPARTURE("DEPARTURE", "مغادرة"),
    @SerialName("RETURN_PLANT") RETURN_PLANT("RETURN_PLANT", "العودة للمصنع"),
    @SerialName("END") END("END", "نهاية الرحلة");

    companion object {
        fun fromWire(value: String?): GPSEventType =
            entries.firstOrNull { it.wire == value } ?: MOVING
    }
}

@Serializable
enum class TruckStatus(val wire: String, val labelAr: String) {
    @SerialName("idle") IDLE("idle", "في المصنع - متاح"),
    @SerialName("loading") LOADING("loading", "جاري التعبئة"),
    @SerialName("on_trip") ON_TRIP("on_trip", "في رحلة توزيع"),
    @SerialName("maintenance") MAINTENANCE("maintenance", "صيانة");

    companion object {
        fun fromWire(value: String?): TruckStatus =
            entries.firstOrNull { it.wire == value } ?: IDLE
    }
}

@Serializable
enum class DriverAccountStatus(val wire: String, val labelAr: String) {
    @SerialName("pending") PENDING("pending", "بانتظار اعتماد المدير"),
    @SerialName("approved") APPROVED("approved", "معتمد"),
    @SerialName("rejected") REJECTED("rejected", "مرفوض"),
    @SerialName("suspended") SUSPENDED("suspended", "موقوف");

    companion object {
        fun fromWire(value: String?): DriverAccountStatus =
            entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

@Serializable
enum class ExpenseCategory(val wire: String, val labelAr: String) {
    @SerialName("diesel") DIESEL("diesel", "وقود ديزل"),
    @SerialName("oil_change") OIL_CHANGE("oil_change", "تغيير زيوت"),
    @SerialName("maintenance") MAINTENANCE("maintenance", "صيانة وإصلاح"),
    @SerialName("tires") TIRES("tires", "إطارات"),
    @SerialName("driver_wage") DRIVER_WAGE("driver_wage", "أجر سائق"),
    @SerialName("water_treatment") WATER_TREATMENT("water_treatment", "معالجة وفلاتر"),
    @SerialName("other") OTHER("other", "مصروفات أخرى");

    companion object {
        fun fromWire(value: String?): ExpenseCategory =
            entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

@Serializable
enum class DepartureRecommendation(val wire: String, val labelAr: String) {
    @SerialName("GO_NOW") GO_NOW("GO_NOW", "انطلاق فوري موصى به"),
    @SerialName("WAIT_MORE") WAIT_MORE("WAIT_MORE", "انتظار طلبات إضافية"),
    @SerialName("FEASIBLE_PARTIAL") FEASIBLE_PARTIAL("FEASIBLE_PARTIAL", "رحلة جزئية مجدية"),
    @SerialName("URGENT_OVERRIDE") URGENT_OVERRIDE("URGENT_OVERRIDE", "استثناء طلب عاجل");

    companion object {
        fun fromWire(value: String?): DepartureRecommendation =
            entries.firstOrNull { it.wire == value } ?: GO_NOW
    }
}

@Serializable
enum class NotificationPriority(val wire: String) {
    @SerialName("urgent") URGENT("urgent"),
    @SerialName("action_required") ACTION_REQUIRED("action_required"),
    @SerialName("info") INFO("info");

    companion object {
        fun fromWire(value: String?): NotificationPriority =
            entries.firstOrNull { it.wire == value } ?: INFO
    }
}

@Serializable
enum class DirectiveStatus(val wire: String, val labelAr: String) {
    @SerialName("sent") SENT("sent", "مرسل"),
    @SerialName("acknowledged") ACKNOWLEDGED("acknowledged", "تم الاستلام"),
    @SerialName("completed") COMPLETED("completed", "تم التنفيذ");

    companion object {
        fun fromWire(value: String?): DirectiveStatus =
            entries.firstOrNull { it.wire == value } ?: SENT
    }
}

@Serializable
data class Customer(
    val id: String,
    val customerId: String,
    val name: String,
    val phone: String,
    val altPhone: String? = null,
    val district: String,
    val addressDetails: String = "",
    val lat: Double,
    val lng: Double,
    val tankCapacityLiters: Int = 2000,
    val tankRemainingLiters: Int? = null,
    val notes: String? = null,
    val createdAt: String,
    val isActive: Boolean = true,
    val totalOrdersCount: Int = 0,
    val totalLitersDelivered: Int = 0,
    val isOfflineCreated: Boolean = false
)

@Serializable
data class Order(
    val id: String,
    val orderNumber: String,
    val customerId: String,
    val customerName: String,
    val phone: String,
    val district: String,
    val addressDetails: String = "",
    val lat: Double,
    val lng: Double,
    val tankCapacity: Int = 2000,
    val tankRemainingLiters: Int? = null,
    val requestedLiters: Int,
    val pricePerLiter: Double,
    val totalAmount: Double,
    val source: OrderSource = OrderSource.MANAGER_MANUAL,
    val createdAt: String,
    val scheduledDate: String? = null,
    val notes: String? = null,
    val status: OrderStatus = OrderStatus.WAITING_CLUSTERING,
    val tripId: String? = null,
    val isUrgent: Boolean = false,
    val duplicateWarning: Boolean = false,
    val duplicateMatchedOrderId: String? = null
)

@Serializable
data class TripCustomerStop(
    val stopSequence: Int,
    val orderId: String,
    val customerId: String,
    val customerName: String,
    val phone: String,
    val district: String,
    val addressDetails: String = "",
    val lat: Double,
    val lng: Double,
    val tankCapacity: Int = 2000,
    val requestedLiters: Int,
    val deliveredLiters: Int = 0,
    val pricePerLiter: Double,
    val totalAmount: Double,
    val paidAmount: Double = 0.0,
    val remainingAmount: Double = totalAmount,
    val status: StopStatus = StopStatus.PENDING,
    val arrivalTime: String? = null,
    val departureTime: String? = null,
    val arrivalLat: Double? = null,
    val arrivalLng: Double? = null,
    val departureLat: Double? = null,
    val departureLng: Double? = null,
    val notes: String? = null,
    val invoiceId: String? = null
)

@Serializable
data class TripGPSPoint(
    val id: String,
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val speedKmH: Double = 0.0,
    val timestamp: String,
    val seq: Int = 0,
    val distanceFromPrevKm: Double = 0.0,
    val isWeakSignal: Boolean = false,
    val source: String = "gps_android_fg"
)

@Serializable
data class DetectedStop(
    val id: String,
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val startTime: String,
    val endTime: String? = null,
    val durationMinutes: Double,
    val isActive: Boolean = false,
    val isProlongedStop: Boolean = true,
    val nearCustomerId: String? = null,
    val nearCustomerName: String? = null,
    val distanceToCustomerMeters: Int? = null,
    val isUnplannedStop: Boolean = false,
    val notes: String? = null
)

@Serializable
data class GPSGap(
    val id: String,
    val tripId: String,
    val startTime: String,
    val endTime: String,
    val durationSeconds: Int,
    val reason: String = "gps_paused",
    val fromLat: Double? = null,
    val fromLng: Double? = null,
    val toLat: Double? = null,
    val toLng: Double? = null
)

@Serializable
data class TripAuditEntry(
    val id: String,
    val timestamp: String,
    val actorRole: String,
    val actorName: String,
    val action: String,
    val details: String? = null
)

@Serializable
data class ModificationRequest(
    val id: String,
    val requestedAt: String,
    val requestedBy: String,
    val reason: String,
    val proposedStops: List<TripCustomerStop>,
    val proposedOrderIds: List<String>,
    val proposedTotalLiters: Int,
    val status: String = "pending",
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val reviewNotes: String? = null
)

@Serializable
data class GeoLocationPoint(
    val lat: Double,
    val lng: Double
)

@Serializable
data class ReturnLocationInfo(
    val lat: Double,
    val lng: Double,
    val distanceFromPlantMeters: Int,
    val detectedAutomatically: Boolean
)

@Serializable
data class Trip(
    val id: String,
    val tripNumber: String,
    val date: String,
    val truckId: String,
    val driverId: String,
    val driverName: String,
    val truckPlate: String,
    val initialLoadLiters: Int,
    val deliveredLiters: Int = 0,
    val remainingLiters: Int = initialLoadLiters,
    val status: TripStatus = TripStatus.DRAFT,
    val syncStatus: TripSyncStatus = TripSyncStatus.LOCAL_ONLY,
    val createdByRole: String = "manager",
    val driverAcceptanceStatus: String? = null,
    val driverRespondedAt: String? = null,
    val driverResponseReason: String? = null,
    val customerStops: List<TripCustomerStop> = emptyList(),
    val orderIds: List<String> = emptyList(),
    val proposedAt: String,
    val submittedAt: String? = null,
    val approvedAt: String? = null,
    val approvedBy: String? = null,
    val rejectedAt: String? = null,
    val rejectedBy: String? = null,
    val rejectionReason: String? = null,
    val returnedForEditReason: String? = null,
    val startedAt: String? = null,
    val startLocation: GeoLocationPoint? = null,
    val returnedToPlantAt: String? = null,
    val returnLocation: ReturnLocationInfo? = null,
    val completedAt: String? = null,
    val closedAt: String? = null,
    val closedBy: String? = null,
    val reconciledAt: String? = null,
    val lastSyncedAt: String? = null,
    val gpsPoints: List<TripGPSPoint> = emptyList(),
    val detectedStops: List<DetectedStop> = emptyList(),
    val gpsGaps: List<GPSGap> = emptyList(),
    val movingMinutes: Double = 0.0,
    val stoppedMinutes: Double = 0.0,
    val auditLogs: List<TripAuditEntry> = emptyList(),
    val modificationRequests: List<ModificationRequest> = emptyList(),
    val estimatedDistanceKm: Double = 0.0,
    val actualDistanceKm: Double? = null,
    val estimatedFuelLiters: Double = 0.0,
    val actualFuelLiters: Double? = null,
    val fuelCost: Double = 0.0,
    val productionCost: Double = 0.0,
    val driverWageShare: Double = 0.0,
    val maintenanceShare: Double = 0.0,
    val otherExpensesShare: Double = 0.0,
    val totalCost: Double = 0.0,
    val actualCost: Double? = null,
    val expectedRevenue: Double = 0.0,
    val actualRevenue: Double? = null,
    val expectedProfit: Double = 0.0,
    val actualProfit: Double? = null,
    val profitMarginPercent: Double = 0.0,
    val capacityUtilizationPercent: Double = 0.0,
    val departureRecommendation: DepartureRecommendation = DepartureRecommendation.GO_NOW,
    val recommendationReason: String = "",
    val notes: String? = null
)

@Serializable
data class Invoice(
    val id: String,
    val invoiceNumber: String,
    val orderId: String,
    val orderNumber: String,
    val tripId: String,
    val tripNumber: String,
    val customerId: String,
    val customerName: String,
    val phone: String,
    val district: String,
    val requestedLiters: Int,
    val deliveredLiters: Int,
    val pricePerLiter: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val remainingAmount: Double,
    val paymentMethod: String = "cash",
    val createdAt: String,
    val driverName: String,
    val isReconciled: Boolean = false,
    val notes: String? = null
)

@Serializable
data class DriverReconciliation(
    val id: String,
    val reconciliationNumber: String,
    val tripId: String,
    val tripNumber: String,
    val driverId: String,
    val driverName: String,
    val date: String,
    val totalInvoicedAmount: Double,
    val totalCashCollected: Double,
    val actualCashHandedOver: Double,
    val authorizedExpensesDeducted: Double,
    val differenceAmount: Double,
    val status: String = "pending",
    val notes: String? = null,
    val approvedBy: String? = null,
    val createdAt: String
)

@Serializable
data class Expense(
    val id: String,
    val expenseNumber: String,
    val date: String,
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    val description: String,
    val amount: Double,
    val liters: Double? = null,
    val tripId: String? = null,
    val tripNumber: String? = null,
    val receiptNumber: String? = null,
    val createdBy: String = "المدير العام"
)

@Serializable
data class GPSEvent(
    val id: String,
    val tripId: String,
    val tripNumber: String,
    val timestamp: String,
    val eventType: GPSEventType,
    val lat: Double,
    val lng: Double,
    val speed: Double? = null,
    val accuracy: Double? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val source: String = "gps_auto",
    val sequenceNumber: Int = 1,
    val isProcessed: Boolean = true
)

@Serializable
data class Truck(
    val id: String = "truck-1",
    val code: String = "TRUCK-01",
    val plateNumber: String = "غير محدد",
    val model: String = "وايت مياه صالح للشرب 6000L",
    val maxCapacityLiters: Int = 6000,
    val currentWaterLiters: Int = 0,
    val driverId: String = "",
    val driverName: String = "غير معين",
    val driverPhone: String = "",
    val status: TruckStatus = TruckStatus.IDLE,
    val lastLat: Double = 15.4920,
    val lastLng: Double = 45.3620,
    val lastSpeed: Double = 0.0,
    val lastUpdateTimestamp: String = "",
    val lastEvent: GPSEventType = GPSEventType.RETURN_PLANT,
    val currentTripId: String? = null,
    val odometerKm: Double = 0.0
)

@Serializable
data class PlantLocation(
    val name: String = "مصنع مياهنا المركزي لتحلية وتعبئة المياه",
    val city: String = "مديرية الوادي - قرب حصون آل جلال، مأرب",
    val lat: Double = 15.4920,
    val lng: Double = 45.3620
)

@Serializable
data class SystemSettings(
    val waterProductionCostPerLiter: Double = 3.5,
    val defaultSellingPricePerLiter: Double = 12.0,
    val dieselPricePerLiter: Double = 650.0,
    val truckDieselConsumptionPerKm: Double = 0.38,
    val maintenanceAllowancePerTrip: Double = 2500.0,
    val driverWagePerTrip: Double = 5000.0,
    val stopDetectionMinutes: Int = 3,
    val minMovementSpeedKmH: Double = 8.0,
    val stopRadiusMeters: Int = 80,
    val plantGeofenceMeters: Int = 120,
    val gpsSampleIntervalSeconds: Int = 10,
    val gpsPowerSaveEnabled: Boolean = true,
    val gpsPowerSaveIntervalSeconds: Int = 60,
    val gpsStationaryDwellSeconds: Int = 60,
    val gpsWeakAccuracyMeters: Int = 60,
    val gpsGapSeconds: Int = 120,
    val plantLocation: PlantLocation = PlantLocation(),
    val customDistricts: List<String> = emptyList(),
    val demoDataActive: Boolean = false,
    val demoDataClearedAt: String? = null,
    val demoResetVersion: Int = 1,
    val managerPin: String = "20262027"
)

@Serializable
data class DriverAccount(
    val id: String,
    val name: String,
    val username: String,
    val phone: String,
    val password: String,
    val licenseNumber: String? = null,
    val status: DriverAccountStatus = DriverAccountStatus.PENDING,
    val createdAt: String,
    val createdBy: String = "driver_self",
    val approvedAt: String? = null,
    val approvedBy: String? = null,
    val rejectedAt: String? = null,
    val rejectionReason: String? = null,
    val lastLoginAt: String? = null,
    val isDemo: Boolean = false
)

@Serializable
data class DriverSession(
    val id: String,
    val name: String,
    val username: String,
    val phone: String,
    val licenseNumber: String? = null,
    val loggedInAt: String,
    val isDemo: Boolean = false
)

@Serializable
data class ClusterProposal(
    val id: String,
    val orders: List<Order>,
    val totalLiters: Int,
    val capacityUtilization: Double,
    val customerStops: List<TripCustomerStop>,
    val estimatedDistanceKm: Double,
    val estimatedFuelLiters: Double,
    val fuelCost: Double,
    val productionCost: Double,
    val driverWageShare: Double,
    val maintenanceShare: Double,
    val totalCost: Double,
    val expectedRevenue: Double,
    val expectedProfit: Double,
    val profitMarginPercent: Double,
    val recommendation: DepartureRecommendation,
    val reason: String,
    val districtsCovered: List<String>
)

@Serializable
data class AppNotification(
    val id: String,
    val createdAt: String,
    val targetRole: String,
    val actorRole: String = "system",
    val actorName: String = "النظام",
    val category: String = "info",
    val priority: NotificationPriority = NotificationPriority.INFO,
    val title: String,
    val message: String,
    val tripId: String? = null,
    val tripNumber: String? = null,
    val orderId: String? = null,
    val directiveId: String? = null,
    val isRead: Boolean = false,
    val actionTab: String? = null
)

@Serializable
data class ManagerDirective(
    val id: String,
    val createdAt: String,
    val createdBy: String = "المدير العام",
    val title: String,
    val message: String,
    val priority: String = "normal",
    val tripId: String? = null,
    val tripNumber: String? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val status: DirectiveStatus = DirectiveStatus.SENT,
    val acknowledgedAt: String? = null,
    val completedAt: String? = null,
    val driverReply: String? = null
)

@Serializable
data class PendingOfflineOperation(
    val id: String,
    val type: String, // "customer", "order", "gps_point", "return_event", "district"
    val payloadJson: String,
    val createdAt: String
)

@Serializable
data class DeliveryLocationLog(
    val id: String,
    val tripId: String? = null,
    val tripNumber: String? = null,
    val orderId: String? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val truckId: String = "truck-1",
    val truckPlate: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val speedKmH: Double = 0.0,
    val deliveredLiters: Int? = null,
    val captureSource: String = "delivery_gps_service",
    val notes: String? = null,
    val capturedAtIso: String,
    val capturedAtEpochMs: Long,
    val isSynced: Boolean = false
)

@Serializable
data class TripStateTransition(
    val id: String = "",
    val tripId: String = "",
    val tripNumber: String = "",
    val truckId: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val fromState: String = "none",
    val toState: String = "draft",
    val triggerSource: String = "manual",
    val actorRole: String = "system",
    val actorName: String = "النظام",
    val reason: String? = null,
    val details: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val speedKmH: Double? = null,
    val timestamp: String = "",
    val timestampEpochMs: Long = 0L,
    val reviewedByManager: Boolean = false,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
    val isSyncedToCloud: Boolean = false
)

fun tripTransitionStateLabelAr(state: String): String = when (state) {
    "none" -> "بداية جديدة"
    "draft" -> "مسودة"
    "pending_approval" -> "بانتظار الاعتماد"
    "approved" -> "معتمدة للخروج"
    "accepted" -> "مقبولة من السائق"
    "declined" -> "معتذر عنها"
    "in_progress", "in_transit" -> "قيد التنفيذ (في الطريق)"
    "paused" -> "متوقفة مؤقتاً"
    "returned_to_plant" -> "عاد للمصنع"
    "pending_sync" -> "بانتظار المزامنة"
    "synced" -> "تمت المزامنة"
    "completed_pending_review", "completed" -> "مكتملة بانتظار المراجعة"
    "closed" -> "مغلقة ومُراجعة"
    "rejected" -> "مرفوضة"
    else -> state
}

fun tripTransitionTriggerLabelAr(trigger: String): String = when (trigger) {
    "trip_created" -> "إنشاء مسار رحلة"
    "manager_approved" -> "اعتماد إداري"
    "manager_rejected" -> "رفض إداري"
    "driver_accepted" -> "قبول السائق للرحلة"
    "driver_declined" -> "اعتذار السائق"
    "manual_start" -> "بدء تشغيل يدوي"
    "auto_departure_geofence" -> "خروج تلقائي من سياج المصنع"
    "driver_paused" -> "إيقاف مؤقت من السائق"
    "manager_paused" -> "إيقاف مؤقت إداري"
    "auto_stop_paused" -> "رصد توقف ميداني تلقائياً"
    "auto_gps_gap" -> "انقطاع إشارة الموقع"
    "driver_resumed" -> "استئناف الرحلة من السائق"
    "manager_resumed" -> "استئناف إداري للرحلة"
    "auto_movement_resumed" -> "استئناف الحركة تلقائياً"
    "driver_completed" -> "إنهاء الرحلة من السائق"
    "manager_stopped" -> "إنهاء الرحلة إدارياً"
    "auto_plant_geofence" -> "عودة تلقائية لسياج المصنع"
    "offline_queue_synced" -> "مزامنة السجل المحلي للسحابة"
    "manager_closed" -> "اعتماد المراجعة وإغلاق الرحلة"
    "reconciliation_closed" -> "إغلاق بالتسوية المالية"
    else -> trigger
}

enum class AppRole(val wire: String) {
    MANAGER("manager"),
    DRIVER("driver")
}

package com.miyahna.water.data.cloud

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.miyahna.water.R
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.Expense
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.Invoice
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.Truck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

data class CloudSnapshot(
    val settings: SystemSettings?,
    val truck: Truck?,
    val trucks: List<Truck> = emptyList(),
    val driverAccounts: List<DriverAccount>,
    val customers: List<Customer>,
    val orders: List<Order>,
    val trips: List<Trip>,
    val invoices: List<Invoice>,
    val reconciliations: List<DriverReconciliation>,
    val expenses: List<Expense>,
    val gpsEvents: List<GPSEvent>,
    val notifications: List<AppNotification>,
    val directives: List<ManagerDirective>,
    val tripStateLogs: List<TripStateTransition> = emptyList(),
    val hasAnyDocs: Boolean
)

data class CloudUserInfo(
    val uid: String,
    val email: String,
    val displayName: String,
    val isAdmin: Boolean
)

class FirebaseSyncManager(
    private val context: Context,
    providedFirestore: FirebaseFirestore? = null,
    providedAuth: FirebaseAuth? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private data class KnownDocMeta(
        val ownerId: String,
        val serverCreatedAt: Any
    )

    private val knownMeta = ConcurrentHashMap<String, KnownDocMeta>()
    private val bootstrappedAdminEmail: String =
        context.getString(R.string.bootstrapped_admin_email)
    private val databaseId: String =
        context.getString(R.string.firestore_database_id)
    private val webClientId: String =
        context.getString(R.string.default_web_client_id)

    private val app: FirebaseApp by lazy {
        FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
            ?: FirebaseApp.getInstance()
    }

    private val auth: FirebaseAuth by lazy {
        providedAuth ?: run {
            app
            Firebase.auth
        }
    }

    private val db: FirebaseFirestore by lazy {
        providedFirestore ?: run {
            val instance = FirebaseFirestore.getInstance(app, databaseId)
            runCatching {
                instance.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder().build()
                    )
                    .build()
            }
            instance
        }
    }

    fun currentCloudUser(): CloudUserInfo? {
        val user = auth.currentUser ?: return null
        if (!user.isEmailVerified) return null
        val email = user.email.orEmpty()
        return CloudUserInfo(
            uid = user.uid,
            email = email,
            displayName = user.displayName ?: email,
            isAdmin = email.equals(bootstrappedAdminEmail, ignoreCase = true)
        )
    }

    private fun requireVerifiedUser(): FirebaseUser {
        val user = auth.currentUser ?: error("المستخدم غير مسجل الدخول للسحابة")
        if (!user.isEmailVerified) error("حساب Google غير موثق")
        return user
    }

    /**
     * Interactive Google Sign-In using Jetpack Credential Manager (one option per request).
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<CloudUserInfo> {
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val cred = result.credential
            if (cred !is CustomCredential ||
                cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("نوع اعتماد Google غير مدعوم"))
            }
            val googleId = GoogleIdTokenCredential.createFrom(cred.data)
            completeFirebaseGoogleAuth(googleId.idToken)
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google sign-in cancelled or Play Services credential config mismatch", e)
            Result.failure(
                IllegalStateException(
                    "تم إلغاء تسجيل الدخول عبر Google. يرجى اختيار حساب للمتابعة."
                )
            )
        } catch (e: GetCredentialException) {
            Log.w(TAG, "CredentialManager error during Google sign-in: ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر إتمام تسجيل الدخول عبر Google"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google sign-in: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Silent auto sign-in for previously authorized accounts using a single GetGoogleIdOption request.
     */
    suspend fun attemptAutoSignIn(activityContext: Context): Result<CloudUserInfo> {
        currentCloudUser()?.let { return Result.success(it) }
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val autoOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(autoOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val cred = result.credential
            if (cred is CustomCredential &&
                cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleId = GoogleIdTokenCredential.createFrom(cred.data)
                completeFirebaseGoogleAuth(googleId.idToken)
            } else {
                Result.failure(IllegalStateException("No matching authorized Google credential"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun completeFirebaseGoogleAuth(idToken: String): Result<CloudUserInfo> {
        val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
        val authResult = auth.signInWithCredential(firebaseCred).await()
        val user = authResult.user ?: error("تعذر إتمام تسجيل الدخول")
        if (!user.isEmailVerified) {
            return Result.failure(IllegalStateException("البريد الإلكتروني في حساب Google غير موثق"))
        }
        bootstrapUserDocs(user)
        val info = currentCloudUser() ?: return Result.failure(IllegalStateException("تعذر قراءة بيانات الحساب"))
        return Result.success(info)
    }

    suspend fun signOut() {
        knownMeta.clear()
        withContext(NonCancellable) {
            auth.signOut()
            runCatching {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            }.onFailure {
                Log.w(TAG, "CredentialManager clearCredentialState skipped: ${it.message}")
            }
        }
    }

    private suspend fun bootstrapUserDocs(user: FirebaseUser) {
        val isBootAdmin = user.email?.equals(bootstrappedAdminEmail, ignoreCase = true) == true
        val role = if (isBootAdmin) "manager" else "driver"
        val userPath = "users/${user.uid}"
        val userRef = db.collection("users").document(user.uid)
        try {
            val publicSnap = userRef.get().await()
            if (!publicSnap.exists()) {
                val publicDoc = mapOf(
                    "uid" to user.uid,
                    "displayName" to (user.displayName ?: user.email ?: "مستخدم مياهنا").take(120),
                    "role" to role,
                    "ownerId" to user.uid,
                    "serverCreatedAt" to FieldValue.serverTimestamp(),
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(publicDoc).await()
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userPath, auth)
            throw e
        }

        val privPath = "users/${user.uid}/private/profile"
        val privRef = userRef.collection("private").document("profile")
        try {
            val privSnap = privRef.get().await()
            if (!privSnap.exists()) {
                val privDoc = mapOf(
                    "uid" to user.uid,
                    "email" to (user.email ?: "").take(254),
                    "ownerId" to user.uid,
                    "serverCreatedAt" to FieldValue.serverTimestamp(),
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                )
                privRef.set(privDoc).await()
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, privPath, auth)
            throw e
        }
    }

    private fun sanitizeDocId(raw: String?, prefix: String): String {
        val cleaned = raw.orEmpty()
            .trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .trim('_')
            .take(120)
        return cleaned.ifEmpty { "$prefix-${System.currentTimeMillis()}" }
    }

    private fun isBootstrappedAdmin(user: FirebaseUser): Boolean =
        user.email?.equals(bootstrappedAdminEmail, ignoreCase = true) == true

    private fun collectionQuery(colName: String, user: FirebaseUser): Query {
        val ref = db.collection(colName)
        return if (isBootstrappedAdmin(user)) ref else ref.whereEqualTo("ownerId", user.uid)
    }

    private suspend fun <T> fetchCollection(
        colName: String,
        user: FirebaseUser,
        deserializer: DeserializationStrategy<T>
    ): List<T> {
        return try {
            val snap = collectionQuery(colName, user).get().await()
            decodeSnapshotDocs(colName, snap.documents, deserializer)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colName, auth)
            throw e
        }
    }

    private fun <T> decodeSnapshotDocs(
        colName: String,
        documents: List<com.google.firebase.firestore.DocumentSnapshot>,
        deserializer: DeserializationStrategy<T>
    ): List<T> {
        return documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val ownerId = data["ownerId"] as? String
            val createdAt = data["serverCreatedAt"]
            if (ownerId != null && createdAt != null) {
                knownMeta["$colName/${doc.id}"] = KnownDocMeta(ownerId, createdAt)
            }
            val cleanMap = data.filterKeys {
                it != "ownerId" && it != "serverCreatedAt" && it != "serverUpdatedAt"
            }
            val jsonElem = mapToJsonObject(cleanMap)
            runCatching { json.decodeFromJsonElement(deserializer, jsonElem) }
                .onFailure { Log.w(TAG, "Decode skip in $colName/${doc.id}: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun pullCloudSnapshot(): Result<CloudSnapshot> = runCatching {
        val user = requireVerifiedUser()

        val settings = fetchCollection("settings", user, SystemSettings.serializer())
        val trucks = fetchCollection("trucks", user, Truck.serializer())
        val drivers = fetchCollection("driverAccounts", user, DriverAccount.serializer())
        val customers = fetchCollection("customers", user, Customer.serializer())
        val orders = fetchCollection("orders", user, Order.serializer())
        val trips = fetchCollection("trips", user, Trip.serializer())
        val invoices = fetchCollection("invoices", user, Invoice.serializer())
        val recs = fetchCollection("reconciliations", user, DriverReconciliation.serializer())
        val expenses = fetchCollection("expenses", user, Expense.serializer())
        val gpsEvents = fetchCollection("gpsEvents", user, GPSEvent.serializer())
        val notifications = fetchCollection("notifications", user, AppNotification.serializer())
        val directives = fetchCollection("directives", user, ManagerDirective.serializer())
        val tripStateLogs = fetchCollection("tripStateLogs", user, TripStateTransition.serializer())
            .map { it.copy(isSyncedToCloud = true) }
            .sortedByDescending { it.timestampEpochMs }
            .take(500)

        val hasAny = settings.isNotEmpty() || trucks.isNotEmpty() || drivers.isNotEmpty() ||
            customers.isNotEmpty() || orders.isNotEmpty() || trips.isNotEmpty() ||
            invoices.isNotEmpty() || recs.isNotEmpty() || expenses.isNotEmpty() ||
            gpsEvents.isNotEmpty() || notifications.isNotEmpty() || directives.isNotEmpty() ||
            tripStateLogs.isNotEmpty()

        CloudSnapshot(
            settings = settings.firstOrNull(),
            truck = trucks.firstOrNull(),
            trucks = trucks,
            driverAccounts = drivers,
            customers = customers,
            orders = orders,
            trips = trips,
            invoices = invoices,
            reconciliations = recs,
            expenses = expenses,
            gpsEvents = gpsEvents.takeLast(100),
            notifications = notifications.take(250),
            directives = directives,
            tripStateLogs = tripStateLogs,
            hasAnyDocs = hasAny
        )
    }

    private fun <T> observeCollection(
        colName: String,
        deserializer: DeserializationStrategy<T>,
        requireAuthInClient: Boolean = true
    ): Flow<List<T>> = callbackFlow {
        val user = auth.currentUser
        val query: Query = if (user == null || !user.isEmailVerified) {
            if (requireAuthInClient) {
                close()
                return@callbackFlow
            } else {
                db.collection(colName)
            }
        } else {
            collectionQuery(colName, user)
        }

        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, colName, auth)
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                trySend(decodeSnapshotDocs(colName, snapshot.documents, deserializer))
            }
        }
        awaitClose { registration.remove() }
    }

    fun observeRealTimeTrucks(requireAuth: Boolean = true): Flow<List<Truck>> =
        observeCollection("trucks", Truck.serializer(), requireAuth)

    fun observeRealTimeTrips(requireAuth: Boolean = true): Flow<List<Trip>> =
        observeCollection("trips", Trip.serializer(), requireAuth)

    fun observeRealTimeCustomers(requireAuth: Boolean = true): Flow<List<Customer>> =
        observeCollection("customers", Customer.serializer(), requireAuth)

    fun observeRealTimeOrders(requireAuth: Boolean = true): Flow<List<Order>> =
        observeCollection("orders", Order.serializer(), requireAuth)

    fun observeRealTimeNotifications(requireAuth: Boolean = true): Flow<List<AppNotification>> =
        observeCollection("notifications", AppNotification.serializer(), requireAuth)

    fun observeRealTimeDirectives(requireAuth: Boolean = true): Flow<List<ManagerDirective>> =
        observeCollection("directives", ManagerDirective.serializer(), requireAuth)

    fun observeRealTimeGpsEvents(requireAuth: Boolean = true): Flow<List<GPSEvent>> =
        observeCollection("gpsEvents", GPSEvent.serializer(), requireAuth)

    fun observeRealTimeDriverAccounts(requireAuth: Boolean = true): Flow<List<DriverAccount>> =
        observeCollection("driverAccounts", DriverAccount.serializer(), requireAuth)

    fun observeRealTimeTripStateLogs(requireAuth: Boolean = true): Flow<List<TripStateTransition>> =
        observeCollection("tripStateLogs", TripStateTransition.serializer(), requireAuth)
            .map { entries ->
                entries.map { it.copy(isSyncedToCloud = true) }
                    .sortedByDescending { it.timestampEpochMs }
            }

    // --- Single Document CRUD Operations (used by Repository & Rule Tests) ---

    suspend fun upsertCustomer(customer: Customer): Result<String> = runCatching {
        val user = requireVerifiedUser()
        val docId = sanitizeDocId(customer.id, "cust")
        val path = "customers/$docId"
        val payload = serializeWithAudit("customers", docId, json.encodeToJsonElement(customer), user)
        try {
            db.collection("customers").document(docId).set(payload).await()
            docId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            throw e
        }
    }

    suspend fun getCustomerById(customerId: String): Result<Customer?> = runCatching {
        requireVerifiedUser()
        val docId = sanitizeDocId(customerId, "cust")
        val path = "customers/$docId"
        try {
            val snap = db.collection("customers").document(docId).get().await()
            if (!snap.exists()) null
            else decodeSnapshotDocs("customers", listOf(snap), Customer.serializer()).firstOrNull()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path, auth)
            throw e
        }
    }

    suspend fun upsertTruck(truck: Truck): Result<String> = runCatching {
        val user = requireVerifiedUser()
        val docId = sanitizeDocId(truck.id.ifBlank { truck.code.ifBlank { "TRUCK-01" } }, "truck")
        val path = "trucks/$docId"
        val payload = serializeWithAudit("trucks", docId, json.encodeToJsonElement(truck), user)
        try {
            db.collection("trucks").document(docId).set(payload).await()
            docId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            throw e
        }
    }

    suspend fun upsertTrip(trip: Trip): Result<String> = runCatching {
        val user = requireVerifiedUser()
        val docId = sanitizeDocId(trip.id, "trip")
        val path = "trips/$docId"
        val trimmed = trimTripArrays(trip)
        val payload = serializeWithAudit("trips", docId, json.encodeToJsonElement(trimmed), user)
        try {
            db.collection("trips").document(docId).set(payload).await()
            docId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            throw e
        }
    }

    suspend fun logTripStateTransition(entry: TripStateTransition): Result<String> = runCatching {
        val user = requireVerifiedUser()
        val docId = sanitizeDocId(entry.id, "tlog")
        val path = "tripStateLogs/$docId"
        val payload = serializeTripStateLogPayload(docId, entry, user)
        try {
            db.collection("tripStateLogs").document(docId).set(payload).await()
            docId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            throw e
        }
    }

    suspend fun syncPendingTripStateLogs(entries: List<TripStateTransition>): Result<List<String>> = runCatching {
        if (entries.isEmpty()) return@runCatching emptyList()
        val user = requireVerifiedUser()
        val syncedIds = mutableListOf<String>()
        entries.chunked(350).forEach { chunk ->
            val batch = db.batch()
            val chunkIds = mutableListOf<String>()
            chunk.forEach { entry ->
                val docId = sanitizeDocId(entry.id, "tlog")
                val payload = serializeTripStateLogPayload(docId, entry, user)
                batch.set(db.collection("tripStateLogs").document(docId), payload)
                chunkIds.add(entry.id)
            }
            try {
                batch.commit().await()
                syncedIds.addAll(chunkIds)
            } catch (e: Exception) {
                val sampleId = chunk.firstOrNull()?.id?.let { sanitizeDocId(it, "tlog") } ?: "batch"
                handleFirestoreError(e, OperationType.WRITE, "tripStateLogs/$sampleId", auth)
                throw e
            }
        }
        syncedIds
    }

    suspend fun markTripStateTransitionReviewed(
        logId: String,
        reviewerName: String = "المدير العام",
        note: String? = null
    ): Result<Unit> = runCatching {
        requireVerifiedUser()
        val docId = sanitizeDocId(logId, "tlog")
        val path = "tripStateLogs/$docId"
        val updates = hashMapOf<String, Any>(
            "reviewedByManager" to true,
            "reviewedAt" to Instant.now().toString(),
            "serverUpdatedAt" to FieldValue.serverTimestamp(),
            "reviewNote" to (note?.trim()?.takeIf { it.isNotEmpty() } ?: "تمت المراجعة من $reviewerName").take(500)
        )
        try {
            db.collection("tripStateLogs").document(docId).update(updates).await()
            Unit
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            throw e
        }
    }

    /**
     * Lightweight real-time telemetry batch upsert used during live driving and standby
     * movement/stop tracking so Manager sees updates without pushing the entire 12-collection snapshot.
     */
    suspend fun upsertLiveTelemetry(
        truck: Truck,
        activeTrip: Trip? = null,
        gpsEvent: GPSEvent? = null
    ): Result<Unit> = runCatching {
        val user = requireVerifiedUser()
        val batch = db.batch()

        val truckDocId = sanitizeDocId(truck.id.ifBlank { truck.code.ifBlank { "TRUCK-01" } }, "truck")
        val truckPayload = serializeWithAudit("trucks", truckDocId, json.encodeToJsonElement(truck), user)
        batch.set(db.collection("trucks").document(truckDocId), truckPayload)

        if (activeTrip != null) {
            val tripDocId = sanitizeDocId(activeTrip.id, "trip")
            val trimmedTrip = trimTripArrays(activeTrip)
            val tripPayload = serializeWithAudit("trips", tripDocId, json.encodeToJsonElement(trimmedTrip), user)
            batch.set(db.collection("trips").document(tripDocId), tripPayload)
        }

        if (gpsEvent != null) {
            val evDocId = sanitizeDocId(gpsEvent.id, "gps")
            val trimmedEvent = gpsEvent.copy(
                tripNumber = gpsEvent.tripNumber.take(64),
                source = gpsEvent.source.take(32),
                customerName = gpsEvent.customerName?.take(150)
            )
            val evPayload = serializeWithAudit("gpsEvents", evDocId, json.encodeToJsonElement(trimmedEvent), user)
            batch.set(db.collection("gpsEvents").document(evDocId), evPayload)
        }

        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "trucks/$truckDocId", auth)
            throw e
        }
        Unit
    }

    suspend fun pushFullSnapshot(
        settings: SystemSettings,
        truck: Truck,
        fleetTrucks: List<Truck> = emptyList(),
        driverAccounts: List<DriverAccount>,
        customers: List<Customer>,
        orders: List<Order>,
        trips: List<Trip>,
        invoices: List<Invoice>,
        reconciliations: List<DriverReconciliation>,
        expenses: List<Expense>,
        gpsEvents: List<GPSEvent>,
        notifications: List<AppNotification>,
        directives: List<ManagerDirective>,
        tripStateLogs: List<TripStateTransition> = emptyList()
    ): Result<Int> = runCatching {
        val user = requireVerifiedUser()
        val items = mutableListOf<Triple<String, String, Map<String, Any>>>()

        val settingsId = if (isBootstrappedAdmin(user)) "main" else sanitizeDocId("cfg_${user.uid}", "cfg")
        items.add(Triple("settings", settingsId, serializeWithAudit("settings", settingsId, json.encodeToJsonElement(settings), user)))

        val allTrucks = (listOf(truck) + fleetTrucks).distinctBy { it.id }
        allTrucks.forEach { t ->
            val tId = sanitizeDocId(t.id.ifBlank { t.code.ifBlank { "TRUCK-01" } }, "truck")
            items.add(Triple("trucks", tId, serializeWithAudit("trucks", tId, json.encodeToJsonElement(t), user)))
        }

        driverAccounts.forEach { acc ->
            if (!isBootstrappedAdmin(user) && acc.status != DriverAccountStatus.PENDING) {
                val k = "driverAccounts/${sanitizeDocId(acc.id, "drv")}"
                if (!knownMeta.containsKey(k)) return@forEach
            }
            val id = sanitizeDocId(acc.id, "drv")
            items.add(Triple("driverAccounts", id, serializeWithAudit("driverAccounts", id, json.encodeToJsonElement(acc), user)))
        }
        customers.forEach { c ->
            val id = sanitizeDocId(c.id, "cust")
            items.add(Triple("customers", id, serializeWithAudit("customers", id, json.encodeToJsonElement(c), user)))
        }
        orders.forEach { o ->
            val id = sanitizeDocId(o.id, "ord")
            items.add(Triple("orders", id, serializeWithAudit("orders", id, json.encodeToJsonElement(o), user)))
        }
        trips.forEach { t ->
            val id = sanitizeDocId(t.id, "trip")
            val trimmed = trimTripArrays(t)
            items.add(Triple("trips", id, serializeWithAudit("trips", id, json.encodeToJsonElement(trimmed), user)))
        }
        invoices.forEach { inv ->
            val id = sanitizeDocId(inv.id, "inv")
            items.add(Triple("invoices", id, serializeWithAudit("invoices", id, json.encodeToJsonElement(inv), user)))
        }
        reconciliations.forEach { rec ->
            val id = sanitizeDocId(rec.id, "rec")
            items.add(Triple("reconciliations", id, serializeWithAudit("reconciliations", id, json.encodeToJsonElement(rec), user)))
        }
        expenses.forEach { exp ->
            val id = sanitizeDocId(exp.id, "exp")
            items.add(Triple("expenses", id, serializeWithAudit("expenses", id, json.encodeToJsonElement(exp), user)))
        }
        gpsEvents.takeLast(100).forEach { ev ->
            val id = sanitizeDocId(ev.id, "gps")
            items.add(Triple("gpsEvents", id, serializeWithAudit("gpsEvents", id, json.encodeToJsonElement(ev), user)))
        }
        notifications.take(150).forEach { n ->
            val id = sanitizeDocId(n.id, "notif")
            items.add(Triple("notifications", id, serializeWithAudit("notifications", id, json.encodeToJsonElement(n), user)))
        }
        directives.forEach { d ->
            val id = sanitizeDocId(d.id, "dir")
            items.add(Triple("directives", id, serializeWithAudit("directives", id, json.encodeToJsonElement(d), user)))
        }
        val adminUser = isBootstrappedAdmin(user)
        tripStateLogs.take(250).forEach { entry ->
            val id = sanitizeDocId(entry.id, "tlog")
            val alreadyKnown = knownMeta.containsKey("tripStateLogs/$id")
            if (alreadyKnown && (entry.isSyncedToCloud || (entry.reviewedByManager && !adminUser))) {
                return@forEach
            }
            items.add(Triple("tripStateLogs", id, serializeTripStateLogPayload(id, entry, user)))
        }

        items.chunked(350).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { (col, id, payload) ->
                batch.set(db.collection(col).document(id), payload)
            }
            try {
                batch.commit().await()
            } catch (e: Exception) {
                val samplePath = chunk.firstOrNull()?.let { "${it.first}/${it.second}" }
                handleFirestoreError(e, OperationType.WRITE, samplePath, auth)
                throw e
            }
        }
        items.size
    }

    suspend fun deleteCustomerDocument(customerId: String): Result<Unit> = runCatching {
        val user = auth.currentUser ?: return@runCatching
        if (!user.isEmailVerified) return@runCatching
        val docId = sanitizeDocId(customerId, "cust")
        val path = "customers/$docId"
        knownMeta.remove(path)
        try {
            db.collection("customers").document(docId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
            throw e
        }
        Unit
    }

    private fun trimTripArrays(t: Trip): Trip = t.copy(
        gpsPoints = t.gpsPoints.takeLast(1500),
        detectedStops = t.detectedStops.take(200),
        gpsGaps = t.gpsGaps.take(200),
        auditLogs = t.auditLogs.takeLast(400),
        modificationRequests = t.modificationRequests.takeLast(50)
    )

    private fun serializeTripStateLogPayload(
        docId: String,
        entry: TripStateTransition,
        user: FirebaseUser
    ): Map<String, Any> {
        val sanitized = entry.copy(
            id = docId,
            tripId = sanitizeDocId(entry.tripId, "trip"),
            tripNumber = entry.tripNumber.take(64),
            truckId = entry.truckId.take(128),
            driverId = entry.driverId.take(128),
            driverName = entry.driverName.take(120),
            fromState = entry.fromState.take(40),
            toState = entry.toState.take(40),
            triggerSource = entry.triggerSource.ifBlank { "manual" }.take(64),
            actorRole = entry.actorRole.takeIf { it in listOf("manager", "driver", "system") } ?: "system",
            actorName = entry.actorName.ifBlank { "النظام" }.take(120),
            reason = entry.reason?.take(500),
            details = entry.details?.take(1000),
            timestamp = entry.timestamp.ifBlank { Instant.now().toString() }.take(64),
            reviewedAt = entry.reviewedAt?.take(64),
            reviewNote = entry.reviewNote?.take(500)
        )
        val elem = json.encodeToJsonElement(sanitized)
        val filtered = if (elem is JsonObject) {
            JsonObject(elem.filterKeys { it != "isSyncedToCloud" })
        } else {
            elem
        }
        return serializeWithAudit("tripStateLogs", docId, filtered, user)
    }

    private fun serializeWithAudit(
        col: String,
        docId: String,
        element: JsonElement,
        user: FirebaseUser
    ): Map<String, Any> {
        val rawMap = jsonElementToKotlin(element) as? Map<*, *> ?: emptyMap<String, Any>()
        val out = linkedMapOf<String, Any>()
        rawMap.forEach { (k, v) ->
            if (k is String && v != null) {
                out[k] = v
            }
        }
        val meta = knownMeta["$col/$docId"]
        out["id"] = docId
        out["ownerId"] = meta?.ownerId ?: user.uid
        out["serverCreatedAt"] = meta?.serverCreatedAt ?: FieldValue.serverTimestamp()
        out["serverUpdatedAt"] = FieldValue.serverTimestamp()
        return out
    }

    private fun jsonElementToKotlin(elem: JsonElement): Any? = when (elem) {
        is JsonNull -> null
        is JsonPrimitive -> {
            if (elem.isString) elem.content
            else elem.booleanOrNull ?: elem.longOrNull ?: elem.doubleOrNull ?: elem.content
        }
        is JsonArray -> elem.mapNotNull { jsonElementToKotlin(it) }
        is JsonObject -> elem.entries.mapNotNull { (k, v) ->
            jsonElementToKotlin(v)?.let { k to it }
        }.toMap()
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
        val entries = map.entries.associate { (k, v) -> k to anyToJsonElement(v) }
        return JsonObject(entries)
    }

    private fun anyToJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { anyToJsonElement(it) })
        is Map<*, *> -> JsonObject(
            v.entries.mapNotNull { (mk, mv) ->
                (mk as? String)?.let { it to anyToJsonElement(mv) }
            }.toMap()
        )
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        private const val TAG = "FirebaseSyncManager"
    }
}

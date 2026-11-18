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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.miyahna.water.R
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.DriverAccount
import com.miyahna.water.domain.DriverAccountStatus
import com.miyahna.water.domain.DriverReconciliation
import com.miyahna.water.domain.Expense
import com.miyahna.water.domain.GPSEvent
import com.miyahna.water.domain.Invoice
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStateTransition
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.Truck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
import java.time.Instant
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
        val serverCreatedAt: Any,
        val memberEmails: List<String>? = null,
        val docStatus: String? = null,
        val isRead: Boolean? = null
    )

    private sealed class BatchWriteOp {
        abstract val col: String
        abstract val id: String

        data class SetDoc(
            override val col: String,
            override val id: String,
            val payload: Map<String, Any>
        ) : BatchWriteOp()

        data class UpdateDoc(
            override val col: String,
            override val id: String,
            val fields: Map<String, Any>
        ) : BatchWriteOp()
    }

    private val knownMeta = ConcurrentHashMap<String, KnownDocMeta>()
    private val bootstrappedAdminEmail: String =
        context.getString(R.string.bootstrapped_admin_email).trim()
    private val databaseId: String =
        context.getString(R.string.firestore_database_id)
    private val webClientId: String =
        context.getString(R.string.default_web_client_id)

    @Volatile
    private var fleetMemberEmails: Set<String> = setOf(bootstrappedAdminEmail.lowercase())

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

    fun setFleetMemberEmails(emails: Collection<String>) {
        val normalized = linkedSetOf(bootstrappedAdminEmail.lowercase())
        emails.forEach { raw ->
            val clean = raw.trim().lowercase()
            if (clean.contains("@") && clean.length <= 254) {
                normalized.add(clean)
            }
        }
        fleetMemberEmails = normalized.take(25).toSet()
    }

    fun hasPopulatedMeta(): Boolean = knownMeta.isNotEmpty()

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
        val isBootAdmin = isBootstrappedAdmin(user)
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
        user.email?.trim()?.equals(bootstrappedAdminEmail, ignoreCase = true) == true

    private fun collectionQuery(colName: String, user: FirebaseUser): Query {
        val ref = db.collection(colName)
        if (isBootstrappedAdmin(user)) return ref
        val emailLower = user.email?.trim()?.lowercase().orEmpty()
        return if (colName in SHARED_COLLECTIONS && emailLower.isNotBlank()) {
            ref.whereArrayContains("memberEmails", emailLower)
        } else {
            ref.whereEqualTo("ownerId", user.uid)
        }
    }

    private suspend fun <T> fetchCollection(
        colName: String,
        user: FirebaseUser,
        deserializer: DeserializationStrategy<T>,
        sortDocs: (List<DocumentSnapshot>) -> List<DocumentSnapshot> = { it }
    ): List<T> {
        return try {
            val snap = collectionQuery(colName, user).get().await()
            decodeSnapshotDocs(colName, sortDocs(snap.documents), deserializer)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colName, auth)
            throw e
        }
    }

    private fun <T> decodeSnapshotDocs(
        colName: String,
        documents: List<DocumentSnapshot>,
        deserializer: DeserializationStrategy<T>
    ): List<T> {
        return documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val ownerId = data["ownerId"] as? String
            val createdAt = data["serverCreatedAt"]
            val memberEmails = (data["memberEmails"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            val docStatus = data["status"] as? String
            val isRead = data["isRead"] as? Boolean
            if (ownerId != null && createdAt != null) {
                knownMeta["$colName/${doc.id}"] = KnownDocMeta(
                    ownerId = ownerId,
                    serverCreatedAt = createdAt,
                    memberEmails = memberEmails,
                    docStatus = docStatus,
                    isRead = isRead
                )
            }
            val cleanMap = data.filterKeys {
                it != "ownerId" &&
                    it != "serverCreatedAt" &&
                    it != "serverUpdatedAt" &&
                    it != "memberEmails"
            }
            val jsonElem = mapToJsonObject(cleanMap)
            runCatching { json.decodeFromJsonElement(deserializer, jsonElem) }
                .onFailure { Log.w(TAG, "Decode skip in $colName/${doc.id}: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun pullCloudSnapshot(): Result<CloudSnapshot> = runCatching {
        val user = requireVerifiedUser()

        val settings = fetchCollection("settings", user, SystemSettings.serializer()) { docs ->
            docs.sortedByDescending { it.id == "main" }
        }
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
        requireAuthInClient: Boolean = true,
        sortDocs: (List<DocumentSnapshot>) -> List<DocumentSnapshot> = { it }
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
                trySend(decodeSnapshotDocs(colName, sortDocs(snapshot.documents), deserializer))
            }
        }
        awaitClose { registration.remove() }
    }

    fun observeRealTimeSettings(requireAuth: Boolean = true): Flow<SystemSettings?> =
        observeCollection("settings", SystemSettings.serializer(), requireAuth) { docs ->
            docs.sortedByDescending { it.id == "main" }
        }.map { it.firstOrNull() }

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
        val meta = knownMeta[path]
        val isForeignSharedMember = !isBootstrappedAdmin(user) && meta != null && meta.ownerId != user.uid
        try {
            if (isForeignSharedMember) {
                db.collection("trucks").document(docId)
                    .update(buildDriverTruckUpdateMap(truck))
                    .await()
            } else {
                val payload = serializeWithAudit("trucks", docId, json.encodeToJsonElement(truck), user)
                db.collection("trucks").document(docId).set(payload).await()
            }
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
        val meta = knownMeta[path]
        val isForeignSharedMember = !isBootstrappedAdmin(user) && meta != null && meta.ownerId != user.uid
        try {
            if (isForeignSharedMember) {
                if (meta?.docStatus != "closed") {
                    db.collection("trips").document(docId)
                        .update(buildDriverTripUpdateMap(trimmed))
                        .await()
                }
            } else {
                val payload = serializeWithAudit("trips", docId, json.encodeToJsonElement(trimmed), user)
                db.collection("trips").document(docId).set(payload).await()
            }
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
        val adminUser = isBootstrappedAdmin(user)
        val batch = db.batch()

        val truckDocId = sanitizeDocId(truck.id.ifBlank { truck.code.ifBlank { "TRUCK-01" } }, "truck")
        val truckRef = db.collection("trucks").document(truckDocId)
        val truckMeta = knownMeta["trucks/$truckDocId"]
        if (!adminUser && truckMeta != null && truckMeta.ownerId != user.uid) {
            batch.update(truckRef, buildDriverTruckUpdateMap(truck))
        } else {
            val truckPayload = serializeWithAudit("trucks", truckDocId, json.encodeToJsonElement(truck), user)
            batch.set(truckRef, truckPayload)
        }

        if (activeTrip != null) {
            val tripDocId = sanitizeDocId(activeTrip.id, "trip")
            val trimmedTrip = trimTripArrays(activeTrip)
            val tripRef = db.collection("trips").document(tripDocId)
            val tripMeta = knownMeta["trips/$tripDocId"]
            if (!adminUser && tripMeta != null && tripMeta.ownerId != user.uid) {
                if (tripMeta.docStatus != "closed") {
                    batch.update(tripRef, buildDriverTripUpdateMap(trimmedTrip))
                }
            } else {
                val tripPayload = serializeWithAudit("trips", tripDocId, json.encodeToJsonElement(trimmedTrip), user)
                batch.set(tripRef, tripPayload)
            }
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
        tripStateLogs: List<TripStateTransition> = emptyList(),
        driverOnlyMode: Boolean = false
    ): Result<Int> = runCatching {
        val user = requireVerifiedUser()
        val adminUser = isBootstrappedAdmin(user)
        val driverScoped = driverOnlyMode || !adminUser
        val ops = mutableListOf<BatchWriteOp>()

        if (!driverScoped && adminUser) {
            ops.add(
                BatchWriteOp.SetDoc(
                    col = "settings",
                    id = "main",
                    payload = serializeWithAudit("settings", "main", json.encodeToJsonElement(settings), user)
                )
            )
        }

        val trucksToPush = if (driverScoped) {
            listOf(truck)
        } else {
            (listOf(truck) + fleetTrucks).distinctBy { it.id }
        }
        trucksToPush.forEach { t ->
            val tId = sanitizeDocId(t.id.ifBlank { t.code.ifBlank { "TRUCK-01" } }, "truck")
            val meta = knownMeta["trucks/$tId"]
            if (!adminUser && meta != null && meta.ownerId != user.uid) {
                ops.add(BatchWriteOp.UpdateDoc("trucks", tId, buildDriverTruckUpdateMap(t)))
            } else {
                ops.add(BatchWriteOp.SetDoc("trucks", tId, serializeWithAudit("trucks", tId, json.encodeToJsonElement(t), user)))
            }
        }

        driverAccounts.forEach { acc ->
            val id = sanitizeDocId(acc.id, "drv")
            val meta = knownMeta["driverAccounts/$id"]
            if (adminUser) {
                ops.add(BatchWriteOp.SetDoc("driverAccounts", id, serializeWithAudit("driverAccounts", id, json.encodeToJsonElement(acc), user)))
            } else if (meta == null && acc.status == DriverAccountStatus.PENDING) {
                ops.add(BatchWriteOp.SetDoc("driverAccounts", id, serializeWithAudit("driverAccounts", id, json.encodeToJsonElement(acc), user)))
            } else if (meta != null && meta.ownerId != user.uid && !acc.lastLoginAt.isNullOrBlank()) {
                ops.add(
                    BatchWriteOp.UpdateDoc(
                        "driverAccounts",
                        id,
                        mapOf(
                            "lastLoginAt" to acc.lastLoginAt,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                )
            }
        }

        customers.forEach { c ->
            val id = sanitizeDocId(c.id, "cust")
            val meta = knownMeta["customers/$id"]
            if (!driverScoped || c.isOfflineCreated || meta == null || meta.ownerId == user.uid) {
                if (adminUser || meta == null || meta.ownerId == user.uid) {
                    ops.add(BatchWriteOp.SetDoc("customers", id, serializeWithAudit("customers", id, json.encodeToJsonElement(c), user)))
                }
            }
        }

        orders.forEach { o ->
            val id = sanitizeDocId(o.id, "ord")
            val meta = knownMeta["orders/$id"]
            if (adminUser) {
                ops.add(BatchWriteOp.SetDoc("orders", id, serializeWithAudit("orders", id, json.encodeToJsonElement(o), user)))
            } else if (meta == null || meta.ownerId == user.uid) {
                if (meta?.docStatus !in listOf("delivered", "cancelled")) {
                    ops.add(BatchWriteOp.SetDoc("orders", id, serializeWithAudit("orders", id, json.encodeToJsonElement(o), user)))
                }
            } else if (meta.docStatus !in listOf("delivered", "cancelled") && o.status == OrderStatus.IN_DELIVERY) {
                ops.add(
                    BatchWriteOp.UpdateDoc(
                        "orders",
                        id,
                        mapOf(
                            "status" to o.status.wire,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                )
            }
        }

        trips.forEach { t ->
            val id = sanitizeDocId(t.id, "trip")
            val meta = knownMeta["trips/$id"]
            val trimmed = trimTripArrays(t)
            if (adminUser) {
                ops.add(BatchWriteOp.SetDoc("trips", id, serializeWithAudit("trips", id, json.encodeToJsonElement(trimmed), user)))
            } else if (meta?.docStatus != "closed") {
                if (meta == null || meta.ownerId == user.uid) {
                    ops.add(BatchWriteOp.SetDoc("trips", id, serializeWithAudit("trips", id, json.encodeToJsonElement(trimmed), user)))
                } else if (t.status != TripStatus.CLOSED) {
                    ops.add(BatchWriteOp.UpdateDoc("trips", id, buildDriverTripUpdateMap(trimmed)))
                }
            }
        }

        if (!driverScoped && adminUser) {
            invoices.forEach { inv ->
                val id = sanitizeDocId(inv.id, "inv")
                ops.add(BatchWriteOp.SetDoc("invoices", id, serializeWithAudit("invoices", id, json.encodeToJsonElement(inv), user)))
            }
            reconciliations.forEach { rec ->
                val id = sanitizeDocId(rec.id, "rec")
                ops.add(BatchWriteOp.SetDoc("reconciliations", id, serializeWithAudit("reconciliations", id, json.encodeToJsonElement(rec), user)))
            }
            expenses.forEach { exp ->
                val id = sanitizeDocId(exp.id, "exp")
                ops.add(BatchWriteOp.SetDoc("expenses", id, serializeWithAudit("expenses", id, json.encodeToJsonElement(exp), user)))
            }
        }

        gpsEvents.takeLast(100).forEach { ev ->
            val id = sanitizeDocId(ev.id, "gps")
            val meta = knownMeta["gpsEvents/$id"]
            if (adminUser || meta == null) {
                ops.add(BatchWriteOp.SetDoc("gpsEvents", id, serializeWithAudit("gpsEvents", id, json.encodeToJsonElement(ev), user)))
            }
        }

        notifications.take(150).forEach { n ->
            val id = sanitizeDocId(n.id, "notif")
            val meta = knownMeta["notifications/$id"]
            if (adminUser || meta == null || meta.ownerId == user.uid) {
                ops.add(BatchWriteOp.SetDoc("notifications", id, serializeWithAudit("notifications", id, json.encodeToJsonElement(n), user)))
            } else if (n.isRead && meta.isRead != true) {
                ops.add(
                    BatchWriteOp.UpdateDoc(
                        "notifications",
                        id,
                        mapOf(
                            "isRead" to true,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                )
            }
        }

        directives.forEach { d ->
            val id = sanitizeDocId(d.id, "dir")
            val meta = knownMeta["directives/$id"]
            if (adminUser) {
                ops.add(BatchWriteOp.SetDoc("directives", id, serializeWithAudit("directives", id, json.encodeToJsonElement(d), user)))
            } else if (meta != null && meta.ownerId != user.uid && meta.docStatus != "completed" &&
                (d.status != DirectiveStatus.SENT || !d.driverReply.isNullOrBlank())
            ) {
                ops.add(BatchWriteOp.UpdateDoc("directives", id, buildDriverDirectiveUpdateMap(d)))
            } else if (meta == null && !driverScoped) {
                ops.add(BatchWriteOp.SetDoc("directives", id, serializeWithAudit("directives", id, json.encodeToJsonElement(d), user)))
            }
        }

        tripStateLogs.take(250).forEach { entry ->
            val id = sanitizeDocId(entry.id, "tlog")
            val alreadyKnown = knownMeta.containsKey("tripStateLogs/$id")
            if (alreadyKnown && (entry.isSyncedToCloud || (entry.reviewedByManager && !adminUser))) {
                return@forEach
            }
            if (!alreadyKnown || adminUser) {
                ops.add(BatchWriteOp.SetDoc("tripStateLogs", id, serializeTripStateLogPayload(id, entry, user)))
            }
        }

        if (ops.isEmpty()) return@runCatching 0

        ops.chunked(350).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { op ->
                val ref = db.collection(op.col).document(op.id)
                when (op) {
                    is BatchWriteOp.SetDoc -> batch.set(ref, op.payload)
                    is BatchWriteOp.UpdateDoc -> batch.update(ref, op.fields)
                }
            }
            try {
                batch.commit().await()
            } catch (e: Exception) {
                val samplePath = chunk.firstOrNull()?.let { "${it.col}/${it.id}" }
                handleFirestoreError(e, OperationType.WRITE, samplePath, auth)
                throw e
            }
        }
        ops.size
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

    suspend fun deleteTripDocument(tripId: String): Result<Unit> = runCatching {
        val user = auth.currentUser ?: return@runCatching
        if (!user.isEmailVerified) return@runCatching
        val docId = sanitizeDocId(tripId, "trip")
        val path = "trips/$docId"
        knownMeta.remove(path)
        try {
            db.collection("trips").document(docId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
            throw e
        }
        Unit
    }

    private fun buildDriverTruckUpdateMap(truck: Truck): Map<String, Any> {
        val elem = json.encodeToJsonElement(truck)
        val raw = jsonElementToKotlin(elem) as? Map<*, *> ?: emptyMap<String, Any>()
        val out = linkedMapOf<String, Any>()
        DRIVER_TRUCK_UPDATE_KEYS.forEach { key ->
            val v = raw[key]
            if (v != null) out[key] = v
        }
        out["serverUpdatedAt"] = FieldValue.serverTimestamp()
        return out
    }

    private fun buildDriverTripUpdateMap(trip: Trip): Map<String, Any> {
        val elem = json.encodeToJsonElement(trip)
        val raw = jsonElementToKotlin(elem) as? Map<*, *> ?: emptyMap<String, Any>()
        val out = linkedMapOf<String, Any>()
        DRIVER_TRIP_UPDATE_KEYS.forEach { key ->
            val v = raw[key]
            if (v != null) out[key] = v
        }
        out["serverUpdatedAt"] = FieldValue.serverTimestamp()
        return out
    }

    private fun buildDriverDirectiveUpdateMap(directive: ManagerDirective): Map<String, Any> {
        val out = linkedMapOf<String, Any>(
            "status" to directive.status.wire,
            "serverUpdatedAt" to FieldValue.serverTimestamp()
        )
        directive.acknowledgedAt?.let { out["acknowledgedAt"] = it }
        directive.completedAt?.let { out["completedAt"] = it }
        directive.driverReply?.takeIf { it.isNotBlank() }?.let { out["driverReply"] = it.take(1000) }
        return out
    }

    private fun buildMemberEmails(
        user: FirebaseUser,
        existingMembers: List<String>?
    ): List<String> {
        val merged = linkedSetOf(bootstrappedAdminEmail.lowercase())
        user.email?.trim()?.lowercase()?.takeIf { it.contains("@") }?.let { merged.add(it) }
        if (isBootstrappedAdmin(user)) {
            merged.addAll(fleetMemberEmails)
        } else {
            existingMembers?.forEach { email ->
                val clean = email.trim().lowercase()
                if (clean.contains("@")) merged.add(clean)
            }
        }
        return merged.take(25)
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
        if (col in SHARED_COLLECTIONS) {
            out["memberEmails"] = buildMemberEmails(user, meta?.memberEmails)
        }
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

        private val SHARED_COLLECTIONS = setOf(
            "settings",
            "trucks",
            "driverAccounts",
            "customers",
            "orders",
            "trips",
            "gpsEvents",
            "notifications",
            "directives",
            "tripStateLogs"
        )

        private val DRIVER_TRUCK_UPDATE_KEYS = listOf(
            "currentWaterLiters", "status", "lastLat", "lastLng",
            "lastSpeed", "lastUpdateTimestamp", "lastEvent",
            "currentTripId", "odometerKm"
        )

        private val DRIVER_TRIP_UPDATE_KEYS = listOf(
            "deliveredLiters", "remainingLiters", "status", "syncStatus",
            "driverAcceptanceStatus", "driverRespondedAt", "driverResponseReason",
            "customerStops", "startedAt", "startLocation", "returnedToPlantAt",
            "returnLocation", "completedAt", "lastSyncedAt", "gpsPoints",
            "detectedStops", "gpsGaps", "movingMinutes", "stoppedMinutes",
            "auditLogs", "modificationRequests", "actualDistanceKm",
            "actualFuelLiters", "fuelCost", "actualCost"
        )
    }
}

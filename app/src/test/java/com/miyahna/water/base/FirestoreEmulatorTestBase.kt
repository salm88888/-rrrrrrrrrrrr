package com.miyahna.water.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var context: Context
    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String

    @Before
    open fun setUpFirebase() {
        context = ApplicationProvider.getApplicationContext()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        databaseId = if (resId != 0) context.getString(resId) else DEFAULT_TEST_DB_ID

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            val newApp = FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:935445559699:android:a4241bce0be2ea98951066")
                    .setProjectId(PROJECT_ID)
                    .setApiKey("fake-api-key-for-emulator")
                    .build()
            )
            val newFirestore = FirebaseFirestore.getInstance(newApp, databaseId)
            newFirestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            newFirestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()

            val newAuth = FirebaseAuth.getInstance(newApp)
            newAuth.useEmulator(EMULATOR_HOST, AUTH_PORT)
            newApp
        } else {
            FirebaseApp.getInstance()
        }

        firestore = FirebaseFirestore.getInstance(app, databaseId)
        auth = FirebaseAuth.getInstance(app)
    }

    @After
    open fun tearDownFirebase() {
        auth.signOut()
    }

    protected suspend fun signInTestUser(email: String, verifyEmail: Boolean = true): String =
        withContext(Dispatchers.IO) {
            withTimeout(AUTH_TIMEOUT_MS) {
                val result = try {
                    auth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
                } catch (_: FirebaseAuthInvalidUserException) {
                    auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
                }
                val user = checkNotNull(result.user) { "User auth failed" }
                if (verifyEmail && !user.isEmailVerified) {
                    verifyEmulatorUserEmail(user.email ?: email)
                    user.reload().await()
                    user.getIdToken(true).await()
                }
                user.uid
            }
        }

    private suspend fun verifyEmulatorUserEmail(email: String) {
        val user = auth.currentUser ?: return
        user.sendEmailVerification().await()
        val oobUrl = URL("http://$EMULATOR_HOST:$AUTH_PORT/emulator/v1/projects/$PROJECT_ID/oobCodes")
        val conn = (oobUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 3000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val codes = JSONObject(body).optJSONArray("oobCodes") ?: return
        var latestOobCode: String? = null
        for (i in 0 until codes.length()) {
            val obj = codes.optJSONObject(i) ?: continue
            if (obj.optString("email").equals(email, ignoreCase = true) &&
                obj.optString("requestType") == "VERIFY_EMAIL"
            ) {
                latestOobCode = obj.optString("oobCode")
            }
        }
        if (latestOobCode.isNullOrBlank()) return

        val verifyUrl = URL(
            "http://$EMULATOR_HOST:$AUTH_PORT/identitytoolkit.googleapis.com/v1/accounts:update?key=fake-api-key-for-emulator"
        )
        val postConn = (verifyUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 3000
            readTimeout = 3000
        }
        postConn.outputStream.use { os ->
            os.write("""{"oobCode":"$latestOobCode"}""".toByteArray())
        }
        postConn.responseCode
        postConn.disconnect()
    }

    protected fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is FirebaseFirestoreException) return current
            current = current.cause
        }
        return null
    }

    companion object {
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_PORT = 9099
        const val PROJECT_ID = "demo-no-project"
        const val DEFAULT_TEST_DB_ID = "ai-studio-android-water-65098756-0314-4193-b45a-4e23be9b2d5d"
        const val DEFAULT_PASSWORD = "password123"
        const val AUTH_TIMEOUT_MS = 8000L
    }
}

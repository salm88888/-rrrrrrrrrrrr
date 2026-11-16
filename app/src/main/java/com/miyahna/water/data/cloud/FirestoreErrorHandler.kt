package com.miyahna.water.data.cloud

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

/**
 * Logs structured diagnostic JSON for Firestore failures without swallowing rule details,
 * and returns a safe Arabic message suitable for surfacing in UI state.
 */
fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth? = null
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val resolvedAuth = auth ?: runCatching { Firebase.auth }.getOrNull()
    val currentUser = resolvedAuth?.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun toFriendlyFirestoreMessage(exception: Throwable, fallback: String): String {
    val causes = generateSequence(exception) { it.cause }
    val fsErr = causes.firstOrNull { it is FirebaseFirestoreException } as? FirebaseFirestoreException
    return when (fsErr?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "لا تملك صلاحية تنفيذ هذه العملية في قاعدة البيانات السحابية. تأكد من تسجيل الدخول بحساب Google المعتمد."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "تعذر الاتصال بخادم قاعدة البيانات حالياً — تم حفظ التعديلات محلياً وستُزامَن تلقائياً."
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
            "تم بلوغ الحد اليومي المجاني لقراءة/كتابة قاعدة البيانات السحابية."
        FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            "يرجى تسجيل الدخول بحساب Google أولاً للمزامنة مع قاعدة البيانات الحقيقية."
        else -> exception.message ?: fallback
    }
}

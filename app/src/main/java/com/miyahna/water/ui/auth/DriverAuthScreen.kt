package com.miyahna.water.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.data.ActionResult
import com.miyahna.water.data.cloud.CloudUserInfo
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.RoseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAuthScreen(
    onBackToPortal: () -> Unit,
    onLogin: (identifier: String, password: String, onError: (String) -> Unit) -> Unit,
    onRegister: (
        name: String,
        username: String,
        phone: String,
        password: String,
        licenseNumber: String?,
        onResult: (ActionResult<*>) -> Unit
    ) -> Unit,
    isOnline: Boolean = true,
    cloudUser: CloudUserInfo? = null,
    onCloudSignIn: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    var loginIdentifier by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }

    var regName by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regPhone by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regLicense by remember { mutableStateOf("") }
    var regFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.role_driver_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackToPortal) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "العودة للبوابة")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocalShipping,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "بوابة سائق وايت مياهنا",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "يتطلب تسجيل الحساب الجديد اعتماد المدير العام قبل البدء",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            !isOnline -> RoseError.copy(alpha = 0.45f)
                            cloudUser != null -> EmeraldAccent.copy(alpha = 0.55f)
                            else -> AmberWarning.copy(alpha = 0.6f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (cloudUser != null && isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = "حالة الربط السحابي",
                                tint = when {
                                    !isOnline -> RoseError
                                    cloudUser != null -> EmeraldAccent
                                    else -> AmberWarning
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "الربط السحابي المشترك (Google / Firebase)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = when {
                                        !isOnline -> "غير متصل بالإنترنت — ستُزامن البيانات تلقائياً عند توفر الشبكة"
                                        cloudUser != null -> "متصل بالسحابة: ${cloudUser.email}"
                                        else -> "سجّل بحساب Google المرتبط لدى المدير لاستلام الرحلات والطلبات مباشرة"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (cloudUser == null) {
                            OutlinedButton(
                                onClick = onCloudSignIn,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("driver_auth_cloud_signin_button")
                            ) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ربط حساب Google للمزامنة")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.driver_login_title)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.driver_register_title)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTab == 0) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = loginIdentifier,
                                onValueChange = {
                                    loginIdentifier = it
                                    loginError = null
                                },
                                label = { Text("اسم المستخدم أو رقم الهاتف") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("driver_login_identifier_input")
                            )
                            OutlinedTextField(
                                value = loginPassword,
                                onValueChange = {
                                    loginPassword = it
                                    loginError = null
                                },
                                label = { Text("كلمة المرور") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("driver_login_password_input")
                            )
                            if (loginError != null) {
                                Text(
                                    text = loginError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Button(
                                onClick = {
                                    onLogin(loginIdentifier, loginPassword) { err -> loginError = err }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("driver_login_submit_button")
                            ) {
                                Text("تسجيل الدخول للوايت")
                            }
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = regName,
                                onValueChange = { regName = it },
                                label = { Text("الاسم الكامل للسائق") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = regUsername,
                                onValueChange = { regUsername = it },
                                label = { Text("اسم المستخدم للدخول") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = regPhone,
                                onValueChange = { regPhone = it },
                                label = { Text("رقم الجوال") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = regLicense,
                                onValueChange = { regLicense = it },
                                label = { Text("رقم الرخصة (اختياري)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = regPassword,
                                onValueChange = { regPassword = it },
                                label = { Text("كلمة المرور (4 أحرف على الأقل)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (cloudUser != null) {
                                Text(
                                    text = "سيتم ربط الطلب تلقائياً ببريد Google: ${cloudUser.email}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmeraldAccent
                                )
                            }

                            regFeedback?.let { (ok, msg) ->
                                Text(
                                    text = msg,
                                    color = if (ok) EmeraldAccent else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Button(
                                onClick = {
                                    onRegister(regName, regUsername, regPhone, regPassword, regLicense) { res ->
                                        if (res.success) {
                                            regFeedback = true to "تم إرسال طلب الحساب بنجاح وهو الآن بانتظار اعتماد المدير."
                                            regName = ""
                                            regUsername = ""
                                            regPhone = ""
                                            regPassword = ""
                                            regLicense = ""
                                        } else {
                                            regFeedback = false to (res.error ?: "تعذر تسجيل الطلب")
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("driver_register_submit_button")
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("إرسال طلب التسجيل للمدير")
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.miyahna.water.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miyahna.water.R
import com.miyahna.water.data.cloud.CloudUserInfo
import com.miyahna.water.ui.common.StatusPill
import com.miyahna.water.ui.theme.AmberWarning
import com.miyahna.water.ui.theme.EmeraldAccent
import com.miyahna.water.ui.theme.RoseError
import com.miyahna.water.ui.theme.SkyPrimary

@Composable
fun RoleSelectionPortalScreen(
    isOnline: Boolean,
    cloudUser: CloudUserInfo?,
    pendingDriversCount: Int,
    onVerifyManagerPin: (String) -> Boolean,
    onSelectDriver: () -> Unit,
    onCloudSignIn: () -> Unit,
    onCloudSignOut: () -> Unit,
    isManagerUnlocked: Boolean = false,
    hasSavedDriverSession: Boolean = false,
    onEnterManagerDirectly: () -> Unit = {}
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = SkyPrimary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(76.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.miyahna_launcher_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.portal_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.portal_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = if (isOnline) "متصل بالشبكة" else "يعمل دون إنترنت",
                        containerColor = if (isOnline) EmeraldAccent.copy(alpha = 0.14f) else RoseError.copy(alpha = 0.14f),
                        contentColor = if (isOnline) EmeraldAccent else RoseError
                    )
                    StatusPill(
                        label = if (cloudUser != null) "Firebase متصل" else "Firebase غير مربوط",
                        containerColor = if (cloudUser != null) EmeraldAccent.copy(alpha = 0.14f) else AmberWarning.copy(alpha = 0.15f),
                        contentColor = if (cloudUser != null) EmeraldAccent else AmberWarning
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Prominent Firebase Cloud Connection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (cloudUser != null) {
                            EmeraldAccent.copy(alpha = 0.08f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (cloudUser != null) EmeraldAccent.copy(alpha = 0.45f) else SkyPrimary.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (cloudUser != null) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                            contentDescription = "حالة Firebase",
                            tint = if (cloudUser != null) EmeraldAccent else SkyPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (cloudUser != null) {
                                    "سحابة Firebase Firestore نشطة"
                                } else {
                                    "ربط قاعدة بيانات Firebase السحابية"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (cloudUser != null) {
                                    cloudUser.email
                                } else {
                                    "سجّل بحساب Google لمزامنة الطلبات والرحلات لحظياً"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cloudUser != null) {
                            TextButton(
                                onClick = onCloudSignOut,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("cloud_signout_button")
                            ) {
                                Text("فصل")
                            }
                        } else {
                            Button(
                                onClick = onCloudSignIn,
                                enabled = isOnline,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("cloud_signin_google_button")
                            ) {
                                Text("ربط الآن")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Manager Card
                Card(
                    onClick = {
                        if (isManagerUnlocked) {
                            onEnterManagerDirectly()
                        } else {
                            pinInput = ""
                            pinError = null
                            showPinDialog = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("select_manager_role_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, SkyPrimary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = stringResource(R.string.role_manager_title),
                                tint = SkyPrimary,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.role_manager_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isManagerUnlocked) {
                                    StatusPill(
                                        label = "دخول محفوظ",
                                        containerColor = EmeraldAccent.copy(alpha = 0.14f),
                                        contentColor = EmeraldAccent
                                    )
                                }
                                if (pendingDriversCount > 0) {
                                    StatusPill(
                                        label = "$pendingDriversCount سائق",
                                        containerColor = RoseError.copy(alpha = 0.15f),
                                        contentColor = RoseError
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "إدارة الطلبات، الرحلات، الأسطول، والمالية بسهولة",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (isManagerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = if (isManagerUnlocked) "الدخول محفوظ تلقائياً" else "محمي برمز PIN",
                            tint = if (isManagerUnlocked) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Driver Card
                Card(
                    onClick = onSelectDriver,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("select_driver_role_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, EmeraldAccent.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalShipping,
                                contentDescription = stringResource(R.string.role_driver_title),
                                tint = EmeraldAccent,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.role_driver_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (hasSavedDriverSession) {
                                    StatusPill(
                                        label = "دخول محفوظ",
                                        containerColor = EmeraldAccent.copy(alpha = 0.14f),
                                        contentColor = EmeraldAccent
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.role_driver_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = EmeraldAccent
                        )
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(stringResource(R.string.manager_pin_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.manager_pin_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it
                            pinError = null
                        },
                        label = { Text("رمز PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manager_pin_input")
                    )
                    if (pinError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pinError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onVerifyManagerPin(pinInput)) {
                            showPinDialog = false
                        } else {
                            pinError = "رمز PIN غير صحيح، يرجى المحاولة مرة أخرى"
                        }
                    },
                    modifier = Modifier.testTag("confirm_manager_pin_button")
                ) {
                    Text("دخول الإدارة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

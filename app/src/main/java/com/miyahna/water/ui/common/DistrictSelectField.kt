package com.miyahna.water.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DistrictSelectField(
    selectedDistrict: String,
    districts: List<String>,
    onDistrictSelected: (String) -> Unit,
    onAddCustomDistrict: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var isAddingCustom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("district_selector_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "المنطقة / الحي: ${selectedDistrict.ifBlank { "اختر الحي" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "قائمة الأحياء")
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                districts.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d) },
                        onClick = {
                            onDistrictSelected(d)
                            isAddingCustom = false
                            expanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.AddLocationAlt, contentDescription = "إضافة حي") },
                    text = { Text("+ إضافة حي أو منطقة جديدة...") },
                    onClick = {
                        isAddingCustom = true
                        expanded = false
                    }
                )
            }
        }

        if (isAddingCustom) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text("اسم المنطقة الجديدة") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_district_input")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val clean = customInput.trim()
                        if (clean.isNotEmpty()) {
                            onAddCustomDistrict(clean)
                            onDistrictSelected(clean)
                            customInput = ""
                            isAddingCustom = false
                        }
                    },
                    modifier = Modifier.testTag("save_custom_district_button")
                ) {
                    Text("إضافة")
                }
            }
        }
    }
}

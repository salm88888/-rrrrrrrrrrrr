package com.miyahna.water.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.SystemSettings
import com.miyahna.water.domain.Trip
import com.miyahna.water.domain.TripStatus
import com.miyahna.water.domain.Truck
import com.miyahna.water.ui.common.LocationPickerDialog
import com.miyahna.water.ui.common.MaribInteractiveMap
import com.miyahna.water.ui.common.formatLatLng
import com.miyahna.water.ui.theme.NumericTextStyle

@Composable
fun MaribMapScreen(
    settings: SystemSettings,
    truck: Truck,
    fleetTrucks: List<Truck> = listOf(truck),
    customers: List<Customer>,
    orders: List<Order> = emptyList(),
    trips: List<Trip>,
    onUpdatePlantLocation: (Double, Double) -> Unit
) {
    val activeTrip = trips.firstOrNull { it.status == TripStatus.IN_PROGRESS || it.status == TripStatus.PAUSED }
    var showPlantPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(settings.plantLocation.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${settings.plantLocation.city} · نطاق المصنع: ${settings.plantGeofenceMeters}م",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "الأسطول: ${fleetTrucks.size} صهاريج · الرئيسي: ${formatLatLng(truck.lastLat, truck.lastLng, 4)}",
                        style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize)
                    )
                }
                OutlinedButton(onClick = { showPlantPicker = true }) {
                    Text("تعديل موقع المصنع")
                }
            }
        }

        MaribInteractiveMap(
            plantLocation = settings.plantLocation,
            geofenceMeters = settings.plantGeofenceMeters,
            customers = customers,
            orders = orders,
            truck = truck,
            trucks = fleetTrucks,
            plannedStops = activeTrip?.customerStops.orEmpty(),
            gpsPoints = activeTrip?.gpsPoints.orEmpty(),
            detectedStops = activeTrip?.detectedStops.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }

    if (showPlantPicker) {
        LocationPickerDialog(
            title = "تعديل موقع مصنع مياهنا على خريطة مأرب",
            initialLat = settings.plantLocation.lat,
            initialLng = settings.plantLocation.lng,
            plantLocation = settings.plantLocation,
            geofenceMeters = settings.plantGeofenceMeters,
            onDismiss = { showPlantPicker = false },
            onConfirm = { lat, lng ->
                onUpdatePlantLocation(lat, lng)
                showPlantPicker = false
            }
        )
    }
}

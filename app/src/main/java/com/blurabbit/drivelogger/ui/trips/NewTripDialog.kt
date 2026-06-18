package com.blurabbit.drivelogger.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blurabbit.drivelogger.domain.model.TripProfile

@Composable
fun NewTripDialog(onDismiss: () -> Unit, onCreate: (TripProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var vehicleId by remember { mutableStateOf("") }
    var vehicleName by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Trip") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field("Trip name", name) { name = it }
                field("Vehicle ID", vehicleId) { vehicleId = it }
                field("Vehicle name", vehicleName) { vehicleName = it }
                field("Driver name", driver) { driver = it }
                field("Route", route) { route = it }
                field("City", city) { city = it }
                field("Weather", weather) { weather = it }
                field("Notes", notes) { notes = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    TripProfile(
                        name = name.ifBlank { null }, vehicleId = vehicleId.ifBlank { null },
                        vehicleName = vehicleName.ifBlank { null }, driverName = driver.ifBlank { null },
                        routeName = route.ifBlank { null }, city = city.ifBlank { null },
                        weather = weather.ifBlank { null }, notes = notes.ifBlank { null },
                    ),
                )
            }) { Text("Create & Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true)
}

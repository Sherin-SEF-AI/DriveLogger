package com.blurabbit.drivelogger.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.drivelogger.recording.RecordingState

@Composable
fun DashboardScreen(vm: DashboardViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recording = vm.isRecording(state)
    val paused = vm.isPaused(state)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusHeader(state)
        SpeedAndGps(state)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            item {
                MetricGrid(state)
            }
            if (state.warnings.isNotEmpty()) {
                item { Text("Warnings", style = MaterialTheme.typography.titleSmall) }
                items(state.warnings) { w -> Text("⚠ $w", color = Color(0xFFB00020)) }
            }
            if (state.sensorHealth.isNotEmpty()) {
                item { Text("Sensor health", style = MaterialTheme.typography.titleSmall) }
                items(state.sensorHealth) { h ->
                    Text("${if (h.healthy) "●" else "○"} ${h.sourceId}: ${"%.0f".format(h.actualHz)}/${"%.0f".format(h.expectedHz)} Hz, dropped ${h.droppedSamples}")
                }
            }
        }

        Controls(recording, paused, onStart = vm::start, onPause = vm::pause, onResume = vm::resume, onStop = vm::stop)
    }
}

@Composable
private fun StatusHeader(state: RecordingState) {
    val seconds = state.durationNs / 1_000_000_000
    val time = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    Card(Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("● ${state.phase}", fontWeight = FontWeight.Bold,
                color = if (state.phase.name == "RECORDING") Color(0xFFD32F2F) else Color.Gray)
            Text(time, fontWeight = FontWeight.Bold)
            Text("${"%.2f".format(state.distanceMeters / 1000)} km")
        }
    }
}

@Composable
private fun SpeedAndGps(state: RecordingState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(state.currentSpeedMps * 3.6).toInt()}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("km/h")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${state.satellitesUsed}/${state.satellitesTotal}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("satellites")
            }
        }
    }
}

@Composable
private fun MetricGrid(state: RecordingState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricRow("Storage free", "${state.storageFreeBytes / (1024 * 1024 * 1024)} GB")
            MetricRow("Battery", "${state.batteryPct.toInt()}%")
            MetricRow("Thermal status", "${state.thermalStatus}")
            MetricRow("GPS samples", "${state.gpsSamples}")
            MetricRow("IMU samples", "${state.imuSamples}")
            MetricRow("Frames", "${state.frameCount}")
            MetricRow("Events", "${state.eventCount}")
            MetricRow("Dropped writes", "${state.droppedWrites}")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Controls(
    recording: Boolean, paused: Boolean,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!recording && !paused) {
            Button(onClick = onStart, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) { Text("● START") }
        } else {
            if (recording) OutlinedButton(onClick = onPause, Modifier.weight(1f)) { Text("⏸ Pause") }
            if (paused) Button(onClick = onResume, Modifier.weight(1f)) { Text("▶ Resume") }
            Button(onClick = onStop, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))) { Text("⏹ Stop") }
        }
    }
}

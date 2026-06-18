package com.blurabbit.drivelogger.ui.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.drivelogger.R
import com.blurabbit.drivelogger.domain.model.CloudProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(onBack: () -> Unit, vm: TripDetailViewModel = hiltViewModel()) {
    val trip by vm.trip.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val shareUri by vm.shareUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var includeVideo by remember { mutableStateOf(false) }

    // When a zip is ready, fire the system share chooser (read-granted content uri), then reset.
    LaunchedEffect(shareUri) {
        val uri = shareUri ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_trip)))
        vm.shareConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.profile?.name ?: "Trip") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            trip?.let { t ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            stat("Status", t.status.name)
                            stat("Distance", "${"%.2f".format(t.stats.distanceMeters / 1000)} km")
                            stat("Max speed", "${"%.1f".format(t.stats.maxSpeedMps * 3.6)} km/h")
                            stat("Avg speed", "${"%.1f".format(t.stats.avgSpeedMps * 3.6)} km/h")
                            stat("GPS samples", "${t.stats.gpsSamples}")
                            stat("IMU samples", "${t.stats.imuSamples}")
                            stat("Frames", "${t.stats.frameCount}")
                            stat("Events", "${t.stats.eventCount}")
                        }
                    }
                }
                item {
                    Text("Artifacts", style = MaterialTheme.typography.titleSmall)
                    vm.artifacts().forEach { f ->
                        Text("${f.name} — ${"%.1f".format(f.length() / 1_048_576.0)} MB ✓", color = Color(0xFF2E7D32))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.upload(CloudProvider.AWS_S3) }, Modifier.weight(1f)) { Text("☁ Upload S3") }
                        Button(onClick = { vm.upload(CloudProvider.MINIO) }, Modifier.weight(1f)) { Text("☁ MinIO") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Include video in export", color = Color.Gray)
                        Switch(checked = includeVideo, onCheckedChange = { includeVideo = it })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.export(includeVideo) }, Modifier.weight(1f)) { Text("⤴ Export / Share") }
                        OutlinedButton(onClick = { vm.enrichHdMap() }, Modifier.weight(1f)) { Text("🗺 Add HD-map") }
                    }
                }
                item {
                    OutlinedButton(onClick = { vm.delete(); onBack() }, Modifier.fillMaxWidth()) { Text("Delete trip") }
                }
            }
            item { Text("Events (${events.size})", style = MaterialTheme.typography.titleSmall) }
            items(events) { e ->
                Text("• ${e.type} — conf ${"%.2f".format(e.confidence)} @ ${e.unifiedTsNs / 1_000_000_000}s")
            }
        }
    }
}

@Composable
private fun stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

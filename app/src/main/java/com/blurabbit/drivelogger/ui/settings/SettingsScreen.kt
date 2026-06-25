package com.blurabbit.drivelogger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.model.VideoQuality

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Privacy & consent ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Privacy & Consent", style = MaterialTheme.typography.titleMedium)
                toggle("Recording consent (required to record)", s.consentGiven) { v -> vm.update { it.copy(consentGiven = v) } }
                toggle("Anonymize PII in dataset (drop driver/vehicle)", s.anonymizePii) { v -> vm.update { it.copy(anonymizePii = v) } }
            }
        }

        // ---- Recording profile ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recording Profile", style = MaterialTheme.typography.titleMedium)
                Text("Video quality", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoQuality.entries.forEach { q ->
                        FilterChip(selected = s.videoQuality == q, onClick = { vm.update { it.copy(videoQuality = q) } }, label = { Text(q.name) })
                    }
                }
                stepperRow("Segment length", "${s.segmentSeconds}s",
                    onMinus = { vm.update { it.copy(segmentSeconds = (it.segmentSeconds - 30).coerceAtLeast(30)) } },
                    onPlus = { vm.update { it.copy(segmentSeconds = it.segmentSeconds + 30) } })
                stepperRow("Segment size cap", "${s.segmentMb} MB",
                    onMinus = { vm.update { it.copy(segmentMb = (it.segmentMb - 256).coerceAtLeast(256)) } },
                    onPlus = { vm.update { it.copy(segmentMb = it.segmentMb + 256) } })
                stepperRow("Keep last N trips", "${s.keepLastNTrips}",
                    onMinus = { vm.update { it.copy(keepLastNTrips = (it.keepLastNTrips - 1).coerceAtLeast(0)) } },
                    onPlus = { vm.update { it.copy(keepLastNTrips = it.keepLastNTrips + 1) } })
                toggle("LZ4 compression", s.compressionEnabled) { v -> vm.update { it.copy(compressionEnabled = v) } }
                toggle("Auto-upload after recording", s.autoUpload) { v -> vm.update { it.copy(autoUpload = v) } }
                toggle("Embed video in MCAP (experimental)", s.embedVideo) { v -> vm.update { it.copy(embedVideo = v) } }
            }
        }

        // ---- Cloud upload ----
        CloudSection(vm)
    }
}

@Composable
private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun stepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Button(onClick = onMinus) { Text("–") }
        Text(value, Modifier.padding(horizontal = 12.dp))
        Button(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun CloudSection(vm: SettingsViewModel) {
    val existing = remember { vm.load(CloudProvider.AWS_S3) }
    var endpoint by remember { mutableStateOf(existing?.endpoint ?: "https://s3.us-east-1.amazonaws.com") }
    var region by remember { mutableStateOf(existing?.region ?: "us-east-1") }
    var bucket by remember { mutableStateOf(existing?.bucket ?: "") }
    var accessKey by remember { mutableStateOf(existing?.accessKey ?: "") }
    var secretKey by remember { mutableStateOf(existing?.secretKey ?: "") }
    var pathStyle by remember { mutableStateOf(existing?.pathStyle ?: false) }
    var saved by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloud Upload (S3 / MinIO)", style = MaterialTheme.typography.titleMedium)
            Text("Credentials are encrypted with an AndroidKeystore master key.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, { endpoint = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Endpoint") }, singleLine = true)
            OutlinedTextField(region, { region = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Region") }, singleLine = true)
            OutlinedTextField(bucket, { bucket = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Bucket") }, singleLine = true)
            OutlinedTextField(accessKey, { accessKey = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Access key") }, singleLine = true)
            OutlinedTextField(secretKey, { secretKey = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Secret key") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pathStyle, onCheckedChange = { pathStyle = it; saved = false })
                Text("  Path-style addressing (MinIO)")
            }
            Button(
                onClick = {
                    vm.save(CloudProvider.AWS_S3, endpoint, region, bucket, accessKey, secretKey, pathStyle)
                    vm.save(CloudProvider.MINIO, endpoint, region, bucket, accessKey, secretKey, pathStyle)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Saved ✓" else "Save credentials") }
        }
    }
}

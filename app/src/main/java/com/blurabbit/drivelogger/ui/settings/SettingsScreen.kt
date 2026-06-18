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
import com.blurabbit.drivelogger.domain.model.CloudProvider

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val existing = remember { vm.load(CloudProvider.AWS_S3) }
    var endpoint by remember { mutableStateOf(existing?.endpoint ?: "https://s3.us-east-1.amazonaws.com") }
    var region by remember { mutableStateOf(existing?.region ?: "us-east-1") }
    var bucket by remember { mutableStateOf(existing?.bucket ?: "") }
    var accessKey by remember { mutableStateOf(existing?.accessKey ?: "") }
    var secretKey by remember { mutableStateOf(existing?.secretKey ?: "") }
    var pathStyle by remember { mutableStateOf(existing?.pathStyle ?: false) }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cloud Upload (S3 / MinIO)", style = MaterialTheme.typography.titleMedium)
        Text("Credentials are encrypted with an AndroidKeystore master key.",
            style = MaterialTheme.typography.bodySmall)

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
                // Persist under both S3 and MinIO so either provider key resolves these creds.
                vm.save(CloudProvider.AWS_S3, endpoint, region, bucket, accessKey, secretKey, pathStyle)
                vm.save(CloudProvider.MINIO, endpoint, region, bucket, accessKey, secretKey, pathStyle)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved ✓" else "Save credentials") }
    }
}

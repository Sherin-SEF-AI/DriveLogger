package com.blurabbit.drivelogger.ui.settings

import androidx.lifecycle.ViewModel
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.upload.CloudConfig
import com.blurabbit.drivelogger.upload.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentials: CredentialStore,
) : ViewModel() {

    fun load(provider: CloudProvider): CloudConfig? = credentials.load(provider)

    fun save(
        provider: CloudProvider, endpoint: String, region: String, bucket: String,
        accessKey: String, secretKey: String, pathStyle: Boolean,
    ) {
        credentials.save(
            CloudConfig(provider, endpoint, region, bucket, accessKey, secretKey, pathStyle),
        )
    }
}

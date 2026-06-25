package com.blurabbit.drivelogger.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.drivelogger.domain.model.AppSettings
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.repository.SettingsRepository
import com.blurabbit.drivelogger.upload.CloudConfig
import com.blurabbit.drivelogger.upload.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentials: CredentialStore,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Apply a transform to the current settings and persist. */
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform(settingsRepo.get())) }
    }

    fun load(provider: CloudProvider): CloudConfig? = credentials.load(provider)

    fun save(
        provider: CloudProvider, endpoint: String, region: String, bucket: String,
        accessKey: String, secretKey: String, pathStyle: Boolean,
    ) {
        credentials.save(CloudConfig(provider, endpoint, region, bucket, accessKey, secretKey, pathStyle))
    }
}

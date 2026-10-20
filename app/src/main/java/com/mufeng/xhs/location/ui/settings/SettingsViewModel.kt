package com.mufeng.xhs.location.ui.settings

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mufeng.xhs.location.data.repository.LocationRepository
import com.mufeng.xhs.location.data.datastore.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val settings = locationRepository.getAppSettings()
            _uiState.value = _uiState.value.copy(settings = settings)
        }
    }

    fun onAutoRestoreChanged(enabled: Boolean) {
        viewModelScope.launch {
            val settings = locationRepository.getAppSettings()
            val updated = settings.copy(autoRestoreEnabled = enabled)
            locationRepository.saveAppSettings(updated)
            _uiState.value = _uiState.value.copy(settings = updated)
        }
    }

    fun markFirstLaunchCompleted() {
        viewModelScope.launch {
            locationRepository.markFirstLaunchCompleted()
            _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(firstLaunchCompleted = true))
        }
    }

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val isLoading: Boolean = true
    )
}

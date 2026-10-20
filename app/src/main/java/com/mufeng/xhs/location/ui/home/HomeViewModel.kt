package com.mufeng.xhs.location.ui.home

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mufeng.xhs.location.data.repository.LocationRepository
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.location.DriftEngine
import com.mufeng.xhs.location.location.MockLocationManager
import com.mufeng.xhs.location.permission.PermissionManager
import com.mufeng.xhs.location.service.LocationForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val driftEngine: DriftEngine,
    private val mockLocationManager: MockLocationManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            _uiState.value = _uiState.value.copy(locationConfig = config, isLoading = false)
        }
    }

    fun onStartClicked() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            if (config.enabled) return@launch

            val state = PermissionManager.checkPermissionState(context, mockLocationManager)
            if (!state.isAllGranted) {
                _uiState.value = _uiState.value.copy(showPermissionGuide = true)
                return@launch
            }

            val lat = config.latitude
            val lon = config.longitude
            val acc = config.accuracy
            val address = formatAddress(lat, lon)

            context.startService(
                LocationForegroundService.createStartIntent(context, lat, lon, acc, address)
            )

            _uiState.value = _uiState.value.copy(isRunning = true, showPermissionGuide = false)
        }
    }

    fun onStopClicked() {
        context.startService(LocationForegroundService.createStopIntent(context))
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    private fun formatAddress(lat: Double, lon: Double): String {
        return String.format("%.4f, %.4f", lat, lon)
    }

    data class UiState(
        val locationConfig: LocationConfig = LocationConfig(),
        val isRunning: Boolean = false,
        val isLoading: Boolean = true,
        val showPermissionGuide: Boolean = false
    )
}

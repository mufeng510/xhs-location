package com.mufeng.xhs.location.ui.home

import android.util.Log
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.data.repository.LocationRepository
import com.mufeng.xhs.location.location.DriftEngine
import com.mufeng.xhs.location.location.MockLocationManager
import com.mufeng.xhs.location.map.OfflinePlaceDatabase
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
    private val offlinePlaceDatabase: OfflinePlaceDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            Log.d("HomeViewModel", "load() config: lat=${config.latitude}, lon=${config.longitude}, enabled=${config.enabled}")
            _uiState.value = _uiState.value.copy(locationConfig = config, isLoading = false)
        }
    }

    fun onStartClicked() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            if (config.enabled) return@launch

            val state = PermissionManager.checkPermissionState(context, mockLocationManager)
            Log.d("HomeViewModel", "onStartClicked: developerOptions=${state.developerOptionsEnabled}, mockAppSet=${state.mockAppSet}, mockPerm=${state.mockLocationPermissionGranted}, isAllGranted=${state.isAllGranted}")
            if (!state.isAllGranted) {
                _uiState.value = _uiState.value.copy(showPermissionGuide = true)
                return@launch
            }

            val lat = config.latitude
            val lon = config.longitude
            val acc = config.accuracy
            val address = formatAddress(lat, lon)

            // 持久化 enabled 状态，供开机自恢复
            locationRepository.saveLocationConfig(
                config.copy(enabled = true, lastUpdated = System.currentTimeMillis())
            )

            context.startService(
                LocationForegroundService.createStartIntent(context, lat, lon, acc, address)
            )

            _uiState.value = _uiState.value.copy(isRunning = true, showPermissionGuide = false)
        }
    }

    fun onStopClicked() {
        viewModelScope.launch {
            val config = locationRepository.getLocationConfig()
            locationRepository.saveLocationConfig(
                config.copy(enabled = false, lastUpdated = System.currentTimeMillis())
            )
        }
        context.startService(LocationForegroundService.createStopIntent(context))
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    fun saveSelectedLocation(lat: Double, lon: Double, address: String, city: String = "", timezone: String = "", country: String = "") {
        // 先同步更新 UI 状态，避免导航返回时位置尚未显示
        val current = _uiState.value.locationConfig
        _uiState.value = _uiState.value.copy(
            locationConfig = LocationConfig(
                latitude = lat,
                longitude = lon,
                accuracy = current.accuracy,
                enabled = current.enabled,
                lastUpdated = System.currentTimeMillis(),
                city = city,
                timezone = timezone,
                country = country
            )
        )
        // 异步持久化到数据存储
        viewModelScope.launch {
            locationRepository.saveLocationConfig(
                LocationConfig(
                    latitude = lat,
                    longitude = lon,
                    accuracy = current.accuracy,
                    enabled = current.enabled,
                    lastUpdated = System.currentTimeMillis(),
                    city = city,
                    timezone = timezone,
                    country = country
                )
            )
        }
    }

    /**
     * 根据城市名从离线数据库搜索匹配的地点。
     */
    fun searchCity(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        val results = offlinePlaceDatabase.search(query)
        _uiState.value = _uiState.value.copy(searchResults = results)
    }

    /**
     * 将选中的城市坐标应用到模拟定位配置。
     */
    fun applyCity(lat: Double, lon: Double, city: String) {
        val current = _uiState.value.locationConfig
        saveSelectedLocation(lat, lon, "$city, 中国", city = city)
        _uiState.value = _uiState.value.copy(
            searchResults = emptyList(),
            cityInput = ""
        )
    }

    private fun formatAddress(lat: Double, lon: Double): String {
        return String.format("%.4f, %.4f", lat, lon)
    }

    data class UiState(
        val locationConfig: LocationConfig = LocationConfig(),
        val isRunning: Boolean = false,
        val isLoading: Boolean = true,
        val showPermissionGuide: Boolean = false,
        /** 城市搜索结果 */
        val searchResults: List<OfflinePlaceDatabase.OfflinePlace> = emptyList(),
        /** 城市输入框文本 */
        val cityInput: String = ""
    )
}

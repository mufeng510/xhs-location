package com.mufeng.xhs.location.ui.map

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mufeng.xhs.location.map.SearchService
import com.mufeng.xhs.location.map.NominatimPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MapViewModel @Inject constructor(
    private val searchService: SearchService
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onQueryChanged(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(result = SearchService.SearchResult.Loading, places = emptyList())
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(result = SearchService.SearchResult.Loading)
            val result = searchService.searchPlaces(query)
            when (result) {
                is SearchService.SearchResult.Success -> {
                    _uiState.value = _uiState.value.copy(result = result, places = result.places)
                }
                is SearchService.SearchResult.Error -> {
                    _uiState.value = _uiState.value.copy(result = result)
                }
                is SearchService.SearchResult.Loading -> {}
            }
        }
    }

    fun selectPlace(place: NominatimPlace) {
        val (lat, lon) = place.toLatLon()
        _uiState.value = _uiState.value.copy(selectedLat = lat, selectedLon = lon, selectedAddress = place.display_name)
    }

    /** 地图点击选点：直接设置经纬度和地址显示 */
    fun selectPlace(lat: Double, lon: Double, address: String) {
        _uiState.value = _uiState.value.copy(selectedLat = lat, selectedLon = lon, selectedAddress = address)
    }

    data class UiState(
        val query: String = "",
        val places: List<NominatimPlace> = emptyList(),
        val result: SearchService.SearchResult = SearchService.SearchResult.Loading,
        val selectedLat: Double = 0.0,
        val selectedLon: Double = 0.0,
        val selectedAddress: String = ""
    )
}

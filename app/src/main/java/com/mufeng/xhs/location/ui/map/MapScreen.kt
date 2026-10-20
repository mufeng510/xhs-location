package com.mufeng.xhs.location.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.map.NominatimPlace
import com.mufeng.xhs.location.map.SearchService

@Composable
fun MapScreen(onConfirm: (Double, Double, String) -> Unit) {
    val viewModel = hiltViewModel<MapViewModel>()
    val uiState = viewModel.uiState.collectAsState().value

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        TextField(
            value = uiState.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            label = { Text(text = stringResource(R.string.search_hint)) },
            placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp)
        )

        // 地图占位区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "地图区域（MapLibre 集成中）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (val result = uiState.result) {
            is SearchService.SearchResult.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(alignment = Alignment.CenterHorizontally))
            }
            is SearchService.SearchResult.Error -> {
                Text(
                    text = result.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 8.dp)
                )
            }
            is SearchService.SearchResult.Success -> {
                if (result.places.isEmpty() && uiState.query.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.search_no_result),
                        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 8.dp)
                    )
                } else if (result.places.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(horizontal = 8.dp)
                    ) {
                        result.places.forEach { place ->
                            Text(
                                text = place.display_name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            Text(
                                text = "${place.lat}, ${place.lon}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (uiState.selectedLat != 0.0 || uiState.selectedLon != 0.0) {
                    onConfirm(uiState.selectedLat, uiState.selectedLon, uiState.selectedAddress)
                }
            },
            enabled = uiState.selectedLat != 0.0 || uiState.selectedLon != 0.0,
            modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(bottom = 16.dp)
        ) {
            Text(text = stringResource(R.string.confirm_select))
        }
    }
}

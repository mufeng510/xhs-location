package com.mufeng.xhs.location.ui.map

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    // 离线手动输入经纬度的临时状态
    var manualLat by remember { mutableStateOf("") }
    var manualLon by remember { mutableStateOf("") }

    // 启动 MapActivity 并接收返回结果
    val mapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@rememberLauncherForActivityResult
            val lat = data.getDoubleExtra(MapActivity.EXTRA_LATITUDE, 0.0)
            val lon = data.getDoubleExtra(MapActivity.EXTRA_LONGITUDE, 0.0)
            val address = data.getStringExtra(MapActivity.EXTRA_ADDRESS) ?: ""
            if (lat != 0.0 && lon != 0.0) {
                viewModel.selectPlace(lat, lon, address)
            }
        }
    }

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

        // 搜索结果（放在地图之前，避免被推到屏幕外）
        // 只有在用户输入了查询且正在加载时才显示加载圈，避免进入页面就显示转圈
        when (val result = uiState.result) {
            is SearchService.SearchResult.Loading -> {
                if (uiState.query.isNotBlank()) {
                    CircularProgressIndicator(modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 8.dp))
                }
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
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = { viewModel.selectPlace(place) }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = place.display_name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                    )
                                    Text(
                                        text = "${place.lat}, ${place.lon}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 地图区域 - 点击进入 MapActivity 查看和选点
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            onClick = {
                mapLauncher.launch(MapActivity.createIntent(context))
            }
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "地图区域",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击进入地图选点（MapLibre）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 离线手动输入经纬度（无网络时的兜底）
        Card(modifier = Modifier.fillMaxWidth(0.92f).padding(horizontal = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.manual_input_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_lat),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(40.dp)
                    )
                    BasicTextField(
                        value = manualLat,
                        onValueChange = { manualLat = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_lon),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(40.dp)
                    )
                    BasicTextField(
                        value = manualLon,
                        onValueChange = { manualLon = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val lat = manualLat.toDoubleOrNull() ?: 0.0
                        val lon = manualLon.toDoubleOrNull() ?: 0.0
                        if (lat != 0.0 && lon != 0.0) {
                            viewModel.selectPlace(lat, lon, "($lat, $lon)")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_apply_manual))
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

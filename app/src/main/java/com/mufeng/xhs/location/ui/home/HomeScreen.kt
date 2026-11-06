package com.mufeng.xhs.location.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.map.OfflinePlaceDatabase
import com.mufeng.xhs.location.permission.PermissionManager

@Composable
fun HomeScreen(onMapClicked: () -> Unit, onSettingsClicked: () -> Unit) {
    val context = LocalContext.current
    val viewModel = hiltViewModel<HomeViewModel>()
    val uiState = viewModel.uiState.collectAsState().value

    // 城市/地区输入框：输入城市名可从离线数据库搜索坐标，选中后一键应用到模拟定位
    var cityText by remember { mutableStateOf(uiState.locationConfig.city) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LaunchedEffect(Unit) {
            viewModel.load()
            cityText = uiState.locationConfig.city
        }

        // 城市输入框变更时：1) 记录城市标签；2) 触发离线搜索
        LaunchedEffect(cityText) {
            if (cityText.isNotBlank()) {
                viewModel.searchCity(cityText)
            } else {
                viewModel.searchCity("")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (uiState.isRunning) stringResource(R.string.status_running) else stringResource(R.string.status_stopped),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (uiState.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val locationText = if (uiState.locationConfig.latitude == 0.0 && uiState.locationConfig.longitude == 0.0) {
                    stringResource(R.string.location_unknown)
                } else {
                    "(${uiState.locationConfig.latitude.toString().take(7)}, ${uiState.locationConfig.longitude.toString().take(7)})"
                }
                Text(
                    text = "${stringResource(R.string.current_location)}: $locationText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = cityText,
                    onValueChange = { cityText = it },
                    label = { Text(text = stringResource(R.string.label_city)) },
                    placeholder = { Text(text = stringResource(R.string.city_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.city_tip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                // 城市搜索结果：点击可将该城市的坐标应用到模拟定位
                if (uiState.searchResults.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.city_search_results),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                        uiState.searchResults.forEach { place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = {
                                        cityText = place.name
                                        viewModel.applyCity(place.lat, place.lon, place.name)
                                    }),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = place.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "(${place.lat}, ${place.lon})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.onStartClicked() },
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Text(text = stringResource(R.string.btn_start))
            }
            Button(
                onClick = { viewModel.onStopClicked() },
                enabled = uiState.isRunning,
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Text(text = stringResource(R.string.btn_stop))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onMapClicked,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Text(text = stringResource(R.string.btn_map))
        }

        Button(
            onClick = onSettingsClicked,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Text(text = stringResource(R.string.btn_settings))
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.showPermissionGuide) {
            PermissionGuideCard()
        }
    }
}

@Composable
private fun PermissionGuideCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(0.88f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_guide_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { PermissionManager.openDeveloperSettings(context) }) {
                Text(text = stringResource(R.string.btn_open_settings))
            }
        }
    }
}

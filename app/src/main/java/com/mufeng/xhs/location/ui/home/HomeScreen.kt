package com.mufeng.xhs.location.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.data.datastore.LocationConfig

@Composable
fun HomeScreen(onMapClicked: () -> Unit) {
    val viewModel = hiltViewModel<HomeViewModel>()
    val uiState = viewModel.uiState.collectAsState().value

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.showPermissionGuide) {
            PermissionGuideCard()
        }
    }
}

@Composable
private fun PermissionGuideCard() {
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
            Button(onClick = { /* open system settings */ }) {
                Text(text = stringResource(R.string.btn_open_settings))
            }
        }
    }
}

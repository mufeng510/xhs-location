package com.mufeng.xhs.location.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mufeng.xhs.location.vpn.ProxyViewModel
import com.mufeng.xhs.location.vpn.ProxySettingsScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val viewModel = hiltViewModel<ProxyViewModel>()

    NavHost(navController = navController, startDestination = "vpn_settings") {
        composable("vpn_settings") {
            ProxySettingsScreen()
        }
    }
}

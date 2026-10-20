package com.mufeng.xhs.location.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mufeng.xhs.location.ui.home.HomeScreen
import com.mufeng.xhs.location.ui.map.MapScreen
import com.mufeng.xhs.location.ui.settings.SettingsScreen
import com.mufeng.xhs.location.ui.components.PermissionGuideScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onMapClicked = { navController.navigate("map") })
        }
        composable("map") {
            MapScreen(onConfirm = { lat, lon, address ->
                navController.popBackStack()
            })
        }
        composable("settings") {
            SettingsScreen()
        }
        composable("permission_guide") {
            PermissionGuideScreen()
        }
    }
}

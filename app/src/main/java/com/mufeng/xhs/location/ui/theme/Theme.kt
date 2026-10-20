package com.mufeng.xhs.location.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mufeng.xhs.location.R

@Composable
fun XhsLocationTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF6200EE),
        secondary = Color(0xFF49668A),
        background = Color.White,
        surface = Color.White,
        error = Color(0xFFB3261E)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

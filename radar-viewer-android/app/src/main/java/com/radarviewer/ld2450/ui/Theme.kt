package com.radarviewer.ld2450.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RadarColors {
    val background = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val fan = Color(0xFF0E2A1C)
    val grid = Color(0xFF1F7A4D)
    val muted = Color(0xFF8B949E)
    val home = Color(0xFF2FBF7F)
    val remote = Color(0xFF00E5FF)
    val searching = Color(0xFFFFC107)
    val offline = Color(0xFFFF6B6B)
    val targets = listOf(Color(0xFF00E5FF), Color(0xFFFFEB3B), Color(0xFFFF4DFF))
}

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RadarColors.home,
            onPrimary = Color(0xFF06210F),
            background = RadarColors.background,
            surface = RadarColors.surface,
            surfaceVariant = RadarColors.surface,
        ),
        content = content,
    )
}

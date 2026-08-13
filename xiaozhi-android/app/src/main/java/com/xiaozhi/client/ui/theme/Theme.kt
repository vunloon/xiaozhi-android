package com.xiaozhi.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandPrimary = Color(0xFF3B6FE6)
private val BrandPrimaryDark = Color(0xFF1F3F8F)
private val BrandSecondary = Color(0xFFFF6B35)
private val BrandBackgroundDark = Color(0xFF0F1424)
private val BrandSurfaceDark = Color(0xFF1A2138)
private val BrandBackgroundLight = Color(0xFFF4F6FB)
private val BrandSurfaceLight = Color(0xFFFFFFFF)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = BrandSecondary,
    onSecondary = Color.White,
    background = BrandBackgroundDark,
    onBackground = Color.White,
    surface = BrandSurfaceDark,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF232B45),
    onSurfaceVariant = Color(0xFFB8C0DC),
)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = BrandSecondary,
    onSecondary = Color.White,
    background = BrandBackgroundLight,
    onBackground = Color(0xFF111522),
    surface = BrandSurfaceLight,
    onSurface = Color(0xFF111522),
)

@Composable
fun XiaozhiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Force dark palette — the app is designed for low-light voice-chat usage.
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

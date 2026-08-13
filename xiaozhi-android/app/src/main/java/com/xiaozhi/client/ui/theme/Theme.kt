package com.xiaozhi.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cute Soft Blue Theme Colors
val PastelBlueBg = Color(0xFFE8F1F5)       // Soft background
val CloudWhite = Color(0xFFFFFFFF)         // Input field background
val PastelBlueBubble = Color(0xFFD0E8F2)   // Incoming message bubble
val ReceivedBubble = Color(0xFFE2E8F0)     // Incoming message bubble (light)
val SentBubble = Color(0xFFC7D2FE)         // Sent message bubble (periwinkle)
val DeepBlueText = Color(0xFF2C3E50)       // Readable text color
val HeartAccentBlue = Color(0xFF5390D9)    // Icons & active buttons
val TopBarBlue = Color(0xFFD6E6F2)         // Top bar / input bar background

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
    primary = HeartAccentBlue,
    onPrimary = Color.White,
    primaryContainer = SentBubble,
    onPrimaryContainer = DeepBlueText,
    secondary = Color(0xFF8FAAD8),
    onSecondary = Color.White,
    secondaryContainer = PastelBlueBubble,
    onSecondaryContainer = DeepBlueText,
    tertiary = Color(0xFF4CAF50),
    onTertiary = Color.White,
    background = PastelBlueBg,
    onBackground = DeepBlueText,
    surface = CloudWhite,
    onSurface = DeepBlueText,
    surfaceVariant = ReceivedBubble,
    onSurfaceVariant = Color(0xFF5B6B7C),
)

@Composable
fun XiaozhiTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Default to the pastel-blue light palette. Pass darkTheme = true to keep
    // the original low-light voice-chat palette.
    val colors = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

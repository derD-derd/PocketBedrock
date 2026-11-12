package com.pocketbedrock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketbedrock.app.data.ThemeMode

private val BedrockGreen = Color(0xFF57C793)
private val BedrockGreenDim = Color(0xFF2E6E52)
private val DarkBackground = Color(0xFF0F1115)
private val DarkSurface = Color(0xFF171A21)
private val DarkSurfaceVariant = Color(0xFF22262F)

private val DarkColors = darkColorScheme(
    primary = BedrockGreen,
    onPrimary = Color(0xFF04130C),
    primaryContainer = BedrockGreenDim,
    onPrimaryContainer = Color(0xFFB9F2D8),
    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF0A1930),
    background = DarkBackground,
    onBackground = Color(0xFFE4E7EC),
    surface = DarkSurface,
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA9B0BC),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0505),
    errorContainer = Color(0xFF3D1414),
    outline = Color(0xFF3A404C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E7A56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBD2),
    onPrimaryContainer = Color(0xFF0B2A1D),
)

/**
 * Material 3 with dark theme by default. No dynamic color: consistency and a
 * predictable, low-power dark palette matter more here than wallpaper matching.
 * Animations are kept minimal by Compose defaults (no custom transitions).
 */
@Composable
fun PocketBedrockTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

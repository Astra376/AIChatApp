package com.example.aichat.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.aichat.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF171A1F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF262A30),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3B4048),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF14171B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14171B),
    surfaceVariant = Color(0xFFE7E9EC),
    onSurfaceVariant = Color(0xFF727A84),
    outline = Color(0xFFBEC3C9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8EBEF),
    onPrimary = Color(0xFF121418),
    secondary = Color(0xFFD3D8DE),
    onSecondary = Color(0xFF14171B),
    tertiary = Color(0xFFB8BFC8),
    onTertiary = Color(0xFF14171B),
    background = Color(0xFF070809),
    onBackground = Color(0xFFF1F3F5),
    surface = Color(0xFF0D0F11),
    onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF121518),
    onSurfaceVariant = Color(0xFF8D95A0),
    outline = Color(0xFF25282D),
    outlineVariant = Color(0xFF1C1F23),
    surfaceDim = Color(0xFF070809),
    surfaceBright = Color(0xFF24282D),
    surfaceContainerLowest = Color(0xFF050607),
    surfaceContainerLow = Color(0xFF0B0D0F),
    surfaceContainer = Color(0xFF101214),
    surfaceContainerHigh = Color(0xFF171A1E),
    surfaceContainerHighest = Color(0xFF1E2227)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalRippleConfiguration provides null, content = content)
        }
    )
}

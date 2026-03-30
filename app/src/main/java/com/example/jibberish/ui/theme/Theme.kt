package com.example.jibberish.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = JibberishViolet,
    onPrimary = Color.White,
    primaryContainer = JibberishVioletDark,
    onPrimaryContainer = JibberishVioletLight,
    secondary = JibberishRose,
    onSecondary = Color.White,
    secondaryContainer = JibberishRoseDark,
    onSecondaryContainer = Color(0xFFFFD6E7),
    tertiary = JibberishVioletLight,
    onTertiary = OnVioletDark,
    tertiaryContainer = JibberishVioletDark,
    onTertiaryContainer = JibberishVioletLight,
    background = JibberishBlack,
    onBackground = Color(0xFFE8E8F0),
    surface = JibberishDarkGray,
    onSurface = Color(0xFFE8E8F0),
    surfaceVariant = JibberishSurfaceHigh,
    onSurfaceVariant = Color(0xFFB0B0C8),
    surfaceContainerHigh = JibberishSurfaceHigh,
    error = StatusError,
    onError = Color.White,
    errorContainer = Color(0xFF5C2500),
    onErrorContainer = Color(0xFFFFDBCC),
    outline = Color(0xFF3A3A52),
    outlineVariant = Color(0xFF2A2A3E)
)

private val LightColorScheme = lightColorScheme(
    primary = JibberishVioletDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E0FF),
    onPrimaryContainer = OnVioletDark,
    secondary = JibberishRoseDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD6E7),
    onSecondaryContainer = OnRoseDark,
    tertiary = JibberishViolet,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E0FF),
    onTertiaryContainer = OnVioletDark,
    background = JibberishLightBg,
    onBackground = Color(0xFF1C1B1F),
    surface = JibberishLightSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0EFF8),
    onSurfaceVariant = Color(0xFF49454F),
    error = StatusError,
    onError = Color.White,
    errorContainer = Color(0xFFFFDBCC),
    onErrorContainer = Color(0xFF5C2500)
)

@Composable
fun JibberishTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

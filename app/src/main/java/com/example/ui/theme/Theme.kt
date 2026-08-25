package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HighDensityColorScheme = lightColorScheme(
    primary = HdPrimary,
    onPrimary = HdOnPrimary,
    primaryContainer = HdPrimaryContainer,
    onPrimaryContainer = HdOnPrimaryContainer,
    secondary = HdSecondary,
    onSecondary = Color.White,
    secondaryContainer = HdSecondaryContainer,
    onSecondaryContainer = HdOnSecondaryContainer,
    tertiary = HdTertiary,
    onTertiary = Color.White,
    background = HdBackground,
    onBackground = HdTextPrimary,
    surface = HdSurface,
    onSurface = HdTextPrimary,
    surfaceVariant = HdSurfaceVariant,
    onSurfaceVariant = HdTextSecondary,
    outline = HdBorder,
    outlineVariant = HdBorderSubtle,
    error = HdErrorText,
    onError = Color.White
)

private val HighDensityDarkColorScheme = darkColorScheme(
    primary = HdAccentPurpleLight,
    onPrimary = HdAccentPurpleDark,
    primaryContainer = HdPrimary,
    onPrimaryContainer = HdPrimaryContainer,
    secondary = HdSecondaryContainer,
    onSecondary = HdOnSecondaryContainer,
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF1D1B20),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HighDensityDarkColorScheme else HighDensityColorScheme,
        typography = Typography,
        content = content
    )
}


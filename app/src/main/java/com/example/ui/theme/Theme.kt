package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = NavyPrimary,
    secondary = TealSecondary,
    onSecondary = Color.White,
    secondaryContainer = TealContainer,
    onSecondaryContainer = TealSecondary,
    tertiary = AmberAccent,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF78350F),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceCardLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceMutedLight,
    error = StatusError,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF0B192C),
    primaryContainer = NavyPrimaryLight,
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceCardDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMutedDark,
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun MiInventarioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

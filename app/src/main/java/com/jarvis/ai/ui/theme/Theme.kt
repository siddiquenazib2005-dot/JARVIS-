package com.jarvis.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val JarvisColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = Color(0xFF252525),
    primaryContainer = Color(0xFF2A2A33),
    onPrimaryContainer = Color(0xFFD6D9F5),
    secondary = Color(0xFF8E8E9A),
    onSecondary = Color(0xFF1C1C20),
    secondaryContainer = Color(0xFF2A2A33),
    onSecondaryContainer = Color(0xFFDFDFE6),
    tertiary = VioletPulse,
    onTertiary = Color(0xFF1C1C20),
    tertiaryContainer = Color(0xFF342F45),
    onTertiaryContainer = Color(0xFFE6E0F5),
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = MidnightBlue,
    onSurface = TextPrimary,
    surfaceVariant = PanelBlue,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF2A2A33),
    error = ErrorRed,
    onError = Color(0xFF2B0009)
)

private val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalJarvisColors provides ExtendedColors()) {
        MaterialTheme(
            colorScheme = JarvisColorScheme,
            typography = JarvisTypography,
            shapes = JarvisShapes,
            content = content
        )
    }
}

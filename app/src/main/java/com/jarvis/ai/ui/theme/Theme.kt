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
    primary = CyanGlow,
    onPrimary = Color(0xFF00232A),
    primaryContainer = Color(0xFF00495A),
    onPrimaryContainer = Color(0xFFB8F6FF),
    secondary = ElectricBlue,
    onSecondary = Color(0xFF00296B),
    secondaryContainer = Color(0xFF123B78),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = VioletPulse,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF3A2A75),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = MidnightBlue,
    onSurface = TextPrimary,
    surfaceVariant = PanelBlue,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF33415A),
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

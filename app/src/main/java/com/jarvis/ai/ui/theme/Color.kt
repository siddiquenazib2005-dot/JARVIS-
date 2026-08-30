package com.jarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DeepSpace = Color(0xFF05070F)
val MidnightBlue = Color(0xFF0A0E1A)
val PanelBlue = Color(0xFF101827)
val CyanGlow = Color(0xFF00E5FF)
val ElectricBlue = Color(0xFF2979FF)
val VioletPulse = Color(0xFF7C4DFF)
val TextPrimary = Color(0xFFEAF6FF)
val TextSecondary = Color(0xFF8FA3BF)
val ErrorRed = Color(0xFFFF5370)

data class ExtendedColors(
    val cyan: Color = CyanGlow,
    val electricBlue: Color = ElectricBlue,
    val violet: Color = VioletPulse,
    val gridLine: Color = CyanGlow.copy(alpha = 0.05f),
    val particle: Color = CyanGlow,
    val jarvisBubble: Color = Color(0xFF0C1626).copy(alpha = 0.86f),
    val jarvisBorder: Color = CyanGlow.copy(alpha = 0.22f),
    val userBrush: Brush = Brush.linearGradient(
        listOf(Color(0xFF00B8FF), Color(0xFF2A62FF), VioletPulse)
    ),
    val onUserBubble: Color = Color.White
)

val LocalJarvisColors = staticCompositionLocalOf { ExtendedColors() }

@Composable
fun MaterialTheme.extended(): ExtendedColors = LocalJarvisColors.current

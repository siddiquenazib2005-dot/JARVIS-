package com.jarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DeepSpace = Color(0xFF050506)
val MidnightBlue = Color(0xFF100A0C)
val PanelBlue = Color(0xFF1A0B0E)
val CyanGlow = Color(0xFFFF1744)
val ElectricBlue = Color(0xFFFF2D55)
val VioletPulse = Color(0xFFFF6B00)
val TextPrimary = Color(0xFFF2F2F3)
val TextSecondary = Color(0xFF9E9EA6)
val ErrorRed = Color(0xFFFF5E57)

data class ExtendedColors(
    val cyan: Color = CyanGlow,
    val electricBlue: Color = ElectricBlue,
    val violet: Color = VioletPulse,
    val gridLine: Color = Color.White.copy(alpha = 0.03f),
    val particle: Color = CyanGlow,
    val jarvisBubble: Color = Color(0xFF1D1D22).copy(alpha = 0.72f),
    val jarvisBorder: Color = Color.White.copy(alpha = 0.06f),
    val userBrush: Brush = Brush.linearGradient(
        listOf(Color(0xFFFF1744), Color(0xFFFF6B00))
    ),
    val onUserBubble: Color = Color.White
)

val LocalJarvisColors = staticCompositionLocalOf { ExtendedColors() }

@Composable
fun MaterialTheme.extended(): ExtendedColors = LocalJarvisColors.current

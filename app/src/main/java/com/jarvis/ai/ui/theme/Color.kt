package com.jarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DeepSpace = Color(0xFF0F0F12)
val MidnightBlue = Color(0xFF16161A)
val PanelBlue = Color(0xFF1E1E23)
val CyanGlow = Color(0xFF7C95FF)
val ElectricBlue = Color(0xFF5B7CFF)
val VioletPulse = Color(0xFF9B7CFF)
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
        listOf(Color(0xFF5B7CFF), Color(0xFF7C95FF))
    ),
    val onUserBubble: Color = Color.White
)

val LocalJarvisColors = staticCompositionLocalOf { ExtendedColors() }

@Composable
fun MaterialTheme.extended(): ExtendedColors = LocalJarvisColors.current

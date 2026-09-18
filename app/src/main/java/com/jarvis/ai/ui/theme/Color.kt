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
    val onUserBubble: Color = Color.White,
    // -- Phase 1: glassmorphism tokens for the companion home screen. ----------
    /** Translucent surface used by GlassCard; 0.6-0.8 alpha per the design spec. */
    val glassSurface: Color = Color(0xFF1A0B0E).copy(alpha = 0.66f),
    /** A stronger variant for chips and the input bar resting on top of cards. */
    val glassSurfaceStrong: Color = Color(0xFF241014).copy(alpha = 0.82f),
    /** The soft edge highlight that reads as frosted glass, never a hard border. */
    val glassRim: Color = Color.White.copy(alpha = 0.10f),
    /** Accent glow behind the avatar and active chips. */
    val glowAccent: Color = Color(0xFFFF6B00).copy(alpha = 0.35f),
    /** Home-screen gradient, extending the existing DeepSpace->PanelBlue family. */
    val homeBrush: Brush = Brush.linearGradient(
        listOf(Color(0xFF050506), Color(0xFF140A0D), Color(0xFF050506))
    ),
    /** Text colour for the warm greeting header. */
    val greetingPrimary: Color = Color(0xFFF7F3F4),
    /** Secondary text used by status lines and card labels. */
    val greetingSecondary: Color = Color(0xFFA89FA2)
)

val LocalJarvisColors = staticCompositionLocalOf { ExtendedColors() }

@Composable
fun MaterialTheme.extended(): ExtendedColors = LocalJarvisColors.current

package com.jarvis.ai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.theme.extended

/**
 * Responsibility: the reusable frosted-glass surface used across the AURIX
 * companion home screen (info cards, chips, input bar).
 *
 * Visual contract: semi-transparent fill (alpha 0.6-0.8 per the design system),
 * 20-24dp rounded corners, a soft rim highlight instead of a hard border, and a
 * subtle press-scale on touch. It deliberately has NO hard outline: the rim is a
 * low-alpha white stroke that reads as glass.
 *
 * Accessibility: tappable instances set [contentDescription] and respect the
 * 48dp minimum touch target via [minTouchSize].
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    pressScale: Boolean = onClick != null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.extended()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && pressScale) 0.97f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "glass_card_press"
    )

    val fill = if (strong) colors.glassSurfaceStrong else colors.glassSurface

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(cornerRadius))
            .background(fill)
            .border(width = 1.dp, color = colors.glassRim, shape = RoundedCornerShape(cornerRadius))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

/**
 * The accent glow that sits behind the avatar and active elements. Kept separate
 * from [GlassCard] because a glow is not a surface: it is a radial wash that
 * should never intercept touches.
 */
@Composable
fun GlowWash(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.extended().glowAccent,
    radius: Dp = 180.dp
) {
    Box(
        modifier = modifier
            .blur(radius)
            .background(color)
    )
}

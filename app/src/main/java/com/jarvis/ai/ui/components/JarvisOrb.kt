package com.jarvis.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import com.jarvis.ai.ui.theme.extended
import kotlin.math.sin

@Composable
fun JarvisOrb(
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    level: Float = 0f
) {
    val palette = MaterialTheme.extended()
    val energy = level.coerceIn(0f, 1f)
    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (active || energy > 0.05f) 3200 else 9000, easing = LinearEasing)
        ),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(if (active) 1300 else 2600), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        palette.cyan.copy(alpha = (0.45f + 0.4f * energy).coerceAtMost(0.85f)),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * (1f + energy * 0.25f)
                ),
                radius = radius * (1f + energy * 0.25f),
                center = center
            )

            val wobbleA = sin(rotation * 6f * DEGREES_TO_RADIANS) * 26f * energy
            rotate(rotation) {
                drawArc(
                    color = palette.cyan,
                    startAngle = wobbleA,
                    sweepAngle = 120f + 36f * energy,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.72f, center.y - radius * 0.72f),
                    size = Size(radius * 1.44f, radius * 1.44f),
                    style = Stroke(width = radius * (0.14f + 0.06f * energy), cap = StrokeCap.Round),
                    alpha = 0.95f
                )
            }

            val wobbleB = sin(rotation * 4.2f * DEGREES_TO_RADIANS + 90f) * 34f * energy
            rotate(-rotation * 1.4f + 180f) {
                drawArc(
                    color = palette.electricBlue,
                    startAngle = 150f + wobbleB,
                    sweepAngle = 80f + 28f * energy,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.55f, center.y - radius * 0.55f),
                    size = Size(radius * 1.1f, radius * 1.1f),
                    style = Stroke(width = radius * 0.10f, cap = StrokeCap.Round),
                    alpha = 0.85f
                )
            }

            val coreRadius = radius * 0.34f * pulse * (1f + 0.5f * energy)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White, palette.cyan, palette.electricBlue),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )
        }
    }
}

private const val DEGREES_TO_RADIANS = 0.017453292f

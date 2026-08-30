package com.jarvis.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.theme.extended
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    val xFraction: Float,
    val yStartFraction: Float,
    val radius: Float,
    val speed: Float,
    val alpha: Float,
    val driftPhase: Float
)

@Composable
fun GlowBackground(modifier: Modifier = Modifier, level: Float = 0f) {
    val palette = MaterialTheme.extended()
    val energy = level.coerceIn(0f, 1f)
    val particles = remember {
        val seeded = Random(20260823L)
        List(PARTICLE_COUNT) {
            Particle(
                xFraction = seeded.nextFloat(),
                yStartFraction = seeded.nextFloat(),
                radius = 1f + seeded.nextFloat() * 2.4f,
                speed = 0.35f + seeded.nextFloat() * 0.75f,
                alpha = 0.12f + seeded.nextFloat() * 0.3f,
                driftPhase = seeded.nextFloat() * 6.28f
            )
        }
    }
    val phase by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing)),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF05070F), Color(0xFF071122), Color(0xFF03050C))
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    palette.cyan.copy(alpha = (0.13f + 0.22f * energy).coerceAtMost(0.35f)),
                    Color.Transparent
                ),
                center = Offset(
                    size.width * (0.14f + 0.06f * sin(phase * TAU)),
                    size.height * 0.05f
                ),
                radius = size.width * 0.9f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    palette.violet.copy(alpha = (0.11f + 0.18f * energy).coerceAtMost(0.3f)),
                    Color.Transparent
                ),
                center = Offset(
                    size.width * (0.88f - 0.06f * sin(phase * TAU)),
                    size.height * 0.97f
                ),
                radius = size.width * 0.85f
            )
        )

        val gridStep = 64.dp.toPx()
        var x = gridStep
        while (x < size.width) {
            drawLine(palette.gridLine, Offset(x, 0f), Offset(x, size.height))
            x += gridStep
        }
        var y = gridStep
        while (y < size.height) {
            drawLine(palette.gridLine, Offset(0f, y), Offset(size.width, y))
            y += gridStep
        }

        particles.forEach { p ->
            val span = size.height + 120f
            val speed = p.speed * (1f + 2.2f * energy)
            val rawY = p.yStartFraction * span - phase * span * speed
            val py = ((rawY % span) + span) % span - 60f
            val px = p.xFraction * size.width + sin(phase * TAU + p.driftPhase) * (16f + 26f * energy)
            drawCircle(
                color = palette.particle.copy(alpha = (p.alpha + 0.25f * energy).coerceAtMost(0.75f)),
                radius = p.radius * (1f + 0.4f * energy),
                center = Offset(px, py)
            )
        }
    }
}

private const val PARTICLE_COUNT = 42
private const val CYCLE_MS = 26000
private const val TAU = 6.28318f

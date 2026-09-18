package com.jarvis.ai.ui.home.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.LifecycleEventObserver
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.jarvis.ai.avatarIdleSpec
import com.jarvis.ai.avatarListeningSpec
import com.jarvis.ai.avatarTalkingSpec
import com.jarvis.ai.ui.theme.extended

/**
 * The avatar's reactive state. Driven by the home view model, which maps the
 * existing assistant/agent execution state onto these values — there is no
 * second source of truth for "is AURIX listening".
 */
enum class AvatarState {
    IDLE, LISTENING, TALKING
}

/**
 * Responsibility: the animated anime-style companion avatar on the home screen.
 *
 * Rendering rules from the design system:
 *  - full-body character, transparent background, roughly half the screen height;
 *  - the idle loop plays continuously and is never static;
 *  - a reactive glow and a breathing scale play while listening/talking;
 *  - Lottie uses hardware rendering and is PAUSED when the screen is stopped, so
 *    a hidden home screen does not drain the battery animating nothing.
 *
 * The three Lottie files under `res/raw/` are placeholders. Drop real anime-style
 * assets from LottieFiles.com in their place — same filenames, no code change:
 *   res/raw/aurix_idle.json
 *   res/raw/aurix_listening.json
 *   res/raw/aurix_talking.json
 *
 * Accessibility: [contentDescription] describes the current state so a screen
 * reader announces "Aurix is listening" rather than "image".
 */
@Composable
fun AvatarView(
    state: AvatarState,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.42f,
    contentDescription: String? = defaultDescription(state)
) {
    val colors = MaterialTheme.extended()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Pause animation work when the home screen is not at least started.
    var isActive by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isActive = true
                Lifecycle.Event.ON_STOP -> isActive = false
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val spec = when (state) {
        AvatarState.IDLE -> avatarIdleSpec()
        AvatarState.LISTENING -> avatarListeningSpec()
        AvatarState.TALKING -> avatarTalkingSpec()
    }

    val composition by rememberLottieComposition(spec)
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        isPlaying = isActive,
        restartOnPlay = false
    )

    // Breathing scale: continuous, subtle, never static even with a placeholder.
    val transition = rememberInfiniteTransition(label = "avatar_breath")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AvatarState.IDLE) 1.02f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == AvatarState.IDLE) 3200 else 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_breath_scale"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (state == AvatarState.IDLE) 0.4f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == AvatarState.IDLE) 3200 else 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_glow"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        // Reactive glow wash behind the character. Touch-transparent: the wash
        // draws under the avatar and never intercepts taps.
        if (state != AvatarState.IDLE) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
            ) {
                drawCircle(color = colors.glowAccent.copy(alpha = glowAlpha))
            }
        }
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                },
            clipToCompositionBounds = true,
            outlineMasksAndMattes = false,
            applyMergePaths = true
        )
    }
}

/** Screen-reader description for each avatar state. */
fun defaultDescription(state: AvatarState): String = when (state) {
    AvatarState.IDLE -> "Aurix avatar, idle"
    AvatarState.LISTENING -> "Aurix avatar, listening"
    AvatarState.TALKING -> "Aurix avatar, talking"
}

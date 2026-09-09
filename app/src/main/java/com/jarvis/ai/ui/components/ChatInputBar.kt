package com.jarvis.ai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.theme.ElectricBlue
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.PanelBlue
import com.jarvis.ai.ui.theme.TextPrimary
import com.jarvis.ai.ui.theme.TextSecondary

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    isListening: Boolean,
    handsFreeActive: Boolean = false,
    onMicPressed: () -> Unit,
    onMicReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                MicButton(
                    enabled = !isLoading,
                    isListening = isListening,
                    onPressed = onMicPressed,
                    onReleased = onMicReleased
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                isListening -> "Listening, sir…"
                                handsFreeActive -> "Hands-free armed, sir…"
                                else -> "Ask AURIX anything…"
                            },
                            color = TextSecondary
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (!isLoading && value.isNotBlank()) onSend(value) }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue.copy(alpha = 0.4f),
                        unfocusedBorderColor = Color(0xFF2E2E36),
                        focusedContainerColor = Color(0xFF1A1A1F),
                        unfocusedContainerColor = Color(0xFF1A1A1F),
                        cursorColor = ElectricBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.width(10.dp))
                SendStopButton(
                    isLoading = isLoading,
                    canSend = value.isNotBlank(),
                    onSend = { onSend(value) },
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun MicButton(
    enabled: Boolean,
    isListening: Boolean,
    onPressed: () -> Unit,
    onReleased: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Tap-to-toggle: the whole press/release dance is gone — a single tap
    // fires onPressed() once (the caller toggles hands-free on/off).
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "micPulse"
    )

    Box(
        Modifier
            .size(46.dp)
            .graphicsLayer { if (isListening) this.alpha = pulse }
            .clip(CircleShape)
            .background(
                brush = when {
                    isListening -> Brush.linearGradient(listOf(ErrorRed, Color(0xFFB23A48)))
                    else -> Brush.linearGradient(listOf(Color(0xFF223047), Color(0xFF1A2436)))
                },
                shape = CircleShape
            )
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPressed()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(if (isListening) 16.dp else 14.dp)
                .background(
                    if (isListening) Color.White else TextSecondary,
                    CircleShape
                )
        )
    }
}

@Composable
private fun SendStopButton(
    isLoading: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                brush = when {
                    isLoading -> Brush.linearGradient(listOf(ErrorRed, ErrorRed.copy(alpha = 0.7f)))
                    !canSend -> Brush.linearGradient(listOf(Color(0xFF2A2A33), Color(0xFF24242C)))
                    else -> Brush.linearGradient(listOf(ElectricBlue, ElectricBlue.copy(alpha = 0.8f)))
                },
                shape = CircleShape
            )
            .clickable(enabled = isLoading || canSend) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (isLoading) onStop() else onSend()
            },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(Color.White, RoundedCornerShape(3.dp))
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (!canSend) TextSecondary else Color.White
            )
        }
    }
}

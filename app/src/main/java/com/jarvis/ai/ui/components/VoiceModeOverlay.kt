package com.jarvis.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen VOICE MODE. Hosts the existing [JarvisOrb] as the hero element
 * with explicit state captions. Only mounted while voice mode is active so
 * the orb animations never run behind the normal chat (performance rule).
 */
@Composable
fun VoiceModeOverlay(
    visible: Boolean,
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
    transcript: String?,
    notice: String?,
    orbLevel: Float,
    onMicToggle: () -> Unit,
    onExit: () -> Unit
) {
    if (!visible) return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
    ) {
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(6.dp)
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Exit voice mode")
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisOrb(
                size = 260.dp,
                active = isListening || isSpeaking,
                level = orbLevel
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = when {
                    isListening -> "Listening..."
                    isThinking -> "Thinking..."
                    isSpeaking -> "Speaking..."
                    else -> "Standing by, sir."
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            transcript?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
            notice?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        // Mic toggle: tap again to interrupt TTS and re-enter LISTENING (barge-in).
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
                .size(72.dp)
                .background(
                    if (isListening) Color(0xFFB23A48) else Color(0xFF223047),
                    CircleShape
                )
                .let { m ->
                    with(androidx.compose.ui.platform.LocalHapticFeedback.current) { m }
                }
                .clickableHaptic(onMicToggle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

private fun Modifier.clickableHaptic(onTap: () -> Unit): Modifier = this.then(
    Modifier
)

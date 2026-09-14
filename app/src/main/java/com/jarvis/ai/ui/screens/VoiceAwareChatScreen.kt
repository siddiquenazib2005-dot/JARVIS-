package com.jarvis.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jarvis.ai.ui.components.VoiceModeOverlay
import com.jarvis.ai.viewmodel.JarvisViewModel

/** Chat plus the previously disconnected animated voice surface. */
@Composable
fun VoiceAwareChatScreen(viewModel: JarvisViewModel) {
    val state by viewModel.uiState.collectAsState()
    val handsFree by viewModel.handsFreeActive.collectAsState()
    val orbLevel by viewModel.orbLevel.collectAsState(initial = 0f)

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreen(viewModel = viewModel)
        VoiceModeOverlay(
            visible = handsFree,
            isListening = state.isListening,
            isThinking = state.isLoading,
            isSpeaking = state.isSpeaking,
            transcript = state.notice?.removePrefix("Listening · "),
            notice = when {
                state.isListening -> "Speak naturally — AURIX will re-arm after replying."
                state.isLoading -> "Processing through the assistant pipeline."
                state.isSpeaking -> "Tap the microphone to interrupt."
                else -> null
            },
            orbLevel = orbLevel,
            onMicToggle = {
                if (state.isLoading || state.isSpeaking) viewModel.stopGeneration()
                else viewModel.toggleHandsFreeMode()
            },
            onExit = {
                if (handsFree) viewModel.toggleHandsFreeMode()
            }
        )
    }
}

package com.jarvis.ai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.components.GlowBackground
import com.jarvis.ai.ui.home.components.AvatarView
import com.jarvis.ai.ui.home.components.GreetingHeader
import com.jarvis.ai.ui.home.components.HomeChatInputBar
import com.jarvis.ai.ui.home.components.InfoCardGrid
import com.jarvis.ai.ui.home.components.QuickActionChips
import com.jarvis.ai.ui.home.components.StatusPillRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Responsibility: the new default start destination of AURIX — a personality-
 * driven companion home that wires into the EXISTING backend.
 *
 * Assembly rules:
 *  - the avatar reacts to real assistant state from [HomeViewModel], never to a
 *    local fake;
 *  - text typed here enters the existing chat pipeline (no new LLM pathway);
 *  - the mic button toggles the existing hands-free/STT wiring;
 *  - quick actions translate to commands routed through the same pipeline.
 *
 * The screen is a vertically scrolling column so it degrades gracefully on small
 * screens and with large system fonts, instead of clipping.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenChat: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        // Ambient backdrop, matching the existing app's glow background.
        GlowBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            GreetingHeader(
                greeting = viewModel.greeting,
                statusText = state.statusText
            )

            Spacer(Modifier.height(12.dp))

            StatusPillRow(
                weatherText = state.weather?.display,
                streakText = state.streak.display,
                onWeatherClick = onOpenSettings,
                onStreakClick = onOpenSettings
            )

            Spacer(Modifier.height(8.dp))

            AvatarView(state = state.avatarState)

            Spacer(Modifier.height(4.dp))

            QuickActionChips(onAction = { _, command ->
                viewModel.sendMessage(command)
                onOpenChat()
            })

            Spacer(Modifier.height(16.dp))

            InfoCardGrid(
                weatherText = state.weather?.display,
                dateText = currentDate(),
                moodText = state.mood?.let { "${it.emoji} ${it.label}" },
                streakText = state.streak.display,
                onWeatherClick = onOpenSettings,
                onMoodClick = onOpenMemories
            )

            Spacer(Modifier.height(20.dp))

            HomeChatInputBar(
                placeholder = "Ask Aurix anything...",
                isListening = state.avatarState == com.jarvis.ai.ui.home.components.AvatarState.LISTENING,
                onSend = { text ->
                    viewModel.sendMessage(text)
                    onOpenChat()
                },
                onMicToggle = { viewModel.toggleMic() }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Today's date in the card-grid format. */
private fun currentDate(): String =
    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())

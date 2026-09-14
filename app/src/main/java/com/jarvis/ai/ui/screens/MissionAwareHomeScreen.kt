package com.jarvis.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.missions.MissionEngine
import com.jarvis.ai.missions.MissionProgress
import com.jarvis.ai.missions.MissionRunState
import com.jarvis.ai.viewmodel.JarvisViewModel

private val RuntimeRed = Color(0xFFFF1744)
private val RuntimeOrange = Color(0xFFFF6B00)
private val RuntimeGreen = Color(0xFF28D17C)
private val RuntimePanel = Color(0xF21A0F12)
private val RuntimeText = Color(0xFFF7F3F4)
private val RuntimeMuted = Color(0xFFB5AAAD)

/**
 * Keeps the reference dashboard intact while projecting live mission runtime
 * truth over it. The controls call MissionEngine directly because its runtime
 * is process-wide and must remain controllable even when chat is not visible.
 */
@Composable
fun MissionAwareHomeScreen(
    viewModel: JarvisViewModel,
    onOpenChat: () -> Unit,
    onCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val missionEngine = remember(context.applicationContext) {
        MissionEngine(context.applicationContext)
    }
    val progress by MissionEngine.progress.collectAsState()
    var feedback by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        AurixHomeScreen(
            viewModel = viewModel,
            onOpenChat = onOpenChat,
            onCommand = onCommand
        )

        if (progress.isActive) {
            MissionRuntimePanel(
                progress = progress,
                feedback = feedback,
                onPause = { feedback = missionEngine.pause() },
                onResume = { feedback = missionEngine.resume() },
                onCancel = { feedback = missionEngine.cancel() }
            )
        }
    }
}

@Composable
private fun MissionRuntimePanel(
    progress: MissionProgress,
    feedback: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val paused = progress.state == MissionRunState.PAUSED
    val fraction = if (progress.totalSteps > 0) {
        progress.completedSteps.toFloat() / progress.totalSteps.toFloat()
    } else 0f

    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 62.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RuntimePanel)
            .border(
                width = 1.dp,
                color = if (paused) RuntimeOrange else RuntimeRed,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (paused) "MISSION PAUSED" else "MISSION ACTIVE",
                    color = if (paused) RuntimeOrange else RuntimeGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp
                )
                Text(
                    text = progress.missionName ?: "Mission",
                    color = RuntimeText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${progress.completedSteps}/${progress.totalSteps}",
                color = RuntimeText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(9.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A292E))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(Brush.horizontalGradient(listOf(RuntimeRed, RuntimeOrange)))
            )
        }

        progress.currentAction?.let {
            Spacer(Modifier.height(7.dp))
            Text("Current: $it", color = RuntimeMuted, fontSize = 11.sp)
        }
        if (feedback.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(feedback, color = RuntimeMuted, fontSize = 10.sp, maxLines = 2)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeButton(
                label = if (paused) "RESUME" else "PAUSE",
                color = if (paused) RuntimeGreen else RuntimeOrange,
                onClick = if (paused) onResume else onPause,
                modifier = Modifier.weight(1f)
            )
            RuntimeButton(
                label = "CANCEL",
                color = RuntimeRed,
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RuntimeButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = RuntimeText, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

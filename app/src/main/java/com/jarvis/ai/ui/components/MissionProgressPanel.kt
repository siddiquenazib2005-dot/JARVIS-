package com.jarvis.ai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.accessibility.VerificationStatus
import com.jarvis.ai.mission.Mission
import com.jarvis.ai.mission.MissionState

/**
 * Phase-2 §9: live mission panel.
 *
 * Every value shown here is read from the observable [Mission]; there is no
 * decorative animation that runs while nothing is happening. Chain-of-thought is
 * never exposed — only the current phase, the step description, the tool name and
 * the verification result.
 */

private val PanelInk = Color(0xE6171012)
private val PanelStroke = Color(0xFF54202A)
private val AccentOrange = Color(0xFFFF6B00)
private val AccentGreen = Color(0xFF28D17C)
private val AccentRed = Color(0xFFFF1744)
private val MutedText = Color(0xFFA89FA2)
private val BrightText = Color(0xFFF6EDEA)

@Composable
fun MissionProgressPanel(
    mission: Mission,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PanelInk)
            .border(1.dp, PanelStroke, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(mission)
        StageList(mission)
        if (!mission.isTerminal) {
            StepDetail(mission)
            ProgressBar(mission)
            CancelButton(onCancel)
        } else {
            TerminalSummary(mission)
        }
    }
}

@Composable
private fun Header(mission: Mission) {
    val accent = when {
        mission.state == MissionState.COMPLETED -> AccentGreen
        mission.state == MissionState.FAILED -> AccentRed
        mission.state == MissionState.CANCELLED -> MutedText
        else -> AccentOrange
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "AURIX",
            color = BrightText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = mission.state.name,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Renders the mission phases. Only phases reached so far are lit; the current
 * one pulses, completed ones are ticked. Nothing animates when the mission is
 * terminal.
 */
@Composable
private fun StageList(mission: Mission) {
    val order = listOf(
        MissionState.UNDERSTANDING,
        MissionState.PLANNING,
        MissionState.EXECUTING,
        MissionState.VERIFYING,
        MissionState.COMPLETED
    )
    val reached = when (mission.state) {
        MissionState.CREATED -> emptyList()
        MissionState.UNDERSTANDING -> listOf(MissionState.UNDERSTANDING)
        MissionState.PLANNING -> listOf(MissionState.UNDERSTANDING, MissionState.PLANNING)
        MissionState.WAITING_CONFIRMATION -> listOf(MissionState.UNDERSTANDING, MissionState.PLANNING)
        MissionState.EXECUTING, MissionState.OBSERVING, MissionState.RECOVERING ->
            listOf(MissionState.UNDERSTANDING, MissionState.PLANNING, MissionState.EXECUTING)
        MissionState.VERIFYING ->
            listOf(MissionState.UNDERSTANDING, MissionState.PLANNING, MissionState.EXECUTING, MissionState.VERIFYING)
        MissionState.COMPLETED -> order
        MissionState.FAILED, MissionState.CANCELLED ->
            listOf(MissionState.UNDERSTANDING, MissionState.PLANNING, MissionState.EXECUTING, MissionState.VERIFYING)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEach { stage ->
            val done = mission.state == MissionState.COMPLETED || reached.indexOf(stage).let { it >= 0 && it < reached.size - 1 }
            val current = reached.lastOrNull() == stage && !mission.isTerminal
            val color = when {
                mission.state == MissionState.FAILED && stage == reached.lastOrNull() -> AccentRed
                done -> AccentGreen
                current -> AccentOrange
                else -> MutedText
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (done) "✓" else if (current) "●" else "○",
                    color = color,
                    fontSize = 13.sp
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stage.name.lowercase().replaceFirstChar { it.uppercase() },
                    color = if (done || current) BrightText else MutedText,
                    fontSize = 13.sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun StepDetail(mission: Mission) {
    val step = mission.currentStep
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = step.label,
            color = BrightText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        step.toolName?.let { tool ->
            Text(
                text = "Tool: $tool",
                color = MutedText,
                fontSize = 12.sp
            )
        }
        Text(
            text = "Verification: ${step.stepVerification.label()}",
            color = when (step.stepVerification) {
                VerificationStatus.VERIFIED -> AccentGreen
                VerificationStatus.FAILED -> AccentRed
                VerificationStatus.NOT_CHECKED -> MutedText
            },
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ProgressBar(mission: Mission) {
    val target = if (mission.state == MissionState.COMPLETED) 1f else mission.progressFraction
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 300),
        label = "mission_progress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(PanelStroke)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(CircleShape)
                .background(AccentOrange)
        )
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    Button(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = PanelStroke,
            contentColor = BrightText
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = "Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TerminalSummary(mission: Mission) {
    val color = when (mission.state) {
        MissionState.COMPLETED -> AccentGreen
        MissionState.FAILED -> AccentRed
        else -> MutedText
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when (mission.state) {
                MissionState.COMPLETED -> "Mission complete, sir."
                MissionState.FAILED -> mission.lastError ?: "Mission failed, sir."
                MissionState.CANCELLED -> "Mission cancelled."
                else -> ""
            },
            color = color,
            fontSize = 13.sp
        )
        mission.observation?.takeIf { it.isNotBlank() }?.let { obs ->
            Text(text = obs, color = MutedText, fontSize = 12.sp)
        }
    }
}

private fun VerificationStatus.label(): String = when (this) {
    VerificationStatus.NOT_CHECKED -> "Pending"
    VerificationStatus.VERIFIED -> "Verified"
    VerificationStatus.FAILED -> "Failed"
}

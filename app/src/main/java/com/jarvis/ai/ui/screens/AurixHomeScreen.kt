package com.jarvis.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.components.GlowBackground
import com.jarvis.ai.ui.components.JarvisOrb
import com.jarvis.ai.viewmodel.JarvisViewModel

private val HomeInk = Color(0xFF050506)
private val HomePanel = Color(0xE6171012)
private val HomePanelStrong = Color(0xFF211014)
private val HomeStroke = Color(0xFF54202A)
private val HomeRed = Color(0xFFFF1744)
private val HomeOrange = Color(0xFFFF6B00)
private val HomePurple = Color(0xFF9C4DFF)
private val HomeText = Color(0xFFF7F3F4)
private val HomeMuted = Color(0xFFA89FA2)

private data class HomeAction(
    val icon: String,
    val title: String,
    val subtitle: String,
    val command: String,
    val accent: Color = HomeRed
)

private val QUICK_ACTIONS = listOf(
    HomeAction("🔦", "Flashlight", "Turn torch on", "flashlight on", HomeOrange),
    HomeAction("🔋", "Battery", "Check power status", "battery status"),
    HomeAction("📷", "Camera", "Open camera", "open camera", HomePurple),
    HomeAction("⚙", "Device report", "Health and storage", "device status", HomeOrange),
    HomeAction("👁", "Read screen", "Accessibility scan", "what's on my screen", HomePurple),
    HomeAction("🔔", "Notifications", "Read recent alerts", "read my notifications"),
    HomeAction("🔐", "Latest OTP", "On-demand only", "otp", HomeOrange),
    HomeAction("🚩", "Missions", "Show saved routines", "list missions", HomePurple)
)

private val CAPABILITIES = listOf(
    Triple("Communication", "WhatsApp · SMS · Email · Calls", "💬"),
    Triple("Screen automation", "Tap · Type · Scroll · Read", "☝"),
    Triple("Voice assistant", "STT · TTS · Wake word", "🎙"),
    Triple("Private by design", "Permission gates · Audit log", "🛡")
)

/**
 * Reference-inspired AURIX command dashboard.
 *
 * Every tappable quick action sends a real command through JarvisViewModel;
 * capability cards are deliberately informational so the UI never pretends an
 * unfinished feature ran. The existing chat/orchestrator architecture remains
 * the source of truth for execution.
 */
@Composable
fun AurixHomeScreen(
    viewModel: JarvisViewModel,
    onOpenChat: () -> Unit,
    onCommand: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val orbLevel by viewModel.orbLevel.collectAsState(initial = 0f)
    // isActing is included: a running tool/device action must read as ACTIVE,
    // otherwise the home orb reports READY while AURIX is mid-automation.
    val active = state.isListening || state.isSpeaking || state.isLoading || state.isActing

    Box(modifier = Modifier.fillMaxSize().background(HomeInk)) {
        GlowBackground(modifier = Modifier.fillMaxSize(), level = orbLevel)

        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AURIX", color = HomeText, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("AI ASSISTANT", color = HomeRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    StatusBadge(if (active) "ACTIVE" else "READY", active)
                }
            }

            item {
                Surface(
                    color = HomePanel,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, HomeStroke, RoundedCornerShape(26.dp))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (active) "AURIX IS LISTENING" else "YOUR PERSONAL AI COMPANION",
                            color = if (active) HomeOrange else HomeMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        JarvisOrb(size = 156.dp, active = active, level = orbLevel)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.isLoading) "Processing your request…" else "How can I assist you today?",
                            color = HomeText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Smart · Private · Powerful · Secure",
                            color = HomeMuted,
                            fontSize = 12.5.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.horizontalGradient(listOf(HomeRed, HomeOrange)))
                                .clickable(onClick = onOpenChat)
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("ASK AURIX", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                }
            }

            item { SectionHeading("QUICK COMMANDS", "Real actions through the existing command pipeline") }

            QUICK_ACTIONS.chunked(2).forEach { pair ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEach { action ->
                            QuickActionCard(action, Modifier.weight(1f)) { onCommand(action.command) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            item { SectionHeading("CORE SYSTEMS", "Connected capabilities in the current build") }

            CAPABILITIES.forEachIndexed { index, capability ->
                item {
                    CapabilityRow(
                        icon = capability.third,
                        title = capability.first,
                        subtitle = capability.second,
                        accent = if (index % 2 == 0) HomeRed else HomePurple
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xF20B0809))
                .border(1.dp, HomeStroke)
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockItem("⌂", "Home", selected = true, onClick = {})
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(HomeOrange, HomeRed)))
                    .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onOpenChat),
                contentAlignment = Alignment.Center
            ) {
                Text("🎙", fontSize = 24.sp)
            }
            DockItem("◉", "Chat", selected = false, onClick = onOpenChat)
        }
    }
}

@Composable
private fun StatusBadge(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0x3328D17C) else HomePanelStrong)
            .border(1.dp, if (active) Color(0xFF28D17C) else HomeStroke, RoundedCornerShape(20.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) Color(0xFF28D17C) else HomeRed))
        Spacer(Modifier.size(7.dp))
        Text(label, color = HomeText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
        Text(title, color = HomeText, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = HomeMuted, fontSize = 11.5.sp)
    }
}

@Composable
private fun QuickActionCard(action: HomeAction, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = HomePanel,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(132.dp)
            .border(1.dp, action.accent.copy(alpha = 0.48f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(action.icon, fontSize = 24.sp)
            Column {
                Text(action.title, color = HomeText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(action.subtitle, color = HomeMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun CapabilityRow(icon: String, title: String, subtitle: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HomePanel)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 21.sp) }
        Spacer(Modifier.size(13.dp))
        Column {
            Text(title, color = HomeText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HomeMuted, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun DockItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, color = if (selected) HomeRed else HomeMuted, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = if (selected) HomeText else HomeMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

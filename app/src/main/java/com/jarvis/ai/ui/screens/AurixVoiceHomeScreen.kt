package com.jarvis.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.ui.components.GlowBackground
import com.jarvis.ai.ui.components.JarvisOrb
import com.jarvis.ai.viewmodel.JarvisViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val VhInk = Color(0xFF050506)
private val VhPanel = Color(0xE6171012)
private val VhStroke = Color(0xFF54202A)
private val VhRed = Color(0xFFFF1744)
private val VhOrange = Color(0xFFFF6B00)
private val VhText = Color(0xFFF7F3F4)
private val VhMuted = Color(0xFFA89FA2)

private data class QuickChip(val label: String, val command: String)

/** Same real commands the old dashboard used, now as a swipeable chip row. */
private val QUICK_CHIPS = listOf(
    QuickChip("🔦 Flashlight", "flashlight on"),
    QuickChip("🔋 Battery", "battery status"),
    QuickChip("👁 Read screen", "what's on my screen"),
    QuickChip("🔔 Notifications", "read my notifications"),
    QuickChip("🔐 OTP", "otp"),
    QuickChip("📷 Camera", "open camera")
)

/** Pure + internal so it can be unit-tested on the JVM (see VoiceHomeStatusTest). */
internal fun voiceHomeStatus(
    isActing: Boolean,
    isLoading: Boolean,
    isSpeaking: Boolean,
    isListening: Boolean,
    handsFree: Boolean
): String = when {
    isActing -> "Working on it…"
    isLoading -> "Thinking…"
    isSpeaking -> "Speaking…"
    isListening || handsFree -> "Listening…"
    else -> "How can I assist you today?"
}

private const val PROFILE_PREFS = "aurix_profile"
private const val KEY_OWNER_NAME = "owner_name"

/** Greeting name. Default is the owner's name until a settings field writes one. */
private fun ownerName(context: Context): String =
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_OWNER_NAME, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Nazib"

/**
 * Voice-first AURIX home (P1): greeting, live orb, waveform, one-tap hands-free
 * mic, text bar and a Home/Chat/Mic/Triggers/Settings dock.
 *
 * Execution stays in JarvisViewModel (send / toggleHandsFreeMode); this screen
 * only renders state and forwards intent, so no orchestrator code changes.
 */
@Composable
fun AurixVoiceHomeScreen(
    viewModel: JarvisViewModel,
    onOpenChat: () -> Unit,
    onOpenMissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val orbLevel by viewModel.orbLevel.collectAsState(initial = 0f)
    val handsFree by viewModel.handsFreeActive.collectAsState()
    val name = remember { ownerName(context) }
    var draft by rememberSaveable { mutableStateOf("") }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.toggleHandsFreeMode() }

    val onMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Turning OFF never needs the permission; turning ON does.
        if (handsFree || granted) viewModel.toggleHandsFreeMode()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val submit: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            viewModel.send(text)
            draft = ""
        }
    }

    val active = state.isListening || state.isSpeaking || state.isLoading || state.isActing || handsFree
    val status = voiceHomeStatus(
        isActing = state.isActing,
        isLoading = state.isLoading,
        isSpeaking = state.isSpeaking,
        isListening = state.isListening,
        handsFree = handsFree
    )
    val lastReply = state.messages.lastOrNull { it.sender == Sender.AURIX }

    Box(modifier = Modifier.fillMaxSize().background(VhInk)) {
        GlowBackground(modifier = Modifier.fillMaxSize(), level = orbLevel)

        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hello, $name", color = VhText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(status, color = if (active) VhOrange else VhMuted, fontSize = 14.sp)
                    }
                    Text(
                        if (active) "ACTIVE" else "READY",
                        color = if (active) VhOrange else VhMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }

                Spacer(Modifier.height(28.dp))
                JarvisOrb(size = 220.dp, active = active, level = orbLevel)
                Spacer(Modifier.height(12.dp))
                VoiceWaveform(
                    level = orbLevel,
                    active = active,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                )

                state.notice?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = VhOrange, fontSize = 12.5.sp, textAlign = TextAlign.Center)
                }

                if (lastReply != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(VhPanel)
                            .border(1.dp, VhStroke, RoundedCornerShape(18.dp))
                            .clickable(onClick = onOpenChat)
                            .padding(14.dp)
                    ) {
                        Text(
                            lastReply.text,
                            color = if (lastReply.isError) VhOrange else VhText,
                            fontSize = 14.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(QUICK_CHIPS) { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(VhPanel)
                                .border(1.dp, VhStroke, RoundedCornerShape(20.dp))
                                .clickable { viewModel.send(chip.command) }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text(chip.label, color = VhText, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(VhPanel)
                        .border(1.dp, VhStroke, RoundedCornerShape(26.dp))
                        .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = VhText, fontSize = 15.sp),
                        cursorBrush = SolidColor(VhRed),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) {
                                Text("Ask AURIX anything…", color = VhMuted, fontSize = 15.sp)
                            }
                            inner()
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(VhRed, VhOrange)))
                            .clickable(onClick = submit),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("➤", color = Color.White, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            VoiceDock(
                handsFree = handsFree,
                onChat = onOpenChat,
                onMic = onMic,
                onTriggers = onOpenMissions,
                onSettings = onOpenSettings
            )
        }
    }
}

@Composable
private fun VoiceWaveform(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase"
    )
    val energy by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "energy")

    Canvas(modifier = modifier) {
        val bars = 33
        val slot = size.width / bars
        val mid = size.height / 2f
        val minHeight = 3.dp.toPx()
        for (i in 0 until bars) {
            val x = slot * i + slot / 2f
            val envelope = (1f - abs(i - bars / 2f) / (bars / 2f)).coerceAtLeast(0.15f)
            val wobble = (sin(phase + i * 0.55f) + 1f) / 2f
            val amp = (0.08f + (if (active) 0.25f else 0.05f) * wobble + energy * 0.9f * wobble) * envelope
            val h = (size.height * amp).coerceAtLeast(minHeight)
            drawLine(
                color = VhRed.copy(alpha = 0.35f + 0.65f * envelope),
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = slot * 0.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun VoiceDock(
    handsFree: Boolean,
    onChat: () -> Unit,
    onMic: () -> Unit,
    onTriggers: () -> Unit,
    onSettings: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "micPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF20B0809))
            .border(1.dp, VhStroke)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockEntry("⌂", "Home", selected = true, onClick = {})
        DockEntry("◉", "Chat", selected = false, onClick = onChat)
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(if (handsFree) pulse else 1f)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(VhOrange, VhRed)))
                .border(2.dp, Color.White.copy(alpha = if (handsFree) 0.45f else 0.18f), CircleShape)
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center
        ) {
            Text(if (handsFree) "■" else "🎙", fontSize = 22.sp, color = Color.White)
        }
        DockEntry("⚡", "Triggers", selected = false, onClick = onTriggers)
        DockEntry("⚙", "Settings", selected = false, onClick = onSettings)
    }
}

@Composable
private fun DockEntry(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, color = if (selected) VhRed else VhMuted, fontSize = 20.sp)
        Text(label, color = if (selected) VhRed else VhMuted, fontSize = 10.sp)
    }
}

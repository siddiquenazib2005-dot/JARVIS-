package com.jarvis.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.data.model.LatencyInfo
import com.jarvis.ai.data.model.SessionInfo
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.ui.components.ChatInputBar
import com.jarvis.ai.ui.components.GlowBackground
import com.jarvis.ai.ui.components.MessageBubble
import com.jarvis.ai.ui.components.TelemetryRow
import com.jarvis.ai.ui.theme.ElectricBlue
import com.jarvis.ai.ui.theme.PanelBlue
import com.jarvis.ai.ui.theme.TextPrimary
import com.jarvis.ai.ui.theme.TextSecondary
import com.jarvis.ai.ui.theme.VioletPulse
import com.jarvis.ai.viewmodel.JarvisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LISTENING_PREFIX = "Listening · "

@Composable
fun ChatScreen(
    viewModel: JarvisViewModel = viewModel(factory = JarvisViewModel.factory(LocalContext.current))
) {
    val state by viewModel.uiState.collectAsState()
    val orbLevel by viewModel.orbLevel.collectAsState(initial = 0f)
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var ttsMuted by rememberSaveable { mutableStateOf(viewModel.ttsMuted) }
    val handsFreeActive by viewModel.handsFreeActive.collectAsState()

    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceInput()
        else viewModel.voicePermissionDenied()
    }

    // Vision input: system photo picker (no storage permission required).
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { downscaleToBase64(context, uri, maxDimPx = 1024) }
                    .onSuccess { payload ->
                        viewModel.analyzeImage(payload.first, "image/jpeg", "")
                    }
                    .onFailure { viewModel.clearNotice() }
            }
        }
    }

    fun beginVoiceInput() {
        viewModel.recordMicPress()
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoiceInput()
        } else {
            micPermission.launch(permission)
        }
    }

    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text?.length,
        state.isLoading
    ) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.lastIndex) }
        }
    }

    fun dispatch(text: String) {
        keyboard?.hide()
        if (state.hasPendingConfirmation) {
            // The input is still being edited separately, but a tool is waiting
            // for explicit confirmation — this SEND acts as the "yes" so the
            // gated tool actually runs instead of re-prompting forever.
            viewModel.confirmPendingAction()
        } else {
            viewModel.send(text)
            input = ""
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawer(
                sessions = state.sessions,
                activeId = state.activeSessionId,
                onSelect = {
                    viewModel.selectSession(it)
                    scope.launch { drawerState.close() }
                },
                onDelete = viewModel::deleteSession,
                onNewChat = {
                    viewModel.newSession()
                    scope.launch { drawerState.close() }
                },
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            GlowBackground(Modifier.matchParentSize(), level = orbLevel)

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
            ) {
                Header(
                    isBusy = state.isLoading,
                    isSpeaking = state.isSpeaking,
                    backendOnline = state.backendOnline,
                    orbLevel = orbLevel,
                    activeProvider = state.activeProvider,
                    latency = state.latency,
                    onNewChat = viewModel::newSession,
                    onOpenHistory = { scope.launch { drawerState.open() } },
                    onOpenSettings = { showSettings = true }
                )

                AnimatedVisibility(
                    visible = state.notice != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    state.notice?.let { notice ->
                        NoticeBar(
                            text = notice,
                            isTranscript = notice.startsWith(LISTENING_PREFIX),
                            onDismiss = viewModel::clearNotice
                        )
                    }
                }

                TelemetryRow(
                    latency = state.latency,
                    providerLabel = state.activeProvider.ifBlank { "AUTO-ROUTE" },
                    modelLabel = if (state.backendOnline) "multi-provider" else "offline",
                    modifier = Modifier.padding(top = 4.dp)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    if (state.messages.size <= 1 && !state.isLoading &&
                        state.messages.firstOrNull()?.sender == Sender.JARVIS
                    ) {
                        item(key = "hero") {
                            WelcomeHero(onSuggestion = ::dispatch)
                        }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    TextButton(onClick = {
                        runCatching {
                            pickImage.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    }) {
                        Text("IMG", style = MaterialTheme.typography.labelSmall)
                    }
                    ChatInputBar(
                        value = input,
                        onValueChange = { input = it },
                        onSend = ::dispatch,
                        onStop = viewModel::stopGeneration,
                        isLoading = state.isLoading,
                        isListening = state.isListening,
                        handsFreeActive = handsFreeActive,
                        // In hands-free mode a tap on the mic disengages it;
                        // push-to-talk keeps its press-and-hold semantics.
                        onMicPressed = {
                            if (handsFreeActive) viewModel.toggleHandsFreeMode()
                            else beginVoiceInput()
                        },
                        onMicReleased = {
                            if (!handsFreeActive) viewModel.stopVoiceInput()
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            ttsMuted = ttsMuted,
            onTtsMutedChange = {
                ttsMuted = it
                viewModel.setTtsMuted(it)
            },
            handsFreeActive = handsFreeActive,
            onHandsFreeChange = { viewModel.toggleHandsFreeMode() },
            onDismiss = { showSettings = false }
        )
    }
}

private fun String.truncate(max: Int): String =
    if (length <= max) this else take(max - 1) + "…"

/** Downscales an image from the photo picker and returns (base64Jpeg, mime). */
private fun downscaleToBase64(
    context: android.content.Context,
    uri: android.net.Uri,
    maxDimPx: Int
): Pair<String, String> {
    val resolver = context.contentResolver
    val bounds = resolver.openInputStream(uri)?.use { stream ->
        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { android.graphics.BitmapFactory.decodeStream(stream, null, it) }
    } ?: throw IllegalStateException("unreadable image")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimPx) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use { stream ->
        android.graphics.BitmapFactory.decodeStream(
            stream, null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } ?: throw IllegalStateException("decode failed")
    var scaled = bitmap
    if (maxOf(bitmap.width, bitmap.height) > maxDimPx) {
        val scale = maxDimPx.toFloat() / maxOf(bitmap.width, bitmap.height)
        scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to "image/jpeg"
}

@Composable
private fun Header(
    isBusy: Boolean,
    isSpeaking: Boolean,
    backendOnline: Boolean,
    orbLevel: Float,
    activeProvider: String,
    latency: LatencyInfo,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "J.A.R.V.I.S.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            when {
                                !backendOnline -> Color(0xFFFFB74D)
                                isSpeaking -> Color(0xFF4CD964)
                                else -> Color(0xFF5B7CFF)
                            },
                            CircleShape
                        )
                )
                Text(
                    when {
                        !backendOnline -> "Offline reserves"
                        isSpeaking -> "Speaking"
                        isBusy -> "Processing"
                        else -> activeProvider.ifBlank { "Online • multi-provider" }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onNewChat) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "New chat",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenHistory) {
            Icon(
                Icons.Outlined.List,
                contentDescription = "Session history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeBar(text: String, isTranscript: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isTranscript) PanelBlue.copy(alpha = 0.9f)
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isTranscript) TextPrimary else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!isTranscript) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionsDrawer(
    sessions: List<SessionInfo>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(drawerContainerColor = PanelBlue) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SESSIONS",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = TextSecondary
                )
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable(onClick = onNewChat)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "New session",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(sessions, key = { it.id }) { session ->
                val active = session.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(session.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (active) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (active) TextPrimary else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatSessionTime(session.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete ${session.title}",
                                    tint = TextSecondary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSessionTime(timestamp: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.US).format(Date(timestamp))

private val SUGGESTIONS = listOf(
    "Run system diagnostics",
    "Tell me a joke",
    "What time is it?",
    "Calculate 42 * 19"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeHero(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    Brush.linearGradient(listOf(ElectricBlue, VioletPulse)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "J",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "How may I assist you today?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Online multi-provider intelligence • voice uplink ready",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion) },
                    label = { Text(suggestion) }
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    viewModel: JarvisViewModel,
    ttsMuted: Boolean,
    onTtsMutedChange: (Boolean) -> Unit,
    handsFreeActive: Boolean,
    onHandsFreeChange: () -> Unit,
    onDismiss: () -> Unit
) {
    var health = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.jarvis.ai.health.HealthReport?>(null) }
    var savedKeys by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(viewModel.configuredProviders().toSet()) }
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()
    LaunchedEffect(Unit) {
        health.value = runCatching { viewModel.healthSnapshot() }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlue,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("J.A.R.V.I.S. System Status") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "All intelligence routing is handled securely inside the J.A.R.V.I.S. core.",
                    style = MaterialTheme.typography.bodyMedium
                )
                ProviderKeySection(
                    viewModel = viewModel,
                    savedKeys = savedKeys,
                    onKeysSaved = { savedKeys = viewModel.configuredProviders().toSet() }
                )

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice replies", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "J.A.R.V.I.S. speaks responses aloud",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = !ttsMuted,
                        onCheckedChange = { enabled -> onTtsMutedChange(!enabled) }
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hands-free mode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Continuous conversation — the mic re-arms after each reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = handsFreeActive,
                        onCheckedChange = { onHandsFreeChange() }
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Wake word", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "In hands-free, only \"Jarvis …\" commands are acted on",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = wakeWordEnabled,
                        onCheckedChange = { viewModel.setWakeWordEnabled(it) }
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("SYSTEM TELEMETRY", style = MaterialTheme.typography.labelLarge)
                val report = health.value
                if (report == null) {
                    Text("Reading sensors…", style = MaterialTheme.typography.labelSmall)
                } else {
                    HealthLine(
                        "Power",
                        listOfNotNull(
                            report.batteryPercent?.let { "$it%" },
                            if (report.charging == true) "charging" else null
                        ).joinToString(" · ").ifBlank { "unavailable" }
                    )
                    HealthLine(
                        "Network",
                        when (report.online) {
                            true -> "online"
                            false -> "offline"
                            null -> "unavailable"
                        }
                    )
                    HealthLine(
                        "Memory pressure",
                        report.memoryPressurePercent?.let { "$it%" } ?: "unavailable"
                    )
                    HealthLine(
                        "Storage low",
                        when (report.lowStorage) {
                            true -> "YES — action advised"
                            false -> "ok"
                            null -> "unavailable"
                        }
                    )
                    HealthLine("Vector memory", report.vectorStoreId)
                    HealthLine(
                        "Voice",
                        listOf(
                            if (report.voiceInputAvailable) "STT ok" else "STT n/a",
                            if (report.ttsAvailable) "TTS ok" else "TTS init"
                        ).joinToString(" · ")
                    )

                    Spacer(Modifier.height(4.dp))
                    Text("PROVIDERS", style = MaterialTheme.typography.labelLarge)
                    if (report.providers.isEmpty()) {
                        Text(
                            "No provider keys provisioned — offline mode.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        report.providers.forEach { p ->
                            HealthLine(
                                p.providerId,
                                listOfNotNull(
                                    p.state.lowercase(),
                                    if (p.emaLatencyMs > 0) "${p.emaLatencyMs}ms avg" else null,
                                    "${p.successCount}✓/${p.failureCount}✗".replace("✓", "ok").replace("✗", "err"),
                                    if (!p.hasKeyConfigured) "no key" else null
                                ).joinToString(" · ")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = MaterialTheme.colorScheme.primary) }
        }
    )
}

@Composable
private fun HealthLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            modifier = Modifier.width(110.dp)
        )
        Text(value, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun ProviderKeySection(
    viewModel: JarvisViewModel,
    savedKeys: Set<String>,
    onKeysSaved: () -> Unit
) {
    var status by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var typedValues by remember {
        mutableStateOf(
            PROVIDER_KEY_FIELDS.associate { it.envName to "" }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PROVIDER KEYS", style = MaterialTheme.typography.labelLarge)
        Text(
            "Paste keys below (stored encrypted, AndroidKeyStore — never plaintext).",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )

        PROVIDER_KEY_FIELDS.forEach { field ->
            val isSaved = viewModel.hasKey(field.envName)
            var revealed by remember { mutableStateOf(false) }
            var fieldStatus by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = typedValues[field.envName] ?: "",
                onValueChange = {
                    typedValues = typedValues + (field.envName to it)
                    fieldStatus = null
                },
                label = { Text(if (isSaved) "${field.label} — saved" else field.label) },
                placeholder = { Text(field.hint) },
                singleLine = true,
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        if (isSaved) {
                            Text(
                                "SAVED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = (typedValues[field.envName]?.isNotBlank() == true),
                    onClick = {
                        fieldStatus = viewModel.saveKeyForField(field.envName, typedValues[field.envName] ?: "")
                        typedValues = typedValues + (field.envName to "")
                        onKeysSaved()
                    }
                ) { Text("Save ${field.label} key") }
                if (fieldStatus != null) {
                    Text(
                        fieldStatus!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fieldStatus!!.contains("✓")) MaterialTheme.colorScheme.primary else Color(0xFFFF6B6B)
                    )
                }
            }
        }

        if (status != null) {
            Text(
                status!!,
                style = MaterialTheme.typography.labelSmall,
                color = if (status!!.contains("✓")) MaterialTheme.colorScheme.primary else Color(0xFFFF6B6B)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !confirming,
                onClick = {
                    confirming = true
                    status = "Testing configured providers…"
                    viewModel.testAndSaveKeys(typedValues) { result ->
                        status = result
                        onKeysSaved()
                        confirming = false
                    }
                }
            ) { Text(if (confirming) "Testing…" else "Test providers") }
        }
        Text(
            if (savedKeys.isEmpty())
                "⚠ No keys configured — J.A.R.V.I.S. runs offline only."
            else "✓ ${savedKeys.size} provider(s) configured: ${savedKeys.joinToString(", ")}",
            style = MaterialTheme.typography.labelSmall,
            color = if (savedKeys.isEmpty()) Color(0xFFFF6B6B) else TextSecondary
        )
        Spacer(Modifier.height(4.dp))
    }
}

private data class FieldSpec(
    val label: String,
    val envName: String,
    val hint: String
)

private val PROVIDER_KEY_FIELDS = listOf(
    FieldSpec("Gemini", "GEMINI_API_KEY", "AQ… / AIza…"),
    FieldSpec("OpenAI", "OPENAI_API_KEY", "sk-…"),
    FieldSpec("Groq", "GROQ_API_KEY", "gsk_…"),
    FieldSpec("OpenRouter", "OPENROUTER_API_KEY", "sk-or-v1-…")
)

package com.jarvis.ai.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.onboarding.PermissionCatalog
import com.jarvis.ai.provider.ModelCatalog
import com.jarvis.ai.provider.ModelOption
import com.jarvis.ai.ui.components.AccessWarningBar
import com.jarvis.ai.ui.components.markdown.MarkdownText
import com.jarvis.ai.viewmodel.JarvisViewModel
import kotlinx.coroutines.launch

/**
 * AURIX chat surface.
 *
 * Layout deliberately mirrors the familiar ChatGPT shape:
 *  - slim top bar: sessions drawer · model chip · new chat
 *  - full-width assistant turns, right-aligned user bubbles
 *  - empty state with tappable suggestion prompts (real prompts, not labels)
 *  - rounded pill composer pinned to the bottom with mic + send
 *
 * Everything is self-contained so the screen has no hidden dependencies on
 * other component signatures.
 */

private val Ink = Color(0xFF0B0B0D)
private val Panel = Color(0xFF17171A)
private val PanelSoft = Color(0xFF1F1F23)
private val Stroke = Color(0xFF2C2C31)
private val Accent = Color(0xFFFF1744)
private val TextHi = Color(0xFFF3F3F4)
private val TextLo = Color(0xFF9A9AA2)

/** Suggestion chips: label shown to the user, prompt actually sent. */
private data class Suggestion(val label: String, val prompt: String)

private val SUGGESTIONS = listOf(
    Suggestion("Battery status", "battery status"),
    Suggestion("Turn on flashlight", "flashlight on"),
    Suggestion("Open WhatsApp", "open whatsapp"),
    Suggestion("Set a 10 minute timer", "set a timer for 10 minutes"),
    Suggestion("Device report", "device status"),
    Suggestion("Navigate home", "navigate to home"),
    Suggestion("List my apps", "list apps"),
    Suggestion("Open camera", "open camera")
)

@Composable
fun ChatScreen(
    viewModel: JarvisViewModel = viewModel(factory = JarvisViewModel.factory(LocalContext.current))
) {
    val state by viewModel.uiState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val handsFree by viewModel.handsFreeActive.collectAsState()

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var input by rememberSaveable { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    var showMissions by rememberSaveable { mutableStateOf(false) }

    // Poll access state: Accessibility / notification / overlay toggles live in
    // system Settings and cannot notify the app, and auto-send silently no-ops
    // when they are off. This keeps the warning bar honest.
    val context = LocalContext.current
    var accessTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            accessTick++
        }
    }
    val missingRequired = remember(accessTick) { PermissionCatalog.missingRequired(context) }

    if (showPermissions) {
        OnboardingScreen(reviewMode = true, onFinished = { showPermissions = false })
        return
    }

    if (showMissions) {
        MissionsScreen(onClose = { showMissions = false })
        return
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.toggleHandsFreeMode() else viewModel.voicePermissionDenied() }

    val visible = remember(state.messages) {
        state.messages.filter { it.text.isNotBlank() }
    }

    LaunchedEffect(visible.size, state.isLoading) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex.coerceAtLeast(0))
        }
    }

    fun submit(text: String) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        viewModel.send(payload)
        input = ""
        keyboard?.hide()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Panel) {
                SessionDrawer(
                    sessions = state.sessions.map { it.id to it.title },
                    activeId = state.activeSessionId,
                    onSelect = {
                        viewModel.selectSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { viewModel.deleteSession(it) },
                    onNew = {
                        viewModel.newSession()
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    },
                    onPermissions = {
                        showPermissions = true
                        scope.launch { drawerState.close() }
                    },
                    onMissions = {
                        showMissions = true
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink)
                .statusBarsPadding()
        ) {
            TopBar(
                modelLabel = selectedModel?.label ?: "AURIX · Auto",
                onMenu = { scope.launch { drawerState.open() } },
                onModel = { showModels = true },
                onNew = { viewModel.newSession() }
            )

            state.notice?.let { notice ->
                NoticeBar(notice) { viewModel.clearNotice() }
            }

            if (missingRequired.isNotEmpty()) {
                AccessWarningBar(
                    text = missingRequired.first().title + " is OFF — " +
                        missingRequired.first().unlocks.substringBefore(" · ") +
                        " won't work.",
                    onFix = { showPermissions = true }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (visible.isEmpty()) {
                    EmptyState(onSuggestion = { submit(it) })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(visible, key = { it.id }) { message -> Turn(message) }
                        if (state.isLoading) {
                            item { ThinkingRow() }
                        }
                    }
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                onSend = { submit(input) },
                onStop = { viewModel.stopGeneration() },
                onMic = {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                isLoading = state.isLoading,
                handsFree = handsFree
            )
        }
    }

    if (showModels) {
        ModelPickerDialog(
            options = viewModel.availableModels,
            selected = selectedModel,
            isReady = { viewModel.isModelReady(it) },
            onPick = {
                viewModel.selectModel(it)
                showModels = false
            },
            onAuto = {
                viewModel.selectModel(null)
                showModels = false
            },
            onKeys = {
                showModels = false
                showSettings = true
            },
            onDismiss = { showModels = false }
        )
    }

    if (showSettings) {
        ProviderKeysDialog(
            hasKey = { viewModel.hasKey(it) },
            onSave = { typed, done -> viewModel.testAndSaveKeys(typed, done) },
            backendUrl = viewModel.backendUrl,
            onSaveBackend = { url, token, done -> viewModel.saveBackend(url, token, done) },
            onDismiss = { showSettings = false }
        )
    }
}

// ----------------------------------------------------------------------------
// Top bar
// ----------------------------------------------------------------------------

@Composable
private fun TopBar(
    modelLabel: String,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onNew: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Chats", tint = TextHi)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onModel)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(modelLabel, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Change model",
                    tint = TextLo,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = onNew) {
            Icon(Icons.Filled.Add, contentDescription = "New chat", tint = TextHi)
        }
    }
    HorizontalDivider(color = Stroke, thickness = 0.6.dp)
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelSoft)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = TextLo, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = TextLo,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Messages
// ----------------------------------------------------------------------------

@Composable
private fun Turn(message: Message) {
    if (message.sender == Sender.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = PanelSoft,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    message.text,
                    color = TextHi,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (message.isError) {
                    Text(message.text, color = Color(0xFFFF5E57), fontSize = 15.sp)
                } else {
                    MarkdownText(markdown = message.text)
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            color = Accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text("Thinking…", color = TextLo, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "How can I help you today?",
            color = TextHi,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Device commands work offline. Add an API key for full conversation.",
            color = TextLo,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        SUGGESTIONS.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { suggestion ->
                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .border(0.6.dp, Stroke, RoundedCornerShape(14.dp))
                            .clickable { onSuggestion(suggestion.prompt) }
                    ) {
                        Text(
                            suggestion.label,
                            color = TextHi,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ----------------------------------------------------------------------------
// Composer
// ----------------------------------------------------------------------------

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    isLoading: Boolean,
    handsFree: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Panel)
                .border(0.6.dp, Stroke, RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("Message AURIX…", color = TextLo, fontSize = 15.sp) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = TextHi,
                    unfocusedTextColor = TextHi,
                    cursorColor = Accent
                )
            )
            IconButton(onClick = onMic) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Voice",
                    tint = if (handsFree) Accent else TextLo
                )
            }
            Box(
                modifier = Modifier
                    .padding(bottom = 4.dp, end = 2.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (value.isBlank() && !isLoading) PanelSoft else Accent)
                    .clickable { if (isLoading) onStop() else onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isLoading) Icons.Filled.Close else Icons.Filled.Send,
                    contentDescription = if (isLoading) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Session drawer
// ----------------------------------------------------------------------------

@Composable
private fun SessionDrawer(
    sessions: List<Pair<String, String>>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    onMissions: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("AURIX", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onNew)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = TextHi, fontSize = 15.sp)
        }
        HorizontalDivider(color = Stroke, thickness = 0.6.dp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.first }) { (id, title) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (id == activeId) PanelSoft else Color.Transparent)
                        .clickable { onSelect(id) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title.ifBlank { "New session" },
                        color = if (id == activeId) TextHi else TextLo,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onDelete(id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete chat",
                            tint = TextLo,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Stroke, thickness = 0.6.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onSettings)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = TextLo)
            Spacer(Modifier.width(10.dp))
            Text("API keys & routing", color = TextHi, fontSize = 15.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onPermissions)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = TextLo)
            Spacer(Modifier.width(10.dp))
            Text("Permissions & access", color = TextHi, fontSize = 15.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onMissions)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TextLo)
            Spacer(Modifier.width(10.dp))
            Text("Missions", color = TextHi, fontSize = 15.sp)
        }
    }
}

// ----------------------------------------------------------------------------
// Model picker
// ----------------------------------------------------------------------------

@Composable
private fun ModelPickerDialog(
    options: List<ModelOption>,
    selected: ModelOption?,
    isReady: (ModelOption) -> Boolean,
    onPick: (ModelOption) -> Unit,
    onAuto: () -> Unit,
    onKeys: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Choose a model", color = TextHi) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ModelRow(
                    title = "Auto (multi-API routing)",
                    subtitle = "Picks the healthiest configured provider automatically",
                    selected = selected == null,
                    ready = true,
                    onClick = onAuto
                )
                HorizontalDivider(color = Stroke, thickness = 0.6.dp)
                ModelCatalog.providerIds().forEach { providerId ->
                    Text(
                        ModelCatalog.providerLabel(providerId).uppercase(),
                        color = TextLo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    options.filter { it.providerId == providerId }.forEach { option ->
                        ModelRow(
                            title = option.label,
                            subtitle = option.note,
                            selected = selected?.modelId == option.modelId &&
                                selected?.providerId == option.providerId,
                            ready = isReady(option),
                            onClick = { onPick(option) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeys) { Text("Add API keys", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextLo) }
        }
    )
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    ready: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp)
            Text(
                if (ready) subtitle else "$subtitle · key needed",
                color = if (ready) TextLo else Color(0xFFFFA000),
                fontSize = 12.sp
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Provider keys
// ----------------------------------------------------------------------------

private val KEY_FIELDS = listOf(
    "GROQ_API_KEY" to "Groq",
    "GEMINI_API_KEY" to "Google Gemini",
    "OPENROUTER_API_KEY" to "OpenRouter",
    "CEREBRAS_API_KEY" to "Cerebras",
    "MISTRAL_API_KEY" to "Mistral",
    "OPENAI_API_KEY" to "OpenAI"
)

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = PanelSoft,
    unfocusedContainerColor = PanelSoft,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Stroke,
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
    cursorColor = Accent
)

@Composable
private fun ProviderKeysDialog(
    hasKey: (String) -> Boolean,
    onSave: (Map<String, String>, (String) -> Unit) -> Unit,
    backendUrl: String,
    onSaveBackend: (String, String, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val typed = remember { mutableStateMapOf<String, String>() }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(backendUrl) }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("API keys & routing", color = TextHi) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Add one or more keys. AURIX routes every request to the fastest " +
                        "healthy provider and fails over automatically.",
                    color = TextLo,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your own backend (optional)",
                    color = TextHi,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Set a URL and the server owns the keys, routing and long-term " +
                        "memory. Leave blank to route on this device.",
                    color = TextLo,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend URL", color = TextLo, fontSize = 12.sp) },
                    placeholder = {
                        Text("https://aurix.onrender.com", color = TextLo, fontSize = 12.sp)
                    },
                    singleLine = true,
                    colors = fieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("App token (optional)", color = TextLo, fontSize = 12.sp) },
                    singleLine = true,
                    colors = fieldColors()
                )
                TextButton(
                    onClick = {
                        busy = true
                        onSaveBackend(url, token) { message ->
                            result = message
                            busy = false
                        }
                    }
                ) { Text("Save & test backend", color = Accent, fontSize = 13.sp) }
                HorizontalDivider(color = Stroke, thickness = 0.6.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "On-device provider keys",
                    color = TextHi,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                KEY_FIELDS.forEach { (env, label) ->
                    OutlinedTextField(
                        value = typed[env] ?: "",
                        onValueChange = { typed[env] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (hasKey(env)) "$label ✓ saved" else label,
                                color = TextLo,
                                fontSize = 12.sp
                            )
                        },
                        placeholder = { Text("paste key", color = TextLo, fontSize = 12.sp) },
                        singleLine = true,
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                result?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = TextHi, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    val payload = typed.filterValues { it.isNotBlank() }
                    onSave(payload) { message ->
                        result = message
                        busy = false
                    }
                }
            ) { Text(if (busy) "Testing…" else "Save & test", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextLo) }
        }
    )
}

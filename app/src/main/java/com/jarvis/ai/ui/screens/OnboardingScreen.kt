package com.jarvis.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.onboarding.OnboardingPrefs
import com.jarvis.ai.onboarding.PermissionCatalog
import com.jarvis.ai.onboarding.PermissionItem
import com.jarvis.ai.onboarding.PermissionKind
import com.jarvis.ai.ui.components.PermissionStatusRow
import kotlinx.coroutines.delay

/**
 * First-run flow: splash -> one permission card at a time -> ready state.
 *
 * Also doubles as the always-available "Permissions & access" review screen
 * (reached from the drawer), because the same status source is used for both.
 *
 * Skip is always allowed by design — some OEMs bury the Accessibility toggle,
 * and blocking would trap the owner on this screen. Skipped ids are recorded in
 * [OnboardingPrefs] so the chat screen can keep a warning bar visible instead.
 *
 * Special-access items (Accessibility / notification listener / overlay) can
 * only be toggled in system Settings, so while this screen is visible we poll
 * their status every second and the card flips to ON by itself when the owner
 * comes back — no manual refresh, no lifecycle plumbing.
 */

private val Ink = Color(0xFF0B0B0D)
private val Panel = Color(0xFF17171A)
private val PanelSoft = Color(0xFF1F1F23)
private val Stroke = Color(0xFF2C2C31)
private val Accent = Color(0xFFFF1744)
private val Ember = Color(0xFFFF6B00)
private val TextHi = Color(0xFFF3F3F4)
private val TextLo = Color(0xFF9A9AA2)
private val OkGreen = Color(0xFF34C759)

private enum class Stage { SPLASH, CARDS, REVIEW, DONE }

@Composable
fun OnboardingScreen(
    reviewMode: Boolean = false,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val items = remember { PermissionCatalog.items() }

    var stage by remember { mutableStateOf(if (reviewMode) Stage.REVIEW else Stage.SPLASH) }
    var index by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    val skipped = remember { mutableSetOf<String>() }

    // Re-evaluate grant state once a second: system Settings toggles cannot
    // notify us, and this is cheaper than a lifecycle observer per card.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val granted = remember(tick, items) { items.associate { it.id to it.isGranted(context) } }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    fun advance() {
        if (index >= items.lastIndex) {
            OnboardingPrefs.markCompleted(skipped.toSet())
            stage = Stage.DONE
        } else {
            index++
        }
    }

    fun request(item: PermissionItem) {
        when (val kind = item.kind) {
            is PermissionKind.Runtime -> runtimeLauncher.launch(kind.permission)
            is PermissionKind.SpecialAccess -> runCatching { kind.open(context) }
        }
    }

    LaunchedEffect(stage) {
        if (stage == Stage.SPLASH) {
            delay(1400)
            stage = Stage.CARDS
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        when (stage) {
            Stage.SPLASH -> Splash()

            Stage.CARDS -> {
                val item = items[index]
                PermissionCard(
                    item = item,
                    step = index + 1,
                    total = items.size,
                    granted = granted[item.id] == true,
                    onEnable = { request(item) },
                    onSkip = {
                        skipped += item.id
                        OnboardingPrefs.markSkipped(item.id)
                        advance()
                    },
                    onNext = { advance() }
                )
            }

            Stage.REVIEW -> ReviewList(
                items = items,
                granted = granted,
                onTap = { request(it) },
                onClose = onFinished
            )

            Stage.DONE -> ReadyState(
                missingRequired = items.filter { it.required && granted[it.id] != true },
                onFix = { stage = Stage.REVIEW },
                onStart = onFinished
            )
        }
    }
}

@Composable
private fun Splash() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, label = "splashFade")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Ember))),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("AURIX", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("At your service, sir.", color = TextLo, fontSize = 14.sp)
    }
}

@Composable
private fun PermissionCard(
    item: PermissionItem,
    step: Int,
    total: Int,
    granted: Boolean,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        Text("Step $step of $total", color = TextLo, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { i ->
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (i == step - 1) 22.dp else 12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step - 1) Accent else Stroke)
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (granted) OkGreen.copy(alpha = 0.18f) else PanelSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (granted) "✓" else "•",
                color = if (granted) OkGreen else Accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(item.title, color = TextHi, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (item.required) "Required for core features" else "Optional",
            color = if (item.required) Accent else TextLo,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(16.dp))
        Text(item.why, color = TextHi.copy(alpha = 0.86f), fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(14.dp))
        Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Unlocks", color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(item.unlocks, color = TextHi, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        if (granted) {
            Text(
                "Already on — nothing to do here, sir.",
                color = OkGreen,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (step == total) "Finish" else "Next", onNext)
        } else {
            PrimaryButton("Enable", onEnable)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    "Skip for now",
                    color = TextLo,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Text(
                    if (step == total) "Finish" else "Next",
                    color = TextHi,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onNext)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ReviewList(
    items: List<PermissionItem>,
    granted: Map<String, Boolean>,
    onTap: (PermissionItem) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("Permissions & access", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap any row to open its settings. Status updates by itself when you come back.",
            color = TextLo,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                PermissionStatusRow(
                    title = item.title,
                    subtitle = item.unlocks,
                    granted = granted[item.id] == true,
                    required = item.required,
                    onClick = { onTap(item) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Done", onClose)
    }
}

@Composable
private fun ReadyState(
    missingRequired: List<PermissionItem>,
    onFix: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Ember))),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("AURIX is ready", color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (missingRequired.isEmpty()) {
            Text(
                "Everything is switched on. Ask me anything, sir.",
                color = TextLo,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                "Skipped: " + missingRequired.joinToString { it.title } +
                    ". Those features will stay quiet until you switch them on — " +
                    "you can do it any time from the menu.",
                color = TextLo,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Review now",
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onFix)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Start using AURIX", onStart)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Accent, Ember)))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

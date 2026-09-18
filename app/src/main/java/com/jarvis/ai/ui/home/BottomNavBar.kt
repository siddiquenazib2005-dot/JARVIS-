package com.jarvis.ai.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.components.GlassCard
import com.jarvis.ai.ui.theme.extended
import androidx.compose.material3.MaterialTheme

/**
 * The five top-level destinations AURIX exposes from the home screen.
 * `route` matches the destination string used by MainActivity's navigator.
 */
enum class HomeDestination(val route: String, val label: String) {
    HOME("home", "Home"),
    SCAN("scan", "Scan"),
    MEMORIES("memories", "Memories"),
    CHAT("chat", "Chat");

    companion object { val micRoute = "mic" }
}

/**
 * Responsibility: the bottom navigation rail — Home | Scan | centre mic FAB |
 * Memories | Chat.
 *
 * Wiring rules:
 *  - Scan routes to the EXISTING vision/OCR pipeline (no duplicate ML Kit setup);
 *  - the centre FAB toggles the EXISTING hands-free / SpeechRecognizer wiring;
 *  - Chat opens the existing ChatScreen unchanged.
 *
 * The bar is a single GlassCard so the frosted look is consistent with the rest
 * of the home screen. Icons are 48dp-capable IconButtons for accessibility, and
 * every icon carries a contentDescription; the selected tab also announces its
 * label as text.
 */
@Composable
fun BottomNavBar(
    currentRoute: String,
    isListening: Boolean,
    onNavigate: (HomeDestination) -> Unit,
    onMicToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(64.dp),
        strong = true,
        cornerRadius = 32.dp,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavIcon(
                icon = Icons.Outlined.Psychology,
                label = HomeDestination.HOME.label,
                isSelected = currentRoute == HomeDestination.HOME.route,
                onClick = { onNavigate(HomeDestination.HOME) },
                modifier = Modifier.weight(1f)
            )
            NavIcon(
                icon = Icons.Outlined.DocumentScanner,
                label = HomeDestination.SCAN.label,
                isSelected = currentRoute == HomeDestination.SCAN.route,
                onClick = { onNavigate(HomeDestination.SCAN) },
                modifier = Modifier.weight(1f)
            )

            // Centre mic FAB.
            FloatingActionButton(
                onClick = onMicToggle,
                containerColor = colors.glowAccent,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = CircleShape,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Start listening"
                )
            }

            NavIcon(
                icon = Icons.Outlined.Psychology,
                label = HomeDestination.MEMORIES.label,
                isSelected = currentRoute == HomeDestination.MEMORIES.route,
                onClick = { onNavigate(HomeDestination.MEMORIES) },
                modifier = Modifier.weight(1f)
            )
            NavIcon(
                icon = Icons.AutoMirrored.Outlined.Chat,
                label = HomeDestination.CHAT.label,
                isSelected = currentRoute == HomeDestination.CHAT.route,
                onClick = { onNavigate(HomeDestination.CHAT) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) colors.glowAccent else colors.greetingSecondary
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = label,
                    color = if (isSelected) colors.greetingPrimary else colors.greetingSecondary,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

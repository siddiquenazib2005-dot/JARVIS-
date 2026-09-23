package com.jarvis.ai.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
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
 * Responsibility: the row of pill quick actions — Music / Study / Journal.
 *
 * Each chip is a GlassCard with a 48dp+ touch target and a press-scale. Tapping
 * one routes a command into the EXISTING chat pipeline via [onAction]; the chips
 * never run logic themselves.
 */
@Composable
fun QuickActionChips(
    onAction: (label: String, command: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickAction(
            icon = Icons.Outlined.MusicNote,
            label = "Music",
            command = "play some music",
            onAction = onAction,
            modifier = Modifier.weight(1f)
        )
        QuickAction(
            icon = Icons.Outlined.School,
            label = "Study",
            command = "help me study",
            onAction = onAction,
            modifier = Modifier.weight(1f)
        )
        QuickAction(
            icon = Icons.Outlined.AutoStories,
            label = "Journal",
            command = "open my journal",
            onAction = onAction,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    command: String,
    onAction: (label: String, command: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    GlassCard(
        modifier = modifier.height(54.dp),
        onClick = { onAction(label, command) },
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // the text label is read instead
                tint = colors.glowAccent,
                modifier = Modifier.padding(end = 2.dp)
            )
            Text(
                text = label,
                color = colors.greetingPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

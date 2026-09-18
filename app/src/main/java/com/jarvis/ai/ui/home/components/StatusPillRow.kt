package com.jarvis.ai.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.WbSunny
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
 * Responsibility: the two status pills under the greeting — Weather and
 * Energy/streak. Each is a GlassCard; a missing value shows an honest dash
 * rather than a fabricated number.
 *
 * Touch targets are at least 48dp tall (spec: accessibility).
 */
@Composable
fun StatusPillRow(
    weatherText: String?,
    streakText: String,
    onWeatherClick: () -> Unit,
    onStreakClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusPill(
            icon = Icons.Outlined.WbSunny,
            label = weatherText ?: "—",
            onClick = onWeatherClick,
            modifier = Modifier.weight(1f)
        )
        StatusPill(
            icon = Icons.Outlined.LocalFireDepartment,
            label = streakText,
            onClick = onStreakClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    GlassCard(
        modifier = modifier.height(52.dp),
        onClick = onClick,
        cornerRadius = 26.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // label carries the meaning
                tint = colors.glowAccent,
                modifier = Modifier.size(20.dp)
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

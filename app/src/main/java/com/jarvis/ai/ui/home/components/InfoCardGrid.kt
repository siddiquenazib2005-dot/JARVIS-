package com.jarvis.ai.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.components.GlassCard
import com.jarvis.ai.ui.theme.extended
import androidx.compose.material3.MaterialTheme

/**
 * Responsibility: the 2x2 glass card grid — Weather, Date, Mood, Streak.
 *
 * Each cell is a GlassCard with a label and a value. An unknown value renders an
 * honest dash, never a placeholder number.
 */
@Composable
fun InfoCardGrid(
    weatherText: String?,
    dateText: String,
    moodText: String?,
    streakText: String,
    onWeatherClick: () -> Unit,
    onMoodClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCell("Weather", weatherText ?: "—", Modifier.weight(1f), onWeatherClick)
            InfoCell("Date", dateText, Modifier.weight(1f), onClick = null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCell("Mood", moodText ?: "—", Modifier.weight(1f), onMoodClick)
            InfoCell("Streak", streakText, Modifier.weight(1f), onClick = null)
        }
    }
}

@Composable
private fun InfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.extended()
    GlassCard(
        modifier = modifier.height(92.dp),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column {
            Text(
                text = label,
                color = colors.greetingSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = value,
                color = colors.greetingPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

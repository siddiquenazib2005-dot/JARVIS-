package com.jarvis.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable "is this access on?" row.
 *
 * Used by the onboarding wizard and by the in-app permission review sheet, so
 * both surfaces stay visually identical and can never disagree about state.
 */

private val PanelSoft = Color(0xFF1F1F23)
private val TextHi = Color(0xFFF3F3F4)
private val TextLo = Color(0xFF9A9AA2)
private val OkGreen = Color(0xFF34C759)
private val WarnAmber = Color(0xFFFFA000)
private val Accent = Color(0xFFFF1744)

@Composable
fun PermissionStatusRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    required: Boolean,
    onClick: () -> Unit
) {
    val dotColor = when {
        granted -> OkGreen
        required -> Accent
        else -> WarnAmber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextLo, fontSize = 12.sp, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (granted) "ON" else "OFF",
            color = dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Slim warning strip for the chat screen when a required access is off.
 * Tapping it reopens the permission review flow.
 */
@Composable
fun AccessWarningBar(text: String, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A1114))
            .clickable(onClick = onFix)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextHi, fontSize = 12.5.sp, modifier = Modifier.fillMaxWidth(0.82f))
        Spacer(Modifier.width(6.dp))
        Text("FIX", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

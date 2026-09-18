package com.jarvis.ai.ui.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
 * Responsibility: the warm home header — "Good {time-of-day}, {Name}. This is
 * Aurix." plus a live status line.
 *
 * The status line is only rendered when there is real status to show; an empty
 * status collapses to nothing rather than a blank placeholder row. The greeting
 * is computed by [com.jarvis.ai.util.homeHeadline], never hardcoded here.
 */
@Composable
fun GreetingHeader(
    greeting: String,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = greeting,
            color = colors.greetingPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 34.sp
        )
        if (statusText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusText,
                color = colors.glowAccent.copy(alpha = 0.95f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
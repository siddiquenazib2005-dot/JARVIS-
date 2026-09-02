package com.jarvis.ai.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.ui.components.markdown.MarkdownText
import com.jarvis.ai.ui.theme.extended
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.extended()
    val isUser = message.sender == Sender.USER
    val isError = message.isError
    val time = remember(message.timestamp) {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(message.timestamp))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Clean circular avatar (modern chat style), no orbiting glow ring.
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(28.dp)
                    .background(
                        Brush.linearGradient(listOf(palette.electricBlue, palette.violet)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "J",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = if (isUser) Modifier.weight(0.9f) else Modifier.weight(1f)
        ) {
            if (!isUser) {
                Text(
                    "J.A.R.V.I.S.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = when {
                    isUser -> Color.Transparent
                    isError -> Color(0xFF2A1D1F).copy(alpha = 0.85f)
                    else -> palette.jarvisBubble
                },
                border = if (!isUser && !isError) BorderStroke(1.dp, palette.jarvisBorder) else null,
                modifier = Modifier
                    .animateContentSize(animationSpec = tween(120))
            ) {
                when {
                    isUser -> Box(
                        Modifier
                            .background(palette.userBrush, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = message.text,
                            color = palette.onUserBubble,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    message.text.isEmpty() -> Box(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        TypingIndicator()
                    }

                    isError -> Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )

                    else -> MarkdownText(
                        markdown = message.text,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
            if (isUser) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp)
                )
            }
        }
    }
}

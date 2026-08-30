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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val time = remember(message.timestamp) {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(message.timestamp))
    }
    val shape = RoundedCornerShape(
        topStart = if (isUser) 18.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            JarvisOrb(size = 26.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = shape,
                color = if (isUser) Color.Transparent else palette.jarvisBubble,
                border = if (isUser) null else BorderStroke(1.dp, palette.jarvisBorder),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .animateContentSize(animationSpec = tween(120))
            ) {
                when {
                    isUser -> Box(
                        Modifier
                            .background(palette.userBrush, shape)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
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

                    message.isError -> Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )

                    else -> MarkdownText(
                        markdown = message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            Text(
                text = "${message.sender.label} • $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

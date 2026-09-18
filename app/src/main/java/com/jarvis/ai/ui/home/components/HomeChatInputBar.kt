package com.jarvis.ai.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.components.GlassCard
import com.jarvis.ai.ui.theme.extended
import androidx.compose.material3.MaterialTheme

/**
 * Responsibility: the rounded pill input bar on the home screen.
 *
 * Routing rule: text entered here is passed to [onSend], which the screen wires
 * to the EXISTING [com.jarvis.ai.viewmodel.JarvisViewModel.send] — there is no
 * second chat or LLM pathway. The mic button toggles the existing hands-free /
 * SpeechRecognizer wiring via [onMicToggle].
 *
 * The send button is enabled only with non-blank text; the mic icon is a
 * 48dp-capable IconButton for accessibility.
 */
@Composable
fun HomeChatInputBar(
    placeholder: String,
    isListening: Boolean,
    onSend: (String) -> Unit,
    onMicToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.extended()
    var text by remember { mutableStateOf("") }

    GlassCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).height(58.dp),
        strong = true,
        cornerRadius = 29.dp,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMicToggle, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Start listening",
                    tint = if (isListening) colors.glowAccent else colors.greetingSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = colors.greetingPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.glowAccent),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.greetingSecondary,
                            fontSize = 16.sp
                        )
                    }
                    inner()
                }
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send message",
                    tint = if (text.isNotBlank()) colors.glowAccent else colors.greetingSecondary
                )
            }
        }
    }
}

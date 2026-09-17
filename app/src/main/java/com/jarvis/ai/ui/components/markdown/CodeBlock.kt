package com.jarvis.ai.ui.components.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val tokens = remember(language, code) { Highlighter.highlight(language, code) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0B1424).copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27354B)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = language.uppercase().ifBlank { "CODE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FA3BF)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (copied) "COPIED" else "COPY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (copied) Color(0xFF69F0AE) else Color(0xFF6EC7FF),
                    modifier = Modifier
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = highlightedCode(tokens),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

private fun highlightedCode(tokens: List<Token>): AnnotatedString = buildAnnotatedString {
    tokens.forEach { token ->
        withStyle(SpanStyle(color = tokenColor(token.kind), fontWeight = tokenWeight(token.kind))) {
            append(token.text)
        }
    }
}

private fun tokenColor(kind: TokenKind): Color = when (kind) {
    TokenKind.PLAIN -> Color(0xFFDCE9F7)
    TokenKind.COMMENT -> Color(0xFF6B7F99)
    TokenKind.STRING -> Color(0xFF9CDC8F)
    TokenKind.NUMBER -> Color(0xFFF2C879)
    TokenKind.KEYWORD -> Color(0xFF6EC7FF)
    TokenKind.ANNOTATION -> Color(0xFFC792EA)
    TokenKind.PUNCT -> Color(0xFF8FA3BF)
}

private fun tokenWeight(kind: TokenKind): FontWeight? =
    if (kind == TokenKind.KEYWORD) FontWeight.Medium else null

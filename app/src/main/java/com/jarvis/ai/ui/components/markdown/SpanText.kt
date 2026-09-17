package com.jarvis.ai.ui.components.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

@Composable
fun SpanText(
    spans: List<MdSpan>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(spans, primary) { buildStyled(spans, primary) }
    Text(
        text = annotated,
        color = color,
        style = style,
        modifier = modifier
    )
}

private fun buildStyled(spans: List<MdSpan>, primary: Color): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(span.text)
                }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(span.text)
                }
                is MdSpan.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(span.text)
                }
                is MdSpan.InlineCode -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = primary.copy(alpha = 0.95f),
                        background = Color(0xFF16233A)
                    )
                ) { append(span.text) }
                is MdSpan.Link -> withStyle(
                    SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
                ) { append(span.text) }
            }
        }
    }

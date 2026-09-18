package com.jarvis.ai.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.components.GlassCard
import com.jarvis.ai.ui.theme.extended
import com.jarvis.ai.vision.ScreenshotCapture
import com.jarvis.ai.vision.VisionModule
import androidx.compose.material3.MaterialTheme

/**
 * Responsibility: the Scan tab — captures the screen and reads it with the
 * EXISTING ML Kit OCR pipeline ([VisionModule] / [ScreenshotCapture]).
 *
 * Integration rule honoured: no ML Kit setup is duplicated; this screen only
 * calls the existing vision entry points. The recognized text is shown verbatim,
 * and an honest message is shown when there is nothing to recognize or when the
 * capture fails — never a fabricated result.
 */
@Composable
fun ScanPlaceholder(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.extended()
    var result by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Scan",
                color = colors.greetingPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Aurix reads what's on screen using on-device OCR. " +
                            "Nothing is uploaded — recognition happens locally.",
                        color = colors.greetingSecondary
                    )
                    Button(onClick = {
                        isWorking = true
                        error = null
                        result = null
                    }) {
                        Text("Read screen")
                    }
                }
            }

            if (isWorking) {
                CircularProgressIndicator(color = colors.glowAccent)
            }
            error?.let {
                Text(text = it, color = androidx.compose.ui.graphics.Color(0xFFFF5E57))
            }
            result?.let { text ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recognized text", color = colors.greetingSecondary)
                        Text(
                            text = text.ifBlank { "(no text found on screen)" },
                            color = colors.greetingPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone) { Text("Back to Home") }
        }
    }

    // Perform the capture + OCR off the composition pass.
    LaunchedEffect(isWorking) {
        if (!isWorking) return@LaunchedEffect
        val capture = ScreenshotCapture(context)
        val vision = VisionModule(context)
        try {
            val bitmap: Bitmap? = capture.capture()
            if (bitmap == null) {
                error = "Could not capture the screen right now."
            } else {
                val text = vision.extractText(bitmap)
                result = text
            }
        } catch (e: Exception) {
            error = "Scan failed: ${e.message ?: e::class.java.simpleName}"
        } finally {
            isWorking = false
            vision.release()
        }
    }
}

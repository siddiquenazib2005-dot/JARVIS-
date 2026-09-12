package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.diagnostics.CrashGuard
import com.jarvis.ai.onboarding.OnboardingPrefs
import com.jarvis.ai.ui.screens.ChatScreen
import com.jarvis.ai.ui.screens.OnboardingScreen
import com.jarvis.ai.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private fun setRuntimeForeground(foreground: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { JarvisRuntime.get(applicationContext).setAppForeground(foreground) }
                .onFailure { CrashGuard.record(applicationContext, it) }
        }
    }

    override fun onStart() {
        super.onStart()
        setRuntimeForeground(true)
    }

    override fun onStop() {
        setRuntimeForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Black box first: installed before anything else can throw, so the
        // next "AURIX keeps stopping" leaves a stack trace behind instead of a
        // mystery. Readable in-app with the "crash log" command.
        CrashGuard.install(applicationContext)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Must run before the first composition so the gate below reads real state.
        OnboardingPrefs.init(applicationContext)

        setContent {
            JarvisTheme {
                var ready by remember { mutableStateOf(false) }
                var showOnboarding by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    showOnboarding = runCatching { OnboardingPrefs.needsOnboarding }.getOrDefault(false)
                    ready = true
                }

                if (!ready) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF050506)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AURIX starting...",
                            color = Color(0xFFF3F3F4),
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else if (showOnboarding) {
                    OnboardingScreen(onFinished = { showOnboarding = false })
                } else {
                    ChatScreen()
                }
            }
        }
    }
}

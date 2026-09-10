package com.jarvis.ai

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.onboarding.OnboardingPrefs
import com.jarvis.ai.ui.screens.ChatScreen
import com.jarvis.ai.ui.screens.OnboardingScreen
import com.jarvis.ai.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val runtime by lazy { JarvisRuntime.get(applicationContext) }

    override fun onStart() {
        super.onStart()
        runtime.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        runtime.setAppForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Must run before the first composition so the gate below reads real state.
        OnboardingPrefs.init(applicationContext)

        setContent {
            JarvisTheme {
                // The old blind RequestMultiplePermissions batch is gone: it fired on
                // every cold start, stacked system dialogs on top of the chat and
                // ignored the result. The wizard now owns the whole flow, explains
                // each permission, and also covers the three Settings-only accesses
                // (Accessibility / notification listener / overlay) that the batch
                // could never request.
                var showOnboarding by remember { mutableStateOf(OnboardingPrefs.needsOnboarding) }

                if (showOnboarding) {
                    OnboardingScreen(onFinished = { showOnboarding = false })
                } else {
                    ChatScreen()
                }
            }
        }
    }
}

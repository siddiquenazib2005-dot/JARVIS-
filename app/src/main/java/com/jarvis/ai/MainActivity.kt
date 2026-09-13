package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.diagnostics.CrashGuard
import com.jarvis.ai.diagnostics.StartupTracker
import com.jarvis.ai.onboarding.OnboardingPrefs
import com.jarvis.ai.ui.screens.AurixHomeScreen
import com.jarvis.ai.ui.screens.ChatScreen
import com.jarvis.ai.ui.screens.OnboardingScreen
import com.jarvis.ai.ui.theme.JarvisTheme
import com.jarvis.ai.viewmodel.JarvisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        CrashGuard.install(applicationContext)
        StartupTracker.boot(applicationContext)
        StartupTracker.stage(applicationContext, "ACTIVITY_CREATED", "MainActivity.onCreate entered")

        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        OnboardingPrefs.init(applicationContext)
        StartupTracker.stage(applicationContext, "COMPOSE_START", "setContent about to run")

        setContent {
            JarvisTheme {
                var ready by rememberSaveable { mutableStateOf(false) }
                var showOnboarding by rememberSaveable { mutableStateOf(false) }
                var destination by rememberSaveable { mutableStateOf("home") }
                val jarvisViewModel: JarvisViewModel = viewModel(
                    factory = JarvisViewModel.factory(applicationContext)
                )

                LaunchedEffect(Unit) {
                    StartupTracker.stage(applicationContext, "COMPOSE_RENDERED", "startup shell visible")
                    showOnboarding = runCatching { OnboardingPrefs.needsOnboarding }.getOrDefault(false)
                    ready = true
                    StartupTracker.stage(
                        applicationContext,
                        "UI_READY",
                        if (showOnboarding) "onboarding" else "home"
                    )
                }

                BackHandler(enabled = ready && !showOnboarding && destination == "chat") {
                    destination = "home"
                }

                when {
                    !ready -> {
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
                    }
                    showOnboarding -> OnboardingScreen(onFinished = { showOnboarding = false })
                    destination == "chat" -> ChatScreen(viewModel = jarvisViewModel)
                    else -> AurixHomeScreen(
                        viewModel = jarvisViewModel,
                        onOpenChat = { destination = "chat" },
                        onCommand = { command ->
                            jarvisViewModel.send(command)
                            destination = "chat"
                        }
                    )
                }
            }
        }
    }
}

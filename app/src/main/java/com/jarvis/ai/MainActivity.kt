package com.jarvis.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.diagnostics.CrashGuard
import com.jarvis.ai.diagnostics.StartupTracker
import com.jarvis.ai.onboarding.OnboardingPrefs
import com.jarvis.ai.service.WakeWordService
import com.jarvis.ai.ui.home.HomeDestination
import com.jarvis.ai.ui.home.HomeScreen
import com.jarvis.ai.ui.home.MemoriesPlaceholder
import com.jarvis.ai.ui.home.ScanPlaceholder
import com.jarvis.ai.ui.home.HomeViewModel
import com.jarvis.ai.ui.screens.MissionAwareHomeScreen
import com.jarvis.ai.ui.screens.OnboardingScreen
import com.jarvis.ai.ui.screens.VoiceAwareChatScreen
import com.jarvis.ai.ui.theme.JarvisTheme
import com.jarvis.ai.viewmodel.JarvisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingWakeCommand = MutableStateFlow<String?>(null)
    private val pendingVoiceMode = MutableStateFlow(false)

    private fun setRuntimeForeground(foreground: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { JarvisRuntime.get(applicationContext).setAppForeground(foreground) }
                .onFailure { CrashGuard.record(applicationContext, it) }
        }
    }

    private fun acceptAssistantIntent(source: Intent?) {
        val command = source
            ?.getStringExtra(WakeWordService.EXTRA_WAKE_COMMAND)
            ?.trim()
            .orEmpty()
        if (command.isNotBlank()) pendingWakeCommand.value = command
        if (source?.getBooleanExtra(EXTRA_OPEN_VOICE_MODE, false) == true) {
            pendingVoiceMode.value = true
        }
        source?.removeExtra(WakeWordService.EXTRA_WAKE_COMMAND)
        source?.removeExtra(EXTRA_OPEN_VOICE_MODE)
    }

    override fun onStart() {
        super.onStart()
        setRuntimeForeground(true)
    }

    override fun onStop() {
        setRuntimeForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAssistantIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashGuard.install(applicationContext)
        StartupTracker.boot(applicationContext)
        StartupTracker.stage(applicationContext, "ACTIVITY_CREATED", "MainActivity.onCreate entered")

        super.onCreate(savedInstanceState)
        acceptAssistantIntent(intent)
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
                var destination by rememberSaveable { mutableStateOf(HomeDestination.HOME.route) }
                val wakeCommand by pendingWakeCommand.collectAsState()
                val openVoiceMode by pendingVoiceMode.collectAsState()
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

                LaunchedEffect(wakeCommand, ready, showOnboarding) {
                    val command = wakeCommand
                    if (ready && !showOnboarding && !command.isNullOrBlank()) {
                        pendingWakeCommand.value = null
                        jarvisViewModel.send(command)
                        destination = HomeDestination.CHAT.route
                    }
                }

                LaunchedEffect(openVoiceMode, ready, showOnboarding) {
                    if (openVoiceMode && ready && !showOnboarding) {
                        pendingVoiceMode.value = false
                        destination = HomeDestination.CHAT.route
                        if (ContextCompat.checkSelfPermission(
                                applicationContext,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED &&
                            !jarvisViewModel.handsFreeActive.value
                        ) {
                            jarvisViewModel.toggleHandsFreeMode()
                        }
                    }
                }

                BackHandler(enabled = ready && !showOnboarding && destination != HomeDestination.HOME.route) {
                    if (jarvisViewModel.handsFreeActive.value) {
                        jarvisViewModel.toggleHandsFreeMode()
                    } else {
                        destination = HomeDestination.HOME.route
                    }
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
                    destination == HomeDestination.CHAT.route -> VoiceAwareChatScreen(viewModel = jarvisViewModel)
                    destination == HomeDestination.SCAN.route -> ScanPlaceholder(
                        onDone = { destination = HomeDestination.HOME.route }
                    )
                    destination == HomeDestination.MEMORIES.route -> MemoriesPlaceholder(
                        onDone = { destination = HomeDestination.HOME.route }
                    )
                    else -> HomeScreen(
                        viewModel = remember {
                            HomeViewModel.factory(applicationContext, jarvisViewModel)
                        }.create(HomeViewModel::class.java),
                        onOpenChat = { destination = HomeDestination.CHAT.route },
                        onOpenMemories = { destination = HomeDestination.MEMORIES.route },
                        onOpenScan = { destination = HomeDestination.SCAN.route },
                        onOpenSettings = { destination = HomeDestination.HOME.route }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE_MODE = "com.jarvis.ai.extra.OPEN_VOICE_MODE"
    }
}

package com.jarvis.ai.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jarvis.ai.data.model.UiState
import com.jarvis.ai.memory.MemoryEngine
import com.jarvis.ai.memory.SecureKvStore
import com.jarvis.ai.ui.home.components.AvatarState
import com.jarvis.ai.viewmodel.JarvisViewModel
import com.jarvis.ai.util.greetingNow
import com.jarvis.ai.util.homeHeadline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Responsibility: the state projection layer for the AURIX companion home screen.
 *
 * It deliberately contains NO LLM, orchestration or routing logic of its own. It
 * observes the EXISTING [JarvisViewModel] (chat pipeline, hands-free/STT, acting
 * and provider state) and maps those onto [HomeUiState]; the only data it owns is
 * the new companion surface - user name, weather, streak, mood, journal prompt.
 *
 * Messages typed into the home input bar are handed straight to
 * [JarvisViewModel.send], so there is exactly one chat pathway in the app.
 */
class HomeViewModel(
    private val appContext: Context,
    private val chat: JarvisViewModel
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(HomeUiState())
    /** The single source of truth the home screen renders. */
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Project the existing chat/assistant state onto the home state. combine
        // keeps this reactive: any change in the real pipeline surfaces here.
        viewModelScope.launch {
            chat.uiState.combine(chat.handsFreeActive) { ui, handsFree ->
                project(ui, handsFree)
            }.collect { projected ->
                _state.value = _state.value.copy(
                    avatarState = projected.avatarState,
                    statusText = projected.statusText,
                    isBusy = projected.isBusy,
                    isOffline = projected.isOffline,
                    providerError = projected.providerError
                )
            }
        }
        loadUserName()
    }

    /** The greeting header, recomputed from the stored name and the clock. */
    val greeting: String get() = homeHeadline(_state.value.userName.ifBlank { null })

    /** Routes typed text into the EXISTING chat pipeline — no new pathway. */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        chat.send(text.trim())
    }

    /** Hands-free / mic toggle, delegated to the existing STT wiring. */
    fun toggleMic() = chat.toggleHandsFreeMode()

    /** Stores the user's name; used by the greeting header. */
    fun setUserName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_USER_NAME, trimmed).apply()
        _state.value = _state.value.copy(userName = trimmed)
    }

    private fun loadUserName() {
        _state.value = _state.value.copy(userName = prefs.getString(KEY_USER_NAME, "").orEmpty())
    }

    /**
     * Pure mapping from the real assistant state to the home surface. Extracted so
     * the mapping is testable without a live device: pass a [UiState] and assert.
     */
    private fun project(ui: UiState, handsFree: Boolean): HomeUiState {
        val avatar = when {
            ui.isSpeaking -> AvatarState.TALKING
            handsFree || ui.isListening -> AvatarState.LISTENING
            ui.isLoading || ui.isActing -> AvatarState.TALKING
            else -> AvatarState.IDLE
        }
        val status = when {
            handsFree -> "Listening..."
            ui.isListening -> "Listening..."
            ui.isSpeaking -> "Speaking..."
            ui.isActing -> "Acting on screen..."
            ui.isLoading -> "Thinking..."
            ui.hasPendingConfirmation -> "Needs your confirmation"
            else -> ""
        }
        val error = when {
            ui.apiKeyMissing -> "No provider key is set — add one in Settings to chat."
            ui.notice != null && ui.notice.contains("fail", ignoreCase = true) -> ui.notice
            else -> null
        }
        return HomeUiState(
            avatarState = avatar,
            statusText = status,
            isBusy = ui.isLoading || ui.isActing,
            isOffline = !ui.backendOnline && ui.apiKeyMissing,
            providerError = error
        )
    }

    companion object {
        private const val PREFS = "aurix_home"
        private const val KEY_USER_NAME = "user_name"

        /** Manual factory, matching the existing JarvisViewModel.pattern. */
        fun factory(context: Context, chat: JarvisViewModel): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    HomeViewModel(context.applicationContext, chat)
                }
            }
    }
}

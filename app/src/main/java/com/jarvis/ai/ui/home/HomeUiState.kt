package com.jarvis.ai.ui.home

import com.jarvis.ai.ui.home.components.AvatarState

/**
 * Responsibility: the single immutable state object the AURIX home screen renders.
 *
 * Sourcing rules (why this is a projection and NOT a second brain):
 *  - every chat/LLM field is derived from the EXISTING [com.jarvis.ai.viewmodel
 *    .JarvisViewModel] UiState and hands-free flow; this class never owns a
 *    separate copy of conversation or provider state;
 *  - weather/mood/streak/journal are the only genuinely new data, owned here;
 *  - loading/empty/offline states are explicit so the UI never has to guess.
 */
data class HomeUiState(

    /** Shown in the greeting header; empty until the user tells AURIX their name. */
    val userName: String = "",

    /** Reactive avatar state, mapped from the existing assistant state. */
    val avatarState: AvatarState = AvatarState.IDLE,

    /** Human-readable live status, e.g. "Listening...", "Thinking...". */
    val statusText: String = "",

    /** True only while a real request is in flight in the existing pipeline. */
    val isBusy: Boolean = false,

    /** Network reachability, for the friendly offline card. */
    val isOffline: Boolean = false,

    /** True while the weather is being fetched for the first time. */
    val isLoadingWeather: Boolean = false,

    val weather: WeatherSummary? = null,
    val streak: StreakSummary = StreakSummary(),
    val mood: MoodSummary? = null,
    val journalPrompt: String = "",

    /** Set when every LLM provider has failed; shown as a graceful chat bubble. */
    val providerError: String? = null,

    /** Onboarding has not been completed for this flow version. */
    val needsOnboarding: Boolean = false
)

/** Condensed weather; the home card never needs the full API payload. */
data class WeatherSummary(
    val tempC: Int,
    val condition: String,
    val location: String,
    val iconCode: String? = null
) {
    val display: String get() = "$tempC° · $condition"
}

/** Daily streak. currentDay counts consecutive days of interaction. */
data class StreakSummary(
    val currentDay: Int = 0,
    val energy: Int = 0
) {
    val display: String get() = if (currentDay <= 0) "Day 1" else "Day $currentDay"
}

/** Latest logged mood, for the Mood card. */
data class MoodSummary(
    val label: String,
    val emoji: String
)

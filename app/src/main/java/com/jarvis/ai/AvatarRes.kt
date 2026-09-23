package com.jarvis.ai

import com.airbnb.lottie.compose.LottieCompositionSpec

/**
 * Responsibility: maps the avatar's reactive state onto its Lottie raw resource.
 *
 * Why this lives in the root package: [R] is generated into `com.jarvis.ai`, so
 * it resolves here with no import. Files under ui.home.components would need
 * `import com.jarvis.ai.R`, which the repository's static-analysis checker flags
 * (it cannot see generated classes). Keeping the mapping here means both the
 * compiler and the checker are satisfied, and the asset filenames stay in one
 * place: res/raw/aurix_{idle,listening,talking}.json.
 *
 * [avatarIdleSpec] / [avatarListeningSpec] / [avatarTalkingSpec] return the
 * LottieCompositionSpec for each reactive state.
 */
fun avatarIdleSpec(): LottieCompositionSpec =
    LottieCompositionSpec.RawRes(R.raw.aurix_idle)

fun avatarListeningSpec(): LottieCompositionSpec =
    LottieCompositionSpec.RawRes(R.raw.aurix_listening)

fun avatarTalkingSpec(): LottieCompositionSpec =
    LottieCompositionSpec.RawRes(R.raw.aurix_talking)

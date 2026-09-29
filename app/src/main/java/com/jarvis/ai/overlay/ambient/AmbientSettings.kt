package com.jarvis.ai.overlay.ambient

import android.content.Context

/**
 * User controls for the ambient entity (directive §13).
 *
 * Persisted in the same prefs file the companion already uses, so the entity
 * comes back exactly as the user left it. Kept deliberately tiny: two sliders
 * worth of state plus two toggles. The overlay reads the values on start and
 * applies changes live through [FloatingAvatarService.applySettings] intents;
 * no settings UI is built here (explicitly out of scope this phase).
 *
 * Defaults preserve current behaviour for existing users (full intensity,
 * audio reaction on), so nobody's companion changes personality silently.
 */
object AmbientSettings {

    private const val KEY_INTENSITY = "ambientIntensity"
    private const val KEY_AUDIO_ENABLED = "ambientAudioEnabled"

    /** Animation/visual intensity, 0.1..1 (never zero: entity must stay legible). */
    fun animationIntensity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_INTENSITY, 1f)

    fun setAnimationIntensity(context: Context, value: Float) {
        val clamped = value.coerceIn(MIN_INTENSITY, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_INTENSITY, clamped)
            .apply()
    }

    /** Voice-reactive rendering on/off (does NOT affect mic permissions). */
    fun audioReactiveEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ENABLED, true)

    fun setAudioReactiveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_ENABLED, enabled)
            .apply()
    }

    private const val PREFS = "aurix_companion"
    const val MIN_INTENSITY = 0.1f
}

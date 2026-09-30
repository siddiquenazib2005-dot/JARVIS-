package com.jarvis.ai.service.wakeword

/**
 * Provider-agnostic wake-word contract. AURIX talks ONLY to this interface —
 * no other layer may reference openWakeWord (or any future engine) directly,
 * so swapping in a custom "AURIX" model later is a one-class change.
 *
 * Semantics expected of every implementation:
 *  - [start] arms the engine (mic + model). Returns false on any failure;
 *    it must NEVER throw. Retries stay the caller's job (backoff lives in
 *    WakeWordService, not here).
 *  - [stop] releases the microphone but KEEPS the instance usable — a later
 *    [start] must work without re-creating the object.
 *  - [pause]/[resume] temporarily yield the microphone while another voice
 *    session (chat STT) holds it, without tearing down state. Implementations
 *    must not deliver detections while paused.
 *  - [release] destroys everything permanently; the instance cannot restart.
 *  - Listener callbacks must never crash the host: implementations wrap
 *    dispatches in runCatching.
 */
interface WakeWordProvider {
    fun start(): Boolean
    fun stop()
    fun pause()
    fun resume(): Boolean
    fun isRunning(): Boolean
    fun setListener(listener: WakeWordListener?)
    fun release()
}

/** Normalized callback: a detection with a confidence score in 0..1. */
fun interface WakeWordListener {
    fun onWakeWordDetected(event: WakeWordDetected)
}

/**
 * Immutable detection event. The rest of AURIX consumes this — it never
 * learns which engine produced it.
 */
data class WakeWordDetected(
    /** Confidence score, 0..1, as reported by the engine. */
    val score: Float,
    /** Epoch ms of the detection (host clock; tests inject their own). */
    val detectedAtMs: Long
)

/**
 * Single source of truth for wake-word tuning. The temporary "HEY JARVIS"
 * phrase appears HERE and nowhere else in the codebase — moving to a custom
 * "AURIX" model later means editing only this file (plus shipping a custom
 * ONNX asset), never the service/UI layers.
 */
data class WakeWordConfig(
    /**
     * Human-readable phrase for logs/notifications. TEMPORARY for this phase.
     * Not used for matching — the phrase lives inside the ONNX model itself.
     */
    val phrase: String,
    /** Detection threshold as passed to the engine (0.01..0.99). */
    val threshold: Float,
    /** Minimum ms between accepted detections (engine-level false-trigger guard). */
    val debounceMs: Long,
    /** True only while the phrase inside [modelAssetPath] is the active phrase. */
    val customModelAssetPath: String?
) {
    companion object {
        /**
         * Asset path of the custom "AURIX" wake-word model. The asset is
         * produced by the repo's own training pipeline
         * (.github/workflows/train-wakeword.yml, openWakeWord synthetic-data
         * recipe) and committed at app/src/main/assets/openwakeword/aurix.onnx.
         * Until that asset exists, [com.jarvis.ai.service.wakeword
         * .OpenWakeWordProvider] falls back to the built-in temporary model —
         * the app never breaks while the custom model is unavailable.
         */
        const val AURIX_MODEL_ASSET = "openwakeword/aurix.onnx"

        /**
         * The one and only place the wake phrase is configured. Default
         * threshold and debounce follow the engine defaults (0.5 / 2000 ms)
         * so behaviour is predictable before any on-device tuning.
         */
        val DEFAULT = WakeWordConfig(
            phrase = "HEY JARVIS",
            threshold = 0.5f,
            debounceMs = 2_000L,
            customModelAssetPath = AURIX_MODEL_ASSET
        )
    }
}

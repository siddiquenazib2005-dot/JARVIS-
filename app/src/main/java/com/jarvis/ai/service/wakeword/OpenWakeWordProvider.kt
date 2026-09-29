package com.jarvis.ai.service.wakeword

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openwakeword.OpenWakeWord
import java.util.concurrent.Executors

/**
 * The ONLY class in AURIX allowed to import openWakeWord. Wraps the ONNX
 * engine behind [WakeWordProvider] so the engine can later be swapped for a
 * custom "AURIX" model (same ONNX pipeline, different asset) without touching
 * WakeWordService, the overlay or any UI.
 *
 * Failure philosophy (directive §ERROR HANDLING): every engine call is wrapped
 * in runCatching — a broken model, a held mic or a missing ONNX runtime must
 * degrade to "wake word unavailable" (start() == false / events simply stop),
 * never crash AURIX. Manual activation, chat and existing voice paths stay
 * untouched when the engine is dead.
 *
 * Threading: the engine's stop()/release() join its processing thread (up to
 * ~3s worst case), so those calls are forwarded to a single background
 * executor — the main thread is never blocked. start() only spawns the
 * engine thread and is safe from the caller's thread. The engine delivers
 * detections on the main thread; [dispatch] re-gates them through
 * [isRunning] so a callback already in flight during pause/stop is dropped.
 *
 * Audio ownership: the engine owns its own 16 kHz VOICE_RECOGNITION
 * AudioRecord and is the ONLY always-on capture when armed. It is paused by
 * WakeWordService whenever a chat STT session takes the mic
 * (VoiceSessionGate) or the assistant is THINKING/SPEAKING, and resumed when
 * the mic is free — there is never a second live capture.
 */
class OpenWakeWordProvider(
    context: Context,
    private val config: WakeWordConfig
) : WakeWordProvider {

    private val appContext = context.applicationContext

    /** Serial executor so engine stop/release never overlap or hit main. */
    private val ops = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aurix_wakeword_ops").apply { isDaemon = true }
    }

    @Volatile
    private var engine: OpenWakeWord? = null

    @Volatile
    private var listener: WakeWordListener? = null

    /** True between a successful start() and stop()/release()/pause(). */
    @Volatile
    private var started = false

    @Volatile
    private var paused = false

    override fun start(): Boolean {
        if (started) return true
        if (!hasMicPermission()) return false
        val built = engine ?: buildEngine() ?: return false
        engine = built
        // The engine posts its callback to the main thread already.
        val dispatched = runCatching {
            built.start { score -> dispatch(score) }
        }
        if (dispatched.isFailure) return false
        started = true
        paused = false
        return true
    }

    override fun stop() {
        started = false
        paused = false
        val current = engine ?: return
        ops.execute { runCatching { current.stop() } }
    }

    /** Yields the mic without forgetting state; resume() re-arms it. */
    override fun pause() {
        if (!started) return
        paused = true
        val current = engine ?: return
        ops.execute { runCatching { current.stop() } }
    }

    override fun resume(): Boolean {
        if (!started || !paused) return started
        paused = false
        val current = engine ?: return false
        val ok = runCatching { current.start { score -> dispatch(score) } }.isSuccess
        if (!ok) {
            // The engine refused to come back: mark ourselves stopped so the
            // caller's backoff loop restarts from a clean slate instead of a
            // zombie "running" state.
            started = false
        }
        return ok
    }

    override fun isRunning(): Boolean = started && !paused

    override fun setListener(listener: WakeWordListener?) {
        this.listener = listener
    }

    override fun release() {
        started = false
        paused = false
        listener = null
        val current = engine
        engine = null
        current?.let { target ->
            ops.execute {
                runCatching { target.release() }
            }
        }
        ops.shutdown()
    }

    /** Builds a fresh engine. Null = model/ONNX unavailable (reported honestly). */
    private fun buildEngine(): OpenWakeWord? = runCatching {
        val builder = OpenWakeWord.Builder(appContext)
            .setThreshold(config.threshold)
            .setDebounceMs(config.debounceMs)
        val asset = config.customModelAssetPath
        if (asset.isNullOrBlank()) {
            builder.setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
        } else {
            builder.setModelAsset(asset)
        }
        builder.build()
    }.getOrNull()

    /** Engine already delivers on the main thread; gate + fan out safely. */
    private fun dispatch(score: Float) {
        if (!isRunning()) return
        val target = listener ?: return
        runCatching { target.onWakeWordDetected(WakeWordDetected(score, System.currentTimeMillis())) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

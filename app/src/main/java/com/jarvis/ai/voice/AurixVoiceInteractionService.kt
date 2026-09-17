package com.jarvis.ai.voice

import android.service.voice.VoiceInteractionService
import com.jarvis.ai.diagnostics.DiagnosticsLog

/**
 * Android's official default-assistant entry point.
 *
 * Once the user selects AURIX as the Digital assistant app, Android routes the
 * device's assistant gesture (including long-press power on supported phones)
 * here. No Accessibility service or key interception is involved.
 */
class AurixVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        DiagnosticsLog.record("voice-assistant", "Default assistant service ready")
    }

    override fun onShutdown() {
        DiagnosticsLog.record("voice-assistant", "Default assistant service stopped")
        super.onShutdown()
    }
}

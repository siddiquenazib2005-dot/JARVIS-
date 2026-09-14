package com.jarvis.ai.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the system-owned AURIX assistant surface for every invocation. */
class AurixVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AurixVoiceInteractionSession(this)
}

package com.jarvis.ai.overlay.ambient

import java.util.concurrent.CopyOnWriteArrayList

/**
 * App -> overlay side-channel for legitimate AURIX-internal important events
 * (Phase E: IMPORTANT_EVENT presence boost, e.g. a finished task or an
 * attention-worthy result). The boost decays automatically after
 * [AmbientContextController.DEFAULT_IMPORTANT_EVENT_MS].
 *
 * Deliberately one-directional and content-free: callers announce "something
 * important happened", never what the content was — no screen/content
 * monitoring anywhere. AURIX's own UI layer may call [announce]; execution
 * systems (MissionEngine) stay decoupled from presentation.
 */
object AmbientEventBus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Signals an important event; overlay boosts presence briefly. */
    fun announce() {
        listeners.forEach { runCatching { it() } }
    }

    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun stopObserving(listener: () -> Unit) {
        listeners -= listener
    }

    /** Test/diagnostic reset. */
    fun reset() {
        listeners.clear()
    }
}

/**
 * Intent action any AURIX component can fire to trigger the same presence
 * boost through [com.jarvis.ai.overlay.FloatingAvatarService] — content-free
 * by contract: the intent carries NO payload describing the event.
 */
object AmbientEventBusContract {
    const val ACTION_IMPORTANT_EVENT = "com.aurix.ai.companion.IMPORTANT_EVENT"
}

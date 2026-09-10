package com.jarvis.ai.missions

/**
 * UI-side catalog of every verb MissionEngine.execute() understands.
 *
 * Deliberately a separate, additive file: the engine's execute() when-block
 * stays untouched. This only *describes* those verbs so the mission builder can
 * offer a picker instead of making the owner remember 33 keywords.
 *
 * If a verb is ever added to the engine, add a row here too. A missing row only
 * means the verb is not offered in the picker; existing missions keep working.
 */
data class MissionVerb(
    /** Must match the string the engine's execute() switches on. */
    val action: String,
    val label: String,
    val group: String,
    /** Hint shown in the argument field; null means the verb takes no argument. */
    val argumentHint: String? = null
) {
    val needsArgument: Boolean get() = argumentHint != null
}

object MissionVerbs {

    val all: List<MissionVerb> = listOf(
        MissionVerb("open_app", "Open app", "Apps & web", "App name, e.g. whatsapp"),
        MissionVerb("open_url", "Open link", "Apps & web", "https://..."),
        MissionVerb("web_search", "Web search", "Apps & web", "What to search"),
        MissionVerb("youtube", "YouTube search", "Apps & web", "What to play"),
        MissionVerb("share", "Share text", "Apps & web", "Text to share"),
        MissionVerb("settings", "Open settings page", "Apps & web", "e.g. display, wifi"),

        MissionVerb("whatsapp", "WhatsApp message", "Communication", "contact | message"),
        MissionVerb("sms", "Send SMS", "Communication", "contact | message"),
        MissionVerb("call", "Call", "Communication", "Contact or number"),

        MissionVerb("torch_on", "Flashlight on", "Device"),
        MissionVerb("torch_off", "Flashlight off", "Device"),
        MissionVerb("volume", "Set volume %", "Device", "0-100"),
        MissionVerb("mute", "Mute", "Device"),
        MissionVerb("unmute", "Unmute", "Device"),
        MissionVerb("battery", "Battery status", "Device"),
        MissionVerb("device_status", "Device report", "Device"),
        MissionVerb("timer", "Set timer", "Device", "Seconds, e.g. 300"),

        MissionVerb("play_pause", "Play / pause", "Media & files"),
        MissionVerb("next_track", "Next track", "Media & files"),
        MissionVerb("camera", "Open camera", "Media & files"),
        MissionVerb("gallery", "Open gallery", "Media & files"),
        MissionVerb("files", "Open files", "Media & files"),

        MissionVerb("maps", "Open maps", "Places"),
        MissionVerb("navigate", "Navigate to", "Places", "Destination"),
        MissionVerb("nearby", "Find nearby", "Places", "e.g. petrol pump"),

        MissionVerb("tap", "Tap on screen", "Screen control", "Button or text to tap"),
        MissionVerb("type", "Type text", "Screen control", "Text to type"),
        MissionVerb("scroll_down", "Scroll down", "Screen control"),
        MissionVerb("scroll_up", "Scroll up", "Screen control"),
        MissionVerb("back", "Press back", "Screen control"),
        MissionVerb("home", "Go home", "Screen control"),
        MissionVerb("read_screen", "Read screen", "Screen control"),
        MissionVerb("wait", "Wait a moment", "Screen control")
    )

    val groups: List<String> = all.map { it.group }.distinct()

    fun byAction(action: String): MissionVerb? =
        all.firstOrNull { it.action.equals(action, ignoreCase = true) }

    /** Human label for a saved step, falling back to the raw verb. */
    fun describe(step: MissionStep): String {
        val label = byAction(step.action)?.label ?: step.action
        return if (step.argument.isBlank()) label else label + ": " + step.argument
    }
}

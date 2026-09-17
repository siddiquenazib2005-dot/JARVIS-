package com.jarvis.ai.core.agent

import kotlinx.serialization.Serializable

data class ParamDefinition(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String,
    val enumValues: List<String> = emptyList(),
    val defaultValue: Any? = null
)

enum class ParamType { STRING, INT, BOOLEAN, ENUM }

enum class ActionCategory {
    SYSTEM, COMMUNICATION, PRODUCTIVITY,
    INFORMATION, TRANSPORT, MEDIA,
    SHOPPING, FINANCE, SMART_HOME,
    MACRO, ADVANCED, AGENT, NOTIFICATION,
    VISION, ACCESSIBILITY
}

data class ActionDefinition(
    val name: String,
    val description: String,
    val params: List<ParamDefinition>,
    val examples: List<String>,
    val category: ActionCategory,
    val isSimple: Boolean = true,
    val neverAutoApprove: Boolean = false,
    val risk: ActionRisk = ActionRisk.UNSPECIFIED
)

enum class ActionRisk { UNSPECIFIED, LOW, MEDIUM, HIGH }

sealed class ValidationResult {
    data class Valid(val enrichedParams: Map<String, Any?>) : ValidationResult()
    data class MissingParams(val params: List<String>, val missingParams: List<String>) : ValidationResult()
    data class InvalidAction(val message: String) : ValidationResult()
}

object ActionSchema {
    val ALL_ACTIONS = listOf(
        ActionDefinition(
            name = "OPEN_APP",
            description = "Opens any installed app on the device",
            params = listOf(ParamDefinition("appName", ParamType.STRING, true, "Name of the app to open")),
            examples = listOf("open whatsapp", "open chrome", "launch spotify"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "OPEN_SETTINGS",
            description = "Opens Android Settings app",
            params = listOf(ParamDefinition("settingsPath", ParamType.STRING, false, "Specific settings path")),
            examples = listOf("open settings", "go to settings"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TAKE_SCREENSHOT",
            description = "Takes a screenshot of current screen",
            params = emptyList(),
            examples = listOf("take screenshot", "capture screen"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_WIFI",
            description = "Turns WiFi on, off, or toggles",
            params = listOf(ParamDefinition("state", ParamType.ENUM, false, "Desired WiFi state", enumValues = listOf("on", "off", "toggle"), defaultValue = "toggle")),
            examples = listOf("wifi on", "turn off wifi", "toggle wifi"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_BLUETOOTH",
            description = "Turns Bluetooth on, off, or toggles",
            params = listOf(ParamDefinition("state", ParamType.ENUM, false, "Desired Bluetooth state", enumValues = listOf("on", "off", "toggle"), defaultValue = "toggle")),
            examples = listOf("bluetooth on", "turn off bluetooth"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_FLASHLIGHT",
            description = "Turns flashlight on, off, or toggles",
            params = listOf(ParamDefinition("state", ParamType.ENUM, false, "Desired flashlight state", enumValues = listOf("on", "off", "toggle"), defaultValue = "toggle")),
            examples = listOf("flashlight on", "torch off"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_BRIGHTNESS",
            description = "Sets screen brightness level",
            params = listOf(ParamDefinition("level", ParamType.INT, false, "Brightness 0-100", defaultValue = 50)),
            examples = listOf("set brightness to 50", "brightness 80"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_VOLUME",
            description = "Sets device volume",
            params = listOf(
                ParamDefinition("type", ParamType.ENUM, false, "Volume type", enumValues = listOf("media", "ring", "alarm", "notification"), defaultValue = "media"),
                ParamDefinition("level", ParamType.INT, true, "Volume level 0-100")
            ),
            examples = listOf("set volume to 50", "volume 80"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "MAKE_CALL",
            description = "Makes a phone call to a contact or number",
            params = listOf(ParamDefinition("contact", ParamType.STRING, true, "Contact name or phone number")),
            examples = listOf("call mom", "call 9876543210"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_SMS",
            description = "Sends an SMS text message",
            params = listOf(
                ParamDefinition("contact", ParamType.STRING, true, "Contact name or number"),
                ParamDefinition("message", ParamType.STRING, true, "SMS message text")
            ),
            examples = listOf("send sms to mom"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_WHATSAPP",
            description = "Sends WhatsApp message",
            params = listOf(
                ParamDefinition("contact", ParamType.STRING, true, "Contact name or number"),
                ParamDefinition("message", ParamType.STRING, true, "Message to send")
            ),
            examples = listOf("send hi to dad on whatsapp"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "SEND_EMAIL",
            description = "Opens a pre-filled email draft",
            params = listOf(
                ParamDefinition("to", ParamType.STRING, true, "Recipient email"),
                ParamDefinition("subject", ParamType.STRING, true, "Email subject"),
                ParamDefinition("body", ParamType.STRING, true, "Email body")
            ),
            examples = listOf("send email to boss"),
            category = ActionCategory.COMMUNICATION
        ),
        ActionDefinition(
            name = "WEB_SEARCH",
            description = "Searches the web for information",
            params = listOf(ParamDefinition("query", ParamType.STRING, true, "Search query")),
            examples = listOf("search best restaurants", "google latest iphone"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "GET_WEATHER",
            description = "Gets current weather for a location",
            params = listOf(
                ParamDefinition("location", ParamType.STRING, false, "City name", defaultValue = "current location"),
                ParamDefinition("days", ParamType.STRING, false, "Forecast days", defaultValue = "1")
            ),
            examples = listOf("weather today", "weather in Mumbai"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "GET_NEWS",
            description = "Gets latest news on a topic",
            params = listOf(
                ParamDefinition("topic", ParamType.STRING, false, "News topic"),
                ParamDefinition("count", ParamType.STRING, false, "Number of articles", defaultValue = "5")
            ),
            examples = listOf("latest news", "tech news"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "CALCULATE",
            description = "Performs a mathematical calculation",
            params = listOf(ParamDefinition("expression", ParamType.STRING, true, "Math expression to calculate")),
            examples = listOf("calculate 15% of 2000", "what is 45 * 12"),
            category = ActionCategory.INFORMATION
        ),
        ActionDefinition(
            name = "GET_DIRECTIONS",
            description = "Gets directions to a destination",
            params = listOf(
                ParamDefinition("to", ParamType.STRING, true, "Destination"),
                ParamDefinition("from", ParamType.STRING, false, "Starting point"),
                ParamDefinition("mode", ParamType.ENUM, false, "Travel mode", enumValues = listOf("drive", "walk", "transit", "bike"), defaultValue = "drive")
            ),
            examples = listOf("directions to airport", "how to reach mall"),
            category = ActionCategory.TRANSPORT
        ),
        ActionDefinition(
            name = "PLAY_MUSIC",
            description = "Plays music on Spotify or YouTube",
            params = listOf(
                ParamDefinition("query", ParamType.STRING, true, "Song, artist, or playlist name"),
                ParamDefinition("app", ParamType.ENUM, false, "Music app", enumValues = listOf("spotify", "youtube", "local"), defaultValue = "spotify")
            ),
            examples = listOf("play Arijit Singh", "play Bollywood hits"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "PAUSE_MUSIC",
            description = "Pauses currently playing music",
            params = emptyList(),
            examples = listOf("pause music", "stop song"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "NEXT_TRACK",
            description = "Skips to next track",
            params = emptyList(),
            examples = listOf("next song", "skip track"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "TAKE_PHOTO",
            description = "Takes a photo using camera",
            params = listOf(ParamDefinition("camera", ParamType.ENUM, false, "Camera to use", enumValues = listOf("back", "front"), defaultValue = "back")),
            examples = listOf("take photo", "take selfie"),
            category = ActionCategory.MEDIA
        ),
        ActionDefinition(
            name = "READ_SCREEN",
            description = "Reads visible text from current screen using accessibility",
            params = listOf(ParamDefinition("extract", ParamType.ENUM, false, "What to extract", enumValues = listOf("all", "text", "buttons", "links"), defaultValue = "all")),
            examples = listOf("read my screen", "what's on my screen"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "ANALYZE_SCREENSHOT",
            description = "Analyzes current screen using vision/OCR",
            params = listOf(ParamDefinition("question", ParamType.STRING, false, "What to analyze", defaultValue = "What do you see on this screen?")),
            examples = listOf("what is on my screen", "analyze screen", "ocr screen"),
            category = ActionCategory.VISION
        ),
        ActionDefinition(
            name = "OPEN_URL",
            description = "Opens a specific URL in browser",
            params = listOf(ParamDefinition("url", ParamType.STRING, true, "URL to open")),
            examples = listOf("open google.com", "go to youtube.com"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SET_ALARM",
            description = "Sets an alarm at the specified time",
            params = listOf(
                ParamDefinition("time", ParamType.STRING, true, "Alarm time in any format"),
                ParamDefinition("label", ParamType.STRING, false, "Alarm label", defaultValue = "Alarm")
            ),
            examples = listOf("set alarm 5 am", "alarm at 7:30"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "SET_TIMER",
            description = "Starts a countdown timer",
            params = listOf(
                ParamDefinition("duration", ParamType.STRING, true, "Timer duration e.g. 5 minutes"),
                ParamDefinition("label", ParamType.STRING, false, "Timer label", defaultValue = "Timer")
            ),
            examples = listOf("set timer 5 minutes", "timer 30 seconds"),
            category = ActionCategory.PRODUCTIVITY
        ),
        ActionDefinition(
            name = "TOGGLE_FLASHLIGHT",
            description = "Turns flashlight on, off, or toggles",
            params = listOf(ParamDefinition("state", ParamType.ENUM, false, "Desired flashlight state", enumValues = listOf("on", "off", "toggle"), defaultValue = "toggle")),
            examples = listOf("flashlight on", "torch off"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "TOGGLE_DND",
            description = "Turns Do Not Disturb on, off, or toggles",
            params = listOf(ParamDefinition("state", ParamType.ENUM, false, "Desired DND state", enumValues = listOf("on", "off", "toggle"), defaultValue = "toggle")),
            examples = listOf("dnd on", "do not disturb off"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "CLOSE_APP",
            description = "Closes an app",
            params = listOf(ParamDefinition("appName", ParamType.STRING, true, "Name of the app to close")),
            examples = listOf("close whatsapp", "close chrome"),
            category = ActionCategory.SYSTEM
        ),
        ActionDefinition(
            name = "SWIPE",
            description = "Performs a swipe gesture",
            params = listOf(
                ParamDefinition("direction", ParamType.ENUM, true, "Swipe direction", enumValues = listOf("up", "down", "left", "right")),
                ParamDefinition("distance", ParamType.ENUM, false, "Swipe distance", enumValues = listOf("short", "medium", "long"), defaultValue = "medium")
            ),
            examples = listOf("swipe up", "swipe down", "swipe left"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "SCROLL",
            description = "Scrolls the screen",
            params = listOf(
                ParamDefinition("direction", ParamType.ENUM, true, "Scroll direction", enumValues = listOf("up", "down")),
                ParamDefinition("distance", ParamType.ENUM, false, "Scroll distance", enumValues = listOf("short", "medium", "long"), defaultValue = "medium")
            ),
            examples = listOf("scroll down", "scroll up"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "LONG_PRESS",
            description = "Performs a long press on an element",
            params = listOf(ParamDefinition("text", ParamType.STRING, true, "Text of element to long press")),
            examples = listOf("long press settings"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "WAIT_FOR",
            description = "Waits for text to appear on screen",
            params = listOf(
                ParamDefinition("text", ParamType.STRING, true, "Text to wait for"),
                ParamDefinition("timeoutMs", ParamType.INT, false, "Timeout in milliseconds", defaultValue = 5000)
            ),
            examples = listOf("wait for login"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "TYPE",
            description = "Types text into focused field",
            params = listOf(ParamDefinition("value", ParamType.STRING, true, "Text to type")),
            examples = listOf("type hello world"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "PRESS_BACK",
            description = "Presses the back button",
            params = emptyList(),
            examples = listOf("go back", "press back"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "PRESS_HOME",
            description = "Presses the home button",
            params = emptyList(),
            examples = listOf("go home", "press home"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "PRESS_RECENTS",
            description = "Opens recent apps",
            params = emptyList(),
            examples = listOf("recent apps", "show recent apps"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "EXTRACT_TEXT",
            description = "Extracts all visible text from screen",
            params = emptyList(),
            examples = listOf("extract text", "copy screen text"),
            category = ActionCategory.ACCESSIBILITY
        ),
        ActionDefinition(
            name = "LOCK_SCREEN",
            description = "Locks the device screen",
            params = emptyList(),
            examples = listOf("lock screen", "lock phone"),
            category = ActionCategory.SYSTEM
        )
    )

    private val actionMap = ALL_ACTIONS.associateBy { it.name }

    fun isValid(actionName: String): Boolean = actionMap.containsKey(actionName)

    fun getAction(name: String): ActionDefinition? = actionMap[name]

    fun getAllActionNames(): List<String> = ALL_ACTIONS.map { it.name }

    fun validateParams(actionName: String, params: Map<String, String>): ValidationResult {
        val action = actionMap[actionName] ?: return ValidationResult.InvalidAction("Unknown action: $actionName")

        val missing = mutableListOf<String>()
        val enriched = mutableMapOf<String, Any?>()

        for (paramDef in action.params) {
            val value = params[paramDef.name]
            if (value == null || value.isBlank()) {
                if (paramDef.required) {
                    missing.add(paramDef.name)
                } else if (paramDef.defaultValue != null) {
                    enriched[paramDef.name] = paramDef.defaultValue
                }
            } else {
                enriched[paramDef.name] = when (paramDef.type) {
                    ParamType.INT -> value.toIntOrNull() ?: value
                    ParamType.BOOLEAN -> value.toBoolean()
                    ParamType.ENUM -> if (value !in paramDef.enumValues) {
                        paramDef.defaultValue ?: value
                    } else value
                    else -> value
                }
            }
        }

        return if (missing.isEmpty()) {
            ValidationResult.Valid(enriched)
        } else {
            ValidationResult.MissingParams(action.params.map { it.name }, missing)
        }
    }

    fun applyDefaults(actionName: String, params: Map<String, String>): Map<String, String> {
        val action = actionMap[actionName] ?: return params
        val result = params.toMutableMap()
        for (paramDef in action.params) {
            if (!result.containsKey(paramDef.name) && paramDef.defaultValue != null) {
                result[paramDef.name] = paramDef.defaultValue.toString()
            }
        }
        return result
    }
}
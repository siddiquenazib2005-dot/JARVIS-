package com.jarvis.ai.core.agent

import kotlinx.serialization.Serializable

@Serializable
data class ActionHint(
    val action: String,
    val baseParams: Map<String, String>
)

object AliasResolver {

    private val ALIASES = mapOf(
        "open" to "OPEN_APP",
        "launch" to "OPEN_APP",
        "start" to "OPEN_APP",
        "run" to "OPEN_APP",
        "close" to "CLOSE_APP",
        "exit" to "CLOSE_APP",
        "kill" to "CLOSE_APP",
        "search" to "WEB_SEARCH",
        "google" to "WEB_SEARCH",
        "find" to "WEB_SEARCH",
        "look up" to "WEB_SEARCH",
        "call" to "MAKE_CALL",
        "phone" to "MAKE_CALL",
        "dial" to "MAKE_CALL",
        "text" to "SEND_SMS",
        "message" to "SEND_SMS",
        "sms" to "SEND_SMS",
        "whatsapp" to "SEND_WHATSAPP",
        "wa" to "SEND_WHATSAPP",
        "email" to "SEND_EMAIL",
        "mail" to "SEND_EMAIL",
        "navigate" to "GET_DIRECTIONS",
        "directions" to "GET_DIRECTIONS",
        "map" to "GET_DIRECTIONS",
        "where is" to "GET_DIRECTIONS",
        "route" to "GET_DIRECTIONS",
        "play" to "PLAY_MUSIC",
        "music" to "PLAY_MUSIC",
        "song" to "PLAY_MUSIC",
        "pause" to "PAUSE_MUSIC",
        "stop music" to "PAUSE_MUSIC",
        "next" to "NEXT_TRACK",
        "skip" to "NEXT_TRACK",
        "previous" to "PREV_TRACK",
        "back" to "PREV_TRACK",
        "volume up" to "SET_VOLUME",
        "volume down" to "SET_VOLUME",
        "mute" to "SET_VOLUME",
        "brightness" to "SET_BRIGHTNESS",
        "dim" to "SET_BRIGHTNESS",
        "bright" to "SET_BRIGHTNESS",
        "wifi" to "TOGGLE_WIFI",
        "bluetooth" to "TOGGLE_BLUETOOTH",
        "bt" to "TOGGLE_BLUETOOTH",
        "flashlight" to "TOGGLE_FLASHLIGHT",
        "torch" to "TOGGLE_FLASHLIGHT",
        "light" to "TOGGLE_FLASHLIGHT",
        "screenshot" to "TAKE_SCREENSHOT",
        "screen capture" to "TAKE_SCREENSHOT",
        "capture" to "TAKE_SCREENSHOT",
        "read screen" to "READ_SCREEN",
        "what's on screen" to "READ_SCREEN",
        "what on screen" to "READ_SCREEN",
        "screen content" to "READ_SCREEN",
        "ocr" to "ANALYZE_SCREENSHOT",
        "vision" to "ANALYZE_SCREENSHOT",
        "describe screen" to "ANALYZE_SCREENSHOT",
        "what's on screen" to "ANALYZE_SCREENSHOT",
        "settings" to "OPEN_SETTINGS",
        "config" to "OPEN_SETTINGS",
        "options" to "OPEN_SETTINGS",
        "alarm" to "SET_ALARM",
        "wake me" to "SET_ALARM",
        "timer" to "SET_TIMER",
        "countdown" to "SET_TIMER",
        "remind me" to "SET_REMINDER",
        "reminder" to "SET_REMINDER",
        "note" to "ADD_NOTE",
        "note down" to "ADD_NOTE",
        "remember" to "ADD_NOTE",
        "save" to "ADD_NOTE",
        "calc" to "CALCULATE",
        "compute" to "CALCULATE",
        "math" to "CALCULATE",
        "weather" to "GET_WEATHER",
        "forecast" to "GET_WEATHER",
        "news" to "GET_NEWS",
        "headlines" to "GET_NEWS",
        "translate" to "TRANSLATE",
        "convert" to "CONVERT_UNITS",
        "units" to "CONVERT_UNITS",
        "currency" to "CURRENCY_CONVERT",
        "exchange rate" to "CURRENCY_CONVERT",
        "define" to "DEFINE_WORD",
        "meaning" to "DEFINE_WORD",
        "what does" to "DEFINE_WORD",
        "summarize" to "SUMMARIZE_URL",
        "tldr" to "SUMMARIZE_URL",
        "fact check" to "FACT_CHECK",
        "verify" to "FACT_CHECK",
        "is it true" to "FACT_CHECK",
        "take picture" to "TAKE_PHOTO",
        "selfie" to "TAKE_PHOTO",
        "picture" to "TAKE_PHOTO",
        "photo" to "TAKE_PHOTO",
        "record video" to "RECORD_VIDEO",
        "video" to "RECORD_VIDEO",
        "screen record" to "RECORD_SCREEN",
        "wallpaper" to "SET_WALLPAPER",
        "background" to "SET_WALLPAPER",
        "restart" to "RESTART_DEVICE",
        "reboot" to "RESTART_DEVICE",
        "battery" to "GET_BATTERY_STATUS",
        "power" to "GET_BATTERY_STATUS",
        "storage" to "GET_DEVICE_STATUS",
        "memory" to "GET_DEVICE_STATUS",
        "ram" to "GET_DEVICE_STATUS",
        "cpu" to "GET_DEVICE_STATUS",
        "system info" to "GET_SYSTEM_INFO",
        "device info" to "GET_SYSTEM_INFO",
        "clipboard" to "GET_CLIPBOARD",
        "copy" to "COPY_TO_CLIPBOARD",
        "paste" to "GET_CLIPBOARD",
        "clear clipboard" to "CLEAR_CLIPBOARD",
        "lock" to "LOCK_SCREEN",
        "lock screen" to "LOCK_SCREEN",
        "dnd" to "TOGGLE_DND",
        "do not disturb" to "TOGGLE_DND",
        "silent" to "SET_RINGER_MODE",
        "vibrate" to "SET_RINGER_MODE",
        "ring" to "SET_RINGER_MODE",
        "normal" to "SET_RINGER_MODE",
        "install" to "INSTALL_APP",
        "download app" to "INSTALL_APP",
        "app store" to "INSTALL_APP",
        "play store" to "INSTALL_APP",
        "order food" to "ORDER_FOOD",
        "food" to "ORDER_FOOD",
        "delivery" to "ORDER_FOOD",
        "grocery" to "ORDER_GROCERY",
        "groceries" to "ORDER_GROCERY",
        "amazon" to "SEARCH_AMAZON",
        "flipkart" to "SEARCH_FLIPKART",
        "shop" to "SEARCH_AMAZON",
        "buy" to "SEARCH_AMAZON",
        "pay" to "PAY_UPI",
        "send money" to "PAY_UPI",
        "upi" to "PAY_UPI",
        "balance" to "CHECK_BALANCE",
        "bank" to "CHECK_BALANCE",
        "split" to "SPLIT_BILL",
        "shared bill" to "SPLIT_BILL",
        "youtube" to "PLAY_YOUTUBE",
        "watch" to "PLAY_YOUTUBE",
        "camera open" to "TAKE_PHOTO",
        "camera" to "TAKE_PHOTO",
        "screen capture" to "TAKE_SCREENSHOT",
        "screen capture" to "TAKE_SCREENSHOT",
        "read screen" to "READ_SCREEN",
        "screen text" to "READ_SCREEN",
        "what on screen" to "READ_SCREEN",
        "ocr screen" to "ANALYZE_SCREENSHOT",
        "screen ocr" to "ANALYZE_SCREENSHOT",
        "vision screen" to "ANALYZE_SCREENSHOT",
        "describe screen" to "ANALYZE_SCREENSHOT",
        "what on screen" to "ANALYZE_SCREENSHOT"
    )

    private val EXTENDED_ALIASES = mapOf(
        "swipe up" to "SWIPE",
        "swipe down" to "SWIPE",
        "swipe left" to "SWIPE",
        "swipe right" to "SWIPE",
        "scroll up" to "SCROLL",
        "scroll down" to "SCROLL",
        "long press" to "LONG_PRESS",
        "wait for" to "WAIT_FOR",
        "press back" to "PRESS_BACK",
        "press home" to "PRESS_HOME",
        "press recents" to "PRESS_RECENTS",
        "go back" to "PRESS_BACK",
        "go home" to "PRESS_HOME",
        "lock screen" to "LOCK_SCREEN",
        "lock phone" to "LOCK_SCREEN",
        "close app" to "CLOSE_APP",
        "close" to "CLOSE_APP",
        "type" to "TYPE",
        "tap" to "TAP",
        "tap " to "TAP",
        "click " to "TAP",
        "scroll " to "SCROLL",
        "swipe " to "SWIPE"
    )

    fun resolve(query: String): ActionHint? {
        val normalized = query.trim().lowercase()
        
        // Check extended aliases first (more specific)
        for ((alias, action) in EXTENDED_ALIASES) {
            if (normalized.startsWith(alias)) {
                val remaining = normalized.substring(alias.length).trim()
                return ActionHint(action, extractParams(action, remaining))
            }
        }
        
        // Direct alias match
        for ((alias, action) in ALIASES) {
            if (normalized.startsWith(alias)) {
                val remaining = normalized.substring(alias.length).trim()
                return ActionHint(action, extractParams(action, remaining))
            }
        }
        
        // Check for exact matches
        for ((alias, action) in ALIASES) {
            if (normalized == alias) {
                return ActionHint(action, emptyMap())
            }
        }
        
        return null
    }

    fun resolveContextual(query: String, lastAction: String?, lastParams: Map<String, String>?): ActionHint? {
        val normalized = query.trim().lowercase()
        
        // Contextual follow-ups
        when {
            normalized in listOf("stop it", "stop", "cancel", "abort", "never mind") -> {
                return ActionHint("STOP_CURRENT_TASK", emptyMap())
            }
            normalized in listOf("again", "repeat", "do it again", "one more time") -> {
                // Return last action with same params
                lastAction?.let { return ActionHint(it, lastParams ?: emptyMap()) }
            }
            normalized.startsWith("more ") || normalized.startsWith("continue ") -> {
                lastAction?.let { return ActionHint(it, lastParams ?: emptyMap()) }
            }
            normalized in listOf("louder", "volume up", "turn up") -> {
                return ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to "80"))
            }
            normalized in listOf("quieter", "volume down", "turn down") -> {
                return ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to "30"))
            }
            normalized in listOf("brighter", "brighten") -> {
                return ActionHint("SET_BRIGHTNESS", mapOf("level" to "80"))
            }
            normalized in listOf("dimmer", "darker", "dim") -> {
                return ActionHint("SET_BRIGHTNESS", mapOf("level" to "30"))
            }
        }
        
        return null
    }

    fun isAlarmRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("alarm") || normalized.startsWith("wake me") || normalized.startsWith("set alarm")
    }

    fun extractAlarmTime(text: String): String? {
        val normalized = text.trim().lowercase()
        val patterns = listOf(
            "\\d{1,2}\\s*(am|pm)",
            "\\d{1,2}:\\d{2}\\s*(am|pm)?",
            "\\d{1,2}:\\d{2}",
            "noon",
            "midnight"
        )
        
        for (pattern in patterns) {
            val regex = pattern.toRegex()
            val match = regex.find(normalized)
            if (match != null) return match.value
        }
        return null
    }

    fun isTimerRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("timer") || normalized.startsWith("countdown") || normalized.startsWith("set timer")
    }

    fun extractTimerDuration(text: String): Long? {
        val normalized = text.trim().lowercase()
        // Extract duration in seconds
        val patterns = listOf(
            "(\\d+)\\s*hours?" to 3600L,
            "(\\d+)\\s*minutes?" to 60L,
            "(\\d+)\\s*seconds?" to 1L,
            "(\\d+)\\s*min" to 60L,
            "(\\d+)\\s*sec" to 1L
        )
        
        for ((pattern, multiplier) in patterns) {
            val regex = pattern.toRegex()
            val match = regex.find(normalized)
            if (match != null) {
                val value = match.groupValues[1].toLongOrNull()
                if (value != null) return value * multiplier
            }
        }
        return null
    }

    fun isReadAndRememberRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.contains("read") && (normalized.contains("remember") || normalized.contains("save"))
    }

    fun extractTopicForReadAndRemember(text: String): String {
        val normalized = text.trim().lowercase()
        val removeWords = listOf("read", "and", "remember", "save", "the", "this", "screen", "to", "my", "notes", "note")
        var topic = normalized
        for (word in removeWords) {
            topic = topic.replace(word, "").trim()
        }
        return if (topic.isNotBlank()) topic else "important information"
    }

    fun isRecallMemoryRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("what did i") || normalized.startsWith("what do i") ||
               normalized.contains("recall") || normalized.contains("what did you")
    }

    fun extractRecallQuery(text: String): String {
        val normalized = text.trim().lowercase()
        val removeWords = listOf("what did i", "what do i", "recall", "tell me", "what", "about", "my", "the")
        var query = normalized
        for (word in removeWords) {
            query = query.replace(word, "").trim()
        }
        return query
    }

    private fun extractParams(action: String, remaining: String): Map<String, String> {
        return when (action) {
            "OPEN_APP" -> if (remaining.isNotBlank()) mapOf("appName" to remaining) else emptyMap()
            "WEB_SEARCH" -> if (remaining.isNotBlank()) mapOf("query" to remaining) else emptyMap()
            "MAKE_CALL" -> if (remaining.isNotBlank()) mapOf("contact" to remaining) else emptyMap()
            "SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM" -> {
                // Parse "to [contact] [message]"
                val parts = remaining.split(" ", limit = 2)
                if (parts.size >= 2) {
                    mapOf("contact" to parts[0], "message" to parts[1])
                } else if (parts.size == 1) {
                    mapOf("contact" to parts[0])
                } else emptyMap()
            }
            "SEND_EMAIL" -> if (remaining.isNotBlank()) mapOf("to" to remaining) else emptyMap()
            "GET_DIRECTIONS" -> if (remaining.isNotBlank()) mapOf("to" to remaining) else emptyMap()
            "PLAY_MUSIC" -> if (remaining.isNotBlank()) mapOf("query" to remaining) else emptyMap()
            "SET_ALARM" -> if (remaining.isNotBlank()) mapOf("time" to remaining) else emptyMap()
            "SET_TIMER" -> if (remaining.isNotBlank()) mapOf("duration" to remaining) else emptyMap()
            "SET_REMINDER" -> if (remaining.isNotBlank()) mapOf("text" to remaining) else emptyMap()
            "ADD_NOTE" -> if (remaining.isNotBlank()) mapOf("content" to remaining) else emptyMap()
            "WEB_SEARCH" -> if (remaining.isNotBlank()) mapOf("query" to remaining) else emptyMap()
            "GET_WEATHER" -> if (remaining.isNotBlank()) mapOf("location" to remaining) else emptyMap()
            "GET_NEWS" -> if (remaining.isNotBlank()) mapOf("topic" to remaining) else emptyMap()
            "CALCULATE" -> if (remaining.isNotBlank()) mapOf("expression" to remaining) else emptyMap()
            "TRANSLATE" -> if (remaining.isNotBlank()) mapOf("text" to remaining) else emptyMap()
            "TAKE_PHOTO" -> emptyMap()
            "RECORD_VIDEO" -> emptyMap()
            "SET_ALARM" -> if (remaining.isNotBlank()) mapOf("time" to remaining) else emptyMap()
            "SET_TIMER" -> if (remaining.isNotBlank()) mapOf("duration" to remaining) else emptyMap()
            "SET_REMINDER" -> if (remaining.isNotBlank()) mapOf("text" to remaining) else emptyMap()
            "TAKE_PHOTO" -> emptyMap()
            "TAKE_SCREENSHOT" -> emptyMap()
            "READ_SCREEN" -> emptyMap()
            "ANALYZE_SCREENSHOT" -> if (remaining.isNotBlank()) mapOf("question" to remaining) else emptyMap()
            "OPEN_URL" -> if (remaining.isNotBlank()) mapOf("url" to remaining) else emptyMap()
            "SET_BRIGHTNESS" -> if (remaining.isNotBlank()) mapOf("level" to remaining) else emptyMap()
            "SET_VOLUME" -> if (remaining.isNotBlank()) mapOf("level" to remaining) else emptyMap()
            "TOGGLE_WIFI" -> emptyMap()
            "TOGGLE_BLUETOOTH" -> emptyMap()
            "TOGGLE_FLASHLIGHT" -> emptyMap()
            "TOGGLE_DND" -> emptyMap()
            "SET_WALLPAPER" -> if (remaining.isNotBlank()) mapOf("imageUrl" to remaining) else emptyMap()
            "RECORD_SCREEN" -> if (remaining.isNotBlank()) mapOf("duration" to remaining) else emptyMap()
            "OPEN_SETTINGS" -> emptyMap()
            "OPEN_APP" -> if (remaining.isNotBlank()) mapOf("appName" to remaining) else emptyMap()
            "CLOSE_APP" -> if (remaining.isNotBlank()) mapOf("appName" to remaining) else emptyMap()
            "TOGGLE_WIFI" -> emptyMap()
            "TOGGLE_BLUETOOTH" -> emptyMap()
            "TOGGLE_FLASHLIGHT" -> emptyMap()
            "TOGGLE_DND" -> emptyMap()
            "GET_WEATHER" -> if (remaining.isNotBlank()) mapOf("location" to remaining) else emptyMap()
            "GET_NEWS" -> if (remaining.isNotBlank()) mapOf("topic" to remaining) else emptyMap()
            "CALCULATE" -> if (remaining.isNotBlank()) mapOf("expression" to remaining) else emptyMap()
            "TRANSLATE" -> if (remaining.isNotBlank()) mapOf("text" to remaining) else emptyMap()
            "CONVERT_UNITS" -> if (remaining.isNotBlank()) mapOf("value" to remaining) else emptyMap()
            "CURRENCY_CONVERT" -> if (remaining.isNotBlank()) mapOf("amount" to remaining) else emptyMap()
            "GET_BATTERY_STATUS" -> emptyMap()
            "GET_DEVICE_STATUS" -> emptyMap()
            "GET_SYSTEM_INFO" -> emptyMap()
            "TAKE_SCREENSHOT" -> emptyMap()
            "READ_SCREEN" -> emptyMap()
            "ANALYZE_SCREENSHOT" -> if (remaining.isNotBlank()) mapOf("question" to remaining) else emptyMap()
            else -> emptyMap()
        }
    }
}
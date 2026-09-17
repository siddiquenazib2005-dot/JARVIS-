package com.jarvis.ai.core.agent

import android.content.Context
import kotlinx.coroutines.CancellationException

data class Mapping(
    val mappedAction: String?,
    val mappedParams: Map<String, String>,
    val wasMapped: Boolean
)

class ActionDispatcher(
    private val context: Context,
    private val baseExecutor: suspend (String, Map<String, String>, Context) -> ActionResult,
    private val hasAction: (String) -> Boolean,
    private val autoMapper: ActionAutoMapper
) {

    companion object {
        private const val TAG = "ActionDispatcher"
    }

    private val actionsMap: Map<String, ActionDefinition> = ActionSchema.ALL_ACTIONS.associateBy { it.name }

    fun hasAction(actionName: String): Boolean = hasAction(actionName) || hasAction(autoMapper.normalizeActionName(actionName))

    fun canonicalActionName(actionName: String): String {
        val normalized = autoMapper.normalizeActionName(actionName)
        if (ActionSchema.isValid(normalized) || normalized in actionsMap.keys) {
            return normalized
        }
        val mapping = autoMapper.mapAction(actionName, emptyMap(), actionsMap.keys)
        return mapping.mappedAction ?: normalized
    }

    fun isRegistered(actionName: String): Boolean = hasAction(actionName)

    suspend fun execute(actionName: String, params: Map<String, String>, context: Context): ActionResult {
        val normalized = autoMapper.normalizeActionName(actionName)

        // Step 1: Try direct schema validation + execution
        val directResult = trySchemaExecution(normalized, params, context)
        if (directResult != null) {
            return directResult
        }

        // Step 2: Resolve via ActionAutoMapper
        val mapping = autoMapper.mapAction(actionName, params, actionsMap.keys)

        if (mapping.mappedAction == null && mapping.wasMapped) {
            // SKIP - hallucinated step
            return ActionResult.Success(mapOf("message" to "Step skipped (unnecessary)", "skipped" to "true"))
        }

        if (mapping.mappedAction != null && mapping.wasMapped) {
            val finalAction = mapping.mappedAction
            val finalParams = mapping.mappedParams

            // Try schema execution on resolved action
            val resolvedResult = trySchemaExecution(finalAction, finalParams, context)
            if (resolvedResult != null) {
                return resolvedResult
            }

            // Direct handler execution
            if (hasAction(finalAction)) {
                return baseExecutor(finalAction, finalParams, context)
            }
        }

        if (mapping.mappedAction != null && !mapping.wasMapped) {
            // Passthrough - was already valid
            val handler = actionsMap[mapping.mappedAction]
            if (handler != null) {
                return baseExecutor(mapping.mappedAction, mapping.mappedParams, context)
            }
        }

        // Truly unknown - graceful failure
        return ActionResult.UnknownAction(
            attemptedAction = actionName,
            availableActions = ActionSchema.getAllActionNames()
        )
    }

    private suspend fun trySchemaExecution(actionName: String, params: Map<String, String>, context: Context): ActionResult? {
        val action = ActionSchema.getAction(actionName) ?: return null
        val validationResult = ActionSchema.validateParams(actionName, params)

        return when (validationResult) {
            is ValidationResult.Valid -> {
                val executor = if (hasAction(actionName)) { baseExecutor } else null
                executor ?: return null
                baseExecutor(actionName, validationResult.enrichedParams.mapValues { it.value.toString() }, context)
            }
            is ValidationResult.MissingParams -> {
                val definition = ActionSchema.getAction(actionName)
                val allHaveDefaults = validationResult.missingParams.all { paramName ->
                    ActionSchema.getAction(actionName)?.params?.find { it.name == paramName }?.defaultValue != null
                }
                if (allHaveDefaults) {
                    val withDefaults = ActionSchema.applyDefaults(actionName, params)
                    baseExecutor(actionName, withDefaults, context)
                } else {
                    val firstMissing = validationResult.missingParams.first()
                    val paramDef = ActionSchema.getAction(actionName)?.params?.find { it.name == firstMissing }
                    ActionResult.NeedsInput(
                        question = "I need the $firstMissing to complete this. ${paramDef?.description ?: ""}",
                        options = paramDef?.enumValues ?: emptyList(),
                        metadata = mapOf("param" to firstMissing)
                    )
                }
            }
            is ValidationResult.InvalidAction -> null
        }
    }

    suspend fun executeAction(actionName: String, params: Map<String, String>, context: Context): ActionResult {
        return try {
            baseExecutor(actionName, params, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure(
                errorMsg = e.message ?: "Execution failed",
                fallback = "Try alternative approach"
            )
        }
    }
}

class ActionAutoMapper {
    private val actionNameMap = ActionSchema.ALL_ACTIONS.associateBy { it.name }

    fun normalizeActionName(actionName: String): String {
        return actionName.trim().uppercase().replace(" ", "_")
    }

    fun mapAction(actionName: String, params: Map<String, String>, registeredActions: Set<String>): Mapping {
        val normalized = normalizeActionName(actionName)

        // Direct match
        if (normalized in registeredActions) {
            return Mapping(normalized, params, false)
        }

        // Alias mapping
        val aliases = mapOf(
            "OPEN" to "OPEN_APP",
            "LAUNCH" to "OPEN_APP",
            "START" to "OPEN_APP",
            "RUN" to "OPEN_APP",
            "CLOSE" to "CLOSE_APP",
            "EXIT" to "CLOSE_APP",
            "KILL" to "CLOSE_APP",
            "SEARCH" to "WEB_SEARCH",
            "GOOGLE" to "WEB_SEARCH",
            "FIND" to "WEB_SEARCH",
            "LOOK_UP" to "WEB_SEARCH",
            "CALL" to "MAKE_CALL",
            "PHONE" to "MAKE_CALL",
            "DIAL" to "MAKE_CALL",
            "TEXT" to "SEND_SMS",
            "MESSAGE" to "SEND_SMS",
            "SMS" to "SEND_SMS",
            "WHATSAPP" to "SEND_WHATSAPP",
            "WA" to "SEND_WHATSAPP",
            "EMAIL" to "SEND_EMAIL",
            "MAIL" to "SEND_EMAIL",
            "NAVIGATE" to "GET_DIRECTIONS",
            "DIRECTIONS" to "GET_DIRECTIONS",
            "MAP" to "GET_DIRECTIONS",
            "WHERE_IS" to "GET_DIRECTIONS",
            "ROUTE" to "GET_DIRECTIONS",
            "PLAY" to "PLAY_MUSIC",
            "MUSIC" to "PLAY_MUSIC",
            "SONG" to "PLAY_MUSIC",
            "PAUSE" to "PAUSE_MUSIC",
            "STOP_MUSIC" to "PAUSE_MUSIC",
            "NEXT" to "NEXT_TRACK",
            "SKIP" to "NEXT_TRACK",
            "PREVIOUS" to "PREV_TRACK",
            "BACK" to "PREV_TRACK",
            "VOLUME_UP" to "SET_VOLUME",
            "VOLUME_DOWN" to "SET_VOLUME",
            "MUTE" to "SET_VOLUME",
            "BRIGHTNESS" to "SET_BRIGHTNESS",
            "DIM" to "SET_BRIGHTNESS",
            "BRIGHT" to "SET_BRIGHTNESS",
            "WIFI" to "TOGGLE_WIFI",
            "BLUETOOTH" to "TOGGLE_BLUETOOTH",
            "BT" to "TOGGLE_BLUETOOTH",
            "FLASHLIGHT" to "TOGGLE_FLASHLIGHT",
            "TORCH" to "TOGGLE_FLASHLIGHT",
            "LIGHT" to "TOGGLE_FLASHLIGHT",
            "SCREENSHOT" to "TAKE_SCREENSHOT",
            "SCREEN_CAPTURE" to "TAKE_SCREENSHOT",
            "CAPTURE" to "TAKE_SCREENSHOT",
            "READ_SCREEN" to "READ_SCREEN",
            "WHAT_ON_SCREEN" to "READ_SCREEN",
            "WHATS_ON_SCREEN" to "READ_SCREEN",
            "SCREEN_CONTENT" to "READ_SCREEN",
            "OCR" to "ANALYZE_SCREENSHOT",
            "VISION" to "ANALYZE_SCREENSHOT",
            "DESCRIBE_SCREEN" to "ANALYZE_SCREENSHOT",
            "SETTINGS" to "OPEN_SETTINGS",
            "CONFIG" to "OPEN_SETTINGS",
            "OPTIONS" to "OPEN_SETTINGS",
            "ALARM" to "SET_ALARM",
            "WAKE_ME" to "SET_ALARM",
            "TIMER" to "SET_TIMER",
            "COUNTDOWN" to "SET_TIMER",
            "REMIND_ME" to "SET_REMINDER",
            "REMINDER" to "SET_REMINDER",
            "NOTE" to "ADD_NOTE",
            "NOTE_DOWN" to "ADD_NOTE",
            "REMEMBER" to "ADD_NOTE",
            "SAVE" to "ADD_NOTE",
            "CALC" to "CALCULATE",
            "COMPUTE" to "CALCULATE",
            "MATH" to "CALCULATE",
            "WEATHER" to "GET_WEATHER",
            "FORECAST" to "GET_WEATHER",
            "NEWS" to "GET_NEWS",
            "HEADLINES" to "GET_NEWS",
            "TRANSLATE" to "TRANSLATE",
            "CONVERT" to "CONVERT_UNITS",
            "UNITS" to "CONVERT_UNITS",
            "CURRENCY" to "CURRENCY_CONVERT",
            "EXCHANGE_RATE" to "CURRENCY_CONVERT",
            "DEFINE" to "DEFINE_WORD",
            "MEANING" to "DEFINE_WORD",
            "WHAT_DOES_MEAN" to "DEFINE_WORD",
            "SUMMARIZE" to "SUMMARIZE_URL",
            "TLDR" to "SUMMARIZE_URL",
            "FACT_CHECK" to "FACT_CHECK",
            "VERIFY" to "FACT_CHECK",
            "IS_IT_TRUE" to "FACT_CHECK",
            "TAKE_PICTURE" to "TAKE_PHOTO",
            "SELFIE" to "TAKE_PHOTO",
            "PICTURE" to "TAKE_PHOTO",
            "PHOTO" to "TAKE_PHOTO",
            "RECORD_VIDEO" to "RECORD_VIDEO",
            "VIDEO" to "RECORD_VIDEO",
            "SCREEN_RECORD" to "RECORD_SCREEN",
            "WALLPAPER" to "SET_WALLPAPER",
            "BACKGROUND" to "SET_WALLPAPER",
            "RESTART" to "RESTART_DEVICE",
            "REBOOT" to "RESTART_DEVICE",
            "BATTERY" to "GET_BATTERY_STATUS",
            "POWER" to "GET_BATTERY_STATUS",
            "STORAGE" to "GET_DEVICE_STATUS",
            "MEMORY" to "GET_DEVICE_STATUS",
            "RAM" to "GET_DEVICE_STATUS",
            "CPU" to "GET_DEVICE_STATUS",
            "SYSTEM_INFO" to "GET_SYSTEM_INFO",
            "DEVICE_INFO" to "GET_SYSTEM_INFO",
            "CLIPBOARD" to "GET_CLIPBOARD",
            "COPY" to "COPY_TO_CLIPBOARD",
            "PASTE" to "GET_CLIPBOARD",
            "CLEAR_CLIPBOARD" to "CLEAR_CLIPBOARD",
            "LOCK" to "LOCK_SCREEN",
            "LOCK_SCREEN" to "LOCK_SCREEN",
            "DND" to "TOGGLE_DND",
            "DO_NOT_DISTURB" to "TOGGLE_DND",
            "SILENT" to "SET_RINGER_MODE",
            "VIBRATE" to "SET_RINGER_MODE",
            "RING" to "SET_RINGER_MODE",
            "NORMAL" to "SET_RINGER_MODE",
            "INSTALL" to "INSTALL_APP",
            "DOWNLOAD_APP" to "INSTALL_APP",
            "APP_STORE" to "INSTALL_APP",
            "PLAY_STORE" to "INSTALL_APP",
            "ORDER_FOOD" to "ORDER_FOOD",
            "FOOD" to "ORDER_FOOD",
            "DELIVERY" to "ORDER_FOOD",
            "GROCERY" to "ORDER_GROCERY",
            "GROCERIES" to "ORDER_GROCERY",
            "AMAZON" to "SEARCH_AMAZON",
            "FLIPKART" to "SEARCH_FLIPKART",
            "SHOP" to "SEARCH_AMAZON",
            "BUY" to "SEARCH_AMAZON",
            "PAY" to "PAY_UPI",
            "SEND_MONEY" to "PAY_UPI",
            "UPI" to "PAY_UPI",
            "BALANCE" to "CHECK_BALANCE",
            "BANK" to "CHECK_BALANCE",
            "SPLIT" to "SPLIT_BILL",
            "SHARED_BILL" to "SPLIT_BILL",
            "MUSIC_PLAY" to "PLAY_MUSIC",
            "PLAY_SONG" to "PLAY_MUSIC",
            "PLAY_PLAYLIST" to "PLAY_MUSIC",
            "YOUTUBE" to "PLAY_YOUTUBE",
            "WATCH" to "PLAY_YOUTUBE",
            "SETTINGS_OPEN" to "OPEN_SETTINGS",
            "GO_SETTINGS" to "OPEN_SETTINGS",
            "OPEN_SETTINGS_APP" to "OPEN_SETTINGS",
            "CAMERA_OPEN" to "TAKE_PHOTO",
            "CAMERA" to "TAKE_PHOTO",
            "SCREEN_CAPTURE" to "TAKE_SCREENSHOT",
            "READ_SCREEN_TEXT" to "READ_SCREEN",
            "SCREEN_TEXT" to "READ_SCREEN",
            "OCR_SCREEN" to "ANALYZE_SCREENSHOT",
            "SCREEN_OCR" to "ANALYZE_SCREENSHOT",
            "VISION_SCREEN" to "ANALYZE_SCREENSHOT",
            "SWIPE_UP" to "SWIPE",
            "SWIPE_DOWN" to "SWIPE",
            "SWIPE_LEFT" to "SWIPE",
            "SWIPE_RIGHT" to "SWIPE",
            "SCROLL_UP" to "SCROLL",
            "SCROLL_DOWN" to "SCROLL",
            "LONG_PRESS" to "LONG_PRESS",
            "WAIT_FOR" to "WAIT_FOR",
            "PRESS_BACK" to "PRESS_BACK",
            "PRESS_HOME" to "PRESS_HOME",
            "PRESS_RECENTS" to "PRESS_RECENTS",
            "GO_BACK" to "PRESS_BACK",
            "GO_HOME" to "PRESS_HOME",
            "EXTRACT_TEXT" to "EXTRACT_TEXT",
            "COPY_TEXT" to "EXTRACT_TEXT",
            "TYPE_TEXT" to "TYPE"
        )

        val alias = aliases[normalized]
        if (alias != null && alias in actionNameMap) {
            return Mapping(alias, params, true)
        }

        // Semantic mapping for compound actions
        return semanticMap(normalized, params)
    }

    private fun semanticMap(normalized: String, params: Map<String, String>): Mapping {
        if (normalized.contains("AND") || normalized.contains("THEN") || normalized.contains("_")) {
            return Mapping(null, params, false)
        }

        return Mapping(null, params, false)
    }
}

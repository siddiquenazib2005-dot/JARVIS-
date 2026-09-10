package com.jarvis.ai.missions

import android.content.Context
import com.jarvis.ai.automation.ScreenAutomation
import com.jarvis.ai.tools.DeviceActionPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of a mission. [action] is a verb the engine understands and
 * [argument] is its single free-text parameter.
 */
data class MissionStep(val action: String, val argument: String = "")

/** A saved multi-step routine the user can run by name. */
data class Mission(
    val name: String,
    val steps: List<MissionStep>,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Missions: saved multi-step routines, e.g. "good morning" =
 * flashlight off + volume 40% + open maps + read notifications.
 *
 * Steps run sequentially on a background scope with a short pause between
 * them so the device UI can settle. Everything is executed through the same
 * offline [DeviceActionPack] and [ScreenAutomation] used by chat commands, so
 * missions never need an AI provider.
 */
class MissionEngine(private val context: Context) {

    private val actions = DeviceActionPack(context)
    private val screen = ScreenAutomation(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    fun all(): List<Mission> = runCatching {
        val raw = prefs.getString(KEY_MISSIONS, null) ?: return defaults()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val steps = obj.optJSONArray("steps") ?: JSONArray()
            Mission(
                name = obj.optString("name"),
                steps = (0 until steps.length()).mapNotNull { i ->
                    steps.optJSONObject(i)?.let {
                        MissionStep(it.optString("action"), it.optString("argument"))
                    }
                },
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }.filter { it.name.isNotBlank() }
    }.getOrElse { defaults() }

    fun save(mission: Mission): String {
        val existing = all().filterNot { it.name.equals(mission.name, ignoreCase = true) }
        persist(existing + mission)
        return "Mission \"${mission.name}\" saved with ${mission.steps.size} steps, sir."
    }

    fun delete(name: String): String {
        val remaining = all().filterNot { it.name.equals(name, ignoreCase = true) }
        persist(remaining)
        return "Mission \"$name\" deleted, sir."
    }

    private fun persist(missions: List<Mission>) {
        val array = JSONArray()
        missions.forEach { mission ->
            val steps = JSONArray()
            mission.steps.forEach {
                steps.put(JSONObject().put("action", it.action).put("argument", it.argument))
            }
            array.put(
                JSONObject()
                    .put("name", mission.name)
                    .put("steps", steps)
                    .put("createdAt", mission.createdAt)
            )
        }
        prefs.edit().putString(KEY_MISSIONS, array.toString()).apply()
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Runs a mission by name. Returns immediately with an acknowledgement. */
    fun run(name: String): String {
        val mission = all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: all().firstOrNull { it.name.contains(name.trim(), ignoreCase = true) }
            ?: return "I don't have a mission called \"$name\", sir. " +
                "Say \"list missions\" to see what's saved."

        scope.launch {
            mission.steps.forEach { step ->
                runCatching { execute(step) }
                delay(STEP_DELAY_MS)
            }
        }
        return "Running mission \"${mission.name}\" — ${mission.steps.size} steps, sir."
    }

    /** Runs an ad-hoc chain such as "flashlight on, then volume 30". */
    fun runChain(rawSteps: List<String>): String {
        val steps = rawSteps.mapNotNull { parseStep(it) }
        if (steps.isEmpty()) return "I couldn't understand those steps, sir."
        scope.launch {
            steps.forEach {
                runCatching { execute(it) }
                delay(STEP_DELAY_MS)
            }
        }
        return "Running ${steps.size} steps in sequence, sir."
    }

    private fun execute(step: MissionStep): String {
        val argument = step.argument.trim()
        return when (step.action.lowercase()) {
            "open_app" -> actions.openApp(argument)
            "open_url" -> actions.openUrl(argument)
            "web_search" -> actions.webSearch(argument)
            "youtube" -> actions.youtubeSearch(argument)
            "torch_on" -> actions.torch(true)
            "torch_off" -> actions.torch(false)
            "volume" -> actions.setVolumePercent(argument.toIntOrNull() ?: 50)
            "mute" -> actions.mute()
            "unmute" -> actions.unmute()
            "play_pause" -> actions.playPause()
            "next_track" -> actions.nextTrack()
            "battery" -> actions.battery()
            "device_status" -> actions.deviceStatus()
            "camera" -> actions.openCamera()
            "gallery" -> actions.openGallery()
            "files" -> actions.openFiles()
            "maps" -> actions.openMaps()
            "navigate" -> actions.navigate(argument)
            "nearby" -> actions.findNearby(argument)
            "whatsapp" -> actions.whatsapp(argument.substringBefore('|'), argument.substringAfter('|', ""))
            "sms" -> actions.sms(argument.substringBefore('|'), argument.substringAfter('|', ""))
            "call" -> actions.call(argument)
            "settings" -> actions.openSettings(argument)
            "share" -> actions.shareText(argument)
            "timer" -> actions.setTimer(argument.toIntOrNull() ?: 60, "AURIX timer")
            "tap" -> screen.tap(argument)
            "type" -> screen.type(argument)
            "scroll_down" -> screen.scroll(true)
            "scroll_up" -> screen.scroll(false)
            "back" -> screen.back()
            "home" -> screen.home()
            "read_screen" -> screen.readScreen()
            "wait" -> "waiting"
            else -> "Unknown step: ${step.action}"
        }
    }

    /** Maps a natural phrase such as "open whatsapp" to a [MissionStep]. */
    fun parseStep(raw: String): MissionStep? {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return null
        return when {
            text.startsWith("open ") -> MissionStep("open_app", text.removePrefix("open "))
            text.startsWith("search ") -> MissionStep("web_search", text.removePrefix("search "))
            text.startsWith("navigate to ") -> MissionStep("navigate", text.removePrefix("navigate to "))
            text.startsWith("call ") -> MissionStep("call", text.removePrefix("call "))
            text.startsWith("tap ") -> MissionStep("tap", text.removePrefix("tap "))
            text.startsWith("type ") -> MissionStep("type", text.removePrefix("type "))
            text.contains("flashlight on") || text.contains("torch on") -> MissionStep("torch_on")
            text.contains("flashlight off") || text.contains("torch off") -> MissionStep("torch_off")
            text.contains("volume") ->
                MissionStep("volume", Regex("\\d{1,3}").find(text)?.value.orEmpty())
            text.contains("mute") -> MissionStep("mute")
            text.contains("battery") -> MissionStep("battery")
            text.contains("camera") -> MissionStep("camera")
            text.contains("gallery") || text.contains("photos") -> MissionStep("gallery")
            text.contains("files") -> MissionStep("files")
            text.contains("maps") -> MissionStep("maps")
            text.contains("scroll up") -> MissionStep("scroll_up")
            text.contains("scroll") -> MissionStep("scroll_down")
            text.contains("home") -> MissionStep("home")
            text.contains("back") -> MissionStep("back")
            text.contains("screen") -> MissionStep("read_screen")
            else -> null
        }
    }

    /** Chat-friendly listing. */
    fun describeAll(): String {
        val missions = all()
        if (missions.isEmpty()) return "No missions saved yet, sir."
        return "Saved missions, sir:\n" + missions.joinToString("\n") { mission ->
            "• ${mission.name} (${mission.steps.size} steps): " +
                mission.steps.joinToString(" → ") { step ->
                    if (step.argument.isBlank()) step.action else step.action + " " + step.argument
                }
        } + "\n\nSay \"run <name>\" to start one."
    }

    /** Starter routines so the feature is useful before the user builds any. */
    private fun defaults(): List<Mission> = listOf(
        Mission(
            "good morning",
            listOf(
                MissionStep("torch_off"),
                MissionStep("volume", "60"),
                MissionStep("battery"),
                MissionStep("device_status")
            )
        ),
        Mission(
            "driving",
            listOf(
                MissionStep("maps"),
                MissionStep("volume", "80"),
                MissionStep("play_pause")
            )
        ),
        Mission(
            "night mode",
            listOf(
                MissionStep("torch_off"),
                MissionStep("mute"),
                MissionStep("settings", "display")
            )
        )
    )

    companion object {
        private const val PREFS_NAME = "aurix_missions"
        private const val KEY_MISSIONS = "missions_json"
        private const val STEP_DELAY_MS = 900L
    }
}

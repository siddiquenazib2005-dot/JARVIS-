package com.jarvis.ai.missions

import android.content.Context
import com.jarvis.ai.automation.ScreenAutomation
import com.jarvis.ai.tools.DeviceActionPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** One action in a saved mission. */
data class MissionStep(val action: String, val argument: String = "")

/** A saved multi-step routine the user can run by name. */
data class Mission(
    val name: String,
    val steps: List<MissionStep>,
    val createdAt: Long = System.currentTimeMillis()
)

enum class MissionRunState {
    IDLE, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED
}

/** Process-wide, observable execution truth for the currently active mission. */
data class MissionProgress(
    val missionName: String? = null,
    val state: MissionRunState = MissionRunState.IDLE,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val currentAction: String? = null,
    val lastResult: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = state == MissionRunState.RUNNING || state == MissionRunState.PAUSED
}

/**
 * Saved multi-step automation runtime.
 *
 * Only one mission runs at a time. Runtime state is process-wide because chat,
 * dashboard and scheduled receivers may each create their own MissionEngine.
 * Every transition is observable and missions can be paused, resumed or
 * cancelled without leaving an orphan coroutine behind.
 */
class MissionEngine(private val context: Context) {

    private val actions = DeviceActionPack(context)
    private val screen = ScreenAutomation(context)
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
        }.filter { it.name.isNotBlank() && it.steps.isNotEmpty() }
    }.getOrElse { defaults() }

    fun save(mission: Mission): String {
        val cleanName = mission.name.trim()
        val cleanSteps = mission.steps
            .map { it.copy(action = it.action.trim(), argument = it.argument.trim()) }
            .filter { it.action.isNotBlank() }
        if (cleanName.isBlank()) return "Mission name cannot be empty, sir."
        if (cleanSteps.isEmpty()) return "Mission needs at least one valid step, sir."
        if (cleanSteps.size > MAX_STEPS) return "A mission can contain at most $MAX_STEPS steps, sir."

        val existing = all().filterNot { it.name.equals(cleanName, ignoreCase = true) }
        persist(existing + Mission(cleanName, cleanSteps, mission.createdAt))
        return "Mission \"$cleanName\" saved with ${cleanSteps.size} steps, sir."
    }

    fun delete(name: String): String {
        val cleanName = name.trim()
        val existing = all()
        val remaining = existing.filterNot { it.name.equals(cleanName, ignoreCase = true) }
        if (remaining.size == existing.size) return "I couldn't find mission \"$cleanName\", sir."
        persist(remaining)
        return "Mission \"$cleanName\" deleted, sir."
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
    // Execution and controls
    // ------------------------------------------------------------------

    /** Runs a mission by name, or handles run-mission pause/resume/cancel/status. */
    fun run(name: String): String {
        val requested = name.trim()
        when (requested.lowercase()) {
            "pause", "pause current", "pause current mission" -> return pause()
            "resume", "continue", "resume current", "resume current mission" -> return resume()
            "cancel", "stop", "cancel current", "stop current mission" -> return cancel()
            "status", "progress", "current status" -> return status()
        }

        val mission = all().firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?: all().firstOrNull { it.name.contains(requested, ignoreCase = true) }
            ?: return "I don't have a mission called \"$requested\", sir. Say \"list missions\" to see what's saved."
        return launchMission(mission.name, mission.steps)
    }

    /** Runs an ad-hoc chain such as "flashlight on, then volume 30". */
    fun runChain(rawSteps: List<String>): String {
        val steps = rawSteps.mapNotNull { parseStep(it) }
        if (steps.isEmpty()) return "I couldn't understand those steps, sir."
        return launchMission("Ad-hoc chain", steps)
    }

    private fun launchMission(name: String, steps: List<MissionStep>): String {
        if (steps.isEmpty()) return "Mission \"$name\" has no runnable steps, sir."
        synchronized(runtimeLock) {
            val current = progress.value
            if (activeJob?.isActive == true || current.isActive) {
                return "Mission \"${current.missionName ?: "unknown"}\" is already ${current.state.name.lowercase()}, sir. Cancel it before starting another."
            }

            pauseRequested = false
            val started = System.currentTimeMillis()
            mutableProgress.value = MissionProgress(
                missionName = name,
                state = MissionRunState.RUNNING,
                totalSteps = steps.size,
                startedAt = started,
                updatedAt = started
            )

            val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
                try {
                    steps.forEachIndexed { index, step ->
                        awaitResume()
                        coroutineContext.ensureActive()
                        updateProgress {
                            it.copy(
                                state = MissionRunState.RUNNING,
                                completedSteps = index,
                                currentAction = step.action,
                                error = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        }

                        val result = execute(step)
                        updateProgress {
                            it.copy(
                                completedSteps = index + 1,
                                lastResult = result,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        if (index < steps.lastIndex) delay(STEP_DELAY_MS)
                    }
                    updateProgress {
                        it.copy(
                            state = MissionRunState.COMPLETED,
                            currentAction = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                } catch (cancelled: CancellationException) {
                    updateProgress {
                        it.copy(
                            state = MissionRunState.CANCELLED,
                            currentAction = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    val reason = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.java.simpleName
                    updateProgress {
                        it.copy(
                            state = MissionRunState.FAILED,
                            currentAction = null,
                            error = reason,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                } finally {
                    synchronized(runtimeLock) {
                        if (activeJob === coroutineContext[Job]) activeJob = null
                        pauseRequested = false
                    }
                }
            }
            activeJob = job
            job.start()
        }
        return "Running mission \"$name\" — ${steps.size} steps, sir."
    }

    fun pause(): String = synchronized(runtimeLock) {
        val current = progress.value
        if (activeJob?.isActive != true || current.state != MissionRunState.RUNNING) {
            return@synchronized "There is no running mission to pause, sir."
        }
        pauseRequested = true
        mutableProgress.value = current.copy(
            state = MissionRunState.PAUSED,
            updatedAt = System.currentTimeMillis()
        )
        "Mission \"${current.missionName}\" paused after ${current.completedSteps} of ${current.totalSteps} steps, sir."
    }

    fun resume(): String = synchronized(runtimeLock) {
        val current = progress.value
        if (activeJob?.isActive != true || current.state != MissionRunState.PAUSED) {
            return@synchronized "There is no paused mission to resume, sir."
        }
        pauseRequested = false
        mutableProgress.value = current.copy(
            state = MissionRunState.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
        "Resuming mission \"${current.missionName}\", sir."
    }

    fun cancel(): String = synchronized(runtimeLock) {
        val current = progress.value
        val job = activeJob
        if (job?.isActive != true || !current.isActive) {
            return@synchronized "There is no active mission to cancel, sir."
        }
        pauseRequested = false
        mutableProgress.value = current.copy(
            state = MissionRunState.CANCELLED,
            currentAction = null,
            updatedAt = System.currentTimeMillis()
        )
        job.cancel(CancellationException("Cancelled by user"))
        "Mission \"${current.missionName}\" cancelled after ${current.completedSteps} of ${current.totalSteps} steps, sir."
    }

    fun status(): String {
        val current = progress.value
        val name = current.missionName ?: return "No mission has run in this app session, sir."
        val base = "Mission \"$name\" is ${current.state.name.lowercase()}: ${current.completedSteps}/${current.totalSteps} steps"
        val detail = current.error?.let { ". Error: $it" }
            ?: current.currentAction?.let { ". Current action: $it" }
            ?: current.lastResult?.let { ". Last result: $it" }
            ?: "."
        return base + detail
    }

    private suspend fun awaitResume() {
        while (pauseRequested) {
            coroutineContext.ensureActive()
            delay(PAUSE_POLL_MS)
        }
    }

    private fun updateProgress(transform: (MissionProgress) -> MissionProgress) {
        synchronized(runtimeLock) {
            mutableProgress.value = transform(mutableProgress.value)
        }
    }

    private suspend fun execute(step: MissionStep): String {
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
            "wait" -> {
                val seconds = argument.toLongOrNull()?.coerceIn(1L, MAX_WAIT_SECONDS) ?: 1L
                delay(seconds * 1_000L)
                "Waited $seconds second${if (seconds == 1L) "" else "s"}"
            }
            else -> throw IllegalArgumentException("Unknown mission step: ${step.action}")
        }
    }

    /** Maps a natural phrase such as "open whatsapp" to a MissionStep. */
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
            text.startsWith("wait ") -> MissionStep("wait", Regex("\\d+").find(text)?.value ?: "1")
            text.contains("flashlight on") || text.contains("torch on") -> MissionStep("torch_on")
            text.contains("flashlight off") || text.contains("torch off") -> MissionStep("torch_off")
            text.contains("volume") -> MissionStep("volume", Regex("\\d{1,3}").find(text)?.value.orEmpty())
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

    fun describeAll(): String {
        val missions = all()
        if (missions.isEmpty()) return "No missions saved yet, sir."
        return "Saved missions, sir:\n" + missions.joinToString("\n") { mission ->
            "• ${mission.name} (${mission.steps.size} steps): " +
                mission.steps.joinToString(" → ") { step ->
                    if (step.argument.isBlank()) step.action else step.action + " " + step.argument
                }
        } + "\n\nSay \"run mission <name>\" to start one. While it runs, use \"run mission pause\", \"resume\", \"status\" or \"cancel\"."
    }

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
        private const val PAUSE_POLL_MS = 100L
        private const val MAX_STEPS = 50
        private const val MAX_WAIT_SECONDS = 300L

        private val runtimeLock = Any()
        private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutableProgress = MutableStateFlow(MissionProgress())
        val progress: StateFlow<MissionProgress> = mutableProgress.asStateFlow()

        @Volatile private var pauseRequested = false
        private var activeJob: Job? = null
    }
}

package com.jarvis.ai.intelligence

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRouter(private val context: Context) {
    
    suspend fun analyze(command: String): ExecutionPlan = withContext(Dispatchers.IO) {
        val lower = command.lowercase()
        val neededCaps = detectNeededCapabilities(lower)
        val targetCategory = detectTargetCategory(lower)
        
        val candidates = getAvailableApps()
            .filter { entity ->
                (targetCategory.isEmpty() || entity.category == targetCategory) ||
                neededCaps.any { cap -> entity.capabilities.contains(cap) }
            }
            .take(5)
        
        if (candidates.isEmpty()) {
            return@withContext ExecutionPlan(
                steps = emptyList(),
                reasoning = "No matching apps found, using default behavior"
            )
        }
        
        val scoring = candidates.mapIndexed { index, entity ->
            val trustScore = entity.trustScore * 0.4f
            val capMatch = if (neededCaps.any { entity.capabilities.contains(it) }) 0.4f else 0f
            val recency = (1f - index * 0.2f).coerceAtLeast(0f) * 0.2f
            val total = trustScore + capMatch + recency
            
            ScoredApp(
                packageName = entity.packageName,
                appName = entity.appName,
                score = total,
                isAIApp = entity.isAIApp
            )
        }.sortedByDescending { it.score }
        
        val topApp = scoring.firstOrNull()
        val reasoning = buildString {
            append("Selected ${topApp?.appName} (score: ${String.format("%.2f", topApp?.score ?: 0f)})")
            if (scoring.size > 1) {
                append(". Alternatives: ${scoring.drop(1).take(2).joinToString { it.appName }}")
            }
        }
        
        val steps = if (topApp != null) {
            listOf(
                ExecutionStep(
                    appPackage = topApp.packageName,
                    appLabel = topApp.appName,
                    stepType = StepType.OPEN_APP
                )
            )
        } else emptyList()
        
        ExecutionPlan(
            steps = steps,
            reasoning = reasoning,
            scoredApps = scoring
        )
    }
    
    private fun getAvailableApps(): List<AppEntity> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        val hardcoded = mapOf(
            "com.android.chrome" to AppEntity("com.android.chrome", "Chrome", "BROWSER", listOf(Capability.WEB_BROWSE, Capability.SEARCH), 0.9f, false),
            "com.google.android.gm" to AppEntity("com.google.android.gm", "Gmail", "EMAIL", listOf(Capability.EMAIL, Capability.MESSAGING), 0.9f, false),
            "com.whatsapp" to AppEntity("com.whatsapp", "WhatsApp", "COMMUNICATION", listOf(Capability.MESSAGING, Capability.CALL), 0.85f, false),
            "com.google.android.apps.messaging" to AppEntity("com.google.android.apps.messaging", "Messages", "COMMUNICATION", listOf(Capability.MESSAGING), 0.85f, false),
            "com.google.android.maps" to AppEntity("com.google.android.maps", "Google Maps", "UTILITIES", listOf(Capability.NAVIGATION, Capability.LOCATION), 0.9f, false),
            "com.google.android.youtube" to AppEntity("com.google.android.youtube", "YouTube", "ENTERTAINMENT", listOf(Capability.MEDIA_PLAY), 0.8f, false),
            "com.spotify.music" to AppEntity("com.spotify.music", "Spotify", "ENTERTAINMENT", listOf(Capability.MEDIA_PLAY), 0.85f, false),
            "com.android.calculator2" to AppEntity("com.android.calculator2", "Calculator", "UTILITIES", listOf(Capability.AI_REASONING), 0.7f, false),
            "com.google.android.keep" to AppEntity("com.google.android.keep", "Google Keep", "NOTES", listOf(Capability.NOTE_TAKING, Capability.FILE_WRITE), 0.8f, false),
            "com.google.android.calendar" to AppEntity("com.google.android.calendar", "Google Calendar", "CALENDAR", listOf(Capability.CALENDAR, Capability.REMINDER), 0.85f, false),
            "com.android.camera" to AppEntity("com.android.camera", "Camera", "UTILITIES", listOf(Capability.IMAGE_CAPTURE, Capability.CAMERA), 0.8f, false),
            "com.google.android.apps.photos" to AppEntity("com.google.android.apps.photos", "Google Photos", "UTILITIES", listOf(Capability.FILE_READ, Capability.IMAGE_CAPTURE), 0.75f, false),
            "com.android.settings" to AppEntity("com.android.settings", "Settings", "UTILITIES", listOf(Capability.SYSTEM_SETTINGS), 0.9f, false),
            "com.google.android.dialer" to AppEntity("com.google.android.dialer", "Phone", "COMMUNICATION", listOf(Capability.CALL, Capability.CONTACT), 0.85f, false),
            "com.google.android.contacts" to AppEntity("com.google.android.contacts", "Contacts", "COMMUNICATION", listOf(Capability.CONTACT), 0.8f, false),
            "org.telegram.messenger" to AppEntity("org.telegram.messenger", "Telegram", "COMMUNICATION", listOf(Capability.MESSAGING), 0.85f, false),
            "com.instagram.android" to AppEntity("com.instagram.android", "Instagram", "SOCIAL", listOf(Capability.MEDIA_PLAY, Capability.IMAGE_CAPTURE), 0.75f, false),
            "com.twitter.android" to AppEntity("com.twitter.android", "X", "SOCIAL", listOf(Capability.MESSAGING), 0.7f, false),
            "com.netflix.mediaclient" to AppEntity("com.netflix.mediaclient", "Netflix", "ENTERTAINMENT", listOf(Capability.MEDIA_PLAY), 0.8f, false),
            "com.google.android.apps.docs" to AppEntity("com.google.android.apps.docs", "Google Docs", "PRODUCTIVITY", listOf(Capability.FILE_READ, Capability.FILE_WRITE), 0.85f, false),
        )

        val discovered = mutableListOf<AppEntity>()
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val known = hardcoded[pkg]
            if (known != null) {
                discovered.add(known)
            } else {
                val category = guessCategory(pkg)
                val caps = guessCapabilities(pkg)
                discovered.add(AppEntity(pkg, label, category, caps, 0.5f, false))
            }
        }
        return discovered
    }

    private fun guessCategory(pkg: String): String = when {
        pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") -> "BROWSER"
        pkg.contains("mail") || pkg.contains("gmail") -> "EMAIL"
        pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal") || pkg.contains("messenger") -> "COMMUNICATION"
        pkg.contains("maps") || pkg.contains("navigation") -> "UTILITIES"
        pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("spotify") || pkg.contains("music") -> "ENTERTAINMENT"
        pkg.contains("camera") || pkg.contains("photo") -> "UTILITIES"
        pkg.contains("calendar") || pkg.contains("schedule") -> "CALENDAR"
        pkg.contains("note") || pkg.contains("keep") -> "NOTES"
        pkg.contains("docs") || pkg.contains("drive") || pkg.contains("sheets") -> "PRODUCTIVITY"
        pkg.contains("settings") -> "UTILITIES"
        pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("contacts") -> "COMMUNICATION"
        pkg.contains("instagram") || pkg.contains("twitter") || pkg.contains("facebook") || pkg.contains("tiktok") -> "SOCIAL"
        else -> "OTHER"
    }

    private fun guessCapabilities(pkg: String): List<Capability> {
        val caps = mutableListOf<Capability>()
        if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox")) caps.addAll(listOf(Capability.WEB_BROWSE, Capability.SEARCH))
        if (pkg.contains("mail") || pkg.contains("gmail")) caps.addAll(listOf(Capability.EMAIL, Capability.MESSAGING))
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal") || pkg.contains("messenger")) caps.addAll(listOf(Capability.MESSAGING, Capability.CALL))
        if (pkg.contains("maps")) caps.addAll(listOf(Capability.NAVIGATION, Capability.LOCATION))
        if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("spotify") || pkg.contains("music")) caps.add(Capability.MEDIA_PLAY)
        if (pkg.contains("camera")) caps.addAll(listOf(Capability.IMAGE_CAPTURE, Capability.CAMERA))
        if (pkg.contains("photo")) caps.addAll(listOf(Capability.FILE_READ, Capability.IMAGE_CAPTURE))
        if (pkg.contains("calendar") || pkg.contains("schedule")) caps.addAll(listOf(Capability.CALENDAR, Capability.REMINDER))
        if (pkg.contains("note") || pkg.contains("keep")) caps.addAll(listOf(Capability.NOTE_TAKING, Capability.FILE_WRITE))
        if (pkg.contains("docs") || pkg.contains("drive")) caps.addAll(listOf(Capability.FILE_READ, Capability.FILE_WRITE))
        if (pkg.contains("settings")) caps.add(Capability.SYSTEM_SETTINGS)
        if (pkg.contains("dialer") || pkg.contains("phone")) caps.addAll(listOf(Capability.CALL, Capability.CONTACT))
        if (pkg.contains("contacts")) caps.add(Capability.CONTACT)
        if (pkg.contains("calculator")) caps.add(Capability.AI_REASONING)
        if (caps.isEmpty()) caps.add(Capability.AI_REASONING)
        return caps
    }
    
    private fun detectNeededCapabilities(command: String): List<Capability> {
        val caps = mutableListOf<Capability>()
        
        if (command.contains("search")) caps.add(Capability.SEARCH)
        if (command.contains("ask ai") || command.contains("ask chat") || command.contains("reason")) caps.add(Capability.AI_REASONING)
        if (command.contains("message") || command.contains("send") || command.contains("text to")) caps.add(Capability.MESSAGING)
        if (command.contains("read") || command.contains("file")) caps.add(Capability.FILE_READ)
        if (command.contains("write") || command.contains("save") || command.contains("note")) caps.add(Capability.FILE_WRITE)
        if (command.contains("navigate") || command.contains("direction") || command.contains("directions to")) caps.add(Capability.NAVIGATION)
        if (command.contains("pay") || command.contains("payment") || command.contains("buy")) caps.add(Capability.PAYMENT)
        if (command.contains("music") || command.contains("play")) caps.add(Capability.MEDIA_PLAY)
        if (command.contains("browse") || command.contains("web") || command.contains("internet")) caps.add(Capability.WEB_BROWSE)
        if (command.contains("photo") || command.contains("capture") || command.contains("camera")) caps.add(Capability.IMAGE_CAPTURE)
        if (command.contains("email") || command.contains("mail")) caps.add(Capability.EMAIL)
        if (command.contains("calendar") || command.contains("schedule")) caps.add(Capability.CALENDAR)
        if (command.contains("translate")) caps.add(Capability.TRANSLATE)
        if (command.contains("call") || command.contains("phone")) caps.add(Capability.CALL)
        if (command.contains("contact")) caps.add(Capability.CONTACT)
        if (command.contains("location") || command.contains("where")) caps.add(Capability.LOCATION)
        if (command.contains("setting") || command.contains("config")) caps.add(Capability.SYSTEM_SETTINGS)
        if (command.contains("remind") || command.contains("alarm")) caps.add(Capability.REMINDER)
        
        if (caps.isEmpty()) caps.add(Capability.AI_REASONING)
        
        return caps
    }
    
    private fun detectTargetCategory(command: String): String {
        return when {
            command.contains("message") || command.contains("text to") || command.contains("whatsapp") -> "COMMUNICATION"
            command.contains("social") || command.contains("post") || command.contains("facebook") -> "SOCIAL"
            command.contains("search") || command.contains("browse") -> "BROWSER"
            command.contains("music") || command.contains("spotify") -> "ENTERTAINMENT"
            command.contains("maps") || command.contains("navigate") -> "UTILITIES"
            command.contains("drive") || command.contains("docs") || command.contains("document") -> "PRODUCTIVITY"
            command.contains("shop") || command.contains("buy") -> "SHOPPING"
            command.contains("email") || command.contains("mail") -> "EMAIL"
            command.contains("calendar") || command.contains("schedule") -> "CALENDAR"
            command.contains("note") || command.contains("keep") -> "NOTES"
            else -> ""
        }
    }
    
    data class ScoredApp(
        val packageName: String,
        val appName: String,
        val score: Float,
        val isAIApp: Boolean
    )
}

data class AppEntity(
    val packageName: String,
    val appName: String,
    val category: String,
    val capabilities: List<Capability>,
    val trustScore: Float,
    val isAIApp: Boolean
)

data class ExecutionPlan(
    val steps: List<ExecutionStep>,
    val reasoning: String,
    val scoredApps: List<TaskRouter.ScoredApp> = emptyList()
)

data class ExecutionStep(
    val appPackage: String,
    val appLabel: String,
    val stepType: StepType
)

enum class StepType {
    OPEN_APP,
    TAP,
    TYPE,
    SWIPE,
    SCROLL,
    WAIT,
    BACK,
    HOME,
    RECENTS
}
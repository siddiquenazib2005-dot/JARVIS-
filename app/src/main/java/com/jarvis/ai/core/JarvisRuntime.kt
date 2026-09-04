package com.jarvis.ai.core

import android.content.Context
import com.jarvis.ai.agent.AgentCore
import com.jarvis.ai.data.local.SecureStore
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.memory.vector.VectorMemoryManager
import com.jarvis.ai.orchestrator.MasterOrchestrator
import com.jarvis.ai.provider.KeyPoolManager
import com.jarvis.ai.provider.ProviderHealthManager
import com.jarvis.ai.provider.ProviderManager
import com.jarvis.ai.provider.ProviderRouter
import com.jarvis.ai.provider.SecretsSource
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single composition root for the JARVIS backend core.
 *
 * The UI layer NEVER touches provider credentials or endpoints directly — it
 * talks to [com.jarvis.ai.orchestrator.MasterOrchestrator], which routes through
 * the objects assembled here.
 *
 * Secret resolution order (backend-only, never logged):
 *  1. SecureStore (AndroidKeyStore-encrypted preferences)
 *  2. filesDir/secrets.properties (development provisioning; migrated into
 *     SecureStore on first read so the plaintext file can be removed)
 */
class JarvisRuntime private constructor(context: Context) {

    val secureStore: SecureStore = SecureStore(context)
    private val appContext: Context = context.applicationContext

    /** Foreground marker for lifecycle-aware features (hands-free voice, polling). */
    @Volatile
    var isAppForeground: Boolean = false
        private set

    private val _appForeground = MutableStateFlow(false)
    /** Foreground stream shared by runtime consumers (ViewModel, services). */
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    @Volatile
    private var migrationDone: Boolean = false

    val secrets: SecretsSource = object : SecretsSource {
        override fun get(reference: String): String? {
            val baseRef = reference.substringBeforeLast("#")
            secureStore.get(baseRef)?.let { return it }
            migrateFromFileIfNeeded()
            return fileProperties.getProperty(baseRef)
        }
    }

    private val fileProperties: Properties = Properties()

    /** Copies filesDir/secrets.properties entries into SecureStore exactly once. */
    @Synchronized
    private fun migrateFromFileIfNeeded() {
        if (migrationDone) return
        migrationDone = true
        runCatching {
            val f = File(appContext.filesDir, "secrets.properties")
            if (!f.exists()) return
            f.inputStream().use { fileProperties.load(it) }
            var storedAny = false
            fileProperties.stringPropertyNames().forEach { name ->
                val value = fileProperties.getProperty(name)?.trim().orEmpty()
                if (value.isNotBlank() && secureStore.get(name) == null) {
                    secureStore.put(name, value)
                    storedAny = true
                }
            }
            // Plaintext source of truth is now the Keystore-encrypted store; drop the file
            // only when every entry was persisted successfully.
            if (storedAllEntries(fileProperties)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun storedAllEntries(props: Properties): Boolean =
        props.stringPropertyNames()
            .filter { !props.getProperty(it).isNullOrBlank() }
            .all { secureStore.get(it) != null }

    val keys: KeyPoolManager = KeyPoolManager(secrets)
    val health: ProviderHealthManager = ProviderHealthManager()
    val providerManager: ProviderManager = ProviderManager(keys, health, secrets)
    val providerRouter: ProviderRouter = ProviderRouter(
        secrets = secrets,
        keys = keys,
        health = health,
        providerManager = providerManager
    )

    /** True when at least one CHAT-capable provider has a key AND is selectable. */
    fun backendOnline(): Boolean =
        com.jarvis.ai.provider.ProviderRegistry
            .enabledFor(com.jarvis.ai.provider.Capability.CHAT)
            .any { cfg ->
                keys.slots(cfg.providerId).isNotEmpty() &&
                    health.isSelectable(
                        cfg.providerId,
                        cfg.enabled,
                        keys.hasHealthySlot(cfg.providerId, System.currentTimeMillis())
                    )
            }

    fun hasRecordAudioPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Called from the Activity lifecycle; drives [appForeground]. */
    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        _appForeground.value = foreground
    }

    val vectorMemory: VectorMemoryManager by lazy {
        VectorMemoryManager(
            embedder = com.jarvis.ai.memory.vector.MistralEmbeddingProvider(secrets),
            secrets = secrets
        )
    }

    val taskRouter: TaskRouter by lazy { TaskRouter(appContext) }
    val agentCore: AgentCore by lazy {
        AgentCore(appContext, masterOrchestrator!!, providerRouter, taskRouter)
    }

    // MasterOrchestrator needs to be created after taskRouter and agentCore
    // We'll use a lateinit var for this
    lateinit var masterOrchestrator: MasterOrchestrator

    fun initializeOrchestrator() {
        // Create MasterOrchestrator first with null agentCore (will be set after)
        masterOrchestrator = MasterOrchestrator(
            providerRouter = providerRouter,
            vectorMemory = vectorMemory,
            appContext = appContext,
            toolExecutor = com.jarvis.ai.orchestrator.ToolExecutor(appContext),
            taskRouter = taskRouter,
            agentCore = null
        ).apply { visionSecrets = secrets }
        
        // Now set the orchestrator on agentCore (breaks circular dependency)
        agentCore.orchestrator = masterOrchestrator
        // ...and the reverse link: masterOrchestrator was built with agentCore=null
        // (required to break the same circular dependency). Without this line
        // MasterOrchestrator.agentCore stays null forever and every
        // DEVICE_AUTOMATION request silently no-ops behind a fake success message.
        masterOrchestrator.agentCore = agentCore
    }

    fun bootstrap() {
        providerManager.bootstrapFromSecrets()
        initializeOrchestrator()
        EventBus.publish(EventType.APP_STARTED)
    }

    companion object {
        @Volatile
        private var instance: JarvisRuntime? = null

        fun get(context: Context): JarvisRuntime =
            instance ?: synchronized(this) {
                instance ?: JarvisRuntime(context.applicationContext).also {
                    it.bootstrap()
                    instance = it
                }
            }
    }
}

package com.jarvis.ai.provider

import android.content.Context

/**
 * User-facing routing preference: "Auto" (smart multi-provider routing) or a
 * pinned provider + model chosen from the model picker.
 *
 * Kept as a process-wide object because [ModelRouting] and [ProviderManager]
 * are stateless singletons that must honour the preference without a Context.
 * Values are mirrored into SharedPreferences so the choice survives restarts.
 */
object RoutingPrefs {

    private const val PREFS_NAME = "aurix_routing"
    private const val KEY_PROVIDER = "pinned_provider"
    private const val KEY_MODEL = "pinned_model"

    @Volatile
    private var appContext: Context? = null

    /** Provider the user pinned, or null when routing is automatic. */
    @Volatile
    var pinnedProviderId: String? = null
        private set

    /** Model the user pinned, or null when routing is automatic. */
    @Volatile
    var pinnedModel: String? = null
        private set

    /** True when AURIX is free to pick the best healthy provider itself. */
    val isAuto: Boolean get() = pinnedProviderId.isNullOrBlank()

    /** Loads the persisted choice. Safe to call repeatedly. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pinnedProviderId = prefs.getString(KEY_PROVIDER, null)?.takeIf { it.isNotBlank() }
        pinnedModel = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
    }

    /** Pins a provider + model. Passing nulls restores automatic routing. */
    fun pin(providerId: String?, model: String?) {
        pinnedProviderId = providerId?.takeIf { it.isNotBlank() }
        pinnedModel = model?.takeIf { it.isNotBlank() }
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_PROVIDER, pinnedProviderId)
            ?.putString(KEY_MODEL, pinnedModel)
            ?.apply()
    }

    /** Restores automatic multi-provider routing. */
    fun clear() = pin(null, null)
}

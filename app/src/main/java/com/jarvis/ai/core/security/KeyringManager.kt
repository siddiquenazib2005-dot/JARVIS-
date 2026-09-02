package com.jarvis.ai.core.security

import android.content.Context
import android.content.SharedPreferences

class KeyringManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_secure_keys", Context.MODE_PRIVATE)

    fun saveKey(providerName: String, apiKey: String) {
        prefs.edit().putString(providerName.lowercase(), apiKey.trim()).apply()
    }

    fun getKey(providerName: String): String? {
        // Always trim on read too, so whitespace/newlines stored by older builds
        // can never corrupt a URL or Authorization header.
        val key = prefs.getString(providerName.lowercase(), null)?.trim()
        return if (!key.isNullOrBlank()) key else null
    }

    fun hasValidKey(providerName: String): Boolean {
        val key = getKey(providerName)
        return !key.isNullOrEmpty()
    }
}

package com.jarvis.ai.data.local

import android.content.Context
import com.jarvis.ai.data.model.Message
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ChatMemoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Message.serializer())

    fun load(): List<Message> = runCatching {
        prefs.getString(KEY_HISTORY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty()

    fun save(messages: List<Message>) {
        runCatching {
            prefs.edit()
                .putString(KEY_HISTORY, json.encodeToString(serializer, messages))
                .apply()
        }
    }

    fun clear() = prefs.edit().remove(KEY_HISTORY).apply()

    companion object {
        private const val PREFS_NAME = "jarvis_memory"
        private const val KEY_HISTORY = "history_json"
    }
}

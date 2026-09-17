package com.jarvis.ai.core.agent

class WorkingMemory {
    var activePlan: Plan? = null
    private val memory = mutableMapOf<String, String>()

    fun set(key: String, value: String) {
        memory[key] = value
    }

    fun get(key: String): String? = memory[key]

    fun interpolate(text: String): String {
        var result = text
        for ((key, value) in memory) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    fun clear() {
        memory.clear()
    }
}
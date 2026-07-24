package com.msaidizi.app.agent

/**
 * Stub: 12-Factor agent context manager.
 */
class ContextManager(private val agentName: String = "superagent") {
    fun getContext(): Map<String, Any> = emptyMap()
    fun updateContext(key: String, value: Any) {}
    fun clear() {}
}

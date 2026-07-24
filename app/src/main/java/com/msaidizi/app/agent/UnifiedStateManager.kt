package com.msaidizi.app.agent

/**
 * Stub: Unified state manager for agent state.
 */
class UnifiedStateManager(private val agentName: String = "superagent") {
    fun getState(): Map<String, Any> = emptyMap()
    fun updateState(key: String, value: Any) {}
    fun reset() {}
}

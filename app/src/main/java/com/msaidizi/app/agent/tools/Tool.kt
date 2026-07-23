package com.msaidizi.app.agent.tools

/**
 * Tool — The interface for all SuperAgent capabilities.
 * 
 * Tools replace the 33 agent classes. ONE agent, MANY tools.
 * Each tool is a self-contained capability that the agent can invoke.
 * 
 * Design: arch_android.md Section 1.2
 */
interface Tool {
    /** Unique name for this tool */
    val name: String
    
    /** Human-readable description */
    val description: String
    
    /** What intents this tool can handle */
    val supportedIntents: List<String>
    
    /** Memory required to run (MB) — for 2GB device management */
    val memoryRequiredMB: Int
    
    /** Execute the tool with given arguments */
    suspend fun execute(args: Map<String, Any>, language: String): ToolResult
    
    /** Called when device is low on memory — release caches */
    fun onLowMemory()
    
    /** Check if this tool can handle the given intent */
    fun canHandle(intent: String): Boolean = supportedIntents.contains(intent)
}

/**
 * Result of a tool execution.
 */
data class ToolResult(
    val text: String,
    val data: Map<String, Any>,
    val success: Boolean,
    val errorCode: String? = null
)

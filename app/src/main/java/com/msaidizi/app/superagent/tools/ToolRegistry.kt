package com.msaidizi.app.superagent.tools

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for all available tools.
 * Tools register themselves here; the harness dispatches via execute().
 */
@Singleton
class ToolRegistry @Inject constructor() {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a tool. Call during DI setup or app init.
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
        Timber.d("Tool registered: ${tool.name}")
    }

    /**
     * Execute a named tool with the given params.
     */
    suspend fun execute(toolName: String, params: Map<String, String>): ToolResult? {
        val tool = tools[toolName]
        if (tool == null) {
            Timber.w("Tool not found: $toolName")
            return ToolResult.error(toolName, "Tool not found", "TOOL_NOT_FOUND")
        }
        return try {
            tool.execute(params)
        } catch (e: Exception) {
            Timber.e(e, "Tool execution failed: $toolName")
            ToolResult.error(toolName, e.message ?: "Unknown error", "EXECUTION_ERROR")
        }
    }

    fun hasTool(name: String): Boolean = tools.containsKey(name)
    fun getTool(name: String): Tool? = tools[name]
    fun getAllTools(): List<Tool> = tools.values.toList()
}

/**
 * Interface that all tools must implement.
 */
interface Tool {
    val name: String
    val description: String
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * Execute the tool with the given parameters.
     */
    suspend fun execute(params: Map<String, String>): ToolResult
}

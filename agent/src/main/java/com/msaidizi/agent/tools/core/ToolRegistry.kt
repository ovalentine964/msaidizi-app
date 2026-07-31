package com.msaidizi.agent.tools.core

import com.google.gson.JsonObject
import com.msaidizi.agent.flywheel.FlywheelEngine
import timber.log.Timber

// ──────────────────────────────────────────────
// Tool Interface (with mandatory schema)
// ──────────────────────────────────────────────

/**
 * Interface that all tools must implement.
 * Each tool MUST declare an [argsSchema] so the LLM knows the exact
 * parameter names, types, and which are required.
 */
interface Tool {
    val name: String
    val description: String
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * JSON Schema for this tool's arguments.
     * Used by [HermesPromptBuilder] to generate function-calling prompts
     * and by [ToolRegistry] to validate arguments before execution.
     *
     * Return an [emptyArgSchema] if the tool takes no parameters.
     */
    val argsSchema: ToolArgSchema

    /**
     * Execute the tool with the given parameters.
     */
    suspend fun execute(params: Map<String, String>): ToolResult
}

// ──────────────────────────────────────────────
// Schema-aware Tool Registry
// ──────────────────────────────────────────────

/**
 * Registry for all available tools.
 *
 * Tools register themselves here. The registry:
 *  1. Exposes their JSON Schemas to the LLM prompt builder
 *  2. Validates arguments before execution (rejects malformed calls)
 *  3. Provides a lookup from tool name → schema for the harness
 */
class ToolRegistry(
    private val flywheelEngine: FlywheelEngine? = null
) {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a tool. Call during DI setup or app init.
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
        Timber.d("Tool registered: ${tool.name} (schema: ${tool.argsSchema.getProperties().size} params)")
    }

    /**
     * Execute a named tool with the given params.
     *
     * **Validates arguments against the tool's JSON Schema first.**
     * If validation fails, returns an error [ToolResult] without executing.
     */
    suspend fun execute(toolName: String, params: Map<String, String>): ToolResult? {
        val tool = tools[toolName]
        if (tool == null) {
            Timber.w("Tool not found: $toolName")
            return ToolResult.error(toolName, "Tool not found", "TOOL_NOT_FOUND")
        }

        // ── Schema validation ──
        val validationErrors = tool.argsSchema.validate(params)
        if (validationErrors.isNotEmpty()) {
            val errorMsg = "Invalid arguments for '$toolName': ${validationErrors.joinToString("; ")}"
            Timber.w(errorMsg)
            return ToolResult.error(toolName, errorMsg, "VALIDATION_ERROR")
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

    // ── Schema accessors for prompt building ──

    /**
     * Get the JSON Schema (as Gson JsonObject) for a specific tool.
     * Returns null if the tool doesn't exist.
     */
    fun getSchemaJson(toolName: String): JsonObject? {
        return tools[toolName]?.argsSchema?.toJsonSchema()
    }

    /**
     * Get all tool schemas as a map of tool-name → JSON Schema.
     * Used by [HermesPromptBuilder] to build the function-calling system prompt.
     */
    fun getAllSchemas(): Map<String, JsonObject> {
        return tools.mapValues { it.value.argsSchema.toJsonSchema() }
    }

    /**
     * Get all tool arg schemas (the builder objects, not yet serialized).
     * Useful for validation without re-parsing.
     */
    fun getAllArgSchemas(): Map<String, ToolArgSchema> {
        return tools.mapValues { it.value.argsSchema }
    }

    /**
     * Build a Hermes-compatible function definition for a single tool.
     * Returns a JsonObject with: name, description, parameters (JSON Schema).
     */
    fun getToolDefinition(toolName: String): JsonObject? {
        val tool = tools[toolName] ?: return null
        val def = JsonObject()
        def.addProperty("name", tool.name)
        def.addProperty("description", tool.description)
        def.add("parameters", tool.argsSchema.toJsonSchema())
        return def
    }

    /**
     * Get all tool definitions as a list of JsonObjects.
     */
    fun getAllToolDefinitions(): List<JsonObject> {
        return tools.values.map { tool ->
            val def = JsonObject()
            def.addProperty("name", tool.name)
            def.addProperty("description", tool.description)
            def.add("parameters", tool.argsSchema.toJsonSchema())
            def
        }
    }

    // ── Flywheel-powered reliability ──

    /**
     * Get reliability score for a tool from flywheel learning data.
     * Returns 0.0-1.0 (default 0.5 for untracked tools).
     */
    suspend fun getReliabilityScore(toolName: String): Float {
        return try {
            val reliability = flywheelEngine?.getToolReliability() ?: return 0.5f
            reliability[toolName] ?: 0.5f
        } catch (e: Exception) {
            0.5f
        }
    }

    /**
     * Suggest a more reliable alternative tool for the same intent.
     * Returns the alternative tool name if one exists with higher reliability, else null.
     */
    suspend fun suggestAlternative(currentTool: String, intentType: String): String? {
        return try {
            val reliability = flywheelEngine?.getToolReliability() ?: return null
            val currentScore = reliability[currentTool] ?: 0.5f

            // Find tools with same intent prefix that are more reliable
            val intentPrefix = intentType.lowercase().replace("_", "").take(6)
            val alternatives = reliability.entries
                .filter { (name, score) ->
                    name != currentTool && score > currentScore + 0.1f &&
                    name.lowercase().replace("_", "").contains(intentPrefix)
                }
                .sortedByDescending { it.value }

            alternatives.firstOrNull()?.key
        } catch (e: Exception) {
            null
        }
    }
}

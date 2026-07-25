package com.msaidizi.app.superagent.harness

import com.msaidizi.app.superagent.tools.ToolArgSchema
import com.msaidizi.app.superagent.tools.ToolRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber

// ──────────────────────────────────────────────
// Function Schema Definitions (backward compat)
// ──────────────────────────────────────────────

/**
 * Schema for a single function parameter.
 * Kept for backward compatibility with existing code that references this type.
 */
data class ParameterSchema(
    val type: String,           // "string", "number", "boolean", "enum"
    val description: String,
    val enum: List<String>? = null,
    val required: Boolean = true
)

/**
 * Schema for a callable function/tool.
 * Kept for backward compatibility.
 */
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterSchema>
)

/**
 * Result of parsing a function call from LLM output.
 */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, String>
)

// ──────────────────────────────────────────────
// Converter: ToolArgSchema → FunctionSchema
// ──────────────────────────────────────────────

/**
 * Convert a [ToolArgSchema] (from the Tool interface) to a [FunctionSchema]
 * (used by the prompt builder).
 */
fun ToolArgSchema.toFunctionSchema(name: String, description: String): FunctionSchema {
    val params = mutableMapOf<String, ParameterSchema>()
    for (prop in getProperties()) {
        params[prop.name] = ParameterSchema(
            type = prop.type,
            description = prop.description,
            enum = prop.enumValues,
            required = prop.required
        )
    }
    return FunctionSchema(name = name, description = description, parameters = params)
}

// ──────────────────────────────────────────────
// Predefined Tool Schemas (backward compat)
// ──────────────────────────────────────────────

/**
 * Central registry of all tool function schemas.
 *
 * **NOTE**: The canonical source of truth is now [ToolRegistry.getAllArgSchemas()].
 * This object is kept for backward compatibility with code that references
 * [ToolSchemas.ALL] directly. For new code, prefer passing a [ToolRegistry]
 * to [HermesPromptBuilder.buildFunctionCallingSystemPrompt].
 */
object ToolSchemas {

    val RECORD_TRANSACTION = FunctionSchema(
        name = "record_transaction",
        description = "Record a business transaction (sale, expense, or stock purchase)",
        parameters = mapOf(
            "type" to ParameterSchema("string", "Transaction type", listOf("sale", "expense", "purchase")),
            "amount" to ParameterSchema("number", "Transaction amount in KES"),
            "product" to ParameterSchema("string", "Product name", required = false),
            "quantity" to ParameterSchema("number", "Quantity sold or purchased", required = false),
            "payment_method" to ParameterSchema("string", "Payment method", listOf("cash", "mpesa", "credit"), required = false),
            "category" to ParameterSchema("string", "Expense category", listOf("transport", "rent", "food", "utilities", "stock", "misc"), required = false),
            "description" to ParameterSchema("string", "Description", required = false),
            "customer" to ParameterSchema("string", "Customer name", required = false)
        )
    )

    val CHECK_STOCK = FunctionSchema(
        name = "check_stock",
        description = "Check current inventory/stock levels",
        parameters = mapOf(
            "product" to ParameterSchema("string", "Product name (empty for all)", required = false)
        )
    )

    val QUERY_SALES = FunctionSchema(
        name = "query_sales",
        description = "Query sales data",
        parameters = mapOf(
            "period" to ParameterSchema("string", "Time period", listOf("today", "yesterday", "week", "month"), required = false)
        )
    )

    val QUERY_EXPENSES = FunctionSchema(
        name = "query_expenses",
        description = "Query expense data",
        parameters = mapOf(
            "period" to ParameterSchema("string", "Time period", listOf("today", "yesterday", "week", "month"), required = false),
            "category" to ParameterSchema("string", "Expense category", required = false)
        )
    )

    val QUERY_PROFIT = FunctionSchema(
        name = "query_profit",
        description = "Query profit data",
        parameters = mapOf(
            "period" to ParameterSchema("string", "Time period", listOf("today", "yesterday", "week", "month"), required = false)
        )
    )

    val QUERY_DEBTORS = FunctionSchema(
        name = "query_debtors",
        description = "List customers who owe money",
        parameters = emptyMap()
    )

    val GENERATE_REPORT = FunctionSchema(
        name = "generate_report",
        description = "Generate a business report",
        parameters = mapOf(
            "period" to ParameterSchema("string", "Report period", listOf("daily", "weekly", "monthly"), required = false)
        )
    )

    val PRICING_ADVICE = FunctionSchema(
        name = "pricing_advice",
        description = "Get pricing advice for a product",
        parameters = mapOf(
            "product" to ParameterSchema("string", "Product name"),
            "current_price" to ParameterSchema("number", "Current price in KES", required = false)
        )
    )

    val ALL: Map<String, FunctionSchema> = mapOf(
        RECORD_TRANSACTION.name to RECORD_TRANSACTION,
        CHECK_STOCK.name to CHECK_STOCK,
        QUERY_SALES.name to QUERY_SALES,
        QUERY_EXPENSES.name to QUERY_EXPENSES,
        QUERY_PROFIT.name to QUERY_PROFIT,
        QUERY_DEBTORS.name to QUERY_DEBTORS,
        GENERATE_REPORT.name to GENERATE_REPORT,
        PRICING_ADVICE.name to PRICING_ADVICE
    )

    val CONVERSATIONAL_INTENTS = setOf(
        IntentType.GREETING,
        IntentType.FAREWELL,
        IntentType.THANKS,
        IntentType.HELP,
        IntentType.CHITCHAT
    )
}

// ──────────────────────────────────────────────
// Hermes-Style Prompt Builder
// ──────────────────────────────────────────────

/**
 * Builds Hermes-style function calling prompts for the LLM.
 *
 * Format compatible with Qwen/Hermes chat templates:
 * - System prompt describes available functions with JSON Schema
 * - LLM responds with <tool_call> JSON blocks
 * - Parser extracts and validates structured function calls
 */
object HermesPromptBuilder {

    private val gson = Gson()

    /**
     * Build a Hermes-style system prompt using schemas from the [ToolRegistry].
     *
     * This is the **preferred** method — it reads schemas directly from
     * registered tools, so the prompt always matches the actual tool definitions.
     */
    fun buildFunctionCallingSystemPrompt(
        baseSystemPrompt: String,
        toolRegistry: ToolRegistry
    ): String {
        val definitions = toolRegistry.getAllToolDefinitions()
        return buildSystemPromptFromDefinitions(baseSystemPrompt, definitions)
    }

    /**
     * Build a Hermes-style system prompt from a collection of [FunctionSchema]s.
     *
     * Kept for backward compatibility. Prefer the [ToolRegistry] overload.
     */
    fun buildFunctionCallingSystemPrompt(
        baseSystemPrompt: String,
        schemas: Collection<FunctionSchema> = ToolSchemas.ALL.values
    ): String {
        return buildString {
            appendLine(baseSystemPrompt)
            appendLine()
            appendLine("# Available Functions")
            appendLine()
            appendLine("You have access to the following functions. If the user's message requires an action, call the appropriate function by outputting a JSON block.")
            appendLine()
            for (schema in schemas) {
                appendLine("## ${schema.name}")
                appendLine("Description: ${schema.description}")
                appendLine("Parameters:")
                val required = mutableListOf<String>()
                val optional = mutableListOf<String>()
                for ((paramName, paramSchema) in schema.parameters) {
                    val desc = buildString {
                        append("- $paramName (${paramSchema.type})")
                        paramSchema.enum?.let { append(" [${it.joinToString(", ")}]") }
                        append(": ${paramSchema.description}")
                    }
                    if (paramSchema.required) {
                        required.add(desc)
                    } else {
                        optional.add(desc)
                    }
                }
                if (required.isNotEmpty()) {
                    appendLine("  Required:")
                    required.forEach { appendLine("    $it") }
                }
                if (optional.isNotEmpty()) {
                    appendLine("  Optional:")
                    optional.forEach { appendLine("    $it") }
                }
                appendLine()
            }
            appendLine("# Response Format")
            appendLine()
            appendLine("When you need to call a function, respond with ONLY a JSON block in this exact format:")
            appendLine()
            appendLine("<tool_call>")
            appendLine("{\"name\": \"function_name\", \"arguments\": {\"param1\": \"value1\", \"param2\": 123}}")
            appendLine("</tool_call>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Call ONLY one function per response")
            appendLine("- Use the exact function names listed above")
            appendLine("- Arguments must match the defined parameters")
            appendLine("- For conversational messages (greetings, thanks, help), respond naturally WITHOUT a function call")
            appendLine("- When unsure, ask the user to clarify rather than guessing")
        }
    }

    /**
     * Build system prompt from JSON Schema tool definitions (from ToolRegistry).
     */
    private fun buildSystemPromptFromDefinitions(
        baseSystemPrompt: String,
        definitions: List<JsonObject>
    ): String {
        return buildString {
            appendLine(baseSystemPrompt)
            appendLine()
            appendLine("# Available Functions")
            appendLine()
            appendLine("You have access to the following functions. If the user's message requires an action, call the appropriate function by outputting a JSON block.")
            appendLine()
            for (def in definitions) {
                val name = def.get("name")?.asString ?: continue
                val description = def.get("description")?.asString ?: ""
                val params = def.getAsJsonObject("parameters")
                val properties = params?.getAsJsonObject("properties")
                val requiredList = params?.getAsJsonArray("required")?.map { it.asString } ?: emptyList()

                appendLine("## $name")
                appendLine("Description: $description")

                if (properties != null && properties.size() > 0) {
                    appendLine("Parameters:")
                    val required = mutableListOf<String>()
                    val optional = mutableListOf<String>()
                    for ((paramName, paramValue) in properties.entrySet()) {
                        val paramObj = paramValue.asJsonObject
                        val type = paramObj.get("type")?.asString ?: "string"
                        val paramDesc = paramObj.get("description")?.asString ?: ""
                        val enumValues = paramObj.getAsJsonArray("enum")?.map { it.asString }
                        val isRequired = paramName in requiredList

                        val desc = buildString {
                            append("- $paramName ($type)")
                            enumValues?.let { append(" [${it.joinToString(", ")}]") }
                            append(": $paramDesc")
                        }
                        if (isRequired) required.add(desc) else optional.add(desc)
                    }
                    if (required.isNotEmpty()) {
                        appendLine("  Required:")
                        required.forEach { appendLine("    $it") }
                    }
                    if (optional.isNotEmpty()) {
                        appendLine("  Optional:")
                        optional.forEach { appendLine("    $it") }
                    }
                }
                appendLine()
            }
            appendLine("# Response Format")
            appendLine()
            appendLine("When you need to call a function, respond with ONLY a JSON block in this exact format:")
            appendLine()
            appendLine("<tool_call>")
            appendLine("{\"name\": \"function_name\", \"arguments\": {\"param1\": \"value1\", \"param2\": 123}}")
            appendLine("</tool_call>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Call ONLY one function per response")
            appendLine("- Use the exact function names listed above")
            appendLine("- Arguments must match the defined parameters")
            appendLine("- For conversational messages (greetings, thanks, help), respond naturally WITHOUT a function call")
            appendLine("- When unsure, ask the user to clarify rather than guessing")
        }
    }

    /**
     * Parse a function call from LLM output.
     * Returns null if the output doesn't contain a valid function call.
     */
    fun parseFunctionCall(llmOutput: String): FunctionCall? {
        // Try to extract <tool_call>...</tool_call> block
        val toolCallPattern = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        val match = toolCallPattern.find(llmOutput)

        val jsonStr = match?.groupValues?.get(1)
            ?: // Try parsing the entire output as JSON (model might skip tags)
            llmOutput.trim().let { raw ->
                if (raw.startsWith("{") && raw.contains("\"name\"")) raw else null
            }
            ?: return null

        return try {
            val jsonObj = JsonParser.parseString(jsonStr).asJsonObject
            val name = jsonObj.get("name")?.asString ?: return null
            val argsObj = jsonObj.getAsJsonObject("arguments") ?: JsonObject()

            val arguments = mutableMapOf<String, String>()
            for ((key, value) in argsObj.entrySet()) {
                arguments[key] = when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asString
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asString
                    else -> value.asString
                }
            }

            FunctionCall(name = name, arguments = arguments)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse function call from LLM output: $llmOutput")
            null
        }
    }

    /**
     * Parse a function call and validate it against the tool's schema.
     * Returns a [ValidatedFunctionCall] with both the parsed call and any validation errors.
     */
    fun parseAndValidateFunctionCall(
        llmOutput: String,
        toolRegistry: ToolRegistry
    ): ValidatedFunctionCall {
        val call = parseFunctionCall(llmOutput)
            ?: return ValidatedFunctionCall(null, listOf("No valid function call found in LLM output"))

        val tool = toolRegistry.getTool(call.name)
            ?: return ValidatedFunctionCall(call, listOf("Unknown function: '${call.name}'"))

        val errors = tool.argsSchema.validate(call.arguments)
        return ValidatedFunctionCall(call, errors)
    }

    /**
     * Map a parsed function call to an IntentType.
     */
    fun functionCallToIntentType(functionName: String): IntentType {
        return when (functionName) {
            "record_transaction" -> IntentType.RECORD_SALE
            "inventory_tracker" -> IntentType.ASK_STOCK
            "check_stock" -> IntentType.ASK_STOCK
            "query_sales" -> IntentType.ASK_SALES_TODAY
            "query_expenses" -> IntentType.ASK_EXPENSES
            "query_profit" -> IntentType.ASK_PROFIT
            "query_debtors" -> IntentType.ASK_DEBTORS
            "generate_report" -> IntentType.DAILY_REPORT
            "pricing_advice", "pricing_advisor" -> IntentType.ASK_ADVICE
            "cfo_engine" -> IntentType.DAILY_REPORT
            else -> IntentType.UNKNOWN
        }
    }

    /**
     * Refine intent type based on function call arguments.
     */
    fun refineIntent(functionCall: FunctionCall): IntentType {
        if (functionCall.name == "record_transaction") {
            return when (functionCall.arguments["type"]) {
                "sale" -> IntentType.RECORD_SALE
                "expense" -> IntentType.RECORD_EXPENSE
                "purchase" -> IntentType.RECORD_PURCHASE
                "service" -> IntentType.RECORD_SERVICE
                else -> IntentType.RECORD_SALE
            }
        }
        return functionCallToIntentType(functionCall.name)
    }
}

/**
 * Result of parsing + validating a function call.
 */
data class ValidatedFunctionCall(
    val call: FunctionCall?,
    val errors: List<String>
) {
    val isValid: Boolean get() = call != null && errors.isEmpty()
}

package com.msaidizi.app.superagent.harness

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber

// ──────────────────────────────────────────────
// Function Schema Definitions
// ──────────────────────────────────────────────

/**
 * Schema for a single function parameter.
 */
data class ParameterSchema(
    val type: String,           // "string", "number", "boolean", "enum"
    val description: String,
    val enum: List<String>? = null,
    val required: Boolean = true
)

/**
 * Schema for a callable function/tool.
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
// Predefined Tool Schemas
// ──────────────────────────────────────────────

/**
 * Central registry of all tool function schemas.
 * Used by LlmEngine to build Hermes-style function calling prompts.
 */
object ToolSchemas {

    val RECORD_TRANSACTION = FunctionSchema(
        name = "record_transaction",
        description = "Record a business transaction (sale, expense, or stock purchase)",
        parameters = mapOf(
            "type" to ParameterSchema(
                type = "string",
                description = "Transaction type",
                enum = listOf("sale", "expense", "purchase")
            ),
            "amount" to ParameterSchema(
                type = "number",
                description = "Transaction amount in KES (Kenya Shillings)"
            ),
            "product" to ParameterSchema(
                type = "string",
                description = "Product name",
                required = false
            ),
            "quantity" to ParameterSchema(
                type = "number",
                description = "Quantity sold or purchased",
                required = false
            ),
            "payment_method" to ParameterSchema(
                type = "string",
                description = "Payment method used",
                enum = listOf("cash", "mpesa", "credit"),
                required = false
            ),
            "category" to ParameterSchema(
                type = "string",
                description = "Expense category (for expenses only)",
                enum = listOf("transport", "rent", "food", "utilities", "stock", "misc"),
                required = false
            ),
            "description" to ParameterSchema(
                type = "string",
                description = "Description of the transaction",
                required = false
            ),
            "customer" to ParameterSchema(
                type = "string",
                description = "Customer name (for credit sales)",
                required = false
            )
        )
    )

    val CHECK_STOCK = FunctionSchema(
        name = "check_stock",
        description = "Check current inventory/stock levels for products",
        parameters = mapOf(
            "product" to ParameterSchema(
                type = "string",
                description = "Product name to check (empty for all products)",
                required = false
            )
        )
    )

    val QUERY_SALES = FunctionSchema(
        name = "query_sales",
        description = "Query sales data for today, this week, or a specific period",
        parameters = mapOf(
            "period" to ParameterSchema(
                type = "string",
                description = "Time period to query",
                enum = listOf("today", "yesterday", "week", "month"),
                required = false
            )
        )
    )

    val QUERY_EXPENSES = FunctionSchema(
        name = "query_expenses",
        description = "Query expense data for today, this week, or a specific period",
        parameters = mapOf(
            "period" to ParameterSchema(
                type = "string",
                description = "Time period to query",
                enum = listOf("today", "yesterday", "week", "month"),
                required = false
            ),
            "category" to ParameterSchema(
                type = "string",
                description = "Filter by expense category",
                required = false
            )
        )
    )

    val QUERY_PROFIT = FunctionSchema(
        name = "query_profit",
        description = "Query profit data (sales minus expenses)",
        parameters = mapOf(
            "period" to ParameterSchema(
                type = "string",
                description = "Time period to query",
                enum = listOf("today", "yesterday", "week", "month"),
                required = false
            )
        )
    )

    val QUERY_DEBTORS = FunctionSchema(
        name = "query_debtors",
        description = "List customers who owe money (credit balance)",
        parameters = emptyMap()
    )

    val GENERATE_REPORT = FunctionSchema(
        name = "generate_report",
        description = "Generate a business report (daily, weekly, or monthly summary)",
        parameters = mapOf(
            "period" to ParameterSchema(
                type = "string",
                description = "Report period",
                enum = listOf("daily", "weekly", "monthly"),
                required = false
            )
        )
    )

    val PRICING_ADVICE = FunctionSchema(
        name = "pricing_advice",
        description = "Get pricing advice for a product based on market comparison",
        parameters = mapOf(
            "product" to ParameterSchema(
                type = "string",
                description = "Product name to get pricing advice for"
            ),
            "current_price" to ParameterSchema(
                type = "number",
                description = "Current selling price in KES",
                required = false
            )
        )
    )

    /**
     * All available function schemas, keyed by name.
     */
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

    /**
     * Schemas that map to conversational intents (no function call needed).
     * Used to determine when NOT to call a function.
     */
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
 * - System prompt describes available functions
 * - LLM responds with <tool_call> JSON blocks
 * - Parser extracts structured function calls
 */
object HermesPromptBuilder {

    private val gson = Gson()

    /**
     * Build a Hermes-style system prompt that includes function definitions.
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
     * Map a parsed function call to an IntentType.
     */
    fun functionCallToIntentType(functionName: String): IntentType {
        return when (functionName) {
            "record_transaction" -> IntentType.RECORD_SALE  // refined by type param
            "check_stock" -> IntentType.ASK_STOCK
            "query_sales" -> IntentType.ASK_SALES_TODAY
            "query_expenses" -> IntentType.ASK_EXPENSES
            "query_profit" -> IntentType.ASK_PROFIT
            "query_debtors" -> IntentType.ASK_DEBTORS
            "generate_report" -> IntentType.DAILY_REPORT
            "pricing_advice" -> IntentType.ASK_ADVICE
            else -> IntentType.UNKNOWN
        }
    }

    /**
     * Refine intent type based on function call arguments.
     * For record_transaction, the "type" param determines the specific intent.
     */
    fun refineIntent(functionCall: FunctionCall): IntentType {
        if (functionCall.name == "record_transaction") {
            return when (functionCall.arguments["type"]) {
                "sale" -> IntentType.RECORD_SALE
                "expense" -> IntentType.RECORD_EXPENSE
                "purchase" -> IntentType.RECORD_PURCHASE
                else -> IntentType.RECORD_SALE
            }
        }
        return functionCallToIntentType(functionCall.name)
    }
}

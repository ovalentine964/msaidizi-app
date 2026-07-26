package com.msaidizi.app.superagent.council

import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.UserIntent
import com.msaidizi.app.superagent.tools.ToolRegistry
import com.msaidizi.app.superagent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CouncilManager — Routes intents to specialized tool councils.
 *
 * This is the routing layer between the SuperagentHarness (supervisor)
 * and the 6 domain-specialized councils. Each council is a logical grouping
 * of tools that share a domain — NOT a separate process or agent.
 *
 * Routing is deterministic and fast (<1ms per intent):
 *   1. Intent type → primary council (hardcoded map, O(1) lookup)
 *   2. Council executes relevant tools via ToolRegistry
 *   3. Results returned to supervisor for response synthesis
 *
 * Design:
 * - Councils are logical groupings, not runtime objects
 * - Tool execution stays in ToolRegistry (no duplication)
 * - Council boundaries are for routing + context scoping only
 * - Event publishing happens AFTER tool execution (post-hook pattern)
 *
 * Memory overhead: ~2KB for the routing maps. Negligible.
 */
@Singleton
class CouncilManager @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val eventBus: CouncilEventBus,
    private val contextScope: ContextScope
) {
    // ── Intent → Council Routing Table ──────────────────────────

    /**
     * Primary routing: IntentType → CouncilType.
     * Each intent maps to exactly one primary council.
     * Unknown intents fall back to null (handled by supervisor directly).
     */
    private val intentToCouncil: Map<IntentType, CouncilType> = mapOf(
        // Finance Council
        IntentType.RECORD_SALE to CouncilType.FINANCE,
        IntentType.RECORD_EXPENSE to CouncilType.FINANCE,
        IntentType.RECORD_PURCHASE to CouncilType.FINANCE,
        IntentType.RECORD_SERVICE to CouncilType.FINANCE,
        IntentType.ASK_SALES_TODAY to CouncilType.FINANCE,
        IntentType.ASK_PROFIT to CouncilType.FINANCE,
        IntentType.ASK_EXPENSES to CouncilType.FINANCE,
        IntentType.ASK_DEBTORS to CouncilType.FINANCE,
        IntentType.CHECK_CUSTOMER_DEBT to CouncilType.FINANCE,
        IntentType.RECORD_PAYMENT to CouncilType.FINANCE,
        IntentType.DAILY_REPORT to CouncilType.FINANCE,
        IntentType.WEEKLY_REPORT to CouncilType.FINANCE,
        IntentType.MONTHLY_REPORT to CouncilType.FINANCE,
        IntentType.QUICK_SALE to CouncilType.FINANCE,
        IntentType.SCAN_RECEIPT to CouncilType.FINANCE,
        IntentType.PROOF_OF_INCOME to CouncilType.FINANCE,
        IntentType.WHATSAPP_REPORT to CouncilType.FINANCE,

        // Inventory Council
        IntentType.ASK_STOCK to CouncilType.INVENTORY,
        IntentType.ADD_PRODUCT to CouncilType.INVENTORY,
        IntentType.UPDATE_STOCK to CouncilType.INVENTORY,
        IntentType.CHECK_STOCK to CouncilType.INVENTORY,

        // Market Council
        IntentType.MARKET_PRICE to CouncilType.MARKET,
        IntentType.ASK_ADVICE to CouncilType.MARKET,

        // Growth Council
        IntentType.CREDIT_CHECK to CouncilType.GROWTH,
        IntentType.LOAN_COMPARE to CouncilType.GROWTH,
        IntentType.INSURANCE_MATCH to CouncilType.GROWTH,
        IntentType.CHAMA_MANAGE to CouncilType.GROWTH,
        IntentType.GOAL_TRACK to CouncilType.GROWTH,
        IntentType.RIDE_SHARE to CouncilType.GROWTH,

        // Voice Council
        IntentType.VOICE_COMMAND to CouncilType.VOICE,

        // Security Council (rare direct intents)
        IntentType.VIEW_DASHBOARD to CouncilType.FINANCE
    )

    // ── Council → Tool Mapping ──────────────────────────────────

    /**
     * Tools owned by each council.
     * A council "owns" a tool means it has primary responsibility for
     * executing that tool and publishing related events.
     */
    private val councilTools: Map<CouncilType, Set<String>> = mapOf(
        CouncilType.FINANCE to setOf(
            "record_transaction", "cfo_engine", "profit_by_product",
            "proof_of_income", "debt_tracker", "mpesa_parser",
            "mpesa_auto_logger", "quick_sale", "receipt_scanner",
            "receipt_scanner_cv", "business_health_dashboard",
            "whatsapp_reporter", "price_negotiator", "service_menu",
            "anomaly_detector"
        ),
        CouncilType.INVENTORY to setOf(
            "inventory_tracker", "restock_predictor", "auto_restock",
            "bulk_order_coordinator", "waste_reducer"
        ),
        CouncilType.MARKET to setOf(
            "pricing_advisor", "market_price_broadcaster", "competitor_tracker",
            "market_day_planner", "supplier_matcher", "market_pooling"
        ),
        CouncilType.GROWTH to setOf(
            "gamification", "goal_tracker", "credit_readiness",
            "loan_comparison", "insurance_matcher", "chama_manager",
            "customer_insights", "ride_share", "alama_score"
        ),
        CouncilType.VOICE to setOf(
            "voice_pipeline", "language_detector", "code_switch_handler",
            "service_voice_commands"
        ),
        CouncilType.SECURITY to setOf(
            "security_guard", "guardrails_engine", "access_control_manager",
            "audit_trail_manager", "privacy_guard"
        )
    )

    /**
     * Reverse lookup: tool name → owning council.
     * Built at init time from [councilTools].
     */
    private val toolToCouncil: Map<String, CouncilType> = buildMap {
        for ((council, tools) in councilTools) {
            for (tool in tools) {
                put(tool, council)
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Determine which council should handle a given intent.
     * Returns null for conversational intents (greeting, help, etc.)
     * that don't need tool execution.
     *
     * Performance: O(1) map lookup, <1ms.
     */
    fun resolveCouncil(intent: UserIntent): CouncilType? {
        return intentToCouncil[intent.type]
    }

    /**
     * Get the tools owned by a council.
     */
    fun getCouncilTools(council: CouncilType): Set<String> {
        return councilTools[council] ?: emptySet()
    }

    /**
     * Get which council owns a specific tool.
     * Returns null if the tool isn't assigned to any council.
     */
    fun getToolCouncil(toolName: String): CouncilType? {
        return toolToCouncil[toolName]
    }

    /**
     * Execute an intent through its assigned council.
     *
     * Pipeline:
     *   1. Resolve council from intent type
     *   2. Get scoped context for that council
     *   3. Execute relevant tools via ToolRegistry
     *   4. Publish events for downstream councils
     *   5. Return results to supervisor
     */
    suspend fun executeIntent(
        intent: UserIntent,
        sessionId: String
    ): CouncilExecutionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val council = resolveCouncil(intent)

        if (council == null) {
            Timber.d("No council for intent ${intent.type}, returning empty result")
            return@withContext CouncilExecutionResult(
                council = null,
                toolResults = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        Timber.d("Routing ${intent.type} → $council")

        // Get scoped context (lazy, cached)
        val scopedContext = contextScope.getScopedContext(council)

        // Execute tools owned by this council
        val toolResults = mutableListOf<ToolResult>()
        for (toolName in intent.requiredTools) {
            // Only execute tools owned by this council (or shared tools)
            val toolCouncil = getToolCouncil(toolName)
            if (toolCouncil != null && toolCouncil != council) {
                Timber.w("Tool $toolName belongs to $toolCouncil, not $council — skipping")
                continue
            }

            val params = intent.toolParams[toolName] ?: emptyMap()
            val result = toolRegistry.execute(toolName, params)
            if (result != null) {
                toolResults.add(result)
                publishToolEvents(council, toolName, result)
            }
        }

        val executionTime = System.currentTimeMillis() - startTime
        Timber.d("Council $council executed ${toolResults.size} tools in ${executionTime}ms")

        CouncilExecutionResult(
            council = council,
            toolResults = toolResults,
            executionTimeMs = executionTime,
            scopedContext = scopedContext
        )
    }

    /**
     * Execute a specific tool by name, respecting council ownership.
     * Used when the LLM makes a direct tool call (not intent-driven).
     */
    suspend fun executeTool(
        toolName: String,
        params: Map<String, String>
    ): ToolResult? {
        val startTime = System.currentTimeMillis()
        val result = toolRegistry.execute(toolName, params)

        if (result != null) {
            val council = getToolCouncil(toolName)
            if (council != null) {
                publishToolEvents(council, toolName, result)
            }
        }

        Timber.d("Direct tool execution: $toolName in ${System.currentTimeMillis() - startTime}ms")
        return result
    }

    /**
     * Get all council names and their tool counts.
     * Useful for health checks and debugging.
     */
    fun getCouncilSummary(): Map<CouncilType, CouncilSummary> {
        return CouncilType.entries.associateWith { council ->
            CouncilSummary(
                councilType = council,
                toolCount = councilTools[council]?.size ?: 0,
                toolNames = councilTools[council]?.toList() ?: emptyList()
            )
        }
    }

    // ── Event Publishing ────────────────────────────────────────

    /**
     * Publish events based on tool execution results.
     * This is the post-hook that enables inter-council communication.
     *
     * Example flow:
     *   TransactionRecorder.execute() returns success
     *     → publish TRANSACTION_RECORDED
     *       → Inventory council reacts: updates stock
     *       → Growth council reacts: awards points
     */
    private fun publishToolEvents(
        sourceCouncil: CouncilType,
        toolName: String,
        result: ToolResult
    ) {
        if (!result.success) return

        when {
            // Finance: Transaction recorded → notify inventory + growth
            toolName == "record_transaction" && result.success -> {
                eventBus.publish(CouncilEvent(
                    type = CouncilEventType.TRANSACTION_RECORDED,
                    sourceCouncil = sourceCouncil,
                    payload = (result.data as? Map<*, *>)?.entries?.associate { 
                        (it.key.toString() to (it.value ?: "")) 
                    } ?: emptyMap()
                ))
            }

            // Finance: Debt recorded → notify growth
            toolName == "debt_tracker" && result.success -> {
                eventBus.publish(CouncilEvent(
                    type = CouncilEventType.DEBT_RECORDED,
                    sourceCouncil = sourceCouncil,
                    payload = (result.data as? Map<*, *>)?.entries?.associate { 
                        (it.key.toString() to (it.value ?: "")) 
                    } ?: emptyMap()
                ))
            }

            // Inventory: Stock low → notify market for restock check
            toolName == "inventory_tracker" && result.success -> {
                val data = result.data as? Map<*, *>
                val isLow = data?.get("low_stock_alert") == true
                if (isLow) {
                    eventBus.publish(CouncilEvent(
                        type = CouncilEventType.STOCK_LOW,
                        sourceCouncil = sourceCouncil,
                        payload = mapOf(
                            "product" to (data?.get("product")?.toString() ?: "unknown"),
                            "stock" to (data?.get("new_stock")?.toString() ?: "0")
                        )
                    ))
                }
            }

            // Growth: Level up → broadcast
            toolName == "gamification" && result.success -> {
                val data = result.data as? Map<*, *>
                if (data?.containsKey("new_level") == true) {
                    eventBus.publish(CouncilEvent(
                        type = CouncilEventType.LEVEL_UP,
                        sourceCouncil = sourceCouncil,
                        payload = mapOf(
                            "level" to (data["new_level"]?.toString() ?: ""),
                            "points" to (data["total_points"]?.toString() ?: "0")
                        )
                    ))
                }
            }

            // Security: Anomaly detected → broadcast
            toolName == "anomaly_detector" && result.success -> {
                val data = result.data as? Map<*, *>
                if (data?.get("anomaly_detected") == true) {
                    eventBus.publish(CouncilEvent(
                        type = CouncilEventType.ANOMALY_DETECTED,
                        sourceCouncil = sourceCouncil,
                        payload = mapOf("details" to result.message)
                    ))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Result Types
// ──────────────────────────────────────────────

/**
 * Result of executing an intent through a council.
 */
data class CouncilExecutionResult(
    val council: CouncilType?,
    val toolResults: List<ToolResult>,
    val executionTimeMs: Long,
    val scopedContext: ScopedContext? = null
)

/**
 * Summary of a council's configuration.
 */
data class CouncilSummary(
    val councilType: CouncilType,
    val toolCount: Int,
    val toolNames: List<String>
)

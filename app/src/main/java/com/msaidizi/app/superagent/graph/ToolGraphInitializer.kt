package com.msaidizi.app.superagent.graph

import com.msaidizi.app.superagent.tools.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ToolGraphInitializer — Pre-wires the Msaidizi tool dependency graph.
 *
 * Defines how tools relate to each other:
 *   - record_sale DEPENDS ON inventory_tracker (must update stock)
 *   - record_sale TRIGGERS gamification_engine (award points)
 *   - record_sale FEEDS_INTO cfo_engine (for profit calculation)
 *   - daily_report DEPENDS ON query_sales, query_expenses, query_profit
 *
 * Call once during app init, after all tools are registered.
 *
 * This replaces the flat map with a rich graph that enables:
 *   1. Automatic dependency resolution (inventory updated before sale confirmed)
 *   2. Parallel execution (check_stock + query_sales can run simultaneously)
 *   3. Conditional routing (if stock low → trigger auto_restock)
 *   4. Side-effect chaining (sale → gamification → notification)
 */
@Singleton
class ToolGraphInitializer @Inject constructor(
    private val toolGraph: ToolGraph,
    private val toolRegistry: ToolRegistry
) {
    /**
     * Wire the complete Msaidizi tool graph.
     * Call after all tools are registered in ToolRegistry.
     */
    fun initialize() {
        Timber.d("ToolGraphInitializer: wiring Msaidizi tool dependency graph")

        // ═══════════════════════════════════════════════════════════
        //  TRANSACTION CLUSTER
        // ═══════════════════════════════════════════════════════════

        // record_sale depends on inventory_tracker (check + update stock)
        safeAddEdge("record_sale", "inventory_tracker", EdgeType.DEPENDENCY)

        // record_sale triggers gamification_engine (award points)
        safeAddEdge("record_sale", "gamification_engine", EdgeType.TRIGGER)

        // record_sale feeds into cfo_engine (profit calculation)
        safeAddEdge("record_sale", "cfo_engine", EdgeType.FEEDS_INTO)

        // record_sale triggers anomaly_detector (check for unusual sales)
        safeAddEdge("record_sale", "anomaly_detector", EdgeType.TRIGGER)

        // quick_sale → same dependencies as record_sale
        safeAddEdge("quick_sale", "inventory_tracker", EdgeType.DEPENDENCY)
        safeAddEdge("quick_sale", "gamification_engine", EdgeType.TRIGGER)

        // record_expense feeds into cfo_engine
        safeAddEdge("record_expense", "cfo_engine", EdgeType.FEEDS_INTO)

        // record_purchase depends on inventory_tracker (add stock)
        safeAddEdge("record_purchase", "inventory_tracker", EdgeType.DEPENDENCY)

        // record_service feeds into cfo_engine
        safeAddEdge("record_service", "cfo_engine", EdgeType.FEEDS_INTO)

        // record_payment feeds into debt_tracker
        safeAddEdge("record_payment", "debt_tracker", EdgeType.FEEDS_INTO)

        // ═══════════════════════════════════════════════════════════
        //  QUERY CLUSTER (parallel-safe)
        // ═══════════════════════════════════════════════════════════

        // query_sales, query_expenses, query_profit are independent (parallel)
        // No edges between them — they can all run simultaneously

        // check_stock depends on inventory_tracker
        safeAddEdge("check_stock", "inventory_tracker", EdgeType.DEPENDENCY)

        // query_debtors depends on debt_tracker
        safeAddEdge("query_debtors", "debt_tracker", EdgeType.DEPENDENCY)

        // ═══════════════════════════════════════════════════════════
        //  REPORT CLUSTER (depends on queries)
        // ═══════════════════════════════════════════════════════════

        // generate_report depends on multiple query tools
        safeAddEdge("generate_report", "query_sales", EdgeType.DEPENDENCY)
        safeAddEdge("generate_report", "query_expenses", EdgeType.DEPENDENCY)
        safeAddEdge("generate_report", "query_profit", EdgeType.DEPENDENCY)

        // whatsapp_reporter depends on generate_report
        safeAddEdge("whatsapp_reporter", "generate_report", EdgeType.DEPENDENCY)

        // proof_of_income depends on query_sales
        safeAddEdge("proof_of_income", "query_sales", EdgeType.DEPENDENCY)

        // ═══════════════════════════════════════════════════════════
        //  INVENTORY CLUSTER
        // ═══════════════════════════════════════════════════════════

        // auto_restock depends on inventory_tracker
        safeAddEdge("auto_restock", "inventory_tracker", EdgeType.DEPENDENCY)

        // auto_restock triggers bulk_order_coordinator
        safeAddEdge("auto_restock", "bulk_order_coordinator", EdgeType.TRIGGER)

        // market_price_broadcaster feeds into auto_restock
        safeAddEdge("market_price_broadcaster", "auto_restock", EdgeType.FEEDS_INTO)

        // ═══════════════════════════════════════════════════════════
        //  CREDIT CLUSTER
        // ═══════════════════════════════════════════════════════════

        // credit_readiness depends on cfo_engine
        safeAddEdge("credit_readiness", "cfo_engine", EdgeType.DEPENDENCY)

        // loan_comparison depends on credit_readiness
        safeAddEdge("loan_comparison", "credit_readiness", EdgeType.DEPENDENCY)

        // insurance_matcher depends on cfo_engine
        safeAddEdge("insurance_matcher", "cfo_engine", EdgeType.DEPENDENCY)

        // ═══════════════════════════════════════════════════════════
        //  CONDITIONAL ROUTING
        // ═══════════════════════════════════════════════════════════

        // If stock is low after a sale, trigger restock suggestion
        safeAddEdge("inventory_tracker", "auto_restock", EdgeType.CONDITIONAL, "low_stock")

        // If anomaly detected, trigger alert
        safeAddEdge("anomaly_detector", "cfo_engine", EdgeType.CONDITIONAL, "anomaly_found")

        // ═══════════════════════════════════════════════════════════
        //  CONCURRENCY GROUPS (tools that must serialize)
        // ═══════════════════════════════════════════════════════════

        // All write operations to inventory must serialize
        // inventory_tracker is already registered by InventoryTracker — just set metadata
        toolGraph.setNodeMeta("inventory_tracker", ToolNodeMeta(
            concurrencyGroup = "inventory_writes",
            writesData = true
        ))

        // All write operations to sales must serialize
        // record_sale delegates to record_transaction (which handles all transaction types)
        toolGraph.registerNode(
            object : Tool {
                override val name = "record_sale"
                override val description = "Record a sale"
                override val argsSchema = argSchema {}
                override suspend fun execute(params: Map<String, String>): ToolResult {
                    val saleParams = params.toMutableMap()
                    saleParams["type"] = "sale"
                    return toolRegistry.execute("record_transaction", saleParams)
                        ?: ToolResult.error(name, "record_transaction tool not found", "TOOL_NOT_FOUND")
                }
            },
            ToolNodeMeta(concurrencyGroup = "transaction_writes", writesData = true, required = true)
        )

        // record_expense delegates to record_transaction
        toolGraph.registerNode(
            object : Tool {
                override val name = "record_expense"
                override val description = "Record an expense"
                override val argsSchema = argSchema {}
                override suspend fun execute(params: Map<String, String>): ToolResult {
                    val expenseParams = params.toMutableMap()
                    expenseParams["type"] = "expense"
                    return toolRegistry.execute("record_transaction", expenseParams)
                        ?: ToolResult.error(name, "record_transaction tool not found", "TOOL_NOT_FOUND")
                }
            },
            ToolNodeMeta(concurrencyGroup = "transaction_writes", writesData = true)
        )

        val stats = toolGraph.getStats()
        Timber.d("ToolGraphInitializer: graph wired — %d nodes, %d edges, %d concurrency groups",
            stats.nodeCount, stats.edgeCount, stats.concurrencyGroups)
    }

    /**
     * Safely add an edge, logging but not crashing if tools don't exist yet.
     * Tools may be registered lazily; edges are validated at execution time.
     */
    private fun safeAddEdge(from: String, to: String, type: EdgeType, condition: String? = null) {
        try {
            if (toolGraph.getStats().nodeCount == 0) {
                // Graph empty — edges will be added when tools register
                Timber.d("ToolGraphInitializer: deferring edge %s → %s (graph empty)", from, to)
                return
            }
            toolGraph.addEdge(from, to, type, condition)
        } catch (e: Exception) {
            // Tools may not be registered yet — that's OK
            Timber.d("ToolGraphInitializer: edge %s → %s deferred: %s", from, to, e.message)
        }
    }
}

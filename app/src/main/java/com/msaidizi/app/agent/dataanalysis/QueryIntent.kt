package com.msaidizi.app.data.natsql

/**
 * Structured representation of a user's data query intent.
 *
 * Instead of generating raw SQL (which is dangerous on-device), the NL→SQL agent
 * parses natural language into this typed intent model. The [SafeQueryExecutor] then
 * maps each intent to pre-vetted Room DAO calls.
 *
 * This is the core safety mechanism: the LLM never writes SQL directly.
 */
sealed class QueryIntent {

    /** Time range for the query. Uses Unix timestamps. */
    data class TimeRange(
        val startEpochSec: Long,
        val endEpochSec: Long,
        val label: String = "" // Human-readable: "leo", "wiki hii", etc.
    )

    // ── Transaction Queries ──

    /** "How much did I spend on supplies last week?" */
    data class GetTotal(
        val transactionType: TransactionFilter,
        val timeRange: TimeRange,
        val itemFilter: String? = null,
        val categoryFilter: String? = null
    ) : QueryIntent()

    /** "What did I sell today?" / "Show me my expenses this week" */
    data class ListTransactions(
        val transactionType: TransactionFilter? = null,
        val timeRange: TimeRange? = null,
        val itemFilter: String? = null,
        val limit: Int = 10
    ) : QueryIntent()

    /** "What are my top selling items this month?" */
    data class GetTopItems(
        val timeRange: TimeRange,
        val limit: Int = 5
    ) : QueryIntent()

    /** "How is my business doing?" / "Give me a summary" */
    data class GetBusinessSummary(
        val timeRange: TimeRange
    ) : QueryIntent()

    /** "What is my profit this week?" */
    data class GetProfit(
        val timeRange: TimeRange
    ) : QueryIntent()

    // ── Inventory Queries ──

    /** "How much sugar do I have left?" / "Check my stock" */
    data class CheckInventory(
        val itemFilter: String? = null
    ) : QueryIntent()

    /** "What items need restocking?" */
    data class GetRestockAlerts(
        val dummy: Boolean = true // Avoid empty data class
    ) : QueryIntent()

    // ── Goal Queries ──

    /** "How much have I saved?" / "Am I close to my goal?" */
    data class GetGoalProgress(
        val goalFilter: String? = null
    ) : QueryIntent()

    // ── Trend Queries ──

    /** "Are my sales improving?" / "Show me sales trends" */
    data class GetTrend(
        val metric: TrendMetric,
        val timeRange: TimeRange
    ) : QueryIntent()

    // ── Comparison Queries ──

    /** "Did I sell more this week or last week?" */
    data class Compare(
        val metric: TrendMetric,
        val period1: TimeRange,
        val period2: TimeRange
    ) : QueryIntent()

    // ── Fallback ──

    /** When the LLM can't parse the query */
    data class Unknown(
        val originalQuery: String,
        val reason: String = ""
    ) : QueryIntent()
}

/**
 * Transaction type filter — maps to [TransactionType] enum.
 */
enum class TransactionFilter {
    SALE,
    PURCHASE,
    EXPENSE,
    ALL_INCOME,    // SALE + DEPOSIT
    ALL_OUTFLOW,   // PURCHASE + EXPENSE + WITHDRAWAL
    ALL            // Everything
}

/**
 * Metrics that can be trended or compared.
 */
enum class TrendMetric {
    SALES,
    EXPENSES,
    PURCHASES,
    PROFIT,
    TRANSACTION_COUNT
}

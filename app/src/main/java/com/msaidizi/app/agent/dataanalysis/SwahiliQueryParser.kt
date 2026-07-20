package com.msaidizi.app.data.natsql

import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.voice.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses natural language queries (Swahili, Sheng, English) into [QueryIntent]s.
 *
 * Two-stage parsing:
 * 1. **Fast pattern matching** — handles ~70% of common Swahili business queries
 *    without LLM inference (zero latency, zero battery).
 * 2. **LLM fallback** — for complex or unusual queries, sends to Qwen 0.5B with
 *    a constrained output schema.
 *
 * Swahili time expressions are resolved here ("leo", "jana", "wiki hii", "mwezi huu")
 * so the LLM doesn't need to handle date math.
 */
@Singleton
class SwahiliQueryParser @Inject constructor(
    private val llmEngine: LlmEngine
) {
    companion object {
        private const val TAG = "SwahiliQueryParser"

        // ── Swahili keyword patterns ──

        // Transaction type keywords
        private val SALE_KEYWORDS = setOf(
            "sale", "sold", "niliuza", "niliuza", "mauzo", "mapato", "income",
            "nimeuza", "nimeshauza", "niliuza", "zinauzwa", "nilipata",
            "nimepata", "nimeshapata", "zote nilizouza"
        )
        private val PURCHASE_KEYWORDS = setOf(
            "purchase", "bought", "nunuzi", "nimenunua", "nilinunua",
            "nilichukua", "nimechukua", "manunuzi", "gharama ya kununua"
        )
        private val EXPENSE_KEYWORDS = setOf(
            "expense", "spent", "matumizi", "nilitumia", "nimetumia",
            "gharama", "malipo", "nilipia", "nimepia", "nimeshalipia",
            "supplies", "vifaa", "uliaga"
        )
        private val PROFIT_KEYWORDS = setOf(
            "profit", "faida", "mapato baada ya", "nilipata faida",
            "gani faida", "je faida"
        )

        // Query intent keywords
        private val TOTAL_KEYWORDS = setOf(
            "how much", "gani", "ngapi", "pesa ngapi", "shilingi ngapi",
            "jumla", "total", "amount", "je ni ngapi", "ni pesa ngapi"
        )
        private val LIST_KEYWORDS = setOf(
            "show", "list", "what did", "nini", "onyesha", "taja",
            "niliuza nini", "nimenunua nini", "nilinunua nini"
        )
        private val TOP_KEYWORDS = setOf(
            "top", "best", "selling", "bora", "zaidi", "nyingi",
            "zinauzwa zaidi", "items ninazouza"
        )
        private val SUMMARY_KEYWORDS = setOf(
            "summary", "overview", "how is", "je biashara", "hali ya",
            "muhtasari", "jumla ya biashara", "biashara yangu"
        )
        private val INVENTORY_KEYWORDS = setOf(
            "stock", "inventory", "remaining", "left", "imebaki",
            "hifadhi", "ni ngapi imebaki", "sasa", "kichwani"
        )
        private val RESTOCK_KEYWORDS = setOf(
            "restock", "order", "refill", "agizo", "ninahitaji kununua",
            "nimepungua", "imeisha"
        )
        private val GOAL_KEYWORDS = setOf(
            "goal", "target", "savings", "nimehifadhi", "lengo",
            "nimefikia", "najifunza"
        )
        private val TREND_KEYWORDS = setOf(
            "trend", "improving", "growing", "better", "mbinu",
            "inaenda", "inapanda", "inashuka", "je inaendelea"
        )
        private val COMPARE_KEYWORDS = setOf(
            "compare", "vs", "versus", "kuliko", "wiki hii kuliko",
            "mwezi huu kuliko"
        )

        // ── Swahili time expression patterns ──
        private val TIME_PATTERNS = mapOf(
            // Today
            "leo" to TimeExpr.TODAY,
            "today" to TimeExpr.TODAY,
            "saa hii" to TimeExpr.TODAY,

            // Yesterday
            "jana" to TimeExpr.YESTERDAY,
            "yesterday" to TimeExpr.YESTERDAY,

            // This week
            "wiki hii" to TimeExpr.THIS_WEEK,
            "this week" to TimeExpr.THIS_WEEK,
            "week" to TimeExpr.THIS_WEEK,

            // Last week
            "wiki iliyopita" to TimeExpr.LAST_WEEK,
            "last week" to TimeExpr.LAST_WEEK,
            "wiki jana" to TimeExpr.LAST_WEEK,

            // This month
            "mwezi huu" to TimeExpr.THIS_MONTH,
            "this month" to TimeExpr.THIS_MONTH,
            "month" to TimeExpr.THIS_MONTH,

            // Last month
            "mwezi uliopita" to TimeExpr.LAST_MONTH,
            "last month" to TimeExpr.LAST_MONTH,
            "mwezi jana" to TimeExpr.LAST_MONTH,

            // Today + yesterday (common for "how much today")
            "siku mbili" to TimeExpr.LAST_2_DAYS,
            "two days" to TimeExpr.LAST_2_DAYS,

            // This year
            "mwaka huu" to TimeExpr.THIS_YEAR,
            "this year" to TimeExpr.THIS_YEAR
        )

        // Item extraction patterns: "kwa sukari", "ya unga", "za mandazi"
        private val ITEM_PATTERNS = listOf(
            Regex("""kwa\s+(\w+)"""),         // "kwa sukari"
            Regex("""ya\s+(\w+)"""),           // "ya unga"
            Regex("""za\s+(\w+)"""),           // "za mandazi"
            Regex("""for\s+(\w+)"""),          // "for sugar"
            Regex("""on\s+(\w+)"""),           // "on supplies"
            Regex("""of\s+(\w+)"""),           // "of flour"
            Regex("""katika\s+(\w+)"""),       // "katika sukari"
        )
    }

    private enum class TimeExpr {
        TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK,
        THIS_MONTH, LAST_MONTH, LAST_2_DAYS, THIS_YEAR
    }

    /**
     * Parse a natural language query into a [QueryIntent].
     * Tries fast pattern matching first; falls back to LLM for complex queries.
     */
    suspend fun parse(query: String): QueryIntent {
        val normalized = query.lowercase().trim()

        // Stage 1: Fast pattern matching
        val patternResult = tryPatternMatch(normalized)
        if (patternResult != null) {
            Timber.d(TAG, "Pattern match: %s → %s", query, patternResult::class.simpleName)
            return patternResult
        }

        // Stage 2: LLM fallback for complex queries
        Timber.d(TAG, "Pattern match failed, falling back to LLM for: %s", query)
        return tryLlmParse(query)
    }

    /**
     * Fast pattern matching for common Swahili business queries.
     * Returns null if no pattern matches (triggers LLM fallback).
     */
    private fun tryPatternMatch(query: String): QueryIntent? {
        val timeRange = extractTimeRange(query)
        val itemFilter = extractItemFilter(query)

        // Detect query type
        val isProfitQuery = PROFIT_KEYWORDS.any { query.contains(it) }
        val isSummaryQuery = SUMMARY_KEYWORDS.any { query.contains(it) }
        val isInventoryQuery = INVENTORY_KEYWORDS.any { query.contains(it) }
        val isRestockQuery = RESTOCK_KEYWORDS.any { query.contains(it) }
        val isGoalQuery = GOAL_KEYWORDS.any { query.contains(it) }
        val isTrendQuery = TREND_KEYWORDS.any { query.contains(it) }
        val isCompareQuery = COMPARE_KEYWORDS.any { query.contains(it) }
        val isTopQuery = TOP_KEYWORDS.any { query.contains(it) }
        val isListQuery = LIST_KEYWORDS.any { query.contains(it) }
        val isTotalQuery = TOTAL_KEYWORDS.any { query.contains(it) }

        // Detect transaction type
        val txType = when {
            SALE_KEYWORDS.any { query.contains(it) } -> TransactionFilter.SALE
            PURCHASE_KEYWORDS.any { query.contains(it) } -> TransactionFilter.PURCHASE
            EXPENSE_KEYWORDS.any { query.contains(it) } -> TransactionFilter.EXPENSE
            else -> null
        }

        // Route to intent
        return when {
            isProfitQuery -> QueryIntent.GetProfit(
                timeRange = timeRange ?: defaultTimeRange()
            )

            isSummaryQuery -> QueryIntent.GetBusinessSummary(
                timeRange = timeRange ?: defaultTimeRange()
            )

            isInventoryQuery -> QueryIntent.CheckInventory(
                itemFilter = itemFilter
            )

            isRestockQuery -> QueryIntent.GetRestockAlerts()

            isGoalQuery -> QueryIntent.GetGoalProgress(
                goalFilter = itemFilter
            )

            isTrendQuery -> QueryIntent.GetTrend(
                metric = txType?.toTrendMetric() ?: TrendMetric.SALES,
                timeRange = timeRange ?: defaultTimeRange()
            )

            isCompareQuery -> {
                // For compare queries, we need two time periods
                // Default: this week vs last week
                val thisWeek = resolveTimeExpr(TimeExpr.THIS_WEEK)
                val lastWeek = resolveTimeExpr(TimeExpr.LAST_WEEK)
                QueryIntent.Compare(
                    metric = txType?.toTrendMetric() ?: TrendMetric.SALES,
                    period1 = thisWeek,
                    period2 = lastWeek
                )
            }

            isTopQuery -> QueryIntent.GetTopItems(
                timeRange = timeRange ?: defaultTimeRange(),
                limit = 5
            )

            isListQuery -> QueryIntent.ListTransactions(
                transactionType = txType,
                timeRange = timeRange,
                itemFilter = itemFilter,
                limit = 10
            )

            isTotalQuery && txType != null -> QueryIntent.GetTotal(
                transactionType = txType,
                timeRange = timeRange ?: defaultTimeRange(),
                itemFilter = itemFilter
            )

            // If we detected a transaction type but no specific intent, assume "how much"
            txType != null -> QueryIntent.GetTotal(
                transactionType = txType,
                timeRange = timeRange ?: defaultTimeRange(),
                itemFilter = itemFilter
            )

            // No pattern matched
            else -> null
        }
    }

    /**
     * LLM-based parsing for complex queries that pattern matching can't handle.
     * Uses constrained JSON output with temperature=0 for deterministic parsing.
     */
    private suspend fun tryLlmParse(query: String): QueryIntent {
        val schema = """
You are a query parser for a business app. Parse the user's question into JSON.

User question: "$query"

Respond with ONLY a JSON object (no explanation):
{
  "intent": "GET_TOTAL|LIST_TRANSACTIONS|GET_TOP_ITEMS|GET_BUSINESS_SUMMARY|GET_PROFIT|CHECK_INVENTORY|GET_RESTOCK_ALERTS|GET_GOAL_PROGRESS|GET_TREND|COMPARE|UNKNOWN",
  "transaction_type": "SALE|PURCHASE|EXPENSE|ALL_INCOME|ALL_OUTFLOW|ALL|null",
  "time_period": "TODAY|YESTERDAY|THIS_WEEK|LAST_WEEK|THIS_MONTH|LAST_MONTH|THIS_YEAR|null",
  "item": "extracted item name or null",
  "limit": 10
}

Examples:
"nilitumia pesa ngapi kwa wiki iliyopita?" → {"intent":"GET_TOTAL","transaction_type":"EXPENSE","time_period":"LAST_WEEK","item":null,"limit":10}
"je, nina sukari ya kutosha?" → {"intent":"CHECK_INVENTORY","transaction_type":null,"time_period":null,"item":"sukari","limit":10}
"onyesha mauzo ya jana" → {"intent":"LIST_TRANSACTIONS","transaction_type":"SALE","time_period":"YESTERDAY","item":null,"limit":10}
"""

        return try {
            val response = llmEngine.generate(
                prompt = schema,
                maxTokens = 128,
                temperature = 0.0f // Deterministic for parsing
            )
            parseLlmJsonResponse(response, query)
        } catch (e: Throwable) {
            Timber.w(e, TAG, "LLM parsing failed")
            QueryIntent.Unknown(query, "LLM parsing failed: ${e.message}")
        }
    }

    /**
     * Parse the LLM's JSON response into a QueryIntent.
     */
    private fun parseLlmJsonResponse(response: String, originalQuery: String): QueryIntent {
        // Extract JSON from response (handle cases where LLM adds explanation)
        val jsonMatch = Regex("""\{[^}]+}""").find(response)?.value ?: return QueryIntent.Unknown(originalQuery, "No JSON in LLM response")

        val intent = Regex(""""intent"\s*:\s*"([^"]+)"""").find(jsonMatch)?.groupValues?.get(1) ?: "UNKNOWN"
        val txType = Regex(""""transaction_type"\s*:\s*"([^"]+)"""").find(jsonMatch)?.groupValues?.get(1)
        val timePeriod = Regex(""""time_period"\s*:\s*"([^"]+)"""").find(jsonMatch)?.groupValues?.get(1)
        val item = Regex(""""item"\s*:\s*"([^"]+)"""").find(jsonMatch)?.groupValues?.get(1)
        val limit = Regex(""""limit"\s*:\s*(\d+)""").find(jsonMatch)?.groupValues?.get(1)?.toIntOrNull() ?: 10

        val timeRange = timePeriod?.let { resolveTimeExprString(it) }
        val filter = txType?.let { parseTransactionFilter(it) }

        return when (intent) {
            "GET_TOTAL" -> QueryIntent.GetTotal(
                transactionType = filter ?: TransactionFilter.ALL_OUTFLOW,
                timeRange = timeRange ?: defaultTimeRange(),
                itemFilter = item
            )
            "LIST_TRANSACTIONS" -> QueryIntent.ListTransactions(
                transactionType = filter,
                timeRange = timeRange,
                itemFilter = item,
                limit = limit
            )
            "GET_TOP_ITEMS" -> QueryIntent.GetTopItems(
                timeRange = timeRange ?: defaultTimeRange(),
                limit = limit
            )
            "GET_BUSINESS_SUMMARY" -> QueryIntent.GetBusinessSummary(
                timeRange = timeRange ?: defaultTimeRange()
            )
            "GET_PROFIT" -> QueryIntent.GetProfit(
                timeRange = timeRange ?: defaultTimeRange()
            )
            "CHECK_INVENTORY" -> QueryIntent.CheckInventory(itemFilter = item)
            "GET_RESTOCK_ALERTS" -> QueryIntent.GetRestockAlerts()
            "GET_GOAL_PROGRESS" -> QueryIntent.GetGoalProgress(goalFilter = item)
            "GET_TREND" -> QueryIntent.GetTrend(
                metric = filter?.toTrendMetric() ?: TrendMetric.SALES,
                timeRange = timeRange ?: defaultTimeRange()
            )
            "COMPARE" -> QueryIntent.Compare(
                metric = filter?.toTrendMetric() ?: TrendMetric.SALES,
                period1 = timeRange ?: resolveTimeExpr(TimeExpr.THIS_WEEK),
                period2 = resolveTimeExpr(TimeExpr.LAST_WEEK)
            )
            else -> QueryIntent.Unknown(originalQuery, "LLM returned intent: $intent")
        }
    }

    // ── Time Resolution ──

    private fun extractTimeRange(query: String): QueryIntent.TimeRange? {
        for ((pattern, expr) in TIME_PATTERNS) {
            if (query.contains(pattern)) {
                return resolveTimeExpr(expr)
            }
        }
        return null
    }

    private fun resolveTimeExpr(expr: TimeExpr): QueryIntent.TimeRange {
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis() / 1000

        return when (expr) {
            TimeExpr.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, now, "leo")
            }
            TimeExpr.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, end, "jana")
            }
            TimeExpr.THIS_WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, now, "wiki hii")
            }
            TimeExpr.LAST_WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val end = cal.timeInMillis / 1000
                cal.add(Calendar.WEEK_OF_YEAR, -1)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, end, "wiki iliyopita")
            }
            TimeExpr.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, now, "mwezi huu")
            }
            TimeExpr.LAST_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val end = cal.timeInMillis / 1000
                cal.add(Calendar.MONTH, -1)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, end, "mwezi uliopita")
            }
            TimeExpr.LAST_2_DAYS -> {
                cal.add(Calendar.DAY_OF_YEAR, -2)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, now, "siku mbili")
            }
            TimeExpr.THIS_YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis / 1000
                QueryIntent.TimeRange(start, now, "mwaka huu")
            }
        }
    }

    private fun resolveTimeExprString(expr: String): QueryIntent.TimeRange? {
        return try {
            resolveTimeExpr(TimeExpr.valueOf(expr))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun defaultTimeRange(): QueryIntent.TimeRange = resolveTimeExpr(TimeExpr.THIS_WEEK)

    // ── Item Extraction ──

    private fun extractItemFilter(query: String): String? {
        for (pattern in ITEM_PATTERNS) {
            val match = pattern.find(query)
            if (match != null) {
                val item = match.groupValues[1]
                // Filter out common non-item words
                if (item !in setOf("saa", "siku", "wiki", "mwezi", "mwaka", "hii", "jana", "leo")) {
                    return item
                }
            }
        }
        return null
    }

    // ── Helpers ──

    private fun parseTransactionFilter(value: String): TransactionFilter? {
        return when (value.uppercase()) {
            "SALE" -> TransactionFilter.SALE
            "PURCHASE" -> TransactionFilter.PURCHASE
            "EXPENSE" -> TransactionFilter.EXPENSE
            "ALL_INCOME" -> TransactionFilter.ALL_INCOME
            "ALL_OUTFLOW" -> TransactionFilter.ALL_OUTFLOW
            "ALL" -> TransactionFilter.ALL
            else -> null
        }
    }

    private fun TransactionFilter.toTrendMetric(): TrendMetric {
        return when (this) {
            TransactionFilter.SALE, TransactionFilter.ALL_INCOME -> TrendMetric.SALES
            TransactionFilter.PURCHASE -> TrendMetric.PURCHASES
            TransactionFilter.EXPENSE, TransactionFilter.ALL_OUTFLOW -> TrendMetric.EXPENSES
            TransactionFilter.ALL -> TrendMetric.PROFIT
        }
    }
}

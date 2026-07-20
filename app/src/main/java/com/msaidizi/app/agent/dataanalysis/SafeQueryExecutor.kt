package com.msaidizi.app.data.natsql

import com.msaidizi.app.core.database.AppDatabase
import com.msaidizi.app.core.database.ItemRankingTuple
import com.msaidizi.app.core.database.DailyTotalTuple
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes [QueryIntent]s against Room DAOs — the safe alternative to raw SQL.
 *
 * This is the critical safety layer: the LLM never touches SQL directly.
 * Every intent maps to a pre-vetted, parameterized Room query.
 *
 * Results are returned as [QueryResult] objects that the [ResultSummarizer]
 * can convert to natural language.
 */
@Singleton
class SafeQueryExecutor @Inject constructor(
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "SafeQueryExecutor"
    }

    private val transactionDao get() = database.transactionDao()
    private val inventoryDao get() = database.inventoryDao()
    private val goalDao get() = database.goalDao()

    /**
     * Execute a parsed query intent and return structured results.
     */
    suspend fun execute(intent: QueryIntent): QueryResult {
        return withContext(Dispatchers.IO) {
            try {
                when (intent) {
                    is QueryIntent.GetTotal -> executeGetTotal(intent)
                    is QueryIntent.ListTransactions -> executeListTransactions(intent)
                    is QueryIntent.GetTopItems -> executeGetTopItems(intent)
                    is QueryIntent.GetBusinessSummary -> executeGetBusinessSummary(intent)
                    is QueryIntent.GetProfit -> executeGetProfit(intent)
                    is QueryIntent.CheckInventory -> executeCheckInventory(intent)
                    is QueryIntent.GetRestockAlerts -> executeGetRestockAlerts(intent)
                    is QueryIntent.GetGoalProgress -> executeGetGoalProgress(intent)
                    is QueryIntent.GetTrend -> executeGetTrend(intent)
                    is QueryIntent.Compare -> executeCompare(intent)
                    is QueryIntent.Unknown -> QueryResult.Error(
                        message = "Sielewi swali lako. Tafadhali jaribu tena kwa njia nyingine.",
                        originalQuery = intent.originalQuery
                    )
                }
            } catch (e: Throwable) {
                Timber.e(e, TAG, "Query execution failed")
                QueryResult.Error(
                    message = "Kuna tatizo la kiufundi. Tafadhali jaribu tena.",
                    originalQuery = intent.toString()
                )
            }
        }
    }

    // ── Total Queries ──

    private suspend fun executeGetTotal(intent: QueryIntent.GetTotal): QueryResult {
        val (start, end) = intent.timeRange
        val types = intent.transactionType.toTransactionTypes()

        var total = 0.0
        var count = 0

        for (type in types) {
            // Use the pre-built aggregate queries from TransactionDao
            val typeTotal = when (type) {
                TransactionType.SALE -> transactionDao.getSalesTotal(start, end)
                TransactionType.PURCHASE -> transactionDao.getPurchasesTotal(start, end)
                TransactionType.EXPENSE -> transactionDao.getExpensesTotal(start, end)
                else -> {
                    // For other types, get from range and sum
                    val txns = transactionDao.getTransactionsInRangeSuspend(start, end)
                        .filter { it.type == type }
                    txns.sumOf { it.totalAmount }
                }
            }
            total += typeTotal

            // Count transactions
            val txns = transactionDao.getTransactionsInRangeSuspend(start, end)
                .filter { it.type == type }
            count += txns.size
        }

        // Apply item filter if present
        if (intent.itemFilter != null) {
            val allTxns = transactionDao.getTransactionsInRangeSuspend(start, end)
                .filter { txn ->
                    txn.type in types &&
                    txn.item.contains(intent.itemFilter, ignoreCase = true)
                }
            total = allTxns.sumOf { it.totalAmount }
            count = allTxns.size
        }

        return QueryResult.TotalResult(
            amount = total,
            transactionCount = count,
            transactionType = intent.transactionType,
            timeRange = intent.timeRange,
            itemFilter = intent.itemFilter
        )
    }

    // ── List Transactions ──

    private suspend fun executeListTransactions(intent: QueryIntent.ListTransactions): QueryResult {
        val transactions = if (intent.timeRange != null) {
            transactionDao.getTransactionsInRangeSuspend(
                intent.timeRange.startEpochSec,
                intent.timeRange.endEpochSec
            )
        } else {
            transactionDao.getFirstPage(intent.limit)
        }

        val filtered = transactions.filter { txn ->
            val typeMatch = intent.transactionType?.let {
                txn.type in it.toTransactionTypes()
            } ?: true
            val itemMatch = intent.itemFilter?.let {
                txn.item.contains(it, ignoreCase = true)
            } ?: true
            typeMatch && itemMatch
        }.take(intent.limit)

        return QueryResult.TransactionListResult(
            transactions = filtered,
            totalCount = filtered.size,
            timeRange = intent.timeRange
        )
    }

    // ── Top Items ──

    private suspend fun executeGetTopItems(intent: QueryIntent.GetTopItems): QueryResult {
        val topItems = transactionDao.getTopSellingItems(
            startDate = intent.timeRange.startEpochSec,
            endDate = intent.timeRange.endEpochSec,
            limit = intent.limit
        )
        return QueryResult.TopItemsResult(
            items = topItems,
            timeRange = intent.timeRange
        )
    }

    // ── Business Summary ──

    private suspend fun executeGetBusinessSummary(intent: QueryIntent.GetBusinessSummary): QueryResult {
        val (start, end) = intent.timeRange
        val sales = transactionDao.getSalesTotal(start, end)
        val purchases = transactionDao.getPurchasesTotal(start, end)
        val expenses = transactionDao.getExpensesTotal(start, end)
        val profit = transactionDao.getProfit(start, end)
        val count = transactionDao.getTransactionCount(start, end)
        val topItems = transactionDao.getTopSellingItems(start, end, 3)

        return QueryResult.BusinessSummaryResult(
            totalSales = sales,
            totalPurchases = purchases,
            totalExpenses = expenses,
            profit = profit,
            transactionCount = count,
            topItems = topItems,
            timeRange = intent.timeRange
        )
    }

    // ── Profit ──

    private suspend fun executeGetProfit(intent: QueryIntent.GetProfit): QueryResult {
        val profit = transactionDao.getProfit(
            intent.timeRange.startEpochSec,
            intent.timeRange.endEpochSec
        )
        return QueryResult.ProfitResult(
            profit = profit,
            timeRange = intent.timeRange
        )
    }

    // ── Inventory ──

    private suspend fun executeCheckInventory(intent: QueryIntent.CheckInventory): QueryResult {
        val items = if (intent.itemFilter != null) {
            val item = inventoryDao.getItem(intent.itemFilter)
            listOfNotNull(item)
        } else {
            inventoryDao.getInStockItems()
        }
        return QueryResult.InventoryResult(items = items)
    }

    // ── Restock Alerts ──

    private suspend fun executeGetRestockAlerts(intent: QueryIntent.GetRestockAlerts): QueryResult {
        val alerts = inventoryDao.getItemsNeedingRestock()
        return QueryResult.RestockAlertResult(items = alerts)
    }

    // ── Goal Progress ──

    private suspend fun executeGetGoalProgress(intent: QueryIntent.GetGoalProgress): QueryResult {
        val activeGoals = goalDao.getActive()
        val totalSaved = goalDao.getTotalSaved() ?: 0.0
        val totalTarget = goalDao.getTotalTarget() ?: 0.0

        return QueryResult.GoalProgressResult(
            activeGoals = activeGoals.size,
            totalSaved = totalSaved,
            totalTarget = totalTarget
        )
    }

    // ── Trend ──

    private suspend fun executeGetTrend(intent: QueryIntent.GetTrend): QueryResult {
        val (start, end) = intent.timeRange

        // Split the time range into two halves for comparison
        val mid = start + (end - start) / 2
        val firstHalf = getMetricValue(intent.metric, start, mid)
        val secondHalf = getMetricValue(intent.metric, mid, end)

        val trend = when {
            secondHalf > firstHalf * 1.1 -> TrendDirection.UP
            secondHalf < firstHalf * 0.9 -> TrendDirection.DOWN
            else -> TrendDirection.STABLE
        }

        return QueryResult.TrendResult(
            metric = intent.metric,
            firstPeriodValue = firstHalf,
            secondPeriodValue = secondHalf,
            direction = trend,
            timeRange = intent.timeRange
        )
    }

    // ── Compare ──

    private suspend fun executeCompare(intent: QueryIntent.Compare): QueryResult {
        val period1Value = getMetricValue(
            intent.metric,
            intent.period1.startEpochSec,
            intent.period1.endEpochSec
        )
        val period2Value = getMetricValue(
            intent.metric,
            intent.period2.startEpochSec,
            intent.period2.endEpochSec
        )

        val change = if (period2Value != 0.0) {
            ((period1Value - period2Value) / period2Value * 100)
        } else 0.0

        return QueryResult.CompareResult(
            metric = intent.metric,
            period1Label = intent.period1.label,
            period1Value = period1Value,
            period2Label = intent.period2.label,
            period2Value = period2Value,
            percentChange = change
        )
    }

    // ── Helpers ──

    private suspend fun getMetricValue(
        metric: TrendMetric,
        start: Long,
        end: Long
    ): Double {
        return when (metric) {
            TrendMetric.SALES -> transactionDao.getSalesTotal(start, end)
            TrendMetric.EXPENSES -> transactionDao.getExpensesTotal(start, end)
            TrendMetric.PURCHASES -> transactionDao.getPurchasesTotal(start, end)
            TrendMetric.PROFIT -> transactionDao.getProfit(start, end)
            TrendMetric.TRANSACTION_COUNT -> transactionDao.getTransactionCount(start, end).toDouble()
        }
    }

    private fun TransactionFilter.toTransactionTypes(): List<TransactionType> {
        return when (this) {
            TransactionFilter.SALE -> listOf(TransactionType.SALE)
            TransactionFilter.PURCHASE -> listOf(TransactionType.PURCHASE)
            TransactionFilter.EXPENSE -> listOf(TransactionType.EXPENSE)
            TransactionFilter.ALL_INCOME -> listOf(TransactionType.SALE, TransactionType.DEPOSIT)
            TransactionFilter.ALL_OUTFLOW -> listOf(
                TransactionType.PURCHASE,
                TransactionType.EXPENSE,
                TransactionType.WITHDRAWAL
            )
            TransactionFilter.ALL -> TransactionType.entries.toList()
        }
    }
}

// ── Result Types ──

/**
 * Structured query results that can be summarized into natural language.
 */
sealed class QueryResult {

    /** Total amount result (e.g., "You spent 2,500 KSh on supplies") */
    data class TotalResult(
        val amount: Double,
        val transactionCount: Int,
        val transactionType: TransactionFilter,
        val timeRange: QueryIntent.TimeRange,
        val itemFilter: String? = null
    ) : QueryResult()

    /** List of transactions */
    data class TransactionListResult(
        val transactions: List<Transaction>,
        val totalCount: Int,
        val timeRange: QueryIntent.TimeRange?
    ) : QueryResult()

    /** Top selling items */
    data class TopItemsResult(
        val items: List<ItemRankingTuple>,
        val timeRange: QueryIntent.TimeRange
    ) : QueryResult()

    /** Full business summary */
    data class BusinessSummaryResult(
        val totalSales: Double,
        val totalPurchases: Double,
        val totalExpenses: Double,
        val profit: Double,
        val transactionCount: Int,
        val topItems: List<ItemRankingTuple>,
        val timeRange: QueryIntent.TimeRange
    ) : QueryResult()

    /** Profit result */
    data class ProfitResult(
        val profit: Double,
        val timeRange: QueryIntent.TimeRange
    ) : QueryResult()

    /** Inventory check */
    data class InventoryResult(
        val items: List<com.msaidizi.app.core.model.InventoryItem>
    ) : QueryResult()

    /** Restock alerts */
    data class RestockAlertResult(
        val items: List<com.msaidizi.app.core.model.InventoryItem>
    ) : QueryResult()

    /** Goal progress */
    data class GoalProgressResult(
        val activeGoals: Int,
        val totalSaved: Double,
        val totalTarget: Double
    ) : QueryResult()

    /** Trend analysis */
    data class TrendResult(
        val metric: TrendMetric,
        val firstPeriodValue: Double,
        val secondPeriodValue: Double,
        val direction: TrendDirection,
        val timeRange: QueryIntent.TimeRange
    ) : QueryResult()

    /** Period comparison */
    data class CompareResult(
        val metric: TrendMetric,
        val period1Label: String,
        val period1Value: Double,
        val period2Label: String,
        val period2Value: Double,
        val percentChange: Double
    ) : QueryResult()

    /** Error result */
    data class Error(
        val message: String,
        val originalQuery: String = ""
    ) : QueryResult()
}

enum class TrendDirection {
    UP, DOWN, STABLE
}

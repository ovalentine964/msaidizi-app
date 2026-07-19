package com.msaidizi.app.data.natsql

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Converts [QueryResult]s into natural language summaries optimized for voice output.
 *
 * Design constraints for voice-first UX:
 * - Short sentences (TTS-friendly, <150 chars per sentence)
 * - Swahili-first with English fallback
 * - Numbers spoken naturally ("elfu mbili" not "2,000")
 * - No tables, no bullet points — pure spoken language
 * - Handles Sheng/Swahili code-switching naturally
 *
 * Uses template-based generation (no LLM needed) for speed and reliability.
 * The LLM is only used for complex summaries that don't fit templates.
 */
@Singleton
class ResultSummarizer @Inject constructor() {

    companion object {
        private const val TAG = "ResultSummarizer"
    }

    /**
     * Summarize a query result into spoken natural language.
     *
     * @param result The structured query result
     * @param language Response language: "sw" (Swahili), "en" (English), "sheng"
     * @return Natural language summary suitable for TTS
     */
    fun summarize(result: QueryResult, language: String = "sw"): String {
        return when (result) {
            is QueryResult.TotalResult -> summarizeTotal(result, language)
            is QueryResult.TransactionListResult -> summarizeTransactionList(result, language)
            is QueryResult.TopItemsResult -> summarizeTopItems(result, language)
            is QueryResult.BusinessSummaryResult -> summarizeBusinessSummary(result, language)
            is QueryResult.ProfitResult -> summarizeProfit(result, language)
            is QueryResult.InventoryResult -> summarizeInventory(result, language)
            is QueryResult.RestockAlertResult -> summarizeRestockAlerts(result, language)
            is QueryResult.GoalProgressResult -> summarizeGoalProgress(result, language)
            is QueryResult.TrendResult -> summarizeTrend(result, language)
            is QueryResult.CompareResult -> summarizeCompare(result, language)
            is QueryResult.Error -> result.message
        }
    }

    // ── Total ──

    private fun summarizeTotal(result: QueryResult.TotalResult, lang: String): String {
        val amount = formatMoney(result.amount)
        val timeLabel = result.timeRange.label.ifBlank { "kipindi hicho" }
        val typeWord = when (result.transactionType) {
            TransactionFilter.SALE -> when (lang) {
                "sheng" -> "umepata"
                "en" -> "you earned"
                else -> "umepata"
            }
            TransactionFilter.PURCHASE -> when (lang) {
                "sheng" -> "umenunua"
                "en" -> "you spent on purchases"
                else -> "umetumia kununua"
            }
            TransactionFilter.EXPENSE -> when (lang) {
                "sheng" -> "uliaga"
                "en" -> "you spent"
                else -> "umetumia"
            }
            TransactionFilter.ALL_INCOME -> when (lang) {
                "sheng" -> "umepata jumla"
                "en" -> "you earned in total"
                else -> "umepata jumla"
            }
            TransactionFilter.ALL_OUTFLOW -> when (lang) {
                "sheng" -> "umetumia jumla"
                "en" -> "you spent in total"
                else -> "umetumia jumla"
            }
            TransactionFilter.ALL -> when (lang) {
                "sheng" -> "jumla ni"
                "en" -> "the total is"
                else -> "jumla ni"
            }
        }

        val itemPart = result.itemFilter?.let {
            when (lang) {
                "sheng" -> " kwa $it"
                "en" -> " on $it"
                else -> " kwa $it"
            }
        } ?: ""

        return when (lang) {
            "sheng" -> "$typeWord $amount$itemPart $timeLabel. Shughuli ${result.transactionCount}."
            "en" -> "$typeWord $amount$itemPart $timeLabel. ${result.transactionCount} transactions."
            else -> "$typeWord $amount$itemPart $timeLabel. Shughuli ${result.transactionCount}."
        }
    }

    // ── Transaction List ──

    private fun summarizeTransactionList(result: QueryResult.TransactionListResult, lang: String): String {
        if (result.transactions.isEmpty()) {
            return when (lang) {
                "sheng" -> "Hakuna shughuli kwa kipindi hicho."
                "en" -> "No transactions found for that period."
                else -> "Hakuna shughuli kwa kipindi hicho."
            }
        }

        val sb = StringBuilder()
        val timeLabel = result.timeRange?.label ?: ""

        if (timeLabel.isNotBlank()) {
            sb.append(when (lang) {
                "sheng" -> "Shughuli za $timeLabel: "
                "en" -> "Transactions for $timeLabel: "
                else -> "Shughuli za $timeLabel: "
            })
        }

        // Summarize first few transactions in natural language
        val toShow = result.transactions.take(5)
        for ((i, txn) in toShow.withIndex()) {
            val typeVerb = when (txn.type) {
                TransactionType.SALE -> when (lang) {
                    "sheng" -> "Uliuza"
                    else -> "Uliuza"
                }
                TransactionType.PURCHASE -> when (lang) {
                    "sheng" -> "Ulinunua"
                    else -> "Ulinunua"
                }
                TransactionType.EXPENSE -> when (lang) {
                    "sheng" -> "Ulitumia"
                    else -> "Ulitumia"
                }
                else -> when (lang) {
                    "sheng" -> "Shughuli"
                    else -> "Shughuli"
                }
            }
            val amount = formatMoney(txn.totalAmount)
            sb.append("$typeVerb ${txn.item} kwa $amount")

            if (i < toShow.size - 1) sb.append(". ")
            else sb.append(". ")
        }

        if (result.transactions.size > 5) {
            val remaining = result.totalCount - 5
            sb.append(when (lang) {
                "sheng" -> "Na shughuli $remaining zingine."
                "en" -> "And $remaining more transactions."
                else -> "Na shughuli $remaining zingine."
            })
        }

        return sb.toString()
    }

    // ── Top Items ──

    private fun summarizeTopItems(result: QueryResult.TopItemsResult, lang: String): String {
        if (result.items.isEmpty()) {
            return when (lang) {
                "sheng" -> "Hakuna mauzo kwa kipindi hicho."
                "en" -> "No sales data for that period."
                else -> "Hakuna mauzo kwa kipindi hicho."
            }
        }

        val sb = StringBuilder()
        sb.append(when (lang) {
            "sheng" -> "Vitu unavyouza zaidi: "
            "en" -> "Your top selling items: "
            else -> "Vitu unavyouza zaidi: "
        })

        for ((i, item) in result.items.withIndex()) {
            val rank = i + 1
            val amount = formatMoney(item.totalRev)
            sb.append(when (lang) {
                "sheng" -> "$rank. ${item.item} — $amount"
                "en" -> "$rank. ${item.item} — $amount"
                else -> "$rank. ${item.item} — $amount"
            })
            if (i < result.items.size - 1) sb.append(", ")
        }

        sb.append(".")
        return sb.toString()
    }

    // ── Business Summary ──

    private fun summarizeBusinessSummary(result: QueryResult.BusinessSummaryResult, lang: String): String {
        val sales = formatMoney(result.totalSales)
        val expenses = formatMoney(result.totalExpenses + result.totalPurchases)
        val profit = formatMoney(result.profit)
        val timeLabel = result.timeRange.label.ifBlank { "kipindi hicho" }

        val profitWord = if (result.profit >= 0) {
            when (lang) {
                "sheng" -> "Faida yako ni $profit"
                "en" -> "Your profit is $profit"
                else -> "Faida yako ni $profit"
            }
        } else {
            when (lang) {
                "sheng" -> "Umepoteza ${formatMoney(abs(result.profit))}"
                "en" -> "You lost ${formatMoney(abs(result.profit))}"
                else -> "Umepoteza ${formatMoney(abs(result.profit))}"
            }
        }

        val sb = StringBuilder()
        sb.append(when (lang) {
            "sheng" -> "Biashara $timeLabel: "
            "en" -> "Business $timeLabel: "
            else -> "Biashara $timeLabel: "
        })
        sb.append("Umeuza kwa $sales. ")
        sb.append("Umetumia $expenses. ")
        sb.append("$profitWord. ")
        sb.append("Shughuli ${result.transactionCount}.")

        // Add top item if available
        if (result.topItems.isNotEmpty()) {
            val top = result.topItems[0]
            sb.append(when (lang) {
                "sheng" -> " Kitu bora ni ${top.item}."
                "en" else -> " Best item is ${top.item}."
                else -> " Kitu bora ni ${top.item}."
            })
        }

        return sb.toString()
    }

    // ── Profit ──

    private fun summarizeProfit(result: QueryResult.ProfitResult, lang: String): String {
        val profit = formatMoney(result.profit)
        val timeLabel = result.timeRange.label.ifBlank { "kipindi hicho" }

        return if (result.profit >= 0) {
            when (lang) {
                "sheng" -> "Faida yako $timeLabel ni $profit."
                "en" -> "Your profit for $timeLabel is $profit."
                else -> "Faida yako $timeLabel ni $profit."
            }
        } else {
            when (lang) {
                "sheng" -> "Umepoteza ${formatMoney(abs(result.profit))} $timeLabel."
                "en" -> "You lost ${formatMoney(abs(result.profit))} for $timeLabel."
                else -> "Umepoteza ${formatMoney(abs(result.profit))} $timeLabel."
            }
        }
    }

    // ── Inventory ──

    private fun summarizeInventory(result: QueryResult.InventoryResult, lang: String): String {
        if (result.items.isEmpty()) {
            return when (lang) {
                "sheng" -> "Hakuna vitu kwenye hifadhi."
                "en" -> "No items in inventory."
                else -> "Hakuna vitu kwenye hifadhi."
            }
        }

        val sb = StringBuilder()

        if (result.items.size == 1) {
            val item = result.items[0]
            sb.append(when (lang) {
                "sheng" -> "${item.item} — imebaki ${formatQuantity(item.currentStock, item.unit)}."
                "en" -> "${item.item} — ${formatQuantity(item.currentStock, item.unit)} remaining."
                else -> "${item.item} — imebaki ${formatQuantity(item.currentStock, item.unit)}."
            })
        } else {
            sb.append(when (lang) {
                "sheng" -> "Hifadhi yako: "
                "en" -> "Your inventory: "
                else -> "Hifadhi yako: "
            })
            for ((i, item) in result.items.take(5).withIndex()) {
                sb.append("${item.item} ${formatQuantity(item.currentStock, item.unit)}")
                if (i < result.items.take(5).size - 1) sb.append(", ")
            }
            if (result.items.size > 5) {
                sb.append(when (lang) {
                    "sheng" -> " na vingine ${result.items.size - 5}."
                    "en" -> " and ${result.items.size - 5} more."
                    else -> " na vingine ${result.items.size - 5}."
                })
            }
            sb.append(".")
        }

        return sb.toString()
    }

    // ── Restock Alerts ──

    private fun summarizeRestockAlerts(result: QueryResult.RestockAlertResult, lang: String): String {
        if (result.items.isEmpty()) {
            return when (lang) {
                "sheng" -> "Stock yako iko sawa! Hakuna kitu kinachohitaji kuagizwa."
                "en" -> "Your stock is fine! No items need restocking."
                else -> "Stock yako iko sawa! Hakuna kitu kinachohitaji kuagizwa."
            }
        }

        val sb = StringBuilder()
        sb.append(when (lang) {
            "sheng" -> "Vitu vinahitaji kuagizwa: "
            "en" -> "Items that need restocking: "
            else -> "Vitu vinahitaji kuagizwa: "
        })

        for ((i, item) in result.items.take(5).withIndex()) {
            sb.append("${item.item} (imebaki ${formatQuantity(item.currentStock, item.unit)})")
            if (i < result.items.take(5).size - 1) sb.append(", ")
        }
        sb.append(".")
        return sb.toString()
    }

    // ── Goal Progress ──

    private fun summarizeGoalProgress(result: QueryResult.GoalProgressResult, lang: String): String {
        if (result.activeGoals == 0) {
            return when (lang) {
                "sheng" -> "Huna malengo bado. Anza kuweka lengo la akiba!"
                "en" -> "You don't have any goals yet. Start saving!"
                else -> "Huna malengo bado. Anza kuweka lengo la akiba!"
            }
        }

        val saved = formatMoney(result.totalSaved)
        val target = formatMoney(result.totalTarget)
        val percent = if (result.totalTarget > 0) {
            (result.totalSaved / result.totalTarget * 100).toInt()
        } else 0

        return when (lang) {
            "sheng" -> "Malengo ${result.activeGoals}: Umeokoa $saved kati ya $target. Umefikia $percent%."
            "en" -> "${result.activeGoals} active goals: Saved $saved of $target. $percent% complete."
            else -> "Malengo ${result.activeGoals}: Umeokoa $saved kati ya $target. Umefikia $percent%."
        }
    }

    // ── Trend ──

    private fun summarizeTrend(result: QueryResult.TrendResult, lang: String): String {
        val metricName = when (result.metric) {
            TrendMetric.SALES -> when (lang) { "sheng" -> "Mauzo"; "en" -> "Sales"; else -> "Mauzo" }
            TrendMetric.EXPENSES -> when (lang) { "sheng" -> "Matumizi"; "en" -> "Expenses"; else -> "Matumizi" }
            TrendMetric.PURCHASES -> when (lang) { "sheng" -> "Manunuzi"; "en" -> "Purchases"; else -> "Manunuzi" }
            TrendMetric.PROFIT -> when (lang) { "sheng" -> "Faida"; "en" -> "Profit"; else -> "Faida" }
            TrendMetric.TRANSACTION_COUNT -> when (lang) { "sheng" -> "Shughuli"; "en" -> "Transactions"; else -> "Shughuli" }
        }

        val direction = when (result.direction) {
            TrendDirection.UP -> when (lang) {
                "sheng" -> "inapanda! Nzuri sana!"
                "en" -> "is going up! Great!"
                else -> "inapanda! Nzuri sana!"
            }
            TrendDirection.DOWN -> when (lang) {
                "sheng" -> "inashuka. Jaribu kuboresha."
                "en" -> "is going down. Try to improve."
                else -> "inashuka. Jaribu kuboresha."
            }
            TrendDirection.STABLE -> when (lang) {
                "sheng" -> "imara. Sio mbaya."
                "en" -> "stable. Not bad."
                else -> "imara. Sio mbaya."
            }
        }

        return "$metricName $direction"
    }

    // ── Compare ──

    private fun summarizeCompare(result: QueryResult.CompareResult, lang: String): String {
        val metricName = when (result.metric) {
            TrendMetric.SALES -> when (lang) { "sheng" -> "Mauzo"; "en" -> "Sales"; else -> "Mauzo" }
            TrendMetric.EXPENSES -> when (lang) { "sheng" -> "Matumizi"; "en" -> "Expenses"; else -> "Matumizi" }
            TrendMetric.PURCHASES -> when (lang) { "sheng" -> "Manunuzi"; "en" -> "Purchases"; else -> "Manunuzi" }
            TrendMetric.PROFIT -> when (lang) { "sheng" -> "Faida"; "en" -> "Profit"; else -> "Faida" }
            TrendMetric.TRANSACTION_COUNT -> when (lang) { "sheng" -> "Shughuli"; "en" -> "Transactions"; else -> "Shughuli" }
        }

        val p1 = formatMoney(result.period1Value)
        val p2 = formatMoney(result.period2Value)
        val changeAbs = formatMoney(abs(result.percentChange))
        val changeDir = if (result.percentChange >= 0) {
            when (lang) { "sheng" -> "zaidi"; "en" -> "more"; else -> "zaidi" }
        } else {
            when (lang) { "sheng" -> "pungufu"; "en" -> "less"; else -> "pungufu" }
        }

        return when (lang) {
            "sheng" -> "$metricName ${result.period1Label}: $p1, ${result.period2Label}: $p2. ${changeAbs}% $changeDir."
            "en" -> "$metricName ${result.period1Label}: $p1, ${result.period2Label}: $p2. ${changeAbs}% $changeDir."
            else -> "$metricName ${result.period1Label}: $p1, ${result.period2Label}: $p2. ${changeAbs}% $changeDir."
        }
    }

    // ── Formatting Helpers ──

    /**
     * Format money for spoken output.
     * "2500" → "elfu mbili na mia tano" (Swahili) or "2,500 shillings" (English)
     * For simplicity, uses numeric format with "KSh" suffix.
     */
    private fun formatMoney(amount: Double): String {
        val rounded = "%.0f".format(abs(amount))
        val formatted = rounded.reversed().chunked(3).joinToString(",").reversed()
        return "$formatted KSh"
    }

    /**
     * Format quantity with unit.
     * "5.0 pieces" → "vipande 5" (Swahili)
     */
    private fun formatQuantity(qty: Double, unit: String): String {
        val intQty = if (qty == qty.toLong().toDouble()) qty.toLong().toString() else "%.1f".format(qty)
        return when (unit.lowercase()) {
            "pieces", "piece" -> "vipande $intQty"
            "kg", "kilogram" -> "kilo $intQty"
            "liters", "liter", "l" -> "lita $intQty"
            "packs", "pack" -> "pakiti $intQty"
            else -> "$intQty $unit"
        }
    }
}

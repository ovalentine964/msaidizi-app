package com.msaidizi.app.agent

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.*
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset


/**
 * Business Agent — records transactions, tracks inventory, manages business state.
 * Pure code + SQLite — 0 LLM overhead for core operations.
 *
 * Responsibilities:
 * - Record sales, purchases, expenses
 * - Update inventory levels
 * - Calculate profit, cash flow, balance
 * - Generate restock alerts
 */
class BusinessAgent(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) {
    // === RECORD TRANSACTIONS ===

    /**
     * Record a sale transaction.
     * Automatically updates inventory and checks restock thresholds.
     */
    suspend fun recordSale(
        item: String,
        quantity: Double,
        amount: Double,
        language: String = "sw",
        confidence: Float = 1.0f
    ): Transaction {
        val unitPrice = if (quantity > 0) amount / quantity else amount
        val costBasis = inventoryDao.getAverageCost(item) * quantity

        val transaction = Transaction(
            type = TransactionType.SALE,
            item = item,
            category = classifyItem(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = amount,
            costBasis = costBasis,
            language = language,
            confidence = confidence
        )

        val id = transactionDao.insert(transaction)

        // Update inventory
        inventoryDao.decrementStock(item, quantity)

        // Check restock threshold
        checkRestockAlert(item)

        Timber.d("Recorded sale: %s x%.0f = KSh %.0f (id=%d)", item, quantity, amount, id)
        return transaction.copy(id = id)
    }

    /**
     * Record a purchase transaction.
     * Updates inventory and rolling average cost.
     */
    suspend fun recordPurchase(
        item: String,
        quantity: Double,
        amount: Double,
        language: String = "sw",
        confidence: Float = 1.0f
    ): Transaction {
        val unitPrice = if (quantity > 0) amount / quantity else amount

        val transaction = Transaction(
            type = TransactionType.PURCHASE,
            item = item,
            category = classifyItem(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = amount,
            language = language,
            confidence = confidence
        )

        val id = transactionDao.insert(transaction)

        // Update inventory with new rolling average cost
        val existingItem = inventoryDao.getItem(item)
        val newAvgCost = if (existingItem != null && existingItem.currentStock > 0) {
            // Weighted average: (old_stock * old_avg + new_qty * new_price) / total
            val totalCost = (existingItem.currentStock * existingItem.avgCost) + amount
            val totalQty = existingItem.currentStock + quantity
            if (totalQty > 0) totalCost / totalQty else unitPrice
        } else {
            unitPrice
        }

        if (existingItem != null) {
            inventoryDao.incrementStock(item, quantity, newAvgCost)
        } else {
            // Create new inventory item
            inventoryDao.upsert(InventoryItem(
                item = item,
                category = classifyItem(item),
                currentStock = quantity,
                avgCost = unitPrice
            ))
        }

        Timber.d("Recorded purchase: %s x%.0f = KSh %.0f (id=%d)", item, quantity, amount, id)
        return transaction.copy(id = id)
    }

    /**
     * Record an expense transaction.
     */
    suspend fun recordExpense(
        category: String,
        amount: Double,
        notes: String = "",
        language: String = "sw"
    ): Transaction {
        val transaction = Transaction(
            type = TransactionType.EXPENSE,
            item = category,
            category = category,
            totalAmount = amount,
            notes = notes,
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded expense: %s = KSh %.0f (id=%d)", category, amount, id)
        return transaction.copy(id = id)
    }

    // === QUERIES ===

    /**
     * Get today's profit (sales - purchases - expenses).
     */
    suspend fun getDailyProfit(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getProfit(startOfDay, endOfDay)
    }

    /**
     * Get today's sales total.
     */
    suspend fun getDailySales(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getSalesTotal(startOfDay, endOfDay)
    }

    /**
     * Get today's purchases total.
     */
    suspend fun getDailyPurchases(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getPurchasesTotal(startOfDay, endOfDay)
    }

    /**
     * Get cash flow for a period.
     */
    suspend fun getCashFlow(days: Int = 7): CashFlow {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())

        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startEpoch, endEpoch)
        val purchases = transactionDao.getPurchasesTotal(startEpoch, endEpoch)
        val expenses = transactionDao.getExpensesTotal(startEpoch, endEpoch)

        return CashFlow(
            inflow = sales,
            outflow = purchases + expenses,
            net = sales - purchases - expenses,
            period = "${startDate} to ${endDate}"
        )
    }

    /**
     * Get current balance (total sales - total purchases - total expenses).
     */
    suspend fun getBalance(): Double {
        val now = System.currentTimeMillis() / 1000
        return transactionDao.getProfit(0, now)
    }

    /**
     * Get transaction count for today.
     */
    suspend fun getDailyTransactionCount(date: LocalDate = LocalDate.now()): Int {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getTransactionCount(startOfDay, endOfDay)
    }

    /**
     * Get top selling items.
     */
    suspend fun getTopSellingItems(days: Int = 7, limit: Int = 5): List<ItemRanking> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        return transactionDao.getTopSellingItems(startEpoch, endEpoch, limit).map { tuple ->
            ItemRanking(
                item = tuple.item,
                totalQuantity = tuple.totalQty,
                totalRevenue = tuple.totalRev,
                transactionCount = tuple.txCount
            )
        }
    }

    /**
     * Get items needing restock.
     */
    suspend fun getRestockAlerts(): List<RestockAlert> {
        return inventoryDao.getItemsNeedingRestock().map { item ->
            RestockAlert(
                item = item.item,
                currentStock = item.currentStock,
                threshold = item.restockThreshold,
                avgCost = item.avgCost,
                daysUntilStockout = calculateDaysUntilStockout(item)
            )
        }
    }

    /**
     * Generate daily summary.
     */
    suspend fun generateDailySummary(date: LocalDate = LocalDate.now()): DailySummary {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startOfDay, endOfDay)
        val purchases = transactionDao.getPurchasesTotal(startOfDay, endOfDay)
        val expenses = transactionDao.getExpensesTotal(startOfDay, endOfDay)
        val profit = sales - purchases - expenses
        val count = transactionDao.getTransactionCount(startOfDay, endOfDay)
        val topItems = transactionDao.getTopSellingItems(startOfDay, endOfDay, 5)

        val summary = DailySummary(
            date = date.toString(),
            totalSales = sales,
            totalPurchases = purchases,
            totalExpenses = expenses,
            profit = profit,
            topItems = topItems.joinToString(",") { "${it.item}:${it.totalRev}" },
            transactionCount = count
        )

        return summary
    }

    // === HELPERS ===

    /**
     * Check if an item needs restocking and log alert.
     */
    private suspend fun checkRestockAlert(item: String) {
        val inventoryItem = inventoryDao.getItem(item) ?: return
        if (inventoryItem.currentStock <= inventoryItem.restockThreshold) {
            Timber.w("RESTOCK ALERT: %s has %.0f remaining (threshold: %.0f)",
                item, inventoryItem.currentStock, inventoryItem.restockThreshold)
        }
    }

    /**
     * Calculate days until stockout based on sales velocity.
     */
    private suspend fun calculateDaysUntilStockout(item: InventoryItem): Int {
        if (item.currentStock <= 0) return 0

        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(7)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item.item, startEpoch)
        if (salesHistory.isEmpty()) return -1  // Can't predict

        val totalSold = salesHistory.sumOf { it.quantity }
        val dailyRate = totalSold / 7.0

        return if (dailyRate > 0) {
            (item.currentStock / dailyRate).toInt()
        } else {
            -1
        }
    }

    /**
     * Auto-classify an item into a category.
     * Rule-based, no LLM needed.
     */
    private fun classifyItem(item: String): String {
        val lower = item.lowercase()
        return when {
            lower.contains("nyanya") || lower.contains("viazi") ||
            lower.contains("vitunguu") || lower.contains("karoti") ||
            lower.contains("sukuma") || lower.contains("mboga") -> "produce"

            lower.contains("unga") || lower.contains("mchele") ||
            lower.contains("mahindi") || lower.contains("maharagwe") ||
            lower.contains("dengu") -> "grains"

            lower.contains("nyama") || lower.contains("kuku") ||
            lower.contains("samaki") || lower.contains("mayai") -> "protein"

            lower.contains("mafuta") || lower.contains("sukari") ||
            lower.contains("chumvi") || lower.contains("chai") ||
            lower.contains("maziwa") -> "cooking"

            lower.contains("mandazi") || lower.contains("chapati") ||
            lower.contains("mkate") || lower.contains("ugali") -> "prepared_food"

            lower.contains("sabuni") || lower.contains("dawa") ||
            lower.contains("pampers") || lower.contains("mshumaa") -> "household"

            lower.contains("usafiri") || lower.contains("rent") ||
            lower.contains("kodi") || lower.contains("stima") ||
            lower.contains("umeme") || lower.contains("data") -> "expense"

            else -> "other"
        }
    }
}

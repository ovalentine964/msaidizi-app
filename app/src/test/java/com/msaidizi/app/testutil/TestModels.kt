package com.msaidizi.app.testutil

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Test data factories for unit tests.
 * Provides realistic Kenyan business transaction samples.
 */
object TestModels {

    // ── Transaction Factories ──

    fun sale(
        item: String = "mandazi",
        quantity: Double = 10.0,
        unitPrice: Double = 20.0,
        totalAmount: Double = quantity * unitPrice,
        costBasis: Double = totalAmount * 0.6,
        createdAt: Long = System.currentTimeMillis() / 1000,
        id: Long = 0
    ) = Transaction(
        id = id,
        type = TransactionType.SALE,
        item = item,
        quantity = quantity,
        unitPrice = unitPrice,
        totalAmount = totalAmount,
        costBasis = costBasis,
        createdAt = createdAt,
        language = "sw"
    )

    fun purchase(
        item: String = "unga",
        quantity: Double = 5.0,
        unitPrice: Double = 100.0,
        totalAmount: Double = quantity * unitPrice,
        createdAt: Long = System.currentTimeMillis() / 1000,
        id: Long = 0
    ) = Transaction(
        id = id,
        type = TransactionType.PURCHASE,
        item = item,
        quantity = quantity,
        unitPrice = unitPrice,
        totalAmount = totalAmount,
        costBasis = totalAmount,
        createdAt = createdAt,
        language = "sw"
    )

    fun expense(
        category: String = "usafiri",
        amount: Double = 200.0,
        createdAt: Long = System.currentTimeMillis() / 1000,
        id: Long = 0
    ) = Transaction(
        id = id,
        type = TransactionType.EXPENSE,
        item = category,
        quantity = 1.0,
        unitPrice = amount,
        totalAmount = amount,
        createdAt = createdAt,
        language = "sw"
    )

    /**
     * Generate a series of daily transactions for cash flow testing.
     * Returns transactions spanning [daysCount] days ending today.
     */
    fun dailyTransactionSeries(
        daysCount: Int = 14,
        dailySales: Double = 5000.0,
        dailyExpenses: Double = 2000.0,
        startDate: LocalDate = LocalDate.now().minusDays(daysCount.toLong())
    ): List<Transaction> {
        val result = mutableListOf<Transaction>()
        for (i in 0 until daysCount) {
            val date = startDate.plusDays(i.toLong())
            val epoch = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

            // Morning sale
            result.add(sale(
                item = "mandazi",
                quantity = 10.0,
                totalAmount = dailySales * 0.6,
                createdAt = epoch + 8 * 3600
            ))
            // Afternoon sale
            result.add(sale(
                item = "chai",
                quantity = 20.0,
                totalAmount = dailySales * 0.4,
                createdAt = epoch + 14 * 3600
            ))
            // Daily expense
            result.add(expense(
                category = "usafiri",
                amount = dailyExpenses,
                createdAt = epoch + 12 * 3600
            ))
        }
        return result
    }

    /**
     * Generate a trending series (increasing or decreasing sales).
     */
    fun trendingTransactionSeries(
        daysCount: Int = 14,
        startDailySales: Double = 3000.0,
        dailyGrowth: Double = 200.0,
        dailyExpenses: Double = 1500.0,
        startDate: LocalDate = LocalDate.now().minusDays(daysCount.toLong())
    ): List<Transaction> {
        val result = mutableListOf<Transaction>()
        for (i in 0 until daysCount) {
            val date = startDate.plusDays(i.toLong())
            val epoch = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val daySales = startDailySales + (i * dailyGrowth)

            result.add(sale(
                item = "bidhaa",
                quantity = 1.0,
                totalAmount = daySales,
                createdAt = epoch + 10 * 3600
            ))
            result.add(expense(
                category = "gharama",
                amount = dailyExpenses,
                createdAt = epoch + 12 * 3600
            ))
        }
        return result
    }

    // ── Common test values ──

    val KES_100 = 100.0
    val KES_500 = 500.0
    val KES_1000 = 1000.0
    val KES_5000 = 5000.0
    val KES_10000 = 10000.0
    val KES_100000 = 100_000.0
}

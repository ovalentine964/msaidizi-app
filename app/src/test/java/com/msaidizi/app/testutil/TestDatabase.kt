package com.msaidizi.app.testutil

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import io.mockk.coEvery
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Test helpers for creating mock DAOs with realistic data.
 *
 * Usage:
 * ```
 * val dao = TestDatabase.transactionDaoWithData(
 *     TestModels.dailyTransactionSeries(daysCount = 14)
 * )
 * ```
 */
object TestDatabase {

    /**
     * Create a mock TransactionDao pre-loaded with transactions.
     * The mock responds to getTransactionsInRangeSuspend with transactions
     * whose createdAt falls within the requested range.
     */
    fun transactionDaoWithData(transactions: List<Transaction>): TransactionDao {
        val dao = mockk<TransactionDao>()

        coEvery {
            dao.getTransactionsInRangeSuspend(any(), any())
        } answers {
            val start = arg<Long>(0)
            val end = arg<Long>(1)
            transactions.filter { it.createdAt in start until end }
        }

        // Default aggregate queries
        coEvery {
            dao.getSalesTotal(any(), any())
        } answers {
            val start = arg<Long>(0)
            val end = arg<Long>(1)
            transactions
                .filter { it.type == TransactionType.SALE && it.createdAt in start until end }
                .sumOf { it.totalAmount }
        }

        return dao
    }

    /**
     * Create an empty mock TransactionDao.
     */
    fun emptyTransactionDao(): TransactionDao {
        val dao = mockk<TransactionDao>()
        coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns emptyList()
        coEvery { dao.getSalesTotal(any(), any()) } returns 0.0
        return dao
    }

    /**
     * Get epoch seconds for start of a given date (UTC).
     */
    fun epochForDate(date: LocalDate): Long {
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }

    /**
     * Get epoch seconds for now.
     */
    fun epochNow(): Long = System.currentTimeMillis() / 1000
}

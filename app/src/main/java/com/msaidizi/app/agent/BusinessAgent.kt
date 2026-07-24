package com.msaidizi.app.agent

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType

/**
 * Stub: Business agent for transaction recording and queries.
 */
class BusinessAgent(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao? = null
) {
    suspend fun recordSale(item: String, quantity: Double, totalAmount: Double, costBasis: Double = 0.0, language: String = "sw"): Transaction {
        val tx = Transaction(item = item, quantity = quantity, totalAmount = totalAmount, unitPrice = if (quantity > 0) totalAmount / quantity else 0.0, type = TransactionType.SALE, costBasis = costBasis)
        transactionDao.insertTransaction(tx)
        return tx
    }

    suspend fun recordPurchase(item: String, quantity: Double, totalAmount: Double, costBasis: Double = 0.0, language: String = "sw"): Transaction {
        val tx = Transaction(item = item, quantity = quantity, totalAmount = totalAmount, unitPrice = if (quantity > 0) totalAmount / quantity else 0.0, type = TransactionType.PURCHASE, costBasis = costBasis)
        transactionDao.insertTransaction(tx)
        return tx
    }

    suspend fun recordExpense(category: String, amount: Double, description: String = "", language: String = "sw"): Transaction {
        val tx = Transaction(item = category, quantity = 1.0, totalAmount = amount, unitPrice = amount, type = TransactionType.EXPENSE)
        transactionDao.insertTransaction(tx)
        return tx
    }

    suspend fun getTransactionsInRange(start: Long, end: Long): List<Transaction> = transactionDao.getTransactionsInRange(start, end)
    suspend fun getTodayTransactions(): List<Transaction> = emptyList()
    suspend fun getBalance(): Double = 0.0
}

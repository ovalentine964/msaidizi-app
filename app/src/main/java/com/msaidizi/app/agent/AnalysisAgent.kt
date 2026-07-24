package com.msaidizi.app.agent

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction

/**
 * Stub: Analysis agent for transaction analysis.
 */
class AnalysisAgent(private val transactionDao: TransactionDao) {
    suspend fun analyzeTransactions(transactions: List<Transaction>): AnalysisResult = AnalysisResult()
    suspend fun getSpendingPatterns(): Map<String, Double> = emptyMap()
}

data class AnalysisResult(
    val totalSales: Double = 0.0,
    val totalExpenses: Double = 0.0,
    val profit: Double = 0.0,
    val topItems: List<String> = emptyList()
)

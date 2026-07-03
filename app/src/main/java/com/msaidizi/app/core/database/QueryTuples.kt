package com.msaidizi.app.core.database

/**
 * Tuple classes for Room query results.
 * Moved from TransactionDao.kt for KSP compatibility —
 * Room KSP processor needs all referenced types to be resolvable.
 */

data class ItemRankingTuple(
    val item: String,
    val totalQty: Double,
    val totalRev: Double,
    val txCount: Int
)

data class SalesHistoryTuple(
    val createdAt: Long,
    val quantity: Double,
    val totalAmount: Double
)

data class DailyTotalTuple(
    val day: Long,
    val total: Double
)

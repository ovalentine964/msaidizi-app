package com.msaidizi.app.agent

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao

/**
 * Stub: Business pattern tracker for detecting recurring patterns.
 */
class BusinessPatternTracker(
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao
) {
    suspend fun detectPatterns(): List<String> = emptyList()
    suspend fun getPatternSummary(): String = ""
}

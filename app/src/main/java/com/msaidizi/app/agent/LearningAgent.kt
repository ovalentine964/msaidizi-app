package com.msaidizi.app.agent

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.InventoryDao

/**
 * Stub: Learning agent for pattern learning from transactions.
 */
class LearningAgent(
    private val patternDao: PatternDao,
    private val inventoryDao: InventoryDao? = null
) {
    fun recordSaleTime(item: String, timestamp: Long) {}
    fun recordPurchaseTime(item: String, timestamp: Long) {}
    suspend fun learnFromPattern(pattern: String) {}
}

package com.msaidizi.core.network

import kotlinx.serialization.Serializable

/**
 * Anonymized payload sent to the sync server.
 * All PII has been stripped/hashed before serialization.
 */
@Serializable
data class SyncPayload(
    val deviceId: String,               // Anonymous device fingerprint
    val businessCategory: String,       // e.g. "Trade", "Food" — no business name
    val ward: String,                   // Generalized location (ward level, no GPS)
    val transactions: List<AnonymizedTransaction>,
    val learnedPatterns: List<AnonymizedPattern>,
    val vocabularyHashes: List<String>, // Hashed word forms
    val anomalyStats: AnomalyStats,     // Aggregated stats only
    val timestamp: Long,
    val syncProtocolVersion: Int = 1
)

@Serializable
data class AnonymizedTransaction(
    val amountBucket: String,       // "0-100", "100-500", "500-1000", "1000+"
    val category: String,           // product category, not product name
    val paymentMethod: String,      // cash, mpesa, credit
    val hourOfDay: Int,             // 0-23 for time-of-day patterns
    val dayOfWeek: Int,             // 1-7
    val isService: Boolean = false,
    val dedupKey: String? = null    // SHA-256 hash for server-side deduplication
)

@Serializable
data class AnonymizedPattern(
    val patternType: String,        // "consistent_sales", "growing_sales", etc.
    val confidence: Double,
    val occurrenceCount: Int
)

@Serializable
data class AnomalyStats(
    val totalTransactionsAnalyzed: Int,
    val anomalyCount: Int,
    val meanAmount: Double,
    val stdDev: Double
)

@Serializable
data class SyncResponse(
    val status: String,             // "ok", "partial", "error"
    val syncedCount: Int,
    val serverTimestamp: Long,
    val conflictsResolved: Int = 0,
    val message: String? = null
)

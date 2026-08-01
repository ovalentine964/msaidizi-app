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
    val message: String? = null,
    // Bidirectional: data flowing back from server
    val protocolVersion: Int? = null,
    val alamaScoreUpdate: AlamaScoreUpdate? = null,
    val modelDelta: ModelDelta? = null,
    val marketIntelligence: MarketIntelligence? = null,
    val alerts: List<SyncAlert> = emptyList(),
    val freshness: FreshnessMetadata? = null,
    val verification: VerificationResult? = null
)

@Serializable
data class AlamaScoreUpdate(
    val score: Int,                 // 300-850
    val factors: List<ScoreFactorUpdate> = emptyList(),
    val confidence: Double = 0.0,
    val computedAt: Long = 0
)

@Serializable
data class ScoreFactorUpdate(
    val name: String,
    val impact: Double,
    val weight: Double,
    val description: String
)

@Serializable
data class ModelDelta(
    val targetVersion: String,
    val isFullModel: Boolean = false,
    val downloadUrl: String,
    val checksum: String,
    val sizeBytes: Long = 0,
    val minProtocolVersion: Int = 1
)

@Serializable
data class MarketIntelligence(
    val ward: String,
    val priceTrends: Map<String, PriceTrend> = emptyMap(),
    val demandSignals: List<DemandSignal> = emptyList(),
    val dataTimestamp: Long = 0,
    val ttlSeconds: Long = 3600
)

@Serializable
data class PriceTrend(
    val category: String,
    val currentAvgPrice: Double,
    val weekOverWeekChange: Double,
    val direction: String           // "rising", "falling", "stable"
)

@Serializable
data class DemandSignal(
    val category: String,
    val demandLevel: String,        // "high", "medium", "low"
    val confidence: Double
)

@Serializable
data class SyncAlert(
    val alertType: String,
    val severity: String,           // "info", "warning", "critical"
    val title: String,
    val body: String,
    val timestamp: Long,
    val actionUrl: String? = null
)

@Serializable
data class FreshnessMetadata(
    val serverTimestamp: Long,
    val marketDataFresh: Boolean = false,
    val scoreDataFresh: Boolean = false,
    val staleness: String = "unknown"
)

@Serializable
data class VerificationResult(
    val allValid: Boolean = true,
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    val duplicateCount: Int = 0,
    val rejectionReasons: List<RejectionReason> = emptyList()
)

@Serializable
data class RejectionReason(
    val transactionIndex: Int,
    val reason: String,
    val severity: String
)

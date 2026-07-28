package com.msaidizi.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ──────────────────────────────────────────────
// Anomaly Detection History (persisted)
// ──────────────────────────────────────────────

@Entity(tableName = "anomaly_history")
data class AnomalyHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val isAnomaly: Boolean,
    val zScore: Double,
    val meanAtTime: Double,
    val stdDevAtTime: Double,
    val timestamp: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Learned Vocabulary (persisted)
// ──────────────────────────────────────────────

@Entity(tableName = "learned_vocabulary")
data class LearnedVocabularyEntity(
    @PrimaryKey val word: String,       // lowercase
    val language: String,               // sw, en, sheng, etc.
    val frequency: Int = 1,
    val confidence: Double = 0.5,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Business Patterns (persisted)
// ──────────────────────────────────────────────

@Entity(tableName = "business_patterns")
data class BusinessPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patternType: String,            // "consistent_sales", "growing_sales", etc.
    val category: String,               // "revenue", "inventory", etc.
    val confidence: Double,
    val occurrenceCount: Int,
    val firstDetectedAt: Long = System.currentTimeMillis(),
    val lastDetectedAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Sync State (tracks last sync time, pending count)
// ──────────────────────────────────────────────

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,        // singleton
    val lastSyncTimestamp: Long = 0,
    val lastSyncStatus: String = "never", // "ok", "partial", "error", "never"
    val pendingTransactionCount: Int = 0,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

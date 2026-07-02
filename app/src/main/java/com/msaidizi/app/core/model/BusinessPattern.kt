package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Business patterns learned from user behavior.
 * Used by the LearningAgent to predict restocking, pricing, and sales patterns.
 */
@Entity(
    tableName = "patterns"
)

data class BusinessPattern(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Pattern type
    val patternType: PatternType,

    // JSON-encoded pattern data
    val data: String = "{}",

    // Confidence in this pattern (0.0-1.0)
    val confidence: Double = 0.5,

    // When this pattern was first observed
    val createdAt: Long = System.currentTimeMillis() / 1000,

    // When this pattern was last updated
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

@Serializable
enum class PatternType {
    // User's vocabulary (spoken → canonical mapping)
    VOCABULARY,

    // Restocking cycle for an item
    RESTOCK_CYCLE,

    // Price trend for an item
    PRICE_TREND,

    // Peak selling hours
    PEAK_HOURS,

    // Seasonal patterns
    SEASONAL,

    // Language preference
    LANGUAGE_SWITCH,

    // Day-of-week sales pattern
    DAY_OF_WEEK
}

/**
 * Vocabulary mapping: user's spoken term → canonical business term.
 */
@Entity(tableName = "vocabulary")

data class VocabularyEntry(
    @PrimaryKey
    val spokenForm: String,

    // Canonical/standard form
    val canonicalForm: String,

    // Language code (sw, en, sheng)
    val language: String = "sw",

    // How often the user uses this term
    val frequency: Int = 1,

    // Last time user used this term
    val lastUsedAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Daily summary for quick dashboard display.
 */
@Entity(tableName = "daily_summaries")

data class DailySummary(
    @PrimaryKey
    val date: String, // YYYY-MM-DD format

    val totalSales: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val totalExpenses: Double = 0.0,
    val profit: Double = 0.0,

    // JSON array of top items
    val topItems: String = "[]",

    val transactionCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis() / 1000
)

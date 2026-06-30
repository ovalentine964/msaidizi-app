package com.msaidizi.app.core.model

import kotlinx.serialization.Serializable

/**
 * Intent detected from user's voice input.
 * Used by IntentRouter to determine what the user wants to do.
 */
@Serializable
data class Intent(
    val type: IntentType,
    val confidence: Double = 0.0,
    val extractedData: Map<String, String> = emptyMap(),
    val needsLLM: Boolean = false,
    val rawText: String = ""
)

@Serializable
enum class IntentType {
    /** Record a sale: "Nimeuza mandazi kumi kwa Sh 500" */
    SALE,

    /** Record a purchase: "Nimenunua unga kwa Sh 200" */
    PURCHASE,

    /** Record an expense: "Nimetumia Sh 100 kwa usafiri" */
    EXPENSE,

    /** Ask about balance/sales: "Salio langu ni ngapi?" */
    CHECK_BALANCE,

    /** Ask about profit: "Faida yangu ni ngapi?" */
    PROFIT_QUERY,

    /** Ask about stock: "Nina baki ngapi ya mandazi?" */
    STOCK_QUERY,

    /** Request advice: "Nisaidie na biashara yangu" */
    ASK_ADVICE,

    /** Get daily summary: "Report ya leo" */
    DAILY_SUMMARY,

    /** Get weekly summary */
    WEEKLY_SUMMARY,

    /** Help command */
    HELP,

    /** Greeting */
    GREETING,

    /** Correction to previous transaction */
    CORRECTION,

    /** Unknown/ambiguous intent */
    UNKNOWN
}

/**
 * Result of intent classification.
 */
@Serializable
data class IntentResult(
    val intent: IntentType,
    val confidence: Double,
    val extractedData: Map<String, String> = emptyMap(),
    val needsLLM: Boolean = false
)

/**
 * Extracted sale data from voice input.
 */
data class SaleData(
    val item: String,
    val quantity: Double,
    val amount: Double,
    val unitPrice: Double = if (quantity > 0) amount / quantity else amount
)

/**
 * Extracted purchase data from voice input.
 */
data class PurchaseData(
    val item: String,
    val quantity: Double,
    val amount: Double,
    val unitPrice: Double = if (quantity > 0) amount / quantity else amount
)

/**
 * Cash flow analysis result.
 */
data class CashFlow(
    val inflow: Double,
    val outflow: Double,
    val net: Double,
    val period: String
)

/**
 * Trend direction.
 */
enum class Trend {
    RISING,
    STABLE,
    FALLING,
    INSUFFICIENT_DATA
}

/**
 * Item ranking by revenue.
 */
data class ItemRanking(
    val item: String,
    val totalQuantity: Double,
    val totalRevenue: Double,
    val transactionCount: Int
)

/**
 * User preferences stored in DataStore.
 */
data class UserPreferences(
    val language: String = "sw",
    val voiceSpeed: Float = 1.0f,
    val autoSync: Boolean = true,
    val syncOnWifiOnly: Boolean = true,
    val businessName: String = "",
    val businessType: String = "",
    val currency: String = "KES",
    val onboardingComplete: Boolean = false
)

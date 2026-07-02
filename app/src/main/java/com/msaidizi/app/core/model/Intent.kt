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
    // Record a sale: "Nimeuza mandazi kumi kwa Sh 500"
    SALE,

    // Record a purchase: "Nimenunua unga kwa Sh 200"
    PURCHASE,

    // Record an expense: "Nimetumia Sh 100 kwa usafiri"
    EXPENSE,

    // Ask about balance/sales: "Salio langu ni ngapi?"
    CHECK_BALANCE,

    // Ask about profit: "Faida yangu ni ngapi?"
    PROFIT_QUERY,

    // Ask about stock: "Nina baki ngapi ya mandazi?"
    STOCK_QUERY,

    // Request advice: "Nisaidie na biashara yangu"
    ASK_ADVICE,

    // Get daily summary: "Report ya leo"
    DAILY_SUMMARY,

    // Get weekly summary
    WEEKLY_SUMMARY,

    // Help command
    HELP,

    // Greeting
    GREETING,

    // Correction to previous transaction
    CORRECTION,

    // Transport-specific: record trip
    TRANSPORT_TRIP,

    // Transport-specific: fuel/expense
    TRANSPORT_EXPENSE,

    // Farming-specific: planting/harvesting
    FARMING_ACTIVITY,

    // Farming-specific: input purchase
    FARMING_INPUT,

    // Digital/gig: commission earned
    DIGITAL_COMMISSION,

    // Digital/gig: transaction volume
    DIGITAL_TRANSACTION,

    // Service-specific: client served
    SERVICE_CLIENT,

    // Service-specific: job completed
    SERVICE_JOB,

    // Giving/tithing: record giving
    GIVING_RECORD,

    // Giving/tithing: query giving report
    GIVING_QUERY,

    // Giving/tithing: set giving goal
    GIVING_GOAL,

    // Goal planning: "Lengo langu ni kununua friji"
    GOAL_CREATE,

    // Goal progress: "Nimefikia 50% ya lengo"
    GOAL_PROGRESS,

    // Goal report: "Ripoti ya malengo"
    GOAL_REPORT,

    // Time to goal: "Muda wa kufikia lengo"
    GOAL_TIME_FORECAST,

    // Goal adjustment: "Badilisha lengo"
    GOAL_ADJUST,

    // Goal encouragement: "Nisaidie na lengo"
    GOAL_ENCOURAGEMENT,

    // Loan management: record a new loan
    // "Nimechukua mkopo wa KSh 10,000"
    LOAN_RECORD,

    // Loan management: check loan payments
    // "Malipo ya mkopo"
    LOAN_QUERY,

    // Loan management: get loan report
    // "Ripoti ya mkopo"
    LOAN_REPORT,

    // Loan management: check payment deadline
    // "Muda wa kulipa"
    LOAN_DEADLINE,

    // Unknown/ambiguous intent
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
    val onboardingComplete: Boolean = false,
    val dialectRegion: DialectRegion = DialectRegion.STANDARD
)

/**
 * Dialect regions supported by Msaidizi.
 * Phase 1: Migori County (Luo substrate)
 * Phase 2: Kenya nationwide
 * Phase 3: East Africa
 * Phase 4: All Africa
 */
enum class DialectRegion {
    // Standard Swahili (taught in schools)
    STANDARD,
    // Migori County — Luo substrate, code-switching with Dholuo
    MIGORI,
    // Nairobi — heavy Sheng influence
    NAIROBI,
    // Coast — Mvita/Swahili coast dialect, Arabic loanwords
    COAST,
    // Tanzania — standard Kiswahili with Bongo flavor
    TANZANIA,
    // Uganda — Luganda substrate
    UGANDA,

    // ── East Africa ──
    // Kikuyu — Central Kenya (Nyeri, Murang'a, Kiambu)
    KIKUYU,
    // Dholuo — Western Kenya (Kisumu, Siaya, Homa Bay)
    DHOLUO,
    // Luhya — Western Kenya (Kakamega, Bungoma, Busia)
    LUHYA,
    // Kalenjin — Rift Valley (Nandi, Baringo, Uasin Gishu)
    KALENJIN,
    // Maasai — Southern Kenya / Northern Tanzania (Kajiado, Narok)
    MAASAI,

    // ── Horn of Africa ──
    // Somali — Somalia, Djibouti, NE Kenya
    SOMALI,
    // Amharic — Ethiopia (romanized input from ASR)
    AMHARIC,

    // ── West Africa ──
    // Yoruba — Southwestern Nigeria
    YORUBA,
    // Igbo — Southeastern Nigeria
    IGBO,
    // Hausa — West Africa (Nigeria, Niger, Ghana, Chad)
    HAUSA,

    // ── Southern Africa ──
    // Zulu — South Africa (KwaZulu-Natal, Gauteng)
    ZULU,
    // Xhosa — South Africa (Eastern Cape, Western Cape)
    XHOSA,

    // ── Urban slang ──
    // Sheng — Kenyan urban slang (Nairobi, Mombasa)
    SHENG
}

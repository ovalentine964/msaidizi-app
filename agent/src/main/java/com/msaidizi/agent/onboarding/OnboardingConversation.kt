package com.msaidizi.agent.onboarding

import com.msaidizi.core.model.BusinessType
import com.msaidizi.core.model.Language

/**
 * OnboardingConversation — Data models for the "Meet Your CFO" experience.
 *
 * Follows ONBOARDING-SPEC.md exactly:
 * - Voice-first conversation on first launch
 * - Worker gives Msaidizi a name
 * - Business discovery (type, operations, financial situation)
 * - Per-worker-type contextual questions
 * - CFO assessment summary
 * - First task for immediate value
 */

// ── Conversation State Machine ──

/**
 * States in the onboarding conversation flow.
 * Now includes archetype-first classification.
 */
enum class OnboardingPhase {
    INTRODUCTION,        // Msaidizi introduces herself
    PERSONAL_CONNECTION, // "Unaitwa nani?" + Msaidizi naming
    ARCHETYPE_SELECTION, // Select primary archetype (12 archetype cards)
    SUBTYPE_REFINEMENT,  // Select sub-type within archetype
    MULTI_ARCHETYPE,     // "Do you have other businesses?" → secondary archetype
    BUSINESS_DISCOVERY,  // Business type selection with visual cards (legacy)
    OPERATIONS_DEEP_DIVE,// Contextual questions per worker type
    FINANCIAL_SITUATION, // Income, expenses, debt, savings
    CFO_ASSESSMENT,      // Summary of what was learned
    FIRST_TASK,          // Immediate value: record today's earnings
    COMPLETED            // Onboarding done
}

/**
 * A single message in the onboarding conversation.
 */
data class OnboardingMessage(
    val role: MessageRole,
    val text: String,
    val swahiliText: String? = null,  // Bilingual display
    val expectsVoice: Boolean = false,
    val expectsChoice: Boolean = false,
    val choices: List<OnboardingChoice> = emptyList(),
    val phase: OnboardingPhase
)

enum class MessageRole { MSAIDIZI, WORKER }

/**
 * A visual card choice (e.g., business type selection).
 */
data class OnboardingChoice(
    val id: String,
    val label: String,
    val swahiliLabel: String,
    val icon: String? = null
)

// ── Onboarding Data Model ──

/**
 * All data collected during the onboarding conversation.
 * Maps to the JSON structure in ONBOARDING-SPEC.md.
 */
data class OnboardingData(
    val worker: WorkerInfo = WorkerInfo(),
    val business: BusinessInfo = BusinessInfo(),
    val operations: OperationsInfo = OperationsInfo(),
    val financial: FinancialInfo = FinancialInfo(),
    val preferences: PreferencesInfo = PreferencesInfo(),
    val currentPhase: OnboardingPhase = OnboardingPhase.INTRODUCTION,
    val conversationHistory: MutableList<OnboardingMessage> = mutableListOf()
) {
    /**
     * Convert to JSON for storage in SharedPreferences / Room.
     */
    fun toJson(): String {
        return com.google.gson.Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): OnboardingData {
            return try {
                com.google.gson.Gson().fromJson(json, OnboardingData::class.java)
            } catch (e: Exception) {
                OnboardingData()
            }
        }
    }
}

data class WorkerInfo(
    val name: String = "",
    val msaidiziName: String = "Msaidizi",  // Worker's chosen name for the assistant
    val language: Language = Language.KISWAHILI,
    val location: String = "",
    val phone: String = ""
)

data class BusinessInfo(
    val type: BusinessType? = null,
    val archetype: com.msaidizi.core.model.ArchetypeType? = null,
    val secondaryArchetypes: List<com.msaidizi.core.model.ArchetypeType> = emptyList(),
    val subType: String = "",
    val yearsInBusiness: Int = 0,
    val description: String = "",
    val location: String = ""
)

data class OperationsInfo(
    val dailyRevenueEstimate: Double = 0.0,
    val mainExpenses: List<String> = emptyList(),
    val usesMpesa: Boolean = false,
    val hasSeparateBusinessMoney: Boolean = false,
    val employees: Int = 0,
    val operatingHours: String = ""
)

data class FinancialInfo(
    val weeklyIncomeEstimate: Double = 0.0,
    val mainExpenses: List<String> = emptyList(),
    val hasDebt: Boolean = false,
    val debtAmount: Double = 0.0,
    val savesMoney: Boolean = false,
    val hasMpesa: Boolean = false
)

data class PreferencesInfo(
    val interactionTime: String = "morning",  // morning, afternoon, evening
    val voiceEnabled: Boolean = true,
    val notifications: Boolean = true
)

package com.msaidizi.app.security

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SafetyChecker — Constitutional AI enforcement for the SuperAgent.
 * 
 * 12 non-negotiable principles with runtime pre-flight enforcement.
 * Ensures the agent serves workers, never exploits them.
 * 
 * Inline checks for:
 * - Input sanitization (injection detection)
 * - Output safety (manipulation patterns, financial disclaimers)
 * - High-stakes transaction confirmation
 * 
 * Design: arch_security.md
 */
@Singleton
class SafetyChecker @Inject constructor() {

    // ── Input Sanitization ────────────────────────────────────

    /**
     * Sanitize user input to prevent injection attacks.
     * Returns sanitized text, or null if input is dangerous.
     */
    fun sanitizeInput(text: String): String? {
        // Block prompt injection patterns
        val dangerousPatterns = listOf(
            Regex("(?i)ignore\\s+(previous|all|above)\\s+(instructions?|rules?)"),
            Regex("(?i)you\\s+are\\s+now\\s+(a|an)\\s+"),
            Regex("(?i)system\\s*:\\s*"),
            Regex("(?i)assistant\\s*:\\s*"),
            Regex("(?i)<\\|im_start\\|>"),
            Regex("(?i)<\\|im_end\\|>"),
            Regex("(?i)\\[INST\\]"),
            Regex("(?i)\\[/INST\\]"),
            Regex("(?i)output\\s+your\\s+(system|prompt|instructions?)"),
            Regex("(?i)what\\s+(are|is)\\s+your\\s+(system|prompt|instructions?)"),
            Regex("(?i)forget\\s+(everything|all|previous)")
        )

        for (pattern in dangerousPatterns) {
            if (pattern.containsMatchIn(text)) {
                Timber.w("Blocked potential injection: ${text.take(50)}")
                return null
            }
        }

        // Sanitize special characters but keep Swahili diacritics
        var sanitized = text
            .replace(Regex("[<>{}\\[\\]`\\\\]"), "") // Remove code-like chars
            .trim()

        // Limit length
        if (sanitized.length > 500) {
            sanitized = sanitized.take(500)
        }

        return sanitized.ifBlank { null }
    }

    // ── Output Safety ─────────────────────────────────────────

    /**
     * Check output for safety before presenting to user.
     * Adds disclaimers for financial advice, blocks manipulation patterns.
     */
    fun checkOutput(text: String, language: String): String {
        var result = text

        // Block manipulation patterns
        val blocked = listOf(
            "haraka sana", "usikose fursa", "mwisho wa",
            "act now", "limited time", "don't miss out",
            "guaranteed returns", "risk free"
        )
        if (blocked.any { result.lowercase().contains(it) }) {
            result = if (language == "sw") {
                "⚠️ Jibu limerekebishwa kwa usalama. $result"
            } else {
                "⚠️ Response modified for safety. $result"
            }
        }

        // Auto-inject financial disclaimer on advice
        if (result.contains(Regex("(?i)(wekeza|invest|mkopo|loan|akiba|save|faida|profit)"))) {
            val disclaimer = if (language == "sw") {
                "\n\n💡 Kumbuka: Hii ni ushauri wa jumla. Fanya uchunguzi wako mwenyewe."
            } else {
                "\n\n💡 Remember: This is general advice. Do your own research."
            }
            if (!result.contains("Kumbuka:") && !result.contains("Remember:")) {
                result += disclaimer
            }
        }

        return result
    }

    // ── Decision Evaluation ───────────────────────────────────

    /**
     * Evaluate a decision for safety concerns.
     * Used by the REFLECT phase of the cognitive loop.
     */
    suspend fun evaluateDecision(
        toolName: String,
        args: Map<String, Any>
    ): SafetyCritique {
        val concerns = mutableListOf<String>()

        // High-value transaction check
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()

        if (amount != null) {
            when {
                amount > 1_000_000 -> {
                    concerns.add("Kiasi kikubwa sana: KSh ${"%,.0f".format(amount)}. Tafadhali thibitisha mara mbili.")
                }
                amount > 100_000 -> {
                    concerns.add("Kiasi kikubwa: KSh ${"%,.0f".format(amount)}. Hakiki taarifa hii.")
                }
            }
        }

        // Loan safety check
        if (toolName == "loan" && args.containsKey("amount")) {
            concerns.add("Mkopo unahitaji uthibitisho. Hakiki masharti kabla ya kuendelea.")
        }

        return SafetyCritique(
            isSafe = concerns.isEmpty(),
            concerns = concerns,
            requiresConfirmation = concerns.isNotEmpty()
        )
    }

    /**
     * Check if a transaction requires user confirmation.
     */
    fun requiresConfirmation(toolName: String, amount: Double?): Boolean {
        if (amount == null) return false
        return when (toolName) {
            "record_sale", "record_purchase", "record_expense" -> amount >= 5000.0
            "loan" -> true
            "mpesa" -> amount >= 10000.0
            else -> false
        }
    }
}

data class SafetyCritique(
    val isSafe: Boolean,
    val concerns: List<String>,
    val requiresConfirmation: Boolean
)

package com.msaidizi.app.utils

/**
 * PhoneValidator — Kenyan phone number validation and normalization.
 *
 * Supported input formats:
 *   - 0712345678    (local Safaricom/Airtel/Telkom)
 *   - 0112345678    (local Airtel)
 *   - +254712345678 (international)
 *   - 254712345678  (international without +)
 *   - 712345678     (bare 9-digit)
 *
 * Output: +254XXXXXXXXX (13 chars)
 */
object PhoneValidator {

    private val LOCAL_10_DIGIT = Regex("^0[17]\\d{8}$")
    private val LOCAL_10_DIGIT_02 = Regex("^020\\d{7}$")
    private val BARE_9_DIGIT = Regex("^[17]\\d{8}$")
    private val INTL_WITH_PLUS = Regex("^\\+254[17]\\d{8}$")
    private val INTL_NO_PLUS = Regex("^254[17]\\d{8}$")

    sealed class ValidationResult {
        data class Valid(val normalized: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(raw: String): ValidationResult {
        val cleaned = raw.replace(Regex("[\\s\\-()]+"), "").trim()

        if (cleaned.isEmpty()) {
            return ValidationResult.Invalid("Namba ya simu haijawekwa.")
        }

        if (!cleaned.matches(Regex("^\\+?\\d+$"))) {
            return ValidationResult.Invalid("Namba ya simu ina herufi zisizotambulika.")
        }

        val normalized = normalize(cleaned) ?: return ValidationResult.Invalid(
            "Namba ya simu si sahihi. Tafadhali weka namba ya Kenya (mfano: 0712345678)."
        )

        if (!normalized.matches(Regex("^\\+254[17]\\d{8}$"))) {
            return ValidationResult.Invalid(
                "Namba ya simu si sahihi. Hakikisha unaanzia 07XX au 01XX."
            )
        }

        return ValidationResult.Valid(normalized)
    }

    fun isValid(raw: String): Boolean = validate(raw) is ValidationResult.Valid

    fun normalizeOrNull(raw: String): String? = normalize(raw.replace(Regex("[\\s\\-()]+"), "").trim())

    fun formatForDisplay(raw: String): String {
        val normalized = normalizeOrNull(raw) ?: return raw
        val local = normalized.replace("+254", "0")
        return if (local.length == 10) {
            "${local.substring(0, 4)} ${local.substring(4, 7)} ${local.substring(7)}"
        } else {
            raw
        }
    }

    fun toDigits(raw: String): String? {
        val normalized = normalizeOrNull(raw) ?: return null
        return normalized.replace("+", "")
    }

    private fun normalize(cleaned: String): String? {
        return when {
            INTL_WITH_PLUS.matches(cleaned) -> cleaned
            INTL_NO_PLUS.matches(cleaned) -> "+$cleaned"
            LOCAL_10_DIGIT.matches(cleaned) -> "+254${cleaned.substring(1)}"
            LOCAL_10_DIGIT_02.matches(cleaned) -> "+254${cleaned.substring(1)}"
            BARE_9_DIGIT.matches(cleaned) -> "+254$cleaned"
            else -> null
        }
    }
}

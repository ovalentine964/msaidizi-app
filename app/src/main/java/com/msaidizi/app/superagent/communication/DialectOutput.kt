package com.msaidizi.app.superagent.communication

import timber.log.Timber

/**
 * Dialect Output — output in worker's dialect.
 *
 * ## Dialect Support
 * Transforms standard Swahili/English output into dialect-specific text:
 * - Standard Swahili (Swahili Sanifu)
 * - Sheng (Nairobi slang)
 * - Coastal Swahili (Ki-Pwani)
 * - Kikuyu-influenced Swahili
 * - Dholuo-influenced Swahili
 * - Kalenjin-influenced Swahili
 *
 * ## Transformations
 * - Number formatting (Swahili number words)
 * - Greeting variations by region
 * - Slang substitution for common terms
 * - Code-switching patterns (mixing languages naturally)
 *
 * ## Design
 * Dialect output is applied AFTER content generation.
 * The core content is always in standard Swahili/English,
 * then transformed for the worker's specific dialect.
 */
class DialectOutput {

    companion object {
        private const val TAG = "DialectOutput"

        /** Supported dialects */
        val DIALECTS = listOf("sw", "sheng", "pwani", "kikuyu", "luo", "kalenjin")
    }

    /**
     * Transform text for a specific dialect.
     *
     * @param text The text in standard language
     * @param dialect The target dialect
     * @return Transformed text in the dialect
     */
    fun transform(text: String, dialect: String): String {
        return when (dialect) {
            "sheng" -> transformToSheng(text)
            "pwani" -> transformToPwani(text)
            "kikuyu" -> transformToKikuyuSwahili(text)
            "luo" -> transformToLuoSwahili(text)
            "kalenjin" -> transformToKalenjinSwahili(text)
            else -> text // Standard Swahili/English — no transformation
        }
    }

    /**
     * Format a number for voice output in Swahili.
     * Converts digits to Swahili number words for natural speech.
     *
     * @param number The number to format
     * @return Swahili number word
     */
    fun formatNumber(number: Double): String {
        val intPart = number.toInt()
        val decimalPart = ((number - intPart) * 100).toInt()

        return if (decimalPart > 0) {
            "${formatInteger(intPart)} na ${formatInteger(decimalPart)}"
        } else {
            formatInteger(intPart)
        }
    }

    /**
     * Format currency for voice output.
     *
     * @param amount The amount in KSh
     * @param dialect The target dialect
     * @return Formatted currency string
     */
    fun formatCurrency(amount: Double, dialect: String = "sw"): String {
        return when (dialect) {
            "sheng" -> "KSh ${formatNumber(amount)}"
            else -> "KSh ${formatNumber(amount)}"
        }
    }

    /**
     * Get a dialect-specific greeting.
     */
    fun getGreeting(workerName: String, dialect: String, timeOfDay: String = "morning"): String {
        val name = if (workerName.isNotBlank()) " $workerName" else ""

        return when (dialect) {
            "sheng" -> when (timeOfDay) {
                "morning" -> "Sasa$name! Soko iko aje? ☀️"
                "evening" -> "Sasa$name! Leo ilikuwaje? 🌆"
                else -> "Sasa$name! 💪"
            }
            "pwani" -> when (timeOfDay) {
                "morning" -> "Habari za asubuhi$name! Bahari iko shwari? 🌅"
                "evening" -> "Habari za jioni$name! Siku imeenda vipi? 🌆"
                else -> "Habari$name! 🌊"
            }
            else -> when (timeOfDay) {
                "morning" -> "Habari$name! Soko linaanza vizuri? ☀️"
                "evening" -> "Habari za jioni$name! Umefanya kazi nzuri leo. 🌆"
                else -> "Habari$name! 💪"
            }
        }
    }

    // ═══════════════ DIALECT TRANSFORMERS ═══════════════

    private fun transformToSheng(text: String): String {
        // Sheng substitutions — Nairobi youth slang
        var result = text

        // Common word substitutions
        val shengMap = mapOf(
            "Habari" to "Sasa",
            "nzuri" to "poa",
            "vizuri" to "sawa",
            "sana" to "sana", // keep
            "asante" to "asante", // keep
            "karibu" to "karibu", // keep
            "mfanyabiashara" to "msee wa biashara",
            "biashara" to "biashara", // keep — too common
            "mauzo" to "mauzo", // keep
            "faida" to "faida", // keep — too important
            "soko" to "soko", // keep
            "leo" to "leo", // keep
            "kesho" to "kesho", // keep
            "Endelea" to "Endelea tu",
            "Hongera" to "Hongera sana",
            "Kidogo" to "ka-quarter"
        )

        for ((standard, sheng) in shengMap) {
            result = result.replace(standard, sheng, ignoreCase = true)
        }

        return result
    }

    private fun transformToPwani(text: String): String {
        // Coastal Swahili — more Arabic loanwords, different greetings
        var result = text

        val pwaniMap = mapOf(
            "Habari" to "Shikamoo",
            "nzuri" to "nzuri sana",
            "sana" to "sana", // keep
            "mfanyabiashara" to "mfanyabiashara wa pwani"
        )

        for ((standard, pwani) in pwaniMap) {
            result = result.replace(standard, pwani, ignoreCase = true)
        }

        return result
    }

    private fun transformToKikuyuSwahili(text: String): String {
        // Kikuyu-influenced Swahili — certain vowel shifts, greetings
        // Minimal transformation — mostly standard Swahili with Kikuyu accent patterns
        return text
    }

    private fun transformToLuoSwahili(text: String): String {
        // Dholuo-influenced Swahili — certain consonant patterns
        // Minimal transformation — mostly standard Swahili with Luo accent patterns
        return text
    }

    private fun transformToKalenjinSwahili(text: String): String {
        // Kalenjin-influenced Swahili — certain phonetic patterns
        // Minimal transformation — mostly standard Swahili with Kalenjin accent patterns
        return text
    }

    // ═══════════════ NUMBER FORMATTING ═══════════════

    private fun formatInteger(n: Int): String {
        if (n == 0) return "sifuri"
        if (n < 0) return "hasi ${formatInteger(-n)}"

        return buildString {
            when {
                n >= 1000000 -> {
                    append(formatInteger(n / 1000000))
                    append(" milioni")
                    if (n % 1000000 > 0) append(" ${formatInteger(n % 1000000)}")
                }
                n >= 1000 -> {
                    append(formatInteger(n / 1000))
                    append(" elfu")
                    if (n % 1000 > 0) append(" ${formatInteger(n % 1000)}")
                }
                n >= 100 -> {
                    append(formatInteger(n / 100))
                    append(" mia")
                    if (n % 100 > 0) append(" na ${formatInteger(n % 100)}")
                }
                else -> append(formatTens(n))
            }
        }
    }

    private fun formatTens(n: Int): String {
        val ones = arrayOf("", "moja", "mbili", "tatu", "nne", "tano", "sita", "saba", "nane", "tisa")
        val teens = arrayOf("kumi", "kumi na moja", "kumi na mbili", "kumi na tatu", "kumi na nne",
            "kumi na tano", "kumi na sita", "kumi na saba", "kumi na nane", "kumi na tisa")
        val tens = arrayOf("", "kumi", "ishirini", "thelathini", "arobaini", "hamsini",
            "sitini", "sabini", "themanini", "tisini")

        return when {
            n < 10 -> ones[n]
            n < 20 -> teens[n - 10]
            n % 10 == 0 -> tens[n / 10]
            else -> "${tens[n / 10]} na ${ones[n % 10]}"
        }
    }
}

package com.msaidizi.app.core.dialect

/**
 * KiswahiliDialectAdapter — Handles Kiswahili and its variants.
 *
 * Kiswahili is the lingua franca of East Africa, but it comes in many flavors:
 * - Standard Kiswahili (taught in schools, formal)
 * - Nairobi Kiswahili (informal, mixed with English/Sheng)
 * - Coastal Kiswahili (older, more Arabic loanwords)
 * - Sheng (youth slang, rapidly evolving)
 * - Regional variants (Mombasa, Dar es Salaam, etc.)
 *
 * BCB 108: Msaidizi must detect which variant the worker speaks and
 * respond in the same register. Don't be more formal than the worker.
 *
 * @author Angavu Intelligence — Implementation Swarm 9
 */
class KiswahiliDialectAdapter : DialectAdapter {

    companion object {
        // Sheng markers — words that indicate the speaker uses Sheng
        private val SHENG_MARKERS = setOf(
            "siafiri", "poa", "mambo", "fiti", "nde", "aje", "niaje",
            "wazi", "imebamba", "imechemka", "mbogi", "mzingine",
            "nipee", "tafadhali", "bro", "dude", "manze", "aki",
            "siwezi", "bana", "siezi", "wasee", "ma-people"
        )

        // Coastal Kiswahili markers
        private val COASTAL_MARKERS = setOf(
            "habari yenu", "mambo vipi", "shikamoo", "marahaba",
            "la haula", "bwana", "dada", "kaka"
        )

        // Standard/formal Kiswahili markers
        private val FORMAL_MARKERS = setOf(
            "naomba", "tafadhali", "samahani", "nisamehe",
            "nashukuru", "naomba kujua", "je"
        )

        // Code-switching patterns (Kiswahili + English)
        private val ENGLISH_WORDS = setOf(
            "customer", "stock", "price", "profit", "loss", "sale",
            "business", "money", "cash", "record", "market", "shop"
        )
    }

    override fun detectDialect(text: String): String {
        val lower = text.lowercase()
        val words = lower.split("\\s+".toRegex()).toSet()

        // Check for Sheng
        val shengScore = words.intersect(SHENG_MARKERS).size
        if (shengScore >= 2) return "sheng"

        // Check for code-switching
        val englishScore = words.intersect(ENGLISH_WORDS).size
        if (englishScore >= 1) return "sw_english_mix"

        // Check for coastal
        val coastalScore = words.intersect(COASTAL_MARKERS).size
        if (coastalScore >= 1) return "coastal_swahili"

        // Check for formal
        val formalScore = words.intersect(FORMAL_MARKERS).size
        if (formalScore >= 1) return "formal_swahili"

        return "standard_swahili"
    }

    override fun getResponseRegister(dialect: String): String {
        return when (dialect) {
            "sheng" -> "sheng"
            "sw_english_mix" -> "sw_english_mix"
            "coastal_swahili" -> "coastal_swahili"
            "formal_swahili" -> "formal_swahili"
            else -> "standard_swahili"
        }
    }

    override fun hasCodeSwitching(text: String): Boolean {
        val words = text.lowercase().split("\\s+".toRegex()).toSet()
        return words.intersect(ENGLISH_WORDS).isNotEmpty()
    }

    override fun getGreeting(hourOfDay: Int): String {
        return when {
            hourOfDay < 12 -> "Habari za asubuhi"
            hourOfDay < 17 -> "Habari za mchana"
            hourOfDay < 21 -> "Habari za jioni"
            else -> "Habari za usiku"
        }
    }

    override fun getAcknowledgment(): String {
        return "Sawa"
    }

    override fun formatCurrency(amount: Double): String {
        val amountInt = amount.toInt()
        return when {
            amountInt >= 1000000 -> String.format("milioni %.1f", amount / 1_000_000)
            amountInt >= 1000 -> String.format("elfu %d", amountInt / 1000) +
                    if (amountInt % 1000 > 0) " na ${amountInt % 1000}" else ""
            else -> String.format("%d", amountInt)
        }
    }

    override fun getBusinessTerm(term: BusinessTerm): String {
        return when (term) {
            BusinessTerm.SALE -> "mauzo"
            BusinessTerm.PURCHASE -> "manunuzi"
            BusinessTerm.PROFIT -> "faida"
            BusinessTerm.LOSS -> "hasara"
            BusinessTerm.CUSTOMER -> "mteja"
            BusinessTerm.STOCK -> "bidhaa"
            BusinessTerm.PRICE -> "bei"
            BusinessTerm.EXPENSE -> "gharama"
            BusinessTerm.REVENUE -> "mapato"
            BusinessTerm.CASH -> "pesa taslimu"
            BusinessTerm.MPESA -> "M-Pesa"
            BusinessTerm.RECORD -> "rekodi"
            BusinessTerm.INVENTORY -> "stock"
            BusinessTerm.SUPPLIER -> "msambazaji"
            BusinessTerm.MARKET -> "soko"
        }
    }
}

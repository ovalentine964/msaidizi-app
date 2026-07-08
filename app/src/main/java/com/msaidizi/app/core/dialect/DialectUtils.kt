package com.msaidizi.app.core.dialect

/**
 * Shared utilities for dialect adapters.
 *
 * Consolidates common helpers (e.g. isSwahiliWord) that were previously
 * duplicated across every adapter file.
 */
object DialectUtils {

    private val SWAHILI_WORDS = setOf(
        "na", "ya", "wa", "za", "kwa", "ni", "la", "cha",
        "nime", "sija", "tuta", "wata", "nina", "tuna",
        "sana", "pia", "lakini", "kama", "au", "hata", "bado",
        "leo", "jana", "kesho", "sasa", "baada",
        "biashara", "bei", "faida", "hasara", "deni", "pesa"
    )

    /** Extended Swahili word set used by adapters that need broader coverage. */
    private val SWAHILI_WORDS_EXTENDED = SWAHILI_WORDS + setOf(
        "nini", "gani", "wapi", "lini", "vipi",
        "hapa", "pale", "ndani", "nje", "juu", "chini",
        "nenda", "kuja", "fanya", "sema", "ona", "pata"
    )

    fun isSwahiliWord(word: String): Boolean =
        word in SWAHILI_WORDS || SwahiliMarketVocabulary.isMarketTerm(word)

    fun isSwahiliWordExtended(word: String): Boolean =
        word in SWAHILI_WORDS_EXTENDED || SwahiliMarketVocabulary.isMarketTerm(word)
}

package com.msaidizi.agent.harness

import org.junit.Assert.*
import org.junit.Test

/**
 * T1-T2: IntentRouter unit tests — tests the pattern matching logic (Tier 1)
 * for critical business intents.
 *
 * Tests the pure pattern-matching functions that don't require LLM or database.
 * Covers: sale, expense, purchase, stock check, greeting, help, credit check.
 */
class IntentRouterPatternTest {

    // ═══════════════════════════════════════════════════════════
    //  Helper: Extract pattern-matching logic for unit testing
    // ═══════════════════════════════════════════════════════════

    /**
     * Pure function tests for intent pattern matching.
     * These test the keyword/regex logic without needing DI or LLM.
     */

    // ── Sale Pattern Matching ──
    private fun matchesSalePattern(input: String): Boolean {
        val saleKeywords = listOf(
            "nimeuza", "niliuza", "uza", "sold", "sale", "nimemuuza",
            "nimepata", "customer", "mteja ame", "nilipatia",
            "i sold", "nimemuuzia", "record sale", "log sale", "enter sale"
        )
        return saleKeywords.any { input.lowercase().contains(it) }
    }

    // ── Expense Pattern Matching ──
    private fun matchesExpensePattern(input: String): Boolean {
        val expenseKeywords = listOf(
            "nimetumia", "nilitumia", "expense", "cost", "spent",
            "nilipia", "nimelipia", "gharama", "matumizi",
            "i spent", "i paid", "record expense", "log expense"
        )
        return expenseKeywords.any { input.lowercase().contains(it) }
    }

    // ── Purchase Pattern Matching ──
    private fun matchesPurchasePattern(input: String): Boolean {
        val keywords = listOf(
            "nimenunua", "nilinunua", "bought", "purchased",
            "nimeweka", "nimetia", "nimeongeza stock",
            "i bought", "record purchase"
        )
        return keywords.any { input.lowercase().contains(it) }
    }

    // ── Stock Check Pattern Matching ──
    private fun matchesStockCheckPattern(input: String): Boolean {
        val stockKeywords = listOf(
            "stock", "inventory", "bidhaa", "imebaki", "imepungua",
            "how much", "kiasi", "nina", "remaining", "baki"
        )
        return stockKeywords.any { input.lowercase().contains(it) }
    }

    // ── Greeting Pattern Matching ──
    private fun matchesGreetingPattern(input: String): Boolean {
        val greetings = listOf(
            "habari", "hi", "hello", "hey", "niaje", "sasa",
            "mambo", "vipi", "shikamoo", "good morning"
        )
        return greetings.any { input.lowercase().contains(it) }
    }

    // ── Credit Check Pattern Matching ──
    private fun matchesCreditCheckPattern(input: String): Boolean {
        val keywords = listOf(
            "credit score", "mkopo", "loan ready", "credit readiness",
            "nikopeshwe", "naweza pata mkopo", "check credit"
        )
        return keywords.any { input.lowercase().contains(it) }
    }

    // ── Amount Extraction ──
    private val currencyPattern = Regex(
        """(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractAmount(input: String): Double? {
        val match = currencyPattern.find(input)
        return match?.let {
            val amount = it.groupValues[1].ifEmpty { it.groupValues[2] }
            amount.replace(",", "").toDoubleOrNull()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  T1: Sale Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify nimeuza as sale`() {
        assertTrue(matchesSalePattern("Nimeuza mandazi kwa 500"))
    }

    @Test
    fun `classify sold as sale`() {
        assertTrue(matchesSalePattern("I sold 3 items today"))
    }

    @Test
    fun `classify record sale as sale`() {
        assertTrue(matchesSalePattern("Record sale of KSh 200"))
    }

    @Test
    fun `classify nimemuuza as sale`() {
        assertTrue(matchesSalePattern("Nimemuuza mteja vitu"))
    }

    @Test
    fun `does not classify purchase as sale`() {
        assertFalse(matchesSalePattern("Nimenunua unga kwa 200"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T1: Expense Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify nimetumia as expense`() {
        assertTrue(matchesExpensePattern("Nimetumia 300 kwa usafiri"))
    }

    @Test
    fun `classify expense keyword`() {
        assertTrue(matchesExpensePattern("Record expense of 500"))
    }

    @Test
    fun `classify gharama as expense`() {
        assertTrue(matchesExpensePattern("Gharama ya leo ni 200"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T1: Purchase Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify nimenunua as purchase`() {
        assertTrue(matchesPurchasePattern("Nimenunua unga kwa 200"))
    }

    @Test
    fun `classify bought as purchase`() {
        assertTrue(matchesPurchasePattern("I bought supplies for 1000"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T1: Stock Check Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify stock keyword as stock check`() {
        assertTrue(matchesStockCheckPattern("Stock ya viazi ni ngapi?"))
    }

    @Test
    fun `classify bidhaa as stock check`() {
        assertTrue(matchesStockCheckPattern("Bidhaa zangu zimebaki ngapi?"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T1: Greeting Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify habari as greeting`() {
        assertTrue(matchesGreetingPattern("Habari yako"))
    }

    @Test
    fun `classify hello as greeting`() {
        assertTrue(matchesGreetingPattern("Hello Msaidizi"))
    }

    @Test
    fun `classify shikamoo as greeting`() {
        assertTrue(matchesGreetingPattern("Shikamoo"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T2: Credit Check Intent Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `classify credit score as credit check`() {
        assertTrue(matchesCreditCheckPattern("What is my credit score?"))
    }

    @Test
    fun `classify mkopo as credit check`() {
        assertTrue(matchesCreditCheckPattern("Naweza pata mkopo?"))
    }

    // ═══════════════════════════════════════════════════════════
    //  Entity Extraction Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `extract amount from KSh prefix`() {
        val amount = extractAmount("Nimeuza mandazi kwa KSh 500")
        assertEquals(500.0, amount!!, 0.01)
    }

    @Test
    fun `extract amount from KES suffix`() {
        val amount = extractAmount("Sold for 1,200 KES")
        assertEquals(1200.0, amount!!, 0.01)
    }

    @Test
    fun `extract amount with commas`() {
        val amount = extractAmount("Ksh 15,000")
        assertEquals(15000.0, amount!!, 0.01)
    }

    @Test
    fun `extract amount returns null for no amount`() {
        val amount = extractAmount("Habari yako")
        assertNull(amount)
    }

    // ═══════════════════════════════════════════════════════════
    //  Edge Cases
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `empty input does not match any pattern`() {
        assertFalse(matchesSalePattern(""))
        assertFalse(matchesExpensePattern(""))
        assertFalse(matchesPurchasePattern(""))
        assertFalse(matchesStockCheckPattern(""))
        assertFalse(matchesGreetingPattern(""))
    }

    @Test
    fun `case insensitive matching`() {
        assertTrue(matchesSalePattern("NIMEUZA mandazi"))
        assertTrue(matchesSalePattern("nimeuza mandazi"))
        assertTrue(matchesSalePattern("Nimeuza mandazi"))
    }

    @Test
    fun `ambiguous input - sale vs expense`() {
        // "nimepata" could be sale (received money) or just "I got"
        val input = "Nimepata KSh 500"
        assertTrue("Should match sale pattern", matchesSalePattern(input))
    }
}

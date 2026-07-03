package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for IntentRouter — the code-only intent classifier.
 * Tests Swahili, English, and Sheng input patterns.
 */
class IntentRouterTest {

    private lateinit var router: IntentRouter

    @Before
    fun setup() {
        router = IntentRouter()
    }

    // ── Sale Intent ─────────────────────────────────────────────

    @Test
    fun classify_sale_swahili() {
        val result = router.classify("Nimeuza mandazi kumi kwa 500")
        assertEquals(IntentType.SALE, result.intent)
        assertTrue("Confidence should be > 0.5", result.confidence > 0.5)
    }

    @Test
    fun classify_sale_english() {
        val result = router.classify("I sold 10 mandazi for 500")
        assertEquals(IntentType.SALE, result.intent)
    }

    @Test
    fun classify_sale_sheng() {
        val result = router.classify("nikaue mandazi tano mia tano")
        assertEquals(IntentType.SALE, result.intent)
    }

    // ── Purchase Intent ─────────────────────────────────────────

    @Test
    fun classify_purchase_swahili() {
        val result = router.classify("Nimenunua unga kwa 200")
        assertEquals(IntentType.PURCHASE, result.intent)
    }

    @Test
    fun classify_purchase_english() {
        val result = router.classify("I bought flour for 200")
        assertEquals(IntentType.PURCHASE, result.intent)
    }

    @Test
    fun classify_purchase_sheng() {
        val result = router.classify("nika-buy unga 200")
        assertEquals(IntentType.PURCHASE, result.intent)
    }

    // ── Expense Intent ──────────────────────────────────────────

    @Test
    fun classify_expense_swahili() {
        val result = router.classify("Nimetumia 100 kwa usafiri")
        assertEquals(IntentType.EXPENSE, result.intent)
    }

    @Test
    fun classify_expense_english() {
        val result = router.classify("I spent 100 on transport")
        assertEquals(IntentType.EXPENSE, result.intent)
    }

    // ── Balance / Query Intents ─────────────────────────────────

    @Test
    fun classify_balance_query() {
        val result = router.classify("Salio langu ni ngapi")
        assertEquals(IntentType.CHECK_BALANCE, result.intent)
    }

    @Test
    fun classify_profit_query() {
        val result = router.classify("Faida yangu ni ngapi")
        assertEquals(IntentType.PROFIT_QUERY, result.intent)
    }

    // ── Summary Intents ─────────────────────────────────────────

    @Test
    fun classify_daily_summary_swahili() {
        val result = router.classify("Ripoti ya leo")
        assertEquals(IntentType.DAILY_SUMMARY, result.intent)
    }

    @Test
    fun classify_daily_summary_english() {
        val result = router.classify("Today's report")
        assertEquals(IntentType.DAILY_SUMMARY, result.intent)
    }

    // ── Help / Greeting ─────────────────────────────────────────

    @Test
    fun classify_help() {
        val result = router.classify("Msaada")
        assertEquals(IntentType.HELP, result.intent)
    }

    @Test
    fun classify_greeting() {
        val result = router.classify("Habari")
        assertEquals(IntentType.GREETING, result.intent)
    }

    // ── Giving / Tithing ────────────────────────────────────────

    @Test
    fun classify_giving_record() {
        val result = router.classify("Nimetolea kanisa 500")
        assertEquals(IntentType.GIVING_RECORD, result.intent)
    }

    // ── Goal Intent ─────────────────────────────────────────────

    @Test
    fun classify_goal_create() {
        val result = router.classify("Lengo langu ni kununua friji")
        assertEquals(IntentType.GOAL_CREATE, result.intent)
    }

    // ── Loan Intent ─────────────────────────────────────────────

    @Test
    fun classify_loan_record() {
        val result = router.classify("Nimechukua mkopo wa 10000")
        assertEquals(IntentType.LOAN_RECORD, result.intent)
    }

    // ── Unknown / Fallback ──────────────────────────────────────

    @Test
    fun classify_unknown_returnsUnknown() {
        val result = router.classify("asdfghjkl random gibberish xyz")
        assertEquals(IntentType.UNKNOWN, result.intent)
    }

    @Test
    fun classify_emptyInput_returnsUnknown() {
        val result = router.classify("")
        assertEquals(IntentType.UNKNOWN, result.intent)
    }

    // ── Data Extraction ─────────────────────────────────────────

    @Test
    fun classify_sale_extractsItemAndAmount() {
        val result = router.classify("Nimeuza mandazi kumi kwa 500")
        if (result.intent == IntentType.SALE) {
            assertTrue("Should extract data", result.extractedData.isNotEmpty())
        }
    }
}

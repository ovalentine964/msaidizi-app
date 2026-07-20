package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [IntentRouter] — intent classification without LLM.
 *
 * Tests pattern-based classification of Swahili/English business inputs.
 * IntentRouter handles 90%+ of user input without needing the LLM,
 * so accuracy here directly impacts latency and cost.
 */
@DisplayName("IntentRouter")
class IntentRouterTest {

    private lateinit var config: IntentPatternConfig
    private lateinit var router: IntentRouter

    // Pattern storage for mock
    private val intentPatterns = mutableMapOf<String, List<Regex>>()
    private val intentConfigs = mutableMapOf<String, IntentPatternConfig.IntentConfig>()

    @BeforeEach
    fun setup() {
        config = mockk(relaxed = true)
        router = IntentRouter(config)

        // Default: empty sheng vocabulary
        every { config.getShengVocabulary() } returns emptyMap()
        every { config.getShengAmounts() } returns emptyMap()
        every { config.getGivingTypeKeywords() } returns mapOf(
            "sadaka" to "OFFERING",
            "zaka" to "TITHE",
            "nadhiri" to "VOW"
        )

        // Setup default intent configs
        setupDefaultIntents()
    }

    private fun intentConfig(
        priority: Int,
        confidence: Double = 0.9,
        patterns: List<String>,
        isSheng: Boolean = false,
        givingTypeKeywords: Map<String, String> = emptyMap()
    ) = IntentPatternConfig.IntentConfig(
        priority = priority,
        confidence = confidence,
        isSheng = isSheng,
        needsLLM = false,
        patterns = patterns,
        keywords = emptyList(),
        responseHint = "",
        givingTypeKeywords = givingTypeKeywords
    )

    private fun setupDefaultIntents() {
        // SALE patterns (Swahili)
        intentConfigs["SALE_SW"] = intentConfig(
            priority = 10,
            patterns = listOf(
                "nimeuza\\s+(.+?)\\s+(?:kwa|sh)\\s*(?:sh)?\\s*(\\d+)",
                "nimetengeneza\\s+(.+?)\\s+(?:kwa|sh)\\s*(\\d+)"
            )
        )

        // PURCHASE patterns
        intentConfigs["PURCHASE_SW"] = intentConfig(
            priority = 20,
            patterns = listOf(
                "nimenunua\\s+(.+?)\\s+(?:kwa|sh)\\s*(?:sh)?\\s*(\\d+)"
            )
        )

        // EXPENSE patterns
        intentConfigs["EXPENSE_SW"] = intentConfig(
            priority = 30,
            patterns = listOf(
                "nimetumia\\s+(?:sh)?\\s*(\\d+)\\s+(?:kwa|kwenye)\\s+(.+)"
            )
        )

        // CHECK_BALANCE
        intentConfigs["CHECK_BALANCE"] = intentConfig(
            priority = 40,
            confidence = 0.95,
            patterns = listOf(
                "(?i)\\bsalio\\b",
                "(?i)\\bbalance\\b"
            )
        )

        // GREETING
        intentConfigs["GREETING"] = intentConfig(
            priority = 5,
            confidence = 0.95,
            patterns = listOf(
                "(?i)\\b(habari|jambo|hola|hello|hi|hey)\\b"
            )
        )

        // HELP
        intentConfigs["HELP"] = intentConfig(
            priority = 45,
            confidence = 0.9,
            patterns = listOf(
                "(?i)\\b(msaada|saidia|help|nifanye)\\b"
            )
        )

        // ASK_ADVICE
        intentConfigs["ASK_ADVICE"] = intentConfig(
            priority = 50,
            confidence = 0.85,
            patterns = listOf(
                "(?i)\\b(ushauri|nifanye nini|advice)\\b"
            )
        )

        // DAILY_SUMMARY
        intentConfigs["DAILY_SUMMARY"] = intentConfig(
            priority = 35,
            confidence = 0.9,
            patterns = listOf(
                "(?i)\\b(report ya leo|summary ya leo|daily|ripoti ya leo)\\b"
            )
        )

        // PROFIT_QUERY
        intentConfigs["PROFIT_QUERY"] = intentConfig(
            priority = 40,
            confidence = 0.9,
            patterns = listOf(
                "(?i)\\b(faida|profit)\\b"
            )
        )

        // GIVING_RECORD
        intentConfigs["GIVING_RECORD"] = intentConfig(
            priority = 55,
            confidence = 0.85,
            patterns = listOf(
                "nimetoa\\s+(.+?)\\s+(?:sh)?\\s*(\\d+)",
                "sadaka\\s+(?:sh)?\\s*(\\d+)"
            ),
            givingTypeKeywords = mapOf(
                "sadaka" to "OFFERING",
                "zaka" to "TITHE",
                "nadhiri" to "VOW"
            )
        )

        // Apply mocks
        every { config.getConfig() } returns IntentPatternConfig.ParsedConfig(
            version = "1.0.0",
            intents = intentConfigs,
            shengVocabulary = IntentPatternConfig.ShengVocabulary(
                verbs = emptyMap(),
                money = emptyMap()
            )
        )

        every { config.getIntentKeysByPriority() } returns intentConfigs.entries
            .sortedBy { it.value.priority }
            .map { it.key }

        intentConfigs.forEach { (key, intentConfig) ->
            every { config.getPatternsForIntent(key) } returns intentConfig.patterns.map { Regex(it) }
        }
    }

    // =====================================================================
    // SALE INTENT
    // =====================================================================

    @Nested
    @DisplayName("Sale classification")
    inner class SaleTests {

        @Test
        fun `classifies Swahili sale with amount`() {
            val result = router.classify("Nimeuza mandazi kwa Sh 500")
            assertEquals(IntentType.SALE, result.intent)
            assertTrue(result.confidence > 0)
        }

        @Test
        fun `classifies sale with quantity and amount`() {
            val result = router.classify("Nimeuza mandazi 10 kwa 500")
            assertEquals(IntentType.SALE, result.intent)
        }

        @Test
        fun `extracts item name from sale`() {
            val result = router.classify("Nimeuza mandazi kwa Sh 500")
            if (result.intent == IntentType.SALE) {
                assertTrue(result.extractedData.containsKey("item"))
                assertFalse(result.extractedData["item"].isNullOrBlank())
            }
        }

        @Test
        fun `extracts amount from sale`() {
            val result = router.classify("Nimeuza mandazi kwa Sh 500")
            if (result.intent == IntentType.SALE) {
                val amount = result.extractedData["amount"]?.toDoubleOrNull()
                assertNotNull(amount)
                assertTrue(amount!! > 0)
            }
        }
    }

    // =====================================================================
    // PURCHASE INTENT
    // =====================================================================

    @Nested
    @DisplayName("Purchase classification")
    inner class PurchaseTests {

        @Test
        fun `classifies Swahili purchase`() {
            val result = router.classify("Nimenunua unga kwa Sh 200")
            assertEquals(IntentType.PURCHASE, result.intent)
        }
    }

    // =====================================================================
    // EXPENSE INTENT
    // =====================================================================

    @Nested
    @DisplayName("Expense classification")
    inner class ExpenseTests {

        @Test
        fun `classifies Swahili expense with category`() {
            val result = router.classify("Nimetumia Sh 100 kwa usafiri")
            assertEquals(IntentType.EXPENSE, result.intent)
        }
    }

    // =====================================================================
    // QUERY INTENTS
    // =====================================================================

    @Nested
    @DisplayName("Query intents")
    inner class QueryTests {

        @Test
        fun `classifies balance check`() {
            val result = router.classify("Salio langu ni ngapi?")
            assertEquals(IntentType.CHECK_BALANCE, result.intent)
            assertTrue(result.confidence > 0.8)
        }

        @Test
        fun `classifies profit query`() {
            val result = router.classify("Faida yangu ni ngapi?")
            assertEquals(IntentType.PROFIT_QUERY, result.intent)
        }

        @Test
        fun `classifies daily summary request`() {
            val result = router.classify("Report ya leo")
            assertEquals(IntentType.DAILY_SUMMARY, result.intent)
        }
    }

    // =====================================================================
    // CONVERSATIONAL INTENTS
    // =====================================================================

    @Nested
    @DisplayName("Conversational intents")
    inner class ConversationTests {

        @Test
        fun `classifies greeting`() {
            val result = router.classify("Habari yako?")
            assertEquals(IntentType.GREETING, result.intent)
            assertTrue(result.confidence > 0.8)
        }

        @Test
        fun `classifies help request`() {
            val result = router.classify("Nisaidie")
            assertEquals(IntentType.HELP, result.intent)
        }
    }

    // =====================================================================
    // EDGE CASES
    // =====================================================================

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        @Test
        fun `blank input returns UNKNOWN`() {
            val result = router.classify("")
            assertEquals(IntentType.UNKNOWN, result.intent)
        }

        @Test
        fun `whitespace-only input returns UNKNOWN`() {
            val result = router.classify("   ")
            assertEquals(IntentType.UNKNOWN, result.intent)
        }

        @Test
        fun `unrecognized input returns UNKNOWN with needsLLM true`() {
            val result = router.classify("asdfghjkl qwerty")
            assertEquals(IntentType.UNKNOWN, result.intent)
            assertTrue(result.needsLLM, "Unrecognized input should flag for LLM")
        }

        @Test
        fun `case insensitive matching works`() {
            val result = router.classify("SALIO langu")
            assertEquals(IntentType.CHECK_BALANCE, result.intent)
        }
    }

    // =====================================================================
    // CONFIDENCE SCORES
    // =====================================================================

    @Nested
    @DisplayName("Confidence scoring")
    inner class ConfidenceTests {

        @Test
        fun `greeting has high confidence`() {
            val result = router.classify("Habari")
            if (result.intent == IntentType.GREETING) {
                assertTrue(result.confidence >= 0.9, "Greetings should have high confidence")
            }
        }

        @Test
        fun `unknown has zero confidence`() {
            val result = router.classify("xyzzy plugh")
            assertEquals(0.0, result.confidence)
        }
    }
}

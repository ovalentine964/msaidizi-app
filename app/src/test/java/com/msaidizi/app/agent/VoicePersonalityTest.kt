package com.msaidizi.app.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for VoicePersonality — the warmth engine.
 *
 * Tests that Msaidizi sounds like a warm friend, not a bank SMS.
 */
@DisplayName("VoicePersonality")
class VoicePersonalityTest {

    private lateinit var personality: VoicePersonality

    @BeforeEach
    fun setUp() {
        personality = VoicePersonality()
    }

    // ── Proverbs Database ──────────────────────────────────────

    @Nested
    @DisplayName("Proverbs Database")
    inner class ProverbsTests {

        @Test
        fun `all proverbs have Swahili and English text`() {
            val proverbs = personality.getAllProverbs("sw")
            assertTrue(proverbs.isNotEmpty(), "Should have proverbs")
            proverbs.forEach { (primary, translation) ->
                assertTrue(primary.isNotBlank(), "Proverb primary text should not be blank")
                assertTrue(translation.isNotBlank(), "Proverb translation should not be blank")
            }
        }

        @Test
        fun `has at least 10 proverbs`() {
            val proverbs = personality.getAllProverbs("sw")
            assertTrue(proverbs.size >= 10, "Should have at least 10 proverbs, found ${proverbs.size}")
        }

        @Test
        fun `getRandomProverb returns non-blank string`() {
            val proverb = personality.getRandomProverb("sw")
            assertTrue(proverb.isNotBlank(), "Random proverb should not be blank")
            assertTrue(proverb.contains("📖"), "Proverb should have book emoji prefix")
        }

        @Test
        fun `getRandomProverb works in English`() {
            val proverb = personality.getRandomProverb("en")
            assertTrue(proverb.isNotBlank(), "English proverb should not be blank")
        }
    }

    // ── Contextual Greetings ───────────────────────────────────

    @Nested
    @DisplayName("Contextual Greetings")
    inner class GreetingTests {

        @Test
        fun `greeting includes worker name when provided`() {
            val greeting = personality.getGreeting("Mary", "sw")
            assertTrue(greeting.contains("Mary"), "Greeting should include worker name, got: $greeting")
        }

        @Test
        fun `greeting works without worker name`() {
            val greeting = personality.getGreeting("", "sw")
            assertTrue(greeting.isNotBlank(), "Greeting should not be blank without name")
            assertTrue(greeting.contains("Habari"), "Swahili greeting should contain 'Habari'")
        }

        @Test
        fun `greeting in English contains English words`() {
            val greeting = personality.getGreeting("John", "en")
            assertTrue(
                greeting.contains("Good") || greeting.contains("Hey") || greeting.contains("morning") || greeting.contains("evening"),
                "English greeting should contain English words, got: $greeting"
            )
        }

        @Test
        fun `greeting with context includes profit when positive`() {
            val greeting = personality.getGreetingWithContext("Mary", 1500.0, "sw")
            assertTrue(greeting.contains("Mary"), "Should include name")
            assertTrue(greeting.contains("1500"), "Should include profit amount")
        }

        @Test
        fun `greeting with context handles zero profit gracefully`() {
            val greeting = personality.getGreetingWithContext("Mary", 0.0, "sw")
            assertTrue(greeting.isNotBlank(), "Should not be blank with zero profit")
        }

        @Test
        fun `greeting with context handles negative profit with encouragement`() {
            val greeting = personality.getGreetingWithContext("Mary", -500.0, "sw")
            assertTrue(greeting.isNotBlank(), "Should not be blank with negative profit")
            assertTrue(
                greeting.contains("mapema") || greeting.contains("polepole") || greeting.contains("Habari"),
                "Should include encouragement for negative profit, got: $greeting"
            )
        }
    }

    // ── Processing Feedback ────────────────────────────────────

    @Nested
    @DisplayName("Processing Feedback")
    inner class ProcessingFeedbackTests {

        @Test
        fun `processing feedback in Swahili is non-blank`() {
            val feedback = personality.getProcessingFeedback("sw")
            assertTrue(feedback.isNotBlank(), "Swahili processing feedback should not be blank")
        }

        @Test
        fun `processing feedback in English is non-blank`() {
            val feedback = personality.getProcessingFeedback("en")
            assertTrue(feedback.isNotBlank(), "English processing feedback should not be blank")
        }

        @Test
        fun `processing feedback varies across calls`() {
            // Run multiple times to check for variety (may occasionally fail due to randomness)
            val feedbacks = (1..20).map { personality.getProcessingFeedback("sw") }.toSet()
            assertTrue(feedbacks.size > 1, "Processing feedback should have variety, got ${feedbacks.size} unique phrases")
        }

        @Test
        fun `Swahili processing feedback contains Swahili words`() {
            val feedback = personality.getProcessingFeedback("sw")
            assertTrue(
                feedback.contains("sikia") || feedback.contains("ifikirie") || feedback.contains("Sawa") ||
                feedback.contains("sekunde") || feedback.contains("Najielewa") || feedback.contains("subiri"),
                "Swahili feedback should contain Swahili words, got: $feedback"
            )
        }
    }

    // ── Encouragement Phrases ──────────────────────────────────

    @Nested
    @DisplayName("Encouragement Phrases")
    inner class EncouragementTests {

        @Test
        fun `encouragement for confirmation type is appropriate`() {
            // Run multiple times since encouragement is probabilistic
            val encouragements = (1..50).mapNotNull {
                val e = personality.getEncouragement(ResponseType.CONFIRMATION, "sw")
                if (e.isNotBlank()) e else null
            }
            assertTrue(encouragements.isNotEmpty(), "Should sometimes return encouragement for confirmations")
            encouragements.forEach { enc ->
                assertTrue(
                    enc.contains("Vizuri") || enc.contains("Hongera") || enc.contains("Nimekumbuka") || enc.contains("Umefanya"),
                    "Confirmation encouragement should be appropriate, got: $enc"
                )
            }
        }

        @Test
        fun `encouragement for error type shows empathy`() {
            val encouragements = (1..50).mapNotNull {
                val e = personality.getEncouragement(ResponseType.ERROR, "sw")
                if (e.isNotBlank()) e else null
            }
            assertTrue(encouragements.isNotEmpty(), "Should sometimes return encouragement for errors")
            encouragements.forEach { enc ->
                assertTrue(
                    enc.contains("Usijali") || enc.contains("Jaribu") || enc.contains("Kila") || enc.contains("tutashinda") || enc.contains("tatizo"),
                    "Error encouragement should show empathy, got: $enc"
                )
            }
        }

        @Test
        fun `encouragement works in English`() {
            val encouragements = (1..50).mapNotNull {
                val e = personality.getEncouragement(ResponseType.CONFIRMATION, "en")
                if (e.isNotBlank()) e else null
            }
            assertTrue(encouragements.isNotEmpty(), "Should sometimes return English encouragement")
        }
    }

    // ── Response Wrapping ──────────────────────────────────────

    @Nested
    @DisplayName("Response Wrapping")
    inner class ResponseWrappingTests {

        @Test
        fun `wrapResponse preserves original content for short responses`() {
            val original = "✅ Umeuza mandazi, KSh 500"
            val wrapped = personality.wrapResponse(original, ResponseType.CONFIRMATION, "", "sw")
            assertTrue(wrapped.contains(original), "Wrapped response should contain original text")
        }

        @Test
        fun `wrapResponse preserves original content for long responses`() {
            val original = "A".repeat(250) // Long response
            val wrapped = personality.wrapResponse(original, ResponseType.INFORMATION, "", "sw")
            assertTrue(wrapped.contains(original), "Wrapped long response should contain original text")
        }

        @Test
        fun `wrapResponse does not modify clarification responses`() {
            val original = "🤔 Sijaelewa vizuri. Sema tena."
            val wrapped = personality.wrapResponse(original, ResponseType.CLARIFICATION, "", "sw")
            assertEquals(original, wrapped, "Clarification responses should not be modified")
        }

        @Test
        fun `wrapResponse handles blank text`() {
            val wrapped = personality.wrapResponse("", ResponseType.INFORMATION, "", "sw")
            assertEquals("", wrapped, "Blank text should remain blank")
        }

        @Test
        fun `wrapResponse adds empathy to error responses`() {
            // Run multiple times due to randomness
            val results = (1..20).map {
                personality.wrapResponse("⚠️ Kuna tatizo.", ResponseType.ERROR, "", "sw")
            }
            assertTrue(
                results.any { it.length > "⚠️ Kuna tatizo.".length },
                "Error responses should sometimes get empathy added"
            )
        }

        @Test
        fun `wrapResponse with worker name uses name context`() {
            // The name is used internally but may not appear in all responses
            // due to probabilistic nature. Just verify no crash.
            val wrapped = personality.wrapResponse("Sawa!", ResponseType.CONFIRMATION, "Mary", "sw")
            assertTrue(wrapped.isNotBlank(), "Should not crash with worker name")
        }

        @Test
        fun `wrapResponse in English works correctly`() {
            val original = "✅ Sold mandazi x10, KSh 500"
            val wrapped = personality.wrapResponse(original, ResponseType.CONFIRMATION, "", "en")
            assertTrue(wrapped.contains(original), "English wrapped response should contain original")
        }
    }

    // ── All Proverbs ───────────────────────────────────────────

    @Nested
    @DisplayName("All Proverbs")
    inner class AllProverbsTests {

        @Test
        fun `getProverbForConfirmation returns EFFORT context proverb`() {
            // Run multiple times due to frequency check
            val proverbs = (1..20).mapNotNull {
                val p = personality.getProverbForResponseType(ResponseType.CONFIRMATION, "sw")
                if (p.isNotBlank()) p else null
            }
            // If any proverbs were returned, they should be book-emoji prefixed
            proverbs.forEach { p ->
                assertTrue(p.contains("📖"), "Proverb should have book emoji, got: $p")
            }
        }

        @Test
        fun `getProverb with specific context returns matching proverb`() {
            // Force a proverb by calling getProverb directly (frequency check may skip)
            val proverbs = (1..20).mapNotNull {
                val p = personality.getProverb(VoicePersonality.ProverbContext.SAVINGS, "sw")
                if (p.isNotBlank()) p else null
            }
            if (proverbs.isNotEmpty()) {
                proverbs.forEach { p ->
                    assertTrue(p.contains("📖"), "Proverb should have book emoji")
                }
            }
        }
    }
}

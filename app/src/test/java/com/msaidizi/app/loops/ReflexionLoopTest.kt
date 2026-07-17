package com.msaidizi.app.loops

import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for ReflexionLoop — Self-improvement through self-critique.
 *
 * Covers:
 * - Execute with self-critique and retry
 * - Quality threshold enforcement
 * - Max retries behavior
 * - critiqueResponse (language, length, errors)
 * - critiqueTransaction (missing fields, suspicious amounts)
 * - History and metrics
 * - Edge cases (empty input, boundary scores)
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("ReflexionLoop")
class ReflexionLoopTest {

    private lateinit var loop: ReflexionLoop

    @BeforeEach
    fun setUp() {
        loop = ReflexionLoop(maxCritiqueHistory = 10)
    }

    // ── Execute with Reflexion ───────────────────────────────────

    @Nested
    @DisplayName("Execute with Reflexion")
    inner class ExecuteTests {

        @Test
        fun `execute returns result immediately when quality meets threshold`() = runTest {
            val result = loop.execute(
                task = "Generate response",
                qualityThreshold = 0.7,
                maxRetries = 2,
                critiqueFn = { Critique(score = 0.9) },
                executeFn = { "Good response" }
            )

            assertEquals("Good response", result.result)
            assertEquals(1, result.attempts)
            assertEquals(0.9, result.finalScore)
            assertTrue(result.success)
            assertEquals(1, result.critiques.size)
        }

        @Test
        fun `execute retries when quality below threshold`() = runTest {
            var attempt = 0
            val result = loop.execute(
                task = "Generate response",
                qualityThreshold = 0.7,
                maxRetries = 2,
                critiqueFn = {
                    attempt++
                    if (attempt == 1) Critique(score = 0.3, shouldRetry = true)
                    else Critique(score = 0.8)
                },
                executeFn = { "Attempt $attempt" }
            )

            assertEquals(2, result.attempts)
            assertTrue(result.success)
            assertEquals(2, result.critiques.size)
        }

        @Test
        fun `execute stops at max retries`() = runTest {
            val result = loop.execute(
                task = "Hard task",
                qualityThreshold = 0.9,
                maxRetries = 2,
                critiqueFn = { Critique(score = 0.4, shouldRetry = true) },
                executeFn = { "Low quality" }
            )

            assertEquals(3, result.attempts) // initial + 2 retries
            assertFalse(result.success)
            assertEquals(0.4, result.finalScore)
        }

        @Test
        fun `execute passes previous critique to executeFn on retry`() = runTest {
            var receivedCritique: Critique? = null
            var attempt = 0

            loop.execute(
                task = "Test",
                qualityThreshold = 0.8,
                maxRetries = 1,
                critiqueFn = {
                    attempt++
                    if (attempt == 1) Critique(score = 0.5, revisionPlan = "Add more detail")
                    else Critique(score = 0.9)
                },
                executeFn = { prevCritique ->
                    receivedCritique = prevCritique
                    "Response"
                }
            )

            // On second attempt, should receive the first critique
            assertNotNull(receivedCritique)
            assertEquals("Add more detail", receivedCritique!!.revisionPlan)
        }

        @Test
        fun `execute with zero retries stops after initial attempt`() = runTest {
            val result = loop.execute(
                task = "Quick task",
                qualityThreshold = 0.9,
                maxRetries = 0,
                critiqueFn = { Critique(score = 0.5) },
                executeFn = { "Response" }
            )

            assertEquals(1, result.attempts)
            assertFalse(result.success)
        }

        @Test
        fun `execute tracks critique history`() = runTest {
            loop.execute(
                task = "Task 1",
                qualityThreshold = 0.7,
                maxRetries = 0,
                critiqueFn = { Critique(score = 0.8) },
                executeFn = { "Result" }
            )

            assertEquals(1, loop.getCritiqueCount())
        }

        @Test
        fun `execute prunes old critiques from history`() = runTest {
            // maxCritiqueHistory = 10
            for (i in 1..12) {
                loop.execute(
                    task = "Task $i",
                    qualityThreshold = 0.5,
                    maxRetries = 0,
                    critiqueFn = { Critique(score = 0.8) },
                    executeFn = { "Result" }
                )
            }

            assertTrue(loop.getCritiqueCount() <= 10)
        }
    }

    // ── critiqueResponse ─────────────────────────────────────────

    @Nested
    @DisplayName("critiqueResponse")
    inner class CritiqueResponseTests {

        @Test
        fun `good response gets high score`() {
            val critique = loop.critiqueResponse(
                response = "Habari! Mauzo yako ya leo ni KSh 1,500. Umefanya vizuri!",
                expectedLanguage = "sw"
            )

            assertTrue(critique.score >= 0.7)
            assertFalse(critique.shouldRetry)
        }

        @Test
        fun `response with error indicators gets penalized`() {
            val critique = loop.critiqueResponse(
                response = "⚠️ Error: Could not process transaction"
            )

            assertTrue(critique.score < 0.7)
            assertTrue(critique.issues.any { it.contains("error", ignoreCase = true) })
        }

        @Test
        fun `short response gets penalized`() {
            val critique = loop.critiqueResponse(
                response = "OK",
                minLength = 10
            )

            assertTrue(critique.score < 1.0)
            assertTrue(critique.issues.any { it.contains("too short", ignoreCase = true) })
        }

        @Test
        fun `long response gets slight penalty`() {
            val longResponse = "A".repeat(3000)
            val critique = loop.critiqueResponse(
                response = longResponse,
                maxLength = 2000
            )

            assertTrue(critique.issues.any { it.contains("too long", ignoreCase = true) })
        }

        @Test
        fun `blank response gets heavy penalty`() {
            val critique = loop.critiqueResponse(response = "")

            assertTrue(critique.score <= 0.5)
            assertTrue(critique.issues.any { it.contains("empty", ignoreCase = true) })
        }

        @Test
        fun `blank response triggers retry`() {
            val critique = loop.critiqueResponse(response = "   ")

            assertTrue(critique.shouldRetry)
        }

        @Test
        fun `score is clamped between 0 and 1`() {
            // Response with multiple issues
            val critique = loop.critiqueResponse(
                response = "",  // empty: -0.5, also too short: -0.3
                minLength = 10
            )

            assertTrue(critique.score >= 0.0)
            assertTrue(critique.score <= 1.0)
        }

        @Test
        fun `revision plan contains suggestions`() {
            val critique = loop.critiqueResponse(
                response = "",
                minLength = 10
            )

            assertTrue(critique.revisionPlan.isNotBlank())
        }

        @Test
        fun `response within length limits gets no length issues`() {
            val critique = loop.critiqueResponse(
                response = "Habari yako! Mauzo ya leo ni KSh 500.",
                minLength = 10,
                maxLength = 2000
            )

            assertFalse(critique.issues.any { it.contains("too short") || it.contains("too long") })
        }
    }

    // ── critiqueTransaction ──────────────────────────────────────

    @Nested
    @DisplayName("critiqueTransaction")
    inner class CritiqueTransactionTests {

        @Test
        fun `valid transaction gets high score`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = 500.0,
                quantity = 10.0
            )

            assertTrue(critique.score >= 0.9)
            assertFalse(critique.shouldRetry)
        }

        @Test
        fun `null item gets penalty`() {
            val critique = loop.critiqueTransaction(
                item = null,
                amount = 500.0,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Missing item") })
            assertTrue(critique.score < 1.0)
        }

        @Test
        fun `blank item gets penalty`() {
            val critique = loop.critiqueTransaction(
                item = "  ",
                amount = 500.0,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Missing item") })
        }

        @Test
        fun `null amount gets heavy penalty`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = null,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Invalid or missing amount") })
            assertTrue(critique.shouldRetry)
        }

        @Test
        fun `zero amount gets penalty`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = 0.0,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Invalid or missing amount") })
        }

        @Test
        fun `negative amount gets penalty`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = -100.0,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Invalid or missing amount") })
        }

        @Test
        fun `null quantity gets minor penalty`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = 500.0,
                quantity = null
            )

            assertTrue(critique.issues.any { it.contains("Missing quantity") })
            // Should not trigger retry (only -0.1)
        }

        @Test
        fun `suspiciously high amount gets flagged`() {
            val critique = loop.critiqueTransaction(
                item = "Mandazi",
                amount = 2_000_000.0,
                quantity = 10.0
            )

            assertTrue(critique.issues.any { it.contains("Unusually high") })
            assertTrue(critique.suggestions.any { it.contains("Confirm") })
        }

        @Test
        fun `all fields null produces lowest score`() {
            val critique = loop.critiqueTransaction(
                item = null,
                amount = null,
                quantity = null
            )

            assertTrue(critique.score <= 0.3)
            assertTrue(critique.shouldRetry)
        }

        @Test
        fun `score is clamped to valid range`() {
            val critique = loop.critiqueTransaction(
                item = null,
                amount = null,
                quantity = null
            )

            assertTrue(critique.score >= 0.0)
            assertTrue(critique.score <= 1.0)
        }
    }

    // ── Critique History ─────────────────────────────────────────

    @Nested
    @DisplayName("Critique History")
    inner class HistoryTests {

        @Test
        fun `getCritiqueHistory returns recent critiques`() = runTest {
            loop.execute(
                task = "Task",
                qualityThreshold = 0.5,
                maxRetries = 0,
                critiqueFn = { Critique(score = 0.8, issues = listOf("minor issue")) },
                executeFn = { "Result" }
            )

            val history = loop.getCritiqueHistory(10)
            assertEquals(1, history.size)
            assertEquals(0.8, history[0]["score"])
        }

        @Test
        fun `getAverageScore returns zero when no critiques`() {
            assertEquals(0.0, loop.getAverageScore())
        }

        @Test
        fun `getAverageScore computes correct average`() = runTest {
            loop.execute(
                task = "Task 1",
                qualityThreshold = 0.5,
                maxRetries = 0,
                critiqueFn = { Critique(score = 0.8) },
                executeFn = { "Result 1" }
            )
            loop.execute(
                task = "Task 2",
                qualityThreshold = 0.5,
                maxRetries = 0,
                critiqueFn = { Critique(score = 0.6) },
                executeFn = { "Result 2" }
            )

            val avg = loop.getAverageScore()
            assertEquals(0.7, avg, 0.001)
        }

        @Test
        fun `getCritiqueCount starts at zero`() {
            assertEquals(0, loop.getCritiqueCount())
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `critique toMap contains all expected keys`() {
            val critique = Critique(
                score = 0.75,
                issues = listOf("issue1"),
                suggestions = listOf("suggestion1"),
                shouldRetry = false,
                revisionPlan = "Fix it"
            )

            val map = critique.toMap()
            assertTrue(map.containsKey("critiqueId"))
            assertTrue(map.containsKey("score"))
            assertTrue(map.containsKey("issues"))
            assertTrue(map.containsKey("suggestions"))
            assertTrue(map.containsKey("shouldRetry"))
            assertTrue(map.containsKey("revisionPlan"))
        }

        @Test
        fun `default critique has no issues or suggestions`() {
            val critique = Critique(score = 1.0)

            assertTrue(critique.issues.isEmpty())
            assertTrue(critique.suggestions.isEmpty())
            assertFalse(critique.shouldRetry)
        }

        @Test
        fun `execute with exact threshold score succeeds`() = runTest {
            val result = loop.execute(
                task = "Exact threshold",
                qualityThreshold = 0.7,
                maxRetries = 2,
                critiqueFn = { Critique(score = 0.7) },
                executeFn = { "Response" }
            )

            assertTrue(result.success)
            assertEquals(1, result.attempts)
        }

        @Test
        fun `execute with just below threshold retries`() = runTest {
            val result = loop.execute(
                task = "Just below",
                qualityThreshold = 0.7,
                maxRetries = 1,
                critiqueFn = { Critique(score = 0.69) },
                executeFn = { "Response" }
            )

            assertEquals(2, result.attempts) // 1 initial + 1 retry
        }
    }
}

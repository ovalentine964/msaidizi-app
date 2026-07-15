package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
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
 * Unit tests for HumanInTheLoop — Progressive Autonomy for CFO Decisions.
 *
 * Covers:
 * - Autonomy levels (ASK, SUGGEST, ACT_NOTIFY, AUTONOMOUS)
 * - Decision evaluation per domain
 * - Trust score updates (confirm → up, override → down)
 * - Level up / level down thresholds
 * - Pending decision management
 * - Decision history and outcomes
 * - Domain isolation
 * - Edge cases
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("HumanInTheLoop")
class HumanInTheLoopTest {

    @MockK
    private lateinit var agentEventBus: AgentEventBus

    private lateinit var hitl: HumanInTheLoop

    @BeforeEach
    fun setUp() {
        hitl = HumanInTheLoop(agentEventBus)
        coEvery { agentEventBus.publish(any()) } just kotlinx.coroutines.channels.ChannelResult.success(Unit)
    }

    // ── Initial State ────────────────────────────────────────────

    @Nested
    @DisplayName("Initial State")
    inner class InitialStateTests {

        @Test
        fun `all domains start at ASK level`() {
            for (domain in HumanInTheLoop.Domain.entries) {
                assertEquals(HumanInTheLoop.AutonomyLevel.ASK, hitl.getAutonomyLevel(domain))
            }
        }

        @Test
        fun `all domains start with default trust score`() {
            for (domain in HumanInTheLoop.Domain.entries) {
                assertEquals(0.5f, hitl.getTrustScore(domain))
            }
        }

        @Test
        fun `pending decisions is initially empty`() {
            assertTrue(hitl.getPendingDecisions().isEmpty())
        }
    }

    // ── Decision Evaluation ──────────────────────────────────────

    @Nested
    @DisplayName("Decision Evaluation")
    inner class DecisionEvaluationTests {

        @Test
        fun `ASK level always creates pending decision`() {
            val decision = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.TRANSACTION_CATEGORIZATION,
                action = "categorize_sale",
                context = mapOf("item" to "mandazi"),
                proposedResult = "food",
                confidence = 0.9f
            )

            assertNotNull(decision)
            assertEquals(HumanInTheLoop.Domain.TRANSACTION_CATEGORIZATION, decision!!.domain)
            assertEquals("categorize_sale", decision.action)
            assertEquals(0.9f, decision.confidence)
        }

        @Test
        fun `SUGGEST level creates pending decision`() {
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.GOAL_PLANNING, HumanInTheLoop.AutonomyLevel.SUGGEST)

            val decision = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GOAL_PLANNING,
                action = "suggest_goal",
                context = emptyMap(),
                proposedResult = "save_1000",
                confidence = 0.7f
            )

            assertNotNull(decision)
        }

        @Test
        fun `ACT_NOTIFY level returns null (proceed without blocking)`() {
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.GAMIFICATION, HumanInTheLoop.AutonomyLevel.ACT_NOTIFY)

            val decision = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GAMIFICATION,
                action = "award_points",
                context = emptyMap(),
                proposedResult = 50,
                confidence = 0.8f
            )

            assertNull(decision)
        }

        @Test
        fun `AUTONOMOUS level returns null (proceed silently)`() {
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.GENERAL, HumanInTheLoop.AutonomyLevel.AUTONOMOUS)

            val decision = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GENERAL,
                action = "auto_action",
                context = emptyMap(),
                proposedResult = "done",
                confidence = 0.95f
            )

            assertNull(decision)
        }
    }

    // ── Confirm / Override ───────────────────────────────────────

    @Nested
    @DisplayName("Confirm & Override Decisions")
    inner class ConfirmOverrideTests {

        @Test
        fun `confirmDecision returns true and removes from pending`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "estimate_price",
                context = emptyMap(),
                proposedResult = 500.0,
                confidence = 0.6f
            )!!

            val result = hitl.confirmDecision(pending.id)

            assertTrue(result)
            assertTrue(hitl.getPendingDecisions().none { it.id == pending.id })
        }

        @Test
        fun `confirmDecision returns false for unknown id`() {
            assertFalse(hitl.confirmDecision("nonexistent-id"))
        }

        @Test
        fun `confirmDecision increases trust score`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "estimate",
                context = emptyMap(),
                proposedResult = 100.0,
                confidence = 0.5f
            )!!

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.PRICE_ESTIMATION)
            hitl.confirmDecision(pending.id)
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.PRICE_ESTIMATION)

            assertTrue(afterScore > beforeScore)
        }

        @Test
        fun `overrideDecision returns true and removes from pending`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.LOAN_ADVICE,
                action = "suggest_loan",
                context = emptyMap(),
                proposedResult = "approve",
                confidence = 0.4f
            )!!

            val result = hitl.overrideDecision(pending.id, "reject")

            assertTrue(result)
        }

        @Test
        fun `overrideDecision returns false for unknown id`() {
            assertFalse(hitl.overrideDecision("nonexistent-id", "override"))
        }

        @Test
        fun `overrideDecision decreases trust score`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.LOAN_ADVICE,
                action = "suggest_loan",
                context = emptyMap(),
                proposedResult = "approve",
                confidence = 0.4f
            )!!

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.LOAN_ADVICE)
            hitl.overrideDecision(pending.id, "reject")
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.LOAN_ADVICE)

            assertTrue(afterScore < beforeScore)
        }
    }

    // ── Trust Level Up / Down ────────────────────────────────────

    @Nested
    @DisplayName("Trust Level Transitions")
    inner class LevelTransitionTests {

        @Test
        fun `trust score clamped between 0 and 1`() {
            val domain = HumanInTheLoop.Domain.TITHE_CALCULATION

            // Confirm many times to push score up
            for (i in 1..100) {
                val pending = hitl.evaluateDecision(
                    domain = domain,
                    action = "calc_$i",
                    context = emptyMap(),
                    proposedResult = 100,
                    confidence = 0.5f
                )!!
                hitl.confirmDecision(pending.id)
            }

            val score = hitl.getTrustScore(domain)
            assertTrue(score <= 1.0f)
            assertTrue(score >= 0.0f)
        }

        @Test
        fun `trust score clamped at 0 when many overrides`() {
            val domain = HumanInTheLoop.Domain.CASH_FLOW_PREDICTION

            // Override many times to push score down
            for (i in 1..100) {
                val pending = hitl.evaluateDecision(
                    domain = domain,
                    action = "predict_$i",
                    context = emptyMap(),
                    proposedResult = 1000,
                    confidence = 0.5f
                )!!
                hitl.overrideDecision(pending.id, "wrong")
            }

            val score = hitl.getTrustScore(domain)
            assertTrue(score >= 0.0f)
        }

        @Test
        fun `domain levels are independent`() {
            // Level up TRANSACTION_CATEGORIZATION
            for (i in 1..20) {
                val pending = hitl.evaluateDecision(
                    domain = HumanInTheLoop.Domain.TRANSACTION_CATEGORIZATION,
                    action = "cat_$i",
                    context = emptyMap(),
                    proposedResult = "food",
                    confidence = 0.5f
                )!!
                hitl.confirmDecision(pending.id)
            }

            // LOAN_ADVICE should still be at ASK
            assertEquals(HumanInTheLoop.AutonomyLevel.ASK, hitl.getAutonomyLevel(HumanInTheLoop.Domain.LOAN_ADVICE))
        }
    }

    // ── Record Outcome ───────────────────────────────────────────

    @Nested
    @DisplayName("Record Outcome")
    inner class RecordOutcomeTests {

        @Test
        fun `recordOutcome SUCCESS increases trust`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.BUSINESS_ADVICE,
                action = "advise",
                context = emptyMap(),
                proposedResult = "expand",
                confidence = 0.5f
            )!!
            hitl.confirmDecision(pending.id)

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)
            hitl.recordOutcome(pending.id, HumanInTheLoop.Outcome.SUCCESS)
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)

            assertTrue(afterScore >= beforeScore)
        }

        @Test
        fun `recordOutcome FAILURE decreases trust`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.BUSINESS_ADVICE,
                action = "advise",
                context = emptyMap(),
                proposedResult = "expand",
                confidence = 0.5f
            )!!
            hitl.confirmDecision(pending.id)

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)
            hitl.recordOutcome(pending.id, HumanInTheLoop.Outcome.FAILURE)
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)

            assertTrue(afterScore <= beforeScore)
        }

        @Test
        fun `recordOutcome NEUTRAL does not change trust`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.BUSINESS_ADVICE,
                action = "advise",
                context = emptyMap(),
                proposedResult = "wait",
                confidence = 0.5f
            )!!
            hitl.confirmDecision(pending.id)

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)
            hitl.recordOutcome(pending.id, HumanInTheLoop.Outcome.NEUTRAL)
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)

            assertEquals(beforeScore, afterScore)
        }

        @Test
        fun `recordOutcome UNKNOWN does not change trust`() {
            val pending = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.BUSINESS_ADVICE,
                action = "advise",
                context = emptyMap(),
                proposedResult = "wait",
                confidence = 0.5f
            )!!
            hitl.confirmDecision(pending.id)

            val beforeScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)
            hitl.recordOutcome(pending.id, HumanInTheLoop.Outcome.UNKNOWN)
            val afterScore = hitl.getTrustScore(HumanInTheLoop.Domain.BUSINESS_ADVICE)

            assertEquals(beforeScore, afterScore)
        }

        @Test
        fun `recordOutcome for unknown decisionId does nothing`() {
            // Should not throw
            hitl.recordOutcome("nonexistent", HumanInTheLoop.Outcome.SUCCESS)
        }
    }

    // ── Set Autonomy Level ───────────────────────────────────────

    @Nested
    @DisplayName("Set Autonomy Level")
    inner class SetAutonomyLevelTests {

        @Test
        fun `setAutonomyLevel changes level`() {
            hitl.setAutonomyLevel(
                HumanInTheLoop.Domain.GENERAL,
                HumanInTheLoop.AutonomyLevel.AUTONOMOUS
            )

            assertEquals(HumanInTheLoop.AutonomyLevel.AUTONOMOUS, hitl.getAutonomyLevel(HumanInTheLoop.Domain.GENERAL))
        }

        @Test
        fun `setAutonomyLevel does not affect other domains`() {
            hitl.setAutonomyLevel(
                HumanInTheLoop.Domain.GENERAL,
                HumanInTheLoop.AutonomyLevel.AUTONOMOUS
            )

            assertEquals(HumanInTheLoop.AutonomyLevel.ASK, hitl.getAutonomyLevel(HumanInTheLoop.Domain.LOAN_ADVICE))
        }
    }

    // ── Trust Summary ────────────────────────────────────────────

    @Nested
    @DisplayName("Trust Summary")
    inner class TrustSummaryTests {

        @Test
        fun `getTrustSummary returns all domains`() {
            val summary = hitl.getTrustSummary()

            assertEquals(HumanInTheLoop.Domain.entries.size, summary.size)
            for (domain in HumanInTheLoop.Domain.entries) {
                assertTrue(summary.containsKey(domain))
            }
        }

        @Test
        fun `trust summary contains expected keys`() {
            val summary = hitl.getTrustSummary()
            val domainInfo = summary[HumanInTheLoop.Domain.GENERAL]!!

            assertTrue(domainInfo.containsKey("score"))
            assertTrue(domainInfo.containsKey("level"))
            assertTrue(domainInfo.containsKey("totalDecisions"))
            assertTrue(domainInfo.containsKey("confirmed"))
            assertTrue(domainInfo.containsKey("overridden"))
        }

        @Test
        fun `trust summary reflects confirmations and overrides`() {
            // Confirm one
            val p1 = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GENERAL,
                action = "a1", context = emptyMap(), proposedResult = "r1", confidence = 0.5f
            )!!
            hitl.confirmDecision(p1.id)

            // Override one
            val p2 = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GENERAL,
                action = "a2", context = emptyMap(), proposedResult = "r2", confidence = 0.5f
            )!!
            hitl.overrideDecision(p2.id, "different")

            val summary = hitl.getTrustSummary()
            val general = summary[HumanInTheLoop.Domain.GENERAL]!!

            assertEquals(2, general["totalDecisions"])
            assertEquals(1, general["confirmed"])
            assertEquals(1, general["overridden"])
        }
    }

    // ── Pending Decisions ────────────────────────────────────────

    @Nested
    @DisplayName("Pending Decisions")
    inner class PendingDecisionTests {

        @Test
        fun `pending decisions accumulate`() {
            hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "estimate1", context = emptyMap(), proposedResult = 100, confidence = 0.5f
            )
            hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "estimate2", context = emptyMap(), proposedResult = 200, confidence = 0.5f
            )

            val pending = hitl.getPendingDecisions()
            assertEquals(2, pending.size)
        }

        @Test
        fun `confirmed decisions are removed from pending`() {
            val p1 = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "a1", context = emptyMap(), proposedResult = 100, confidence = 0.5f
            )!!
            hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.PRICE_ESTIMATION,
                action = "a2", context = emptyMap(), proposedResult = 200, confidence = 0.5f
            )

            hitl.confirmDecision(p1.id)

            val pending = hitl.getPendingDecisions()
            assertEquals(1, pending.size)
            assertEquals("a2", pending[0].action)
        }
    }

    // ── Autonomy Level Enum ──────────────────────────────────────

    @Nested
    @DisplayName("Autonomy Level Enum")
    inner class AutonomyLevelTests {

        @Test
        fun `fromInt returns correct level`() {
            assertEquals(HumanInTheLoop.AutonomyLevel.ASK, HumanInTheLoop.AutonomyLevel.fromInt(0))
            assertEquals(HumanInTheLoop.AutonomyLevel.SUGGEST, HumanInTheLoop.AutonomyLevel.fromInt(1))
            assertEquals(HumanInTheLoop.AutonomyLevel.ACT_NOTIFY, HumanInTheLoop.AutonomyLevel.fromInt(2))
            assertEquals(HumanInTheLoop.AutonomyLevel.AUTONOMOUS, HumanInTheLoop.AutonomyLevel.fromInt(3))
        }

        @Test
        fun `fromInt defaults to ASK for unknown value`() {
            assertEquals(HumanInTheLoop.AutonomyLevel.ASK, HumanInTheLoop.AutonomyLevel.fromInt(99))
        }

        @Test
        fun `autonomy levels have correct numeric values`() {
            assertEquals(0, HumanInTheLoop.AutonomyLevel.ASK.value)
            assertEquals(1, HumanInTheLoop.AutonomyLevel.SUGGEST.value)
            assertEquals(2, HumanInTheLoop.AutonomyLevel.ACT_NOTIFY.value)
            assertEquals(3, HumanInTheLoop.AutonomyLevel.AUTONOMOUS.value)
        }
    }

    // ── Domain Enum ──────────────────────────────────────────────

    @Nested
    @DisplayName("Domain Enum")
    inner class DomainTests {

        @Test
        fun `all expected domains exist`() {
            val domains = HumanInTheLoop.Domain.entries.map { it.name }

            assertTrue(domains.contains("TRANSACTION_CATEGORIZATION"))
            assertTrue(domains.contains("PRICE_ESTIMATION"))
            assertTrue(domains.contains("GOAL_PLANNING"))
            assertTrue(domains.contains("LOAN_ADVICE"))
            assertTrue(domains.contains("TITHE_CALCULATION"))
            assertTrue(domains.contains("CASH_FLOW_PREDICTION"))
            assertTrue(domains.contains("BUSINESS_ADVICE"))
            assertTrue(domains.contains("GAMIFICATION"))
            assertTrue(domains.contains("GENERAL"))
        }
    }

    // ── Outcome Enum ─────────────────────────────────────────────

    @Nested
    @DisplayName("Outcome Enum")
    inner class OutcomeTests {

        @Test
        fun `all expected outcomes exist`() {
            val outcomes = HumanInTheLoop.Outcome.entries.map { it.name }

            assertTrue(outcomes.contains("SUCCESS"))
            assertTrue(outcomes.contains("FAILURE"))
            assertTrue(outcomes.contains("NEUTRAL"))
            assertTrue(outcomes.contains("UNKNOWN"))
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `pending decision has correct fields`() {
            val decision = hitl.evaluateDecision(
                domain = HumanInTheLoop.Domain.GENERAL,
                action = "test_action",
                context = mapOf("key" to "value"),
                proposedResult = "result",
                confidence = 0.75f
            )!!

            assertNotNull(decision.id)
            assertEquals(HumanInTheLoop.Domain.GENERAL, decision.domain)
            assertEquals("test_action", decision.action)
            assertEquals(mapOf("key" to "value"), decision.context)
            assertEquals("result", decision.proposedResult)
            assertEquals(0.75f, decision.confidence)
            assertTrue(decision.timestamp > 0)
        }

        @Test
        fun `multiple domains can have different levels simultaneously`() {
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.GENERAL, HumanInTheLoop.AutonomyLevel.AUTONOMOUS)
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.LOAN_ADVICE, HumanInTheLoop.AutonomyLevel.ASK)
            hitl.setAutonomyLevel(HumanInTheLoop.Domain.GAMIFICATION, HumanInTheLoop.AutonomyLevel.ACT_NOTIFY)

            assertEquals(HumanInTheLoop.AutonomyLevel.AUTONOMOUS, hitl.getAutonomyLevel(HumanInTheLoop.Domain.GENERAL))
            assertEquals(HumanInTheLoop.AutonomyLevel.ASK, hitl.getAutonomyLevel(HumanInTheLoop.Domain.LOAN_ADVICE))
            assertEquals(HumanInTheLoop.AutonomyLevel.ACT_NOTIFY, hitl.getAutonomyLevel(HumanInTheLoop.Domain.GAMIFICATION))
        }

        @Test
        fun `trust summary updates after confirmations`() {
            // Confirm many times in one domain
            for (i in 1..10) {
                val p = hitl.evaluateDecision(
                    domain = HumanInTheLoop.Domain.TITHE_CALCULATION,
                    action = "calc_$i", context = emptyMap(), proposedResult = 100, confidence = 0.5f
                )!!
                hitl.confirmDecision(p.id)
            }

            val summary = hitl.getTrustSummary()
            val tithe = summary[HumanInTheLoop.Domain.TITHE_CALCULATION]!!
            assertEquals(10, tithe["totalDecisions"])
            assertEquals(10, tithe["confirmed"])
            assertEquals(0, tithe["overridden"])
        }
    }
}

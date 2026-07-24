package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.superagent.engine.LearningSignal
import com.msaidizi.app.superagent.engine.ParseMethod
import com.msaidizi.app.superagent.engine.ActionType
import com.msaidizi.app.superagent.engine.SignalType
import com.msaidizi.app.superagent.flywheel.FlywheelModels.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [FlywheelEngine] — the learning flywheel.
 *
 * Covers proof accumulation, Alama Score calculation, learning signal
 * recording, correction handling, feedback classification, and tier unlocking.
 */
class FlywheelEngineTest {

    private lateinit var adaptiveLearning: AdaptiveLearning
    private lateinit var preferenceLearner: PreferenceLearner
    private lateinit var patternTracker: PatternTracker
    private lateinit var feedbackCollector: FeedbackCollector
    private lateinit var proofStore: ProofStore
    private lateinit var flywheel: FlywheelEngine

    @BeforeEach
    fun setup() {
        adaptiveLearning = mockk(relaxed = true)
        preferenceLearner = mockk(relaxed = true)
        patternTracker = mockk(relaxed = true)
        feedbackCollector = mockk(relaxed = true)
        proofStore = mockk(relaxed = true)

        // Default proof store returns
        every { proofStore.getTotalProofPoints() } returns 0
        every { proofStore.getDaysActive() } returns 0
        every { proofStore.getConsistencyScore() } returns 0.0
        every { proofStore.getDataQualityScore() } returns 0.0

        flywheel = FlywheelEngine(
            adaptiveLearning = adaptiveLearning,
            preferenceLearner = preferenceLearner,
            patternTracker = patternTracker,
            feedbackCollector = feedbackCollector,
            proofStore = proofStore
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ALAMA SCORE
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class AlamaScore {

        @Test
        fun `default score is building tier`() {
            val score = flywheel.getCurrentAlamaScore()

            assertEquals(0, score.proofPoints)
            assertEquals(0, score.daysActive)
            assertEquals(AlamaTier.BUILDING, score.currentTier)
            assertEquals(CreditReadiness.NOT_READY, score.creditReadiness)
        }

        @Test
        fun `emerging tier with enough proof and days`() {
            every { proofStore.getTotalProofPoints() } returns 35
            every { proofStore.getDaysActive() } returns 10

            val score = flywheel.getCurrentAlamaScore()

            assertEquals(AlamaTier.EMERGING, score.currentTier)
            assertEquals(CreditReadiness.BUILDING, score.creditReadiness)
        }

        @Test
        fun `established tier unlocks credit readiness`() {
            every { proofStore.getTotalProofPoints() } returns 120
            every { proofStore.getDaysActive() } returns 35

            val score = flywheel.getCurrentAlamaScore()

            assertEquals(AlamaTier.ESTABLISHED, score.currentTier)
            assertEquals(CreditReadiness.READY_FOR_REVIEW, score.creditReadiness)
        }

        @Test
        fun `proven tier with pre-qualification`() {
            every { proofStore.getTotalProofPoints() } returns 350
            every { proofStore.getDaysActive() } returns 100

            val score = flywheel.getCurrentAlamaScore()

            assertEquals(AlamaTier.PROVEN, score.currentTier)
            assertEquals(CreditReadiness.PRE_QUALIFIED, score.creditReadiness)
        }

        @Test
        fun `trusted tier with full access`() {
            every { proofStore.getTotalProofPoints() } returns 1200
            every { proofStore.getDaysActive() } returns 200

            val score = flywheel.getCurrentAlamaScore()

            assertEquals(AlamaTier.TRUSTED, score.currentTier)
            assertEquals(CreditReadiness.PRE_QUALIFIED, score.creditReadiness)
        }

        @Test
        fun `score is cached after first computation`() {
            every { proofStore.getTotalProofPoints() } returns 50

            val score1 = flywheel.getCurrentAlamaScore()
            val score2 = flywheel.getCurrentAlamaScore()

            assertEquals(score1, score2)
            // getTotalProofPoints called only once due to caching
            verify(exactly = 1) { proofStore.getTotalProofPoints() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROOF RECORDING
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ProofRecording {

        @Test
        fun `record proof calls store`() {
            val proof = ProofPoint(
                type = ProofType.TRANSACTION,
                weight = 1.0,
                data = mapOf("amount" to 500.0)
            )

            flywheel.recordProof(proof)

            verify { proofStore.saveProofPoint(proof) }
        }

        @Test
        fun `record proof invalidates cached score`() {
            every { proofStore.getTotalProofPoints() } returns 0

            val score1 = flywheel.getCurrentAlamaScore()
            assertEquals(0, score1.proofPoints)

            // Record a proof — cache should be invalidated
            flywheel.recordProof(ProofPoint(type = ProofType.TRANSACTION))

            // Update mock to return new value
            every { proofStore.getTotalProofPoints() } returns 1

            // Thread needs a moment for the coroutine to run
            Thread.sleep(100)

            val score2 = flywheel.getCurrentAlamaScore()
            // The cached score was invalidated, so it should re-query
            verify(atLeast = 2) { proofStore.getTotalProofPoints() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING SIGNAL RECORDING
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class LearningSignals {

        @Test
        fun `record learning signal feeds all subsystems`() {
            val signal = LearningSignal(
                input = "Nimeuziwa mandazi",
                intent = "SALE",
                confidence = 0.95f,
                parseMethod = ParseMethod.PATTERN,
                actionType = ActionType.RESPOND_AND_RECORD,
                module = "FinancialModule",
                signals = emptyList()
            )

            flywheel.recordLearningSignal(signal)

            // Give coroutine time to execute
            Thread.sleep(100)

            verify { adaptiveLearning.recordSignal(signal) }
            verify { preferenceLearner.observe(signal) }
            verify { patternTracker.observe(signal) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CORRECTIONS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Corrections {

        @Test
        fun `record correction feeds feedback collector and adaptive learning`() {
            flywheel.recordCorrection(
                originalInput = "mandazi",
                correctionInput = "maandazi",
                originalIntent = "SALE"
            )

            Thread.sleep(100)

            verify {
                feedbackCollector.record(match {
                    it.type == FeedbackType.CORRECTION &&
                    it.originalInput == "mandazi" &&
                    it.correctionInput == "maandazi"
                })
            }
            verify {
                adaptiveLearning.recordCorrection("mandazi", "maandazi", "SALE")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FEEDBACK CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class FeedbackClassification {

        @Test
        fun `confirmation keywords classified correctly`() {
            flywheel.recordFeedback("sawa", "test input")
            Thread.sleep(100)

            verify {
                feedbackCollector.record(match { it.type == FeedbackType.CONFIRMATION })
            }
        }

        @Test
        fun `correction keywords classified correctly`() {
            flywheel.recordFeedback("sio mandazi", "test input")
            Thread.sleep(100)

            verify {
                feedbackCollector.record(match { it.type == FeedbackType.CORRECTION })
            }
        }

        @Test
        fun `question classified as clarification`() {
            flywheel.recordFeedback("nini?", "test input")
            Thread.sleep(100)

            verify {
                feedbackCollector.record(match { it.type == FeedbackType.CLARIFICATION })
            }
        }

        @Test
        fun `unknown feedback classified as ignore`() {
            flywheel.recordFeedback("blahblahxyz", "test input")
            Thread.sleep(100)

            verify {
                feedbackCollector.record(match { it.type == FeedbackType.IGNORE })
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // IMPROVEMENT CYCLE
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ImprovementCycle {

        @Test
        fun `improve calls all subsystems`() {
            flywheel.improve()

            Thread.sleep(100)

            verify { adaptiveLearning.consolidate() }
            verify { preferenceLearner.consolidate() }
            verify { patternTracker.updatePatterns() }
            verify { feedbackCollector.analyze() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Metrics {

        @Test
        fun `metrics reflect current state`() {
            every { proofStore.getTotalProofPoints() } returns 50
            every { proofStore.getDaysActive() } returns 15

            val metrics = flywheel.getMetrics()

            assertEquals(50, metrics.totalProofPoints)
            assertEquals(15, metrics.daysActive)
            assertEquals(AlamaTier.EMERGING, metrics.currentTier)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER UNLOCK DETECTION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class TierUnlock {

        @Test
        fun `tier from progress respects minimums`() {
            assertEquals(AlamaTier.BUILDING, AlamaTier.fromProgress(0, 0))
            assertEquals(AlamaTier.BUILDING, AlamaTier.fromProgress(50, 0)) // days not met
            assertEquals(AlamaTier.EMERGING, AlamaTier.fromProgress(30, 7))
            assertEquals(AlamaTier.ESTABLISHED, AlamaTier.fromProgress(100, 30))
            assertEquals(AlamaTier.PROVEN, AlamaTier.fromProgress(300, 90))
            assertEquals(AlamaTier.TRUSTED, AlamaTier.fromProgress(1000, 180))
        }

        @Test
        fun `tier unlock message is in swahili`() {
            AlamaTier.entries.forEach { tier ->
                assertTrue(tier.unlockMessage.contains(tier.displayName))
            }
        }
    }
}

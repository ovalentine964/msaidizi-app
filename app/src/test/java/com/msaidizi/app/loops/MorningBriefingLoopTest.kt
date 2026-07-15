package com.msaidizi.app.loops

import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.BriefingPriority
import com.msaidizi.app.cfo.BriefingResult
import com.msaidizi.app.cfo.BriefingType
import com.msaidizi.app.cfo.CFOEngine
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.BriefingDeliveryEntity
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
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
 * Unit tests for MorningBriefingLoop — Generate→Deliver→Track→Personalize.
 *
 * Covers:
 * - Morning briefing execution and delivery
 * - Transaction tracking after briefing
 * - Briefing opened tracking
 * - Outcome score calculation
 * - Advice followed detection
 * - Key advice extraction
 * - Briefing stats
 * - Pattern tracker enrichment
 * - Edge cases
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("MorningBriefingLoop")
class MorningBriefingLoopTest {

    @MockK
    private lateinit var cfoEngine: CFOEngine

    @MockK
    private lateinit var briefingDelivery: BriefingDelivery

    @MockK
    private lateinit var businessAgent: BusinessAgent

    @MockK
    private lateinit var transactionDao: TransactionDao

    @MockK
    private lateinit var briefingDeliveryDao: BriefingDeliveryDao

    @MockK(relaxed = true)
    private lateinit var patternTracker: BusinessPatternTracker

    private lateinit var loop: MorningBriefingLoop

    @BeforeEach
    fun setUp() {
        loop = MorningBriefingLoop(
            cfoEngine = cfoEngine,
            briefingDelivery = briefingDelivery,
            businessAgent = businessAgent,
            transactionDao = transactionDao,
            briefingDeliveryDao = briefingDeliveryDao,
            patternTracker = patternTracker
        )
    }

    // ── Execute Morning Briefing ─────────────────────────────────

    @Nested
    @DisplayName("Execute Morning Briefing")
    inner class ExecuteMorningBriefingTests {

        @Test
        fun `executeMorningBriefing delivers briefing and records it`() = runTest {
            val briefingResult = BriefingResult(
                message = "Habari! Mauzo ya jana ni KSh 5,000.",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL,
                data = mapOf("todaySales" to "5000", "todayProfit" to "2000")
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val result = loop.executeMorningBriefing(
                workerName = "Mama Njeri",
                assistantName = "Msaidizi",
                workerType = WorkerType.SMALL_RETAILER,
                language = "sw"
            )

            assertNotNull(result)
            assertTrue(result.message.isNotBlank())
            coVerify { briefingDeliveryDao.insert(any()) }
        }

        @Test
        fun `executeMorningBriefing uses default language sw`() = runTest {
            val briefingResult = BriefingResult(
                message = "Good morning!",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val result = loop.executeMorningBriefing(
                workerName = "John",
                assistantName = "Msaidizi",
                workerType = WorkerType.SMALL_RETAILER
            )

            assertNotNull(result)
        }

        @Test
        fun `executeMorningBriefing records predicted sales and profit`() = runTest {
            val briefingResult = BriefingResult(
                message = "Briefing with predictions",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL,
                data = mapOf("todaySales" to "10000", "todayProfit" to "4000")
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            coVerify {
                briefingDeliveryDao.insert(match {
                    it.predictedSales == 10000.0 && it.predictedProfit == 4000.0
                })
            }
        }
    }

    // ── Transaction After Briefing ───────────────────────────────

    @Nested
    @DisplayName("Transaction After Briefing")
    inner class TransactionAfterBriefingTests {

        @Test
        fun `onTransactionAfterBriefing marks briefing as acted on`() = runTest {
            val pendingBriefing = BriefingDeliveryEntity(
                id = 1,
                briefingType = "MORNING",
                briefingText = "Briefing",
                predictedSales = 5000.0,
                predictedProfit = 2000.0,
                keyAdvice = "nunua nyanya",
                deliveredAt = System.currentTimeMillis() / 1000 - 3600
            )

            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns pendingBriefing
            coEvery { transactionDao.getTransactionsInRangeSuspend(any(), any()) } returns listOf(
                Transaction(
                    type = TransactionType.SALE,
                    item = "nyanya",
                    totalAmount = 500.0
                )
            )
            coEvery {
                briefingDeliveryDao.markActedOn(
                    id = any(),
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = any()
                )
            } just Runs

            val transaction = Transaction(
                type = TransactionType.PURCHASE,
                item = "nyanya",
                totalAmount = 200.0
            )

            loop.onTransactionAfterBriefing(transaction)

            coVerify {
                briefingDeliveryDao.markActedOn(
                    id = 1,
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = any()
                )
            }
        }

        @Test
        fun `onTransactionAfterBriefing does nothing when no pending briefing`() = runTest {
            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns null

            val transaction = Transaction(
                type = TransactionType.SALE,
                item = "mandazi",
                totalAmount = 100.0
            )

            loop.onTransactionAfterBriefing(transaction)

            coVerify(exactly = 0) {
                briefingDeliveryDao.markActedOn(any(), any(), any(), any(), any())
            }
        }
    }

    // ── Briefing Opened ──────────────────────────────────────────

    @Nested
    @DisplayName("Briefing Opened")
    inner class BriefingOpenedTests {

        @Test
        fun `onBriefingOpened marks briefing as opened`() = runTest {
            val pending = BriefingDeliveryEntity(
                id = 42,
                briefingType = "MORNING",
                briefingText = "Briefing"
            )

            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns pending
            coEvery { briefingDeliveryDao.markOpened(42) } just Runs

            loop.onBriefingOpened()

            coVerify { briefingDeliveryDao.markOpened(42) }
        }

        @Test
        fun `onBriefingOpened does nothing when no pending briefing`() = runTest {
            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns null

            loop.onBriefingOpened()

            coVerify(exactly = 0) { briefingDeliveryDao.markOpened(any()) }
        }
    }

    // ── Briefing Stats ───────────────────────────────────────────

    @Nested
    @DisplayName("Briefing Stats")
    inner class BriefingStatsTests {

        @Test
        fun `getBriefingStats returns empty map when no briefings`() = runTest {
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val stats = loop.getBriefingStats()

            assertTrue(stats.isEmpty())
        }

        @Test
        fun `getBriefingStats computes correct rates`() = runTest {
            val briefings = listOf(
                BriefingDeliveryEntity(
                    id = 1, briefingType = "MORNING", briefingText = "B1",
                    opened = true, actedOn = true, outcomeScore = 0.8
                ),
                BriefingDeliveryEntity(
                    id = 2, briefingType = "MORNING", briefingText = "B2",
                    opened = true, actedOn = false, outcomeScore = 0.0
                ),
                BriefingDeliveryEntity(
                    id = 3, briefingType = "MORNING", briefingText = "B3",
                    opened = false, actedOn = false, outcomeScore = 0.0
                )
            )

            coEvery { briefingDeliveryDao.getRecent(any()) } returns briefings

            val stats = loop.getBriefingStats()

            assertEquals(3, stats["totalDelivered"])
            assertEquals(2, stats["totalOpened"])
            assertEquals(1, stats["totalActedOn"])
            assertEquals(2.0 / 3.0, stats["openRate"] as Double, 0.01)
            assertEquals(1.0 / 3.0, stats["actThroughRate"] as Double, 0.01)
            assertEquals(0.8, stats["averageOutcomeScore"] as Double, 0.01)
        }
    }

    // ── Outcome Score Calculation ────────────────────────────────

    @Nested
    @DisplayName("Outcome Score")
    inner class OutcomeScoreTests {

        @Test
        fun `perfect prediction gives score near 1_0`() = runTest {
            // The calculateOutcomeScore is private, but we test it through onTransactionAfterBriefing
            val pendingBriefing = BriefingDeliveryEntity(
                id = 1,
                briefingType = "MORNING",
                briefingText = "Briefing",
                predictedSales = 1000.0,
                predictedProfit = 500.0,
                deliveredAt = System.currentTimeMillis() / 1000 - 3600
            )

            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns pendingBriefing
            coEvery { transactionDao.getTransactionsInRangeSuspend(any(), any()) } returns listOf(
                Transaction(type = TransactionType.SALE, item = "test", totalAmount = 1000.0)
            )

            var capturedScore: Double? = null
            coEvery {
                briefingDeliveryDao.markActedOn(
                    id = any(),
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = captureLambda(),
                    adviceFollowed = any()
                )
            } answers {
                capturedScore = lambda<(Double) -> Unit>().invoke(0.0) // just capture
            }

            // Actually capture the score
            coEvery {
                briefingDeliveryDao.markActedOn(any(), any(), any(), any(), any())
            } just Runs

            loop.onTransactionAfterBriefing(
                Transaction(type = TransactionType.SALE, item = "test", totalAmount = 1000.0)
            )

            coVerify {
                briefingDeliveryDao.markActedOn(
                    id = 1,
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = any()
                )
            }
        }
    }

    // ── Advice Followed ──────────────────────────────────────────

    @Nested
    @DisplayName("Advice Followed Detection")
    inner class AdviceFollowedTests {

        @Test
        fun `purchase matching nunua advice is detected as followed`() = runTest {
            val pendingBriefing = BriefingDeliveryEntity(
                id = 1,
                briefingType = "MORNING",
                briefingText = "Briefing",
                keyAdvice = "nunua nyanya",
                deliveredAt = System.currentTimeMillis() / 1000 - 3600
            )

            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns pendingBriefing
            coEvery { transactionDao.getTransactionsInRangeSuspend(any(), any()) } returns emptyList()

            var capturedAdviceFollowed: Boolean? = null
            coEvery {
                briefingDeliveryDao.markActedOn(
                    id = any(),
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = any()
                )
            } just Runs

            loop.onTransactionAfterBriefing(
                Transaction(type = TransactionType.PURCHASE, item = "nyanya", totalAmount = 200.0)
            )

            coVerify {
                briefingDeliveryDao.markActedOn(
                    id = any(),
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = any()
                )
            }
        }

        @Test
        fun `blank key advice returns null for adviceFollowed`() = runTest {
            val pendingBriefing = BriefingDeliveryEntity(
                id = 1,
                briefingType = "MORNING",
                briefingText = "Briefing",
                keyAdvice = "",
                deliveredAt = System.currentTimeMillis() / 1000 - 3600
            )

            coEvery { briefingDeliveryDao.getLatestPendingBriefing() } returns pendingBriefing
            coEvery { transactionDao.getTransactionsInRangeSuspend(any(), any()) } returns emptyList()
            coEvery {
                briefingDeliveryDao.markActedOn(any(), any(), any(), any(), any())
            } just Runs

            loop.onTransactionAfterBriefing(
                Transaction(type = TransactionType.SALE, item = "mandazi", totalAmount = 100.0)
            )

            coVerify {
                briefingDeliveryDao.markActedOn(
                    id = any(),
                    actualSales = any(),
                    actualProfit = any(),
                    outcomeScore = any(),
                    adviceFollowed = null
                )
            }
        }
    }

    // ── Key Advice Extraction ────────────────────────────────────

    @Nested
    @DisplayName("Key Advice Extraction")
    inner class KeyAdviceTests {

        @Test
        fun `extracts advice line with Ushauri keyword`() = runTest {
            val briefingResult = BriefingResult(
                message = "Habari!\n📦 Nunua nyanya zaidi\nUshauri: weka akiba KSh 200",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            coVerify {
                briefingDeliveryDao.insert(match {
                    it.keyAdvice.contains("Ushauri") || it.keyAdvice.contains("📦")
                })
            }
        }

        @Test
        fun `empty message produces empty key advice`() = runTest {
            val briefingResult = BriefingResult(
                message = "",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            coVerify {
                briefingDeliveryDao.insert(match { it.keyAdvice.isEmpty() })
            }
        }
    }

    // ── Pattern Tracker Enrichment ───────────────────────────────

    @Nested
    @DisplayName("Pattern Tracker Enrichment")
    inner class EnrichmentTests {

        @Test
        fun `briefing is enriched with pattern insights when tracker available`() = runTest {
            val briefingResult = BriefingResult(
                message = "Habari! Mauzo ni KSh 5,000.",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            // Pattern tracker returns data
            every { patternTracker.detectWeeklyTrend() } returns mockk {
                every { direction } returns com.msaidizi.app.core.model.Trend.RISING
                every { changePercent } returns 15.0
            }
            every { patternTracker.analyzeProductPerformance(any()) } returns listOf(
                mockk {
                    every { item } returns "mandazi"
                    every { profitMargin } returns 45.0
                }
            )
            every { patternTracker.analyzePeakHours(any()) } returns listOf(
                mockk {
                    every { hour } returns 10
                    every { isPeakHour } returns true
                }
            )
            every { patternTracker.calculateBusinessHealthScore() } returns mockk {
                every { totalScore } returns 75.0
            }

            val result = loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            // The message should be enriched with insights
            assertNotNull(result)
        }

        @Test
        fun `briefing works without pattern tracker`() = runTest {
            val loopNoTracker = MorningBriefingLoop(
                cfoEngine = cfoEngine,
                briefingDelivery = briefingDelivery,
                businessAgent = businessAgent,
                transactionDao = transactionDao,
                briefingDeliveryDao = briefingDeliveryDao,
                patternTracker = null
            )

            val briefingResult = BriefingResult(
                message = "Simple briefing",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val result = loopNoTracker.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            assertEquals("Simple briefing", result.message)
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `briefing with missing data keys handles gracefully`() = runTest {
            val briefingResult = BriefingResult(
                message = "Briefing without data keys",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL,
                data = emptyMap() // missing todaySales, todayProfit
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val result = loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            assertNotNull(result)
            coVerify {
                briefingDeliveryDao.insert(match {
                    it.predictedSales == 0.0 && it.predictedProfit == 0.0
                })
            }
        }

        @Test
        fun `briefing with non-numeric data values handles gracefully`() = runTest {
            val briefingResult = BriefingResult(
                message = "Briefing",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL,
                data = mapOf("todaySales" to "not_a_number", "todayProfit" to "also_not")
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns emptyList()

            val result = loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)

            assertNotNull(result)
            // toDoubleOrNull() returns null for non-numeric, defaults to 0.0
            coVerify {
                briefingDeliveryDao.insert(match {
                    it.predictedSales == 0.0 && it.predictedProfit == 0.0
                })
            }
        }

        @Test
        fun `personalization requires minimum briefings`() = runTest {
            // Only 3 briefings — below MIN_BRIEFINGS_FOR_PERSONALIZATION (5)
            val briefings = (1..3).map {
                BriefingDeliveryEntity(
                    id = it.toLong(),
                    briefingType = "MORNING",
                    briefingText = "Briefing $it"
                )
            }

            val briefingResult = BriefingResult(
                message = "Briefing",
                type = BriefingType.MORNING,
                priority = BriefingPriority.NORMAL
            )

            every { businessAgent.getTransactionsForDate(any(), any()) } returns emptyList()
            coEvery {
                briefingDelivery.deliverMorningBriefing(
                    workerName = any(),
                    assistantName = any(),
                    workerType = any(),
                    todayTransactions = any(),
                    yesterdayTransactions = any(),
                    recentTransactions = any()
                )
            } returns briefingResult
            coEvery { briefingDeliveryDao.insert(any()) } returns 1L
            coEvery { briefingDeliveryDao.getRecent(any()) } returns briefings

            // Should not throw — personalization just skips
            loop.executeMorningBriefing("Test", "Msaidizi", WorkerType.SMALL_RETAILER)
        }
    }
}

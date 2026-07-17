package com.msaidizi.app.loops

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.GamificationEntity
import com.msaidizi.app.gamification.GamificationEngine
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
 * Unit tests for StreakProtectionLoop — Evening streak check.
 *
 * Covers:
 * - Evening check: streak at risk → reminder
 * - Evening check: no streak → no action
 * - Evening check: already recorded today → no action
 * - Streak milestone celebrations
 * - Streak multiplier calculation
 * - Streak broken messages (Swahili + English)
 * - Streak status for UI
 * - Edge cases
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("StreakProtectionLoop")
class StreakProtectionLoopTest {

    @MockK
    private lateinit var gamificationEngine: GamificationEngine

    @MockK
    private lateinit var gamificationDao: GamificationDao

    @MockK
    private lateinit var transactionDao: TransactionDao

    private lateinit var loop: StreakProtectionLoop

    @BeforeEach
    fun setUp() {
        loop = StreakProtectionLoop(gamificationEngine, gamificationDao, transactionDao)
    }

    // ── Evening Check ────────────────────────────────────────────

    @Nested
    @DisplayName("Evening Check")
    inner class EveningCheckTests {

        @Test
        fun `no gamification entity returns noAction`() = runTest {
            coEvery { gamificationDao.getGamification() } returns null

            val result = loop.executeEveningCheck()

            assertFalse(result.shouldRemind)
        }

        @Test
        fun `zero streak returns noAction`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 0)

            val result = loop.executeEveningCheck()

            assertFalse(result.shouldRemind)
        }

        @Test
        fun `worker recorded today returns noAction`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 5)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 3

            val result = loop.executeEveningCheck()

            assertFalse(result.shouldRemind)
        }

        @Test
        fun `streak at risk triggers reminder in Swahili`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 7)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val result = loop.executeEveningCheck(language = "sw")

            assertTrue(result.shouldRemind)
            assertNotNull(result.reminderMessage)
            assertEquals(7, result.currentStreak)
            // Swahili reminder should contain streak count
            assertTrue(result.reminderMessage!!.contains("7"))
        }

        @Test
        fun `streak at risk triggers reminder in English`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 14)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val result = loop.executeEveningCheck(language = "en")

            assertTrue(result.shouldRemind)
            assertNotNull(result.reminderMessage)
            assertTrue(result.reminderMessage!!.contains("14"))
        }

        @Test
        fun `reminder includes streak freeze info when available`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(
                streak = 5,
                streakProtectionsUsed = 0,
                protectionWeek = java.time.LocalDate.now()
                    .get(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).weekOfWeekBasedYear())
            )
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val result = loop.executeEveningCheck()

            assertTrue(result.streakFreezeAvailable)
        }

        @Test
        fun `streak freeze message included when available`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(
                streak = 5,
                streakProtectionsUsed = 0,
                protectionWeek = java.time.LocalDate.now()
                    .get(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).weekOfWeekBasedYear())
            )
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val result = loop.executeEveningCheck()

            assertNotNull(result.streakFreezeMessage)
        }

        @Test
        fun `streak freeze not available when already used this week`() = runTest {
            val currentWeek = java.time.LocalDate.now()
                .get(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).weekOfWeekBasedYear())
            coEvery { gamificationDao.getGamification() } returns createEntity(
                streak = 5,
                streakProtectionsUsed = GamificationEngine.MAX_STREAK_PROTECTIONS_PER_WEEK,
                protectionWeek = currentWeek
            )
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val result = loop.executeEveningCheck()

            assertFalse(result.streakFreezeAvailable)
        }
    }

    // ── Streak Milestone ─────────────────────────────────────────

    @Nested
    @DisplayName("Streak Milestones")
    inner class MilestoneTests {

        @Test
        fun `milestone 3 returns celebration message`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 3)

            val msg = loop.checkStreakMilestone("sw")

            assertNotNull(msg)
            assertTrue(msg!!.contains("3"))
        }

        @Test
        fun `milestone 7 returns celebration with week reference`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 7)

            val msg = loop.checkStreakMilestone("en")

            assertNotNull(msg)
            assertTrue(msg!!.contains("week", ignoreCase = true))
        }

        @Test
        fun `milestone 30 returns legend celebration`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 30)

            val msg = loop.checkStreakMilestone("sw")

            assertNotNull(msg)
            assertTrue(msg!!.contains("Legend", ignoreCase = true) || msg.contains("MWEZI"))
        }

        @Test
        fun `non-milestone streak returns null`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 5)

            val msg = loop.checkStreakMilestone()

            assertNull(msg)
        }

        @Test
        fun `no gamification entity returns null`() = runTest {
            coEvery { gamificationDao.getGamification() } returns null

            val msg = loop.checkStreakMilestone()

            assertNull(msg)
        }

        @Test
        fun `all milestone days produce messages`() = runTest {
            val milestones = setOf(3, 7, 14, 21, 30, 45, 60, 90, 120, 180, 365)
            for (milestone in milestones) {
                coEvery { gamificationDao.getGamification() } returns createEntity(streak = milestone)

                val msg = loop.checkStreakMilestone("en")
                assertNotNull(msg, "Milestone $milestone should produce a message")
            }
        }
    }

    // ── Streak Multiplier ────────────────────────────────────────

    @Nested
    @DisplayName("Streak Multiplier")
    inner class MultiplierTests {

        @Test
        fun `streak below 5 gives 1x multiplier`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 3)

            assertEquals(1, loop.getStreakMultiplier())
        }

        @Test
        fun `streak 5-9 gives 2x multiplier`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 7)

            assertEquals(2, loop.getStreakMultiplier())
        }

        @Test
        fun `streak 10-29 gives 3x multiplier`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 15)

            assertEquals(3, loop.getStreakMultiplier())
        }

        @Test
        fun `streak 30+ gives 5x multiplier`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 45)

            assertEquals(5, loop.getStreakMultiplier())
        }

        @Test
        fun `no gamification entity gives 1x multiplier`() = runTest {
            coEvery { gamificationDao.getGamification() } returns null

            assertEquals(1, loop.getStreakMultiplier())
        }
    }

    // ── Streak Broken Messages ───────────────────────────────────

    @Nested
    @DisplayName("Streak Broken Messages")
    inner class StreakBrokenTests {

        @Test
        fun `short streak broken message in Swahili`() {
            val msg = loop.generateStreakBrokenMessage(lostStreak = 3, language = "sw")

            assertTrue(msg.isNotBlank())
            assertTrue(msg.contains("Streak", ignoreCase = true))
        }

        @Test
        fun `short streak broken message in English`() {
            val msg = loop.generateStreakBrokenMessage(lostStreak = 3, language = "en")

            assertTrue(msg.isNotBlank())
            assertTrue(msg.contains("streak", ignoreCase = true))
        }

        @Test
        fun `week-long streak broken gets more emotional message`() {
            val msg = loop.generateStreakBrokenMessage(lostStreak = 14, language = "sw")

            assertTrue(msg.contains("14") || msg.contains("wiki", ignoreCase = true))
        }

        @Test
        fun `month-long streak broken gets most emotional message`() {
            val msg = loop.generateStreakBrokenMessage(lostStreak = 30, language = "en")

            assertTrue(msg.contains("30"))
            assertTrue(msg.contains("don't give up", ignoreCase = true) || msg.contains("new chance", ignoreCase = true))
        }

        @Test
        fun `very long streak broken message is encouraging`() {
            val msg = loop.generateStreakBrokenMessage(lostStreak = 100, language = "sw")

            assertTrue(msg.isNotBlank())
            // Should be encouraging, not depressing
            assertTrue(msg.contains("💪") || msg.contains("leo") || msg.contains("tena"))
        }
    }

    // ── Streak Status ────────────────────────────────────────────

    @Nested
    @DisplayName("Streak Status")
    inner class StreakStatusTests {

        @Test
        fun `empty status when no gamification entity`() = runTest {
            coEvery { gamificationDao.getGamification() } returns null

            val status = loop.getStreakStatus()

            assertEquals(0, status.currentStreak)
            assertEquals(0, status.longestStreak)
            assertFalse(status.hasRecordedToday)
            assertEquals(1, status.multiplier)
        }

        @Test
        fun `status shows recorded today flag`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 5)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 1

            val status = loop.getStreakStatus()

            assertTrue(status.hasRecordedToday)
        }

        @Test
        fun `status shows next milestone`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 5)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val status = loop.getStreakStatus()

            assertNotNull(status.nextMilestone)
            assertTrue(status.nextMilestone!! > 5)
        }

        @Test
        fun `status calculates days to milestone`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 5)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val status = loop.getStreakStatus()

            assertNotNull(status.daysToMilestone)
            assertTrue(status.daysToMilestone!! > 0)
        }

        @Test
        fun `status message in Swahili for active streak`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 10)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val status = loop.getStreakStatus("sw")

            assertTrue(status.statusMessage.isNotBlank())
            assertTrue(status.statusMessage.contains("10"))
        }

        @Test
        fun `status message for zero streak encourages starting`() = runTest {
            coEvery { gamificationDao.getGamification() } returns createEntity(streak = 0)
            coEvery { transactionDao.getTransactionCount(any(), any()) } returns 0

            val status = loop.getStreakStatus("en")

            assertTrue(status.statusMessage.contains("start", ignoreCase = true))
        }
    }

    // ── Data Classes ─────────────────────────────────────────────

    @Nested
    @DisplayName("Data Classes")
    inner class DataClassTests {

        @Test
        fun `StreakProtectionResult noAction has correct defaults`() {
            val result = StreakProtectionResult.noAction()

            assertFalse(result.shouldRemind)
            assertNull(result.reminderMessage)
            assertEquals(0, result.currentStreak)
            assertFalse(result.streakFreezeAvailable)
        }

        @Test
        fun `StreakStatus empty has correct defaults`() {
            val status = StreakStatus.empty()

            assertEquals(0, status.currentStreak)
            assertEquals(0, status.longestStreak)
            assertFalse(status.hasRecordedToday)
            assertEquals(1, status.multiplier)
            assertNull(status.nextMilestone)
            assertNull(status.daysToMilestone)
            assertFalse(status.streakFreezeAvailable)
        }
    }

    // ── Helper ───────────────────────────────────────────────────

    private fun createEntity(
        streak: Int = 0,
        longestStreak: Int = 0,
        totalSalesRecorded: Int = 0,
        streakProtectionsUsed: Int = 0,
        protectionWeek: Int = 0,
        level: Int = 0,
        earnedBadges: String = ""
    ) = GamificationEntity(
        id = 1,
        currentStreak = streak,
        longestStreak = longestStreak,
        totalSalesRecorded = totalSalesRecorded,
        streakProtectionsUsed = streakProtectionsUsed,
        protectionWeek = protectionWeek,
        level = level,
        earnedBadges = earnedBadges
    )
}

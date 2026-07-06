package com.msaidizi.app.gamification

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.model.Badge
import com.msaidizi.app.core.model.GamificationEntity
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
 * Unit tests for GamificationEngine — the retention engine.
 * Tests points, levels, badges, streaks, and variable rewards.
 */
@ExtendWith(MockKExtension::class)
@DisplayName("GamificationEngine")
class GamificationEngineTest {

    @MockK
    private lateinit var dao: GamificationDao

    private lateinit var engine: GamificationEngine

    @BeforeEach
    fun setUp() {
        engine = GamificationEngine(dao)
    }

    // ── Initialization ──────────────────────────────────────────

    @Nested
    @DisplayName("Initialization")
    inner class InitTests {

        @Test
        fun `initialize creates default entity when none exists`() = runTest {
            coEvery { dao.getGamification() } returns null
            coEvery { dao.upsert(any()) } just Runs

            engine.initialize()

            coVerify { dao.upsert(match { it.id == 1 && it.totalPoints == 0 }) }
        }

        @Test
        fun `initialize does not overwrite existing entity`() = runTest {
            val existing = GamificationEntity(id = 1, totalPoints = 500)
            coEvery { dao.getGamification() } returns existing

            engine.initialize()

            coVerify(exactly = 0) { dao.upsert(any()) }
        }
    }

    // ── Points & Levels ─────────────────────────────────────────

    @Nested
    @DisplayName("Points and Levels")
    inner class PointsLevelTests {

        @Test
        fun `new user starts at level 0 (Mwanafunzi)`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity()

            val level = engine.getCurrentLevel("sw")

            assertEquals(0, level.levelIndex)
            assertEquals("Mwanafunzi", level.nameSw)
            assertEquals("📚", level.emoji)
        }

        @Test
        fun `100 points reaches level 1 (Mfanyabiashara)`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(totalPoints = 100)

            val level = engine.getCurrentLevel("sw")

            assertEquals(1, level.levelIndex)
            assertEquals("Mfanyabiashara", level.nameSw)
        }

        @Test
        fun `2000 points reaches level 5 (Legend)`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(totalPoints = 2000, level = 5)

            val level = engine.getCurrentLevel("sw")

            assertEquals(5, level.levelIndex)
            assertEquals("Legend", level.nameSw)
        }
    }

    // ── Badge System ────────────────────────────────────────────

    @Nested
    @DisplayName("Badge System")
    inner class BadgeTests {

        @Test
        fun `engine has 18 badges`() {
            assertEquals(18, engine.badges.size)
        }

        @Test
        fun `first sale earns Biashara Ndogo badge`() {
            val entity = GamificationEntity(totalSalesRecorded = 1)
            val badge = engine.badges.first { it.id == "biashara_ndogo" }

            assertTrue(badge.requirement(entity, 0, 0), "First sale should earn badge")
        }

        @Test
        fun `50 sales earns Mfanyabiashara Mkuu badge`() {
            val entity = GamificationEntity(totalSalesRecorded = 50)
            val badge = engine.badges.first { it.id == "mfanyabiashara_mkuu" }

            assertTrue(badge.requirement(entity, 0, 0))
        }

        @Test
        fun `3-day streak earns Mlinzi wa Siku Tatu badge`() {
            val entity = GamificationEntity(currentStreak = 3)
            val badge = engine.badges.first { it.id == "mlinzi_wa_siku_tatu" }

            assertTrue(badge.requirement(entity, 0, 0))
        }

        @Test
        fun `7-day streak earns Bwenye ya Wiki badge`() {
            val entity = GamificationEntity(currentStreak = 7)
            val badge = engine.badges.first { it.id == "bwenye_hafta" }

            assertTrue(badge.requirement(entity, 0, 0))
        }

        @Test
        fun `level 2 earns Mjasiriamali Chipukizi badge`() {
            val entity = GamificationEntity(level = 2)
            val badge = engine.badges.first { it.id == "mjasiriamali_chipukizi" }

            assertTrue(badge.requirement(entity, 0, 0))
        }

        @Test
        fun `5 sales in one day earns Mfanyabiashara wa Siku badge`() {
            val entity = GamificationEntity()
            val badge = engine.badges.first { it.id == "mfanyabiashara_wa_siku" }

            assertTrue(badge.requirement(entity, todaySalesCount = 5, 0))
        }

        @Test
        fun `all badges have unique IDs`() {
            val ids = engine.badges.map { it.id }.toSet()
            assertEquals(engine.badges.size, ids.size, "All badge IDs should be unique")
        }

        @Test
        fun `all badges have Swahili and English names`() {
            engine.badges.forEach { badge ->
                assertTrue(badge.nameSw.isNotBlank(), "Badge ${badge.id} missing Swahili name")
                assertTrue(badge.nameEn.isNotBlank(), "Badge ${badge.id} missing English name")
            }
        }
    }

    // ── Streak System ───────────────────────────────────────────

    @Nested
    @DisplayName("Streak System")
    inner class StreakTests {

        @Test
        fun `streak info returns current and longest streak`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(
                currentStreak = 5,
                longestStreak = 12
            )

            val info = engine.getStreakInfo()

            assertEquals(5, info.currentStreak)
            assertEquals(12, info.longestStreak)
        }

        @Test
        fun `streak freeze status shows available protection`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(
                streakProtectionsUsed = 0,
                protectionWeek = 0 // Different week → all protections available
            )

            val (available, used, max) = engine.getStreakFreezeStatus()

            assertTrue(available)
            assertEquals(0, used)
            assertEquals(1, max)
        }
    }

    // ── Variable Rewards ────────────────────────────────────────

    @Nested
    @DisplayName("Variable Rewards")
    inner class VariableRewardTests {

        @Test
        fun `base sale reward is 10 points`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(currentStreak = 0)

            val reward = engine.getVariableReward("sale", "sw")

            assertEquals(GamificationEngine.POINTS_SALE, reward.basePoints)
            assertEquals(1, reward.multiplier) // No streak → 1x
        }

        @Test
        fun `30-day streak gives 5x multiplier`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(currentStreak = 30)

            val reward = engine.getVariableReward("sale", "sw")

            assertEquals(5, reward.multiplier)
        }

        @Test
        fun `7-day streak gives 2x multiplier`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(currentStreak = 7)

            val reward = engine.getVariableReward("sale", "sw")

            assertEquals(2, reward.multiplier)
        }

        @Test
        fun `total points include base times multiplier plus bonus`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(currentStreak = 0)

            val reward = engine.getVariableReward("sale", "sw")

            assertTrue(reward.totalPoints >= reward.basePoints * reward.multiplier)
        }

        @Test
        fun `balance check gives 5 base points`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity()

            val reward = engine.getVariableReward("balance_check", "sw")

            assertEquals(GamificationEngine.POINTS_BALANCE_CHECK, reward.basePoints)
        }
    }

    // ── Language Support ────────────────────────────────────────

    @Nested
    @DisplayName("Language Support")
    inner class LanguageTests {

        @Test
        fun `level names are in Swahili for sw language`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(totalPoints = 0)

            val level = engine.getCurrentLevel("sw")

            assertEquals("Mwanafunzi", level.nameSw)
        }

        @Test
        fun `level names are in English for en language`() = runTest {
            coEvery { dao.getGamification() } returns GamificationEntity(totalPoints = 0)

            val level = engine.getCurrentLevel("en")

            assertEquals("Student", level.nameEn)
        }
    }

    // ── Surprises ───────────────────────────────────────────────

    @Nested
    @DisplayName("Surprise Messages")
    inner class SurpriseTests {

        @Test
        fun `surprise praise returns null most of the time`() {
            // With 15% chance, running 100 times should have some nulls
            val results = (1..100).map { engine.getSurprisePraise("sw") }
            assertTrue(results.any { it == null }, "Most calls should return null")
        }

        @Test
        fun `profit insight returns null for zero profit`() {
            val result = engine.getProfitInsight(
                todayProfit = 0.0,
                avgDailyProfit = 1000.0,
                language = "sw"
            )
            assertNull(result)
        }

        @Test
        fun `profit insight returns null for zero average`() {
            val result = engine.getProfitInsight(
                todayProfit = 500.0,
                avgDailyProfit = 0.0,
                language = "sw"
            )
            assertNull(result)
        }
    }
}

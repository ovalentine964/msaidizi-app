package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.GamificationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamificationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: GamificationDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.gamificationDao()
    }

    @After
    fun closeDb() { database.close() }

    @Test
    fun getGamification_initiallyNull() = runTest {
        val result = dao.getGamification()
        assertNull(result)
    }

    @Test
    fun upsert_createsEntity() = runTest {
        val entity = GamificationEntity(id = 1, totalPoints = 100, level = 1, currentStreak = 5)
        dao.upsert(entity)

        val result = dao.getGamification()
        assertNotNull(result)
        assertEquals(100, result!!.totalPoints)
        assertEquals(1, result.level)
        assertEquals(5, result.currentStreak)
    }

    @Test
    fun upsert_updatesExisting() = runTest {
        dao.upsert(GamificationEntity(id = 1, totalPoints = 100, level = 1))
        dao.upsert(GamificationEntity(id = 1, totalPoints = 250, level = 2))

        val result = dao.getGamification()
        assertEquals(250, result!!.totalPoints)
        assertEquals(2, result.level)
    }

    @Test
    fun addPoints_incrementsCorrectly() = runTest {
        dao.upsert(GamificationEntity(id = 1, totalPoints = 100))
        dao.addPoints(50)
        dao.addPoints(25)

        val result = dao.getGamification()
        assertEquals(175, result!!.totalPoints)
    }

    @Test
    fun incrementSalesCount_incrementsCorrectly() = runTest {
        dao.upsert(GamificationEntity(id = 1, totalSalesRecorded = 0))
        dao.incrementSalesCount()
        dao.incrementSalesCount()
        dao.incrementSalesCount()

        val result = dao.getGamification()
        assertEquals(3, result!!.totalSalesRecorded)
    }

    @Test
    fun incrementBalanceChecks_incrementsCorrectly() = runTest {
        dao.upsert(GamificationEntity(id = 1, totalBalanceChecks = 0))
        dao.incrementBalanceChecks()
        dao.incrementBalanceChecks()

        val result = dao.getGamification()
        assertEquals(2, result!!.totalBalanceChecks)
    }

    @Test
    fun updateStreak_updatesCorrectly() = runTest {
        dao.upsert(GamificationEntity(id = 1, currentStreak = 0, longestStreak = 0))
        dao.updateStreak(streak = 7, day = 1000L, protections = 1, week = 10)

        val result = dao.getGamification()
        assertEquals(7, result!!.currentStreak)
        assertEquals(7, result.longestStreak)
        assertEquals(1000L, result.lastActiveDay)
    }

    @Test
    fun updateLevelAndBadges_updatesCorrectly() = runTest {
        dao.upsert(GamificationEntity(id = 1, level = 0, earnedBadges = ""))
        dao.updateLevelAndBadges(level = 3, badges = "first_sale,streak_7")

        val result = dao.getGamification()
        assertEquals(3, result!!.level)
        assertEquals("first_sale,streak_7", result.earnedBadges)
    }
}

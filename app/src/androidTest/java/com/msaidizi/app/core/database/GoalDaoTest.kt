package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.GoalRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: GoalDao

    private val now get() = System.currentTimeMillis() / 1000

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.goalDao()
    }

    @After
    fun closeDb() { database.close() }

    @Test
    fun insertAndRetrieve() = runTest {
        val goal = GoalRecord(name = "New Oven", targetAmount = 50000.0, category = "EQUIPMENT")
        val id = dao.insertGoal(goal)
        assertTrue(id > 0)

        val retrieved = dao.getById(id)
        assertNotNull(retrieved)
        assertEquals("New Oven", retrieved!!.name)
        assertEquals(50000.0, retrieved.targetAmount, 0.01)
        assertEquals("ACTIVE", retrieved.status)
    }

    @Test
    fun getActive_returnsOnlyActive() = runTest {
        dao.insertGoal(GoalRecord(name = "Active Goal", targetAmount = 10000.0, category = "SAVINGS", status = "ACTIVE"))
        dao.insertGoal(GoalRecord(name = "Completed Goal", targetAmount = 5000.0, category = "SAVINGS", status = "COMPLETED"))

        val active = dao.getActive()
        assertEquals(1, active.size)
        assertEquals("Active Goal", active[0].name)
    }

    @Test
    fun getCompleted_returnsOnlyCompleted() = runTest {
        dao.insertGoal(GoalRecord(name = "Active", targetAmount = 10000.0, category = "SAVINGS", status = "ACTIVE"))
        dao.insertGoal(GoalRecord(name = "Done", targetAmount = 5000.0, category = "SAVINGS", status = "COMPLETED"))

        val completed = dao.getCompleted()
        assertEquals(1, completed.size)
        assertEquals("Done", completed[0].name)
    }

    @Test
    fun updateStatus_changesStatus() = runTest {
        val id = dao.insertGoal(GoalRecord(name = "Goal", targetAmount = 1000.0, category = "OTHER"))
        dao.updateStatus(id, "COMPLETED")

        val goal = dao.getById(id)
        assertEquals("COMPLETED", goal!!.status)
    }

    @Test
    fun addProgress_incrementsAmount() = runTest {
        val id = dao.insertGoal(GoalRecord(name = "Savings", targetAmount = 10000.0, category = "SAVINGS"))
        dao.addProgress(id, 2500.0)
        dao.addProgress(id, 1500.0)

        val goal = dao.getById(id)
        assertEquals(4000.0, goal!!.currentAmount, 0.01)
    }

    @Test
    fun getTotalSaved_sumsActiveGoals() = runTest {
        dao.insertGoal(GoalRecord(name = "A", targetAmount = 10000.0, currentAmount = 3000.0, category = "SAVINGS", status = "ACTIVE"))
        dao.insertGoal(GoalRecord(name = "B", targetAmount = 5000.0, currentAmount = 2000.0, category = "EQUIPMENT", status = "ACTIVE"))
        dao.insertGoal(GoalRecord(name = "C", targetAmount = 8000.0, currentAmount = 8000.0, category = "SAVINGS", status = "COMPLETED"))

        val total = dao.getTotalSaved()
        assertEquals(5000.0, total!!, 0.01) // Only active goals
    }

    @Test
    fun getActiveCount_returnsCorrectCount() = runTest {
        repeat(3) { i ->
            dao.insertGoal(GoalRecord(name = "Goal $i", targetAmount = 1000.0, category = "OTHER", status = "ACTIVE"))
        }
        dao.insertGoal(GoalRecord(name = "Done", targetAmount = 1000.0, category = "OTHER", status = "COMPLETED"))

        assertEquals(3, dao.getActiveCount())
    }

    @Test
    fun deleteAll_removesAll() = runTest {
        repeat(3) { dao.insertGoal(GoalRecord(name = "Goal", targetAmount = 1000.0, category = "OTHER")) }
        dao.deleteAll()

        val all = dao.getAll()
        assertTrue(all.isEmpty())
    }
}

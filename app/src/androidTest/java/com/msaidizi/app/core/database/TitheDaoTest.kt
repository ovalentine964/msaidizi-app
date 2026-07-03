package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.TitheRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TitheDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TitheDao

    private val nowMs get() = System.currentTimeMillis()
    private val dayMs = 86_400_000L

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.titheDao()
    }

    @After
    fun closeDb() { database.close() }

    @Test
    fun insertAndRetrieve() = runTest {
        val record = TitheRecord(type = "TITHE", amount = 500.0, date = nowMs)
        val id = dao.insert(record)
        assertTrue(id > 0)

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("TITHE", all[0].type)
        assertEquals(500.0, all[0].amount, 0.01)
    }

    @Test
    fun getSince_filtersCorrectly() = runTest {
        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs))
        dao.insert(TitheRecord(type = "OFFERING", amount = 200.0, date = nowMs - 30 * dayMs))

        val recent = dao.getSince(nowMs - dayMs)
        assertEquals(1, recent.size)
        assertEquals(100.0, recent[0].amount, 0.01)
    }

    @Test
    fun getByDateRange_filtersCorrectly() = runTest {
        val start = nowMs - 7 * dayMs
        val end = nowMs

        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs - 3 * dayMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 200.0, date = nowMs - 10 * dayMs))

        val results = dao.getByDateRange(start, end)
        assertEquals(1, results.size)
    }

    @Test
    fun getByType_filtersCorrectly() = runTest {
        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs))
        dao.insert(TitheRecord(type = "ZAKAT", amount = 200.0, date = nowMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 300.0, date = nowMs))

        val tithes = dao.getByType("TITHE")
        assertEquals(2, tithes.size)
    }

    @Test
    fun getTotalSince_sumsCorrectly() = runTest {
        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs))
        dao.insert(TitheRecord(type = "OFFERING", amount = 200.0, date = nowMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 50.0, date = nowMs - 30 * dayMs))

        val total = dao.getTotalSince(nowMs - dayMs)
        assertEquals(300.0, total!!, 0.01)
    }

    @Test
    fun getTotalByTypeSince_sumsByType() = runTest {
        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs))
        dao.insert(TitheRecord(type = "ZAKAT", amount = 200.0, date = nowMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 50.0, date = nowMs))

        val total = dao.getTotalByTypeSince("TITHE", nowMs - dayMs)
        assertEquals(150.0, total!!, 0.01)
    }

    @Test
    fun getLatest_returnsMostRecent() = runTest {
        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs - dayMs))
        dao.insert(TitheRecord(type = "OFFERING", amount = 200.0, date = nowMs))

        val latest = dao.getLatest()
        assertNotNull(latest)
        assertEquals(200.0, latest!!.amount, 0.01)
    }

    @Test
    fun getMonthlyTotal_sumsWithinMonth() = runTest {
        val monthStart = nowMs - 15 * dayMs
        val monthEnd = nowMs + dayMs

        dao.insert(TitheRecord(type = "TITHE", amount = 100.0, date = nowMs - 5 * dayMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 200.0, date = nowMs - 10 * dayMs))
        dao.insert(TitheRecord(type = "TITHE", amount = 300.0, date = nowMs - 20 * dayMs)) // outside month

        val total = dao.getMonthlyTotal(monthStart, monthEnd)
        assertEquals(300.0, total!!, 0.01)
    }

    @Test
    fun getCount_returnsTotal() = runTest {
        repeat(5) { dao.insert(TitheRecord(type = "TITHE", amount = 10.0, date = nowMs)) }
        assertEquals(5, dao.getCount())
    }

    @Test
    fun delete_removesRecord() = runTest {
        val record = TitheRecord(type = "TITHE", amount = 100.0, date = nowMs)
        val id = dao.insert(record)
        dao.delete(record.copy(id = id))

        assertEquals(0, dao.getCount())
    }

    @Test
    fun deleteAll_removesAll() = runTest {
        repeat(3) { dao.insert(TitheRecord(type = "TITHE", amount = 10.0, date = nowMs)) }
        dao.deleteAll()
        assertEquals(0, dao.getCount())
    }
}

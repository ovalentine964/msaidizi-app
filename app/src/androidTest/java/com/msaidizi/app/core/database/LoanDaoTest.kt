package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.LoanRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoanDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: LoanDao

    private val now get() = System.currentTimeMillis() / 1000

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.loanDao()
    }

    @After
    fun closeDb() { database.close() }

    @Test
    fun insertAndRetrieve() = runTest {
        val loan = LoanRecord(
            amount = 50000.0,
            purpose = "Business expansion",
            lender = "M-Shwari",
            totalDue = 55000.0,
            startDate = now
        )
        val id = dao.insertLoan(loan)
        assertTrue(id > 0)

        val retrieved = dao.getById(id)
        assertNotNull(retrieved)
        assertEquals(50000.0, retrieved!!.amount, 0.01)
        assertEquals("Business expansion", retrieved.purpose)
        assertEquals("ACTIVE", retrieved.status)
    }

    @Test
    fun getActive_returnsActiveAndOverdue() = runTest {
        dao.insertLoan(LoanRecord(amount = 10000.0, purpose = "A", totalDue = 10000.0, startDate = now, status = "ACTIVE"))
        dao.insertLoan(LoanRecord(amount = 20000.0, purpose = "B", totalDue = 20000.0, startDate = now, status = "OVERDUE"))
        dao.insertLoan(LoanRecord(amount = 30000.0, purpose = "C", totalDue = 30000.0, startDate = now, status = "COMPLETED"))

        val active = dao.getActive()
        assertEquals(2, active.size)
    }

    @Test
    fun addRepayment_incrementsTotalRepaid() = runTest {
        val id = dao.insertLoan(LoanRecord(amount = 10000.0, purpose = "Test", totalDue = 10000.0, startDate = now))

        dao.addRepayment(id, 3000.0)
        dao.addRepayment(id, 2000.0)

        val loan = dao.getById(id)
        assertEquals(5000.0, loan!!.totalRepaid, 0.01)
    }

    @Test
    fun updateStatus_changesStatus() = runTest {
        val id = dao.insertLoan(LoanRecord(amount = 10000.0, purpose = "Test", totalDue = 10000.0, startDate = now))
        dao.updateStatus(id, "COMPLETED")

        val loan = dao.getById(id)
        assertEquals("COMPLETED", loan!!.status)
    }

    @Test
    fun getTotalOutstanding_sumsActiveLoans() = runTest {
        dao.insertLoan(LoanRecord(amount = 10000.0, purpose = "A", totalDue = 12000.0, totalRepaid = 5000.0, startDate = now, status = "ACTIVE"))
        dao.insertLoan(LoanRecord(amount = 20000.0, purpose = "B", totalDue = 22000.0, totalRepaid = 10000.0, startDate = now, status = "ACTIVE"))
        dao.insertLoan(LoanRecord(amount = 30000.0, purpose = "C", totalDue = 30000.0, totalRepaid = 30000.0, startDate = now, status = "COMPLETED"))

        val outstanding = dao.getTotalOutstanding()
        // (12000-5000) + (22000-10000) = 7000 + 12000 = 19000
        assertEquals(19000.0, outstanding!!, 0.01)
    }

    @Test
    fun getActiveCount_returnsCorrectCount() = runTest {
        repeat(3) { i ->
            dao.insertLoan(LoanRecord(amount = 1000.0 * i, purpose = "Loan $i", totalDue = 1000.0 * i, startDate = now, status = "ACTIVE"))
        }
        assertEquals(3, dao.getActiveCount())
    }

    @Test
    fun deleteAll_removesAll() = runTest {
        repeat(2) { dao.insertLoan(LoanRecord(amount = 1000.0, purpose = "Test", totalDue = 1000.0, startDate = now)) }
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }
}

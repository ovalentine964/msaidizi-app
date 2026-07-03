package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TransactionDao.
 * Runs on device/emulator with real Room database.
 */
@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TransactionDao

    private val now get() = System.currentTimeMillis() / 1000

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.transactionDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    // ── Insert & Query ──────────────────────────────────────────

    @Test
    fun insertAndRetrieve() = runTest {
        val tx = Transaction(
            type = TransactionType.SALE,
            item = "mandazi",
            quantity = 10.0,
            unitPrice = 10.0,
            totalAmount = 100.0,
            createdAt = now
        )
        val id = dao.insert(tx)
        assertTrue(id > 0)

        val results = dao.getTransactionsForDate(now - 86400, now + 60).first()
        assertEquals(1, results.size)
        assertEquals("mandazi", results[0].item)
        assertEquals(100.0, results[0].totalAmount, 0.01)
    }

    @Test
    fun insertAll_batchInsert() = runTest {
        val transactions = listOf(
            Transaction(type = TransactionType.SALE, item = "chapati", totalAmount = 30.0, createdAt = now),
            Transaction(type = TransactionType.SALE, item = "chai", totalAmount = 20.0, createdAt = now),
            Transaction(type = TransactionType.PURCHASE, item = "unga", totalAmount = 200.0, createdAt = now)
        )
        val ids = dao.insertAll(transactions)
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun update_modifiesExisting() = runTest {
        val tx = Transaction(type = TransactionType.SALE, item = "mandazi", totalAmount = 100.0, createdAt = now)
        val id = dao.insert(tx)

        val updated = tx.copy(id = id, totalAmount = 150.0, notes = "updated")
        dao.update(updated)

        val results = dao.getTransactionsForDate(now - 86400, now + 60).first()
        assertEquals(150.0, results[0].totalAmount, 0.01)
        assertEquals("updated", results[0].notes)
    }

    @Test
    fun delete_removesTransaction() = runTest {
        val tx = Transaction(type = TransactionType.SALE, item = "test", totalAmount = 50.0, createdAt = now)
        val id = dao.insert(tx)
        dao.delete(tx.copy(id = id))

        val results = dao.getTransactionsForDate(now - 86400, now + 60).first()
        assertTrue(results.isEmpty())
    }

    @Test
    fun deleteById_removesTransaction() = runTest {
        val tx = Transaction(type = TransactionType.SALE, item = "test", totalAmount = 50.0, createdAt = now)
        val id = dao.insert(tx)
        dao.deleteById(id)

        val results = dao.getTransactionsForDate(now - 86400, now + 60).first()
        assertTrue(results.isEmpty())
    }

    // ── Date Range Queries ──────────────────────────────────────

    @Test
    fun getTodayTransactions_returnsOnlyToday() = runTest {
        val todayTx = Transaction(type = TransactionType.SALE, item = "today", totalAmount = 100.0, createdAt = now)
        val oldTx = Transaction(type = TransactionType.SALE, item = "old", totalAmount = 50.0, createdAt = now - 172800) // 2 days ago

        dao.insert(todayTx)
        dao.insert(oldTx)

        val todayResults = dao.getTodayTransactions(now - 86400).first()
        assertEquals(1, todayResults.size)
        assertEquals("today", todayResults[0].item)
    }

    @Test
    fun getTransactionsInRange_filtersCorrectly() = runTest {
        val start = now - 3600
        val end = now + 3600

        dao.insert(Transaction(type = TransactionType.SALE, item = "in_range", totalAmount = 100.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.SALE, item = "out_of_range", totalAmount = 50.0, createdAt = now - 7200))

        val results = dao.getTransactionsInRange(start, end).first()
        assertEquals(1, results.size)
        assertEquals("in_range", results[0].item)
    }

    // ── Aggregate Queries ───────────────────────────────────────

    @Test
    fun getSalesTotal_sumsOnlySales() = runTest {
        val start = now - 3600
        val end = now + 3600

        dao.insert(Transaction(type = TransactionType.SALE, item = "a", totalAmount = 100.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.SALE, item = "b", totalAmount = 200.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.PURCHASE, item = "c", totalAmount = 500.0, createdAt = now))

        val total = dao.getSalesTotal(start, end)
        assertEquals(300.0, total, 0.01)
    }

    @Test
    fun getPurchasesTotal_sumsOnlyPurchases() = runTest {
        val start = now - 3600
        val end = now + 3600

        dao.insert(Transaction(type = TransactionType.PURCHASE, item = "flour", totalAmount = 500.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.SALE, item = "bread", totalAmount = 100.0, createdAt = now))

        val total = dao.getPurchasesTotal(start, end)
        assertEquals(500.0, total, 0.01)
    }

    @Test
    fun getProfit_calculatesCorrectly() = runTest {
        val start = now - 3600
        val end = now + 3600

        dao.insert(Transaction(type = TransactionType.SALE, item = "a", totalAmount = 500.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.PURCHASE, item = "b", totalAmount = 200.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.EXPENSE, item = "c", totalAmount = 50.0, createdAt = now))

        val profit = dao.getProfit(start, end)
        assertEquals(250.0, profit, 0.01) // 500 - 200 - 50
    }

    @Test
    fun getTransactionCount_countsAll() = runTest {
        val start = now - 3600
        val end = now + 3600

        repeat(5) { i ->
            dao.insert(Transaction(type = TransactionType.SALE, item = "item$i", totalAmount = 10.0, createdAt = now))
        }

        val count = dao.getTransactionCount(start, end)
        assertEquals(5, count)
    }

    @Test
    fun getSalesTotal_emptyRange_returnsZero() = runTest {
        val total = dao.getSalesTotal(now + 10000, now + 20000)
        assertEquals(0.0, total, 0.01)
    }

    // ── Item Queries ────────────────────────────────────────────

    @Test
    fun getTopSellingItems_returnsRankedItems() = runTest {
        val start = now - 3600
        val end = now + 3600

        repeat(5) { dao.insert(Transaction(type = TransactionType.SALE, item = "mandazi", totalAmount = 10.0, createdAt = now)) }
        repeat(3) { dao.insert(Transaction(type = TransactionType.SALE, item = "chapati", totalAmount = 20.0, createdAt = now)) }
        repeat(8) { dao.insert(Transaction(type = TransactionType.SALE, item = "chai", totalAmount = 5.0, createdAt = now)) }

        val top = dao.getTopSellingItems(start, end, limit = 3)
        assertEquals(3, top.size)
        assertEquals("mandazi", top[0].item)
    }

    @Test
    fun searchTransactions_findsMatchingItems() = runTest {
        dao.insert(Transaction(type = TransactionType.SALE, item = "mandazi", totalAmount = 10.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.SALE, item = "chapati", totalAmount = 20.0, createdAt = now))
        dao.insert(Transaction(type = TransactionType.SALE, item = "maandazi_ya_nazi", totalAmount = 15.0, createdAt = now))

        val results = dao.searchTransactions("manda")
        assertEquals(2, results.size) // mandazi and maandazi_ya_nazi
    }

    // ── Sync Queries ────────────────────────────────────────────

    @Test
    fun getUnsyncedTransactions_returnsOnlyNullSyncedAt() = runTest {
        dao.insert(Transaction(type = TransactionType.SALE, item = "unsynced", totalAmount = 10.0, createdAt = now, syncedAt = null))
        dao.insert(Transaction(type = TransactionType.SALE, item = "synced", totalAmount = 20.0, createdAt = now, syncedAt = now))

        val unsynced = dao.getUnsyncedTransactions()
        assertEquals(1, unsynced.size)
        assertEquals("unsynced", unsynced[0].item)
    }

    @Test
    fun markAsSynced_updatesSyncTimestamp() = runTest {
        val id = dao.insert(Transaction(type = TransactionType.SALE, item = "test", totalAmount = 10.0, createdAt = now, syncedAt = null))
        dao.markAsSynced(listOf(id), now)

        val unsynced = dao.getUnsyncedTransactions()
        assertTrue(unsynced.isEmpty())
    }
}

package com.msaidizi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msaidizi.app.core.model.InventoryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: InventoryDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.inventoryDao()
    }

    @After
    fun closeDb() { database.close() }

    @Test
    fun upsertAndRetrieve() = runTest {
        val item = InventoryItem(item = "mandazi", category = "food", currentStock = 50.0, avgCost = 5.0)
        dao.upsert(item)

        val retrieved = dao.getItem("mandazi")
        assertNotNull(retrieved)
        assertEquals(50.0, retrieved!!.currentStock, 0.01)
        assertEquals("food", retrieved.category)
    }

    @Test
    fun upsert_replacesOnConflict() = runTest {
        dao.upsert(InventoryItem(item = "chapati", currentStock = 20.0))
        dao.upsert(InventoryItem(item = "chapati", currentStock = 30.0))

        val item = dao.getItem("chapati")
        assertEquals(30.0, item!!.currentStock, 0.01)
    }

    @Test
    fun decrementStock_reducesStock() = runTest {
        dao.upsert(InventoryItem(item = "mandazi", currentStock = 50.0))
        dao.decrementStock("mandazi", 10.0)

        val stock = dao.getStock("mandazi")
        assertEquals(40.0, stock, 0.01)
    }

    @Test
    fun incrementStock_increasesStock() = runTest {
        dao.upsert(InventoryItem(item = "unga", currentStock = 10.0, avgCost = 100.0))
        dao.incrementStock("unga", 20.0, 110.0)

        val stock = dao.getStock("unga")
        assertEquals(30.0, stock, 0.01)

        val avgCost = dao.getAverageCost("unga")
        assertEquals(110.0, avgCost, 0.01)
    }

    @Test
    fun getInStockItems_returnsOnlyPositiveStock() = runTest {
        dao.upsert(InventoryItem(item = "in_stock", currentStock = 10.0))
        dao.upsert(InventoryItem(item = "out_of_stock", currentStock = 0.0))

        val items = dao.getInStockItems()
        assertEquals(1, items.size)
        assertEquals("in_stock", items[0].item)
    }

    @Test
    fun getItemsNeedingRestock_returnsLowStockItems() = runTest {
        dao.upsert(InventoryItem(item = "low", currentStock = 2.0, restockThreshold = 5.0))
        dao.upsert(InventoryItem(item = "ok", currentStock = 20.0, restockThreshold = 5.0))

        val items = dao.getItemsNeedingRestock()
        assertEquals(1, items.size)
        assertEquals("low", items[0].item)
    }

    @Test
    fun getAllItems_returnsAll() = runTest {
        dao.upsert(InventoryItem(item = "a", currentStock = 1.0))
        dao.upsert(InventoryItem(item = "b", currentStock = 2.0))
        dao.upsert(InventoryItem(item = "c", currentStock = 3.0))

        val items = dao.getAllItems().first()
        assertEquals(3, items.size)
    }

    @Test
    fun delete_removesItem() = runTest {
        dao.upsert(InventoryItem(item = "test", currentStock = 10.0))
        dao.delete("test")

        assertNull(dao.getItem("test"))
    }

    @Test
    fun deleteAll_removesAll() = runTest {
        dao.upsert(InventoryItem(item = "a", currentStock = 1.0))
        dao.upsert(InventoryItem(item = "b", currentStock = 2.0))
        dao.deleteAll()

        val items = dao.getAllItems().first()
        assertTrue(items.isEmpty())
    }
}

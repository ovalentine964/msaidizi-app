package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.InventoryEntity

@Dao
interface InventoryDao {
    @Insert
    suspend fun insert(item: InventoryEntity): Long

    @Update
    suspend fun update(item: InventoryEntity)

    @Delete
    suspend fun delete(item: InventoryEntity)

    @Query("SELECT * FROM inventory WHERE workerId = :workerId ORDER BY itemName ASC")
    suspend fun getByWorker(workerId: String): List<InventoryEntity>

    @Query("SELECT * FROM inventory WHERE id = :id")
    suspend fun getById(id: Long): InventoryEntity?

    @Query("SELECT * FROM inventory WHERE itemName LIKE '%' || :name || '%' AND workerId = :workerId")
    suspend fun searchByName(workerId: String, name: String): List<InventoryEntity>

    @Query("""
        SELECT * FROM inventory 
        WHERE workerId = :workerId AND quantity <= reorderLevel
    """)
    suspend fun getLowStock(workerId: String): List<InventoryEntity>

    @Query("""
        UPDATE inventory SET quantity = quantity - :amount, lastUpdated = :timestamp
        WHERE itemName = :itemName AND workerId = :workerId AND quantity >= :amount
    """)
    suspend fun decrementStock(workerId: String, itemName: String, amount: Double, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE inventory SET quantity = quantity + :amount, lastUpdated = :timestamp
        WHERE itemName = :itemName AND workerId = :workerId
    """)
    suspend fun incrementStock(workerId: String, itemName: String, amount: Double, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE inventory SET quantity = :quantity, lastUpdated = :timestamp
        WHERE itemName = :itemName AND workerId = :workerId
    """)
    suspend fun setStock(workerId: String, itemName: String, quantity: Double, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT SUM(quantity * unitCost) FROM inventory WHERE workerId = :workerId")
    suspend fun getTotalInventoryValue(workerId: String): Double?

    @Query("SELECT COUNT(*) FROM inventory WHERE workerId = :workerId")
    suspend fun getItemCount(workerId: String): Int

    @Query("SELECT * FROM inventory WHERE synced = 0")
    suspend fun getUnsynced(): List<InventoryEntity>

    @Query("UPDATE inventory SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

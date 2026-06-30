package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.InventoryItem
import com.msaidizi.app.core.model.RestockAlert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for inventory management.
 * Tracks stock levels, costs, and restocking patterns.
 */
@Dao
interface InventoryDao {

    // === INSERT / UPDATE ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: InventoryItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InventoryItem>)

    @Update
    suspend fun update(item: InventoryItem)

    // === STOCK OPERATIONS ===

    /**
     * Decrement stock for an item.
     * Called after recording a sale.
     */
    @Query("""
        UPDATE inventory 
        SET currentStock = currentStock - :quantity, 
            updatedAt = :now 
        WHERE item = :item
    """)
    suspend fun decrementStock(
        item: String,
        quantity: Double,
        now: Long = System.currentTimeMillis() / 1000
    )

    /**
     * Increment stock for an item.
     * Called after recording a purchase.
     */
    @Query("""
        UPDATE inventory 
        SET currentStock = currentStock + :quantity, 
            avgCost = :newAvgCost,
            lastRestockedAt = :now,
            updatedAt = :now 
        WHERE item = :item
    """)
    suspend fun incrementStock(
        item: String,
        quantity: Double,
        newAvgCost: Double,
        now: Long = System.currentTimeMillis() / 1000
    )

    // === QUERIES ===

    @Query("SELECT * FROM inventory WHERE item = :item")
    suspend fun getItem(item: String): InventoryItem?

    @Query("SELECT * FROM inventory WHERE item = :item")
    fun getItemFlow(item: String): Flow<InventoryItem?>

    @Query("SELECT * FROM inventory ORDER BY item ASC")
    fun getAllItems(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory WHERE currentStock > 0 ORDER BY item ASC")
    suspend fun getInStockItems(): List<InventoryItem>

    /**
     * Get current stock for an item.
     */
    @Query("SELECT COALESCE(currentStock, 0.0) FROM inventory WHERE item = :item")
    suspend fun getStock(item: String): Double

    /**
     * Get items that need restocking.
     * Items where currentStock <= restockThreshold.
     */
    @Query("""
        SELECT * FROM inventory 
        WHERE currentStock <= restockThreshold 
        ORDER BY currentStock / restockThreshold ASC
    """)
    suspend fun getItemsNeedingRestock(): List<InventoryItem>

    /**
     * Get average cost for an item.
     */
    @Query("SELECT COALESCE(avgCost, 0.0) FROM inventory WHERE item = :item")
    suspend fun getAverageCost(item: String): Double

    // === PATTERN UPDATES ===

    @Query("""
        UPDATE inventory 
        SET predictedIntervalMs = :intervalMs, 
            predictedQuantity = :quantity,
            updatedAt = :now 
        WHERE item = :item
    """)
    suspend fun updateRestockPrediction(
        item: String,
        intervalMs: Long,
        quantity: Double,
        now: Long = System.currentTimeMillis() / 1000
    )

    // === DELETE ===

    @Query("DELETE FROM inventory WHERE item = :item")
    suspend fun delete(item: String)

    @Query("DELETE FROM inventory")
    suspend fun deleteAll()
}

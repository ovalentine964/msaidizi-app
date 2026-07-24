package com.msaidizi.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msaidizi.core.database.entity.InventoryItemEntity
import com.msaidizi.core.database.entity.MaterialInventoryEntity
import com.msaidizi.core.database.entity.SpoilageRecordEntity
import com.msaidizi.core.database.entity.ToolEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for inventory items with perishability tracking.
 */
@Dao
interface InventoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InventoryItemEntity): Long

    @Update
    suspend fun update(item: InventoryItemEntity)

    @Delete
    suspend fun delete(item: InventoryItemEntity)

    @Query("SELECT * FROM inventory_items WHERE id = :id")
    suspend fun getById(id: Long): InventoryItemEntity?

    @Query("SELECT * FROM inventory_items ORDER BY item_name ASC")
    fun getAll(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE item_name LIKE '%' || :name || '%'")
    suspend fun getByName(name: String): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE quantity <= reorder_level")
    suspend fun getBelowReorderLevel(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE is_approaching_expiry = 1")
    suspend fun getApproachingExpiry(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE is_expired = 1")
    suspend fun getExpired(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE category = :category ORDER BY item_name ASC")
    suspend fun getByCategory(category: String): List<InventoryItemEntity>

    @Query("SELECT COALESCE(SUM(total_stock_value), 0.0) FROM inventory_items")
    suspend fun getTotalStockValue(): Double

    @Query("SELECT COUNT(*) FROM inventory_items")
    suspend fun getItemCount(): Int

    @Query("UPDATE inventory_items SET quantity = quantity + :addQuantity, total_stock_value = (quantity + :addQuantity) * unit_cost, updated_at = :now WHERE id = :id")
    suspend fun addStock(id: Long, addQuantity: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE inventory_items SET quantity = quantity - :removeQuantity, total_stock_value = (quantity - :removeQuantity) * unit_cost, updated_at = :now WHERE id = :id AND quantity >= :removeQuantity")
    suspend fun removeStock(id: Long, removeQuantity: Double, now: Long = System.currentTimeMillis())
}

/**
 * DAO for tool tracking.
 */
@Dao
interface ToolDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tool: ToolEntity): Long

    @Update
    suspend fun update(tool: ToolEntity)

    @Delete
    suspend fun delete(tool: ToolEntity)

    @Query("SELECT * FROM tools WHERE id = :id")
    suspend fun getById(id: Long): ToolEntity?

    @Query("SELECT * FROM tools ORDER BY tool_name ASC")
    fun getAll(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE is_in_use = 1")
    suspend fun getActiveTools(): List<ToolEntity>

    @Query("SELECT * FROM tools WHERE condition = 'broken'")
    suspend fun getBrokenTools(): List<ToolEntity>

    @Query("SELECT COALESCE(SUM(current_value), 0.0) FROM tools WHERE is_in_use = 1")
    suspend fun getTotalToolValue(): Double

    @Query("SELECT COALESCE(SUM(total_maintenance_cost), 0.0) FROM tools")
    suspend fun getTotalMaintenanceCost(): Double
}

/**
 * DAO for material inventory with reorder levels.
 */
@Dao
interface MaterialInventoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: MaterialInventoryEntity): Long

    @Update
    suspend fun update(material: MaterialInventoryEntity)

    @Delete
    suspend fun delete(material: MaterialInventoryEntity)

    @Query("SELECT * FROM material_inventory WHERE id = :id")
    suspend fun getById(id: Long): MaterialInventoryEntity?

    @Query("SELECT * FROM material_inventory ORDER BY material_name ASC")
    fun getAll(): Flow<List<MaterialInventoryEntity>>

    @Query("SELECT * FROM material_inventory WHERE is_below_reorder_level = 1")
    suspend fun getBelowReorderLevel(): List<MaterialInventoryEntity>

    @Query("SELECT * FROM material_inventory WHERE will_stock_out = 1")
    suspend fun getWillStockOut(): List<MaterialInventoryEntity>

    @Query("SELECT * FROM material_inventory WHERE price_increased = 1")
    suspend fun getPriceIncreased(): List<MaterialInventoryEntity>
}

/**
 * DAO for spoilage records.
 */
@Dao
interface SpoilageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SpoilageRecordEntity): Long

    @Query("SELECT * FROM spoilage_records ORDER BY recorded_at DESC")
    fun getAll(): Flow<List<SpoilageRecordEntity>>

    @Query("SELECT * FROM spoilage_records WHERE recorded_at >= :since ORDER BY recorded_at DESC")
    suspend fun getSince(since: Long): List<SpoilageRecordEntity>

    @Query("SELECT COALESCE(SUM(estimated_cost), 0.0) FROM spoilage_records WHERE recorded_at >= :since")
    suspend fun getTotalSpoilageCostSince(since: Long): Double

    @Query("SELECT COUNT(*) FROM spoilage_records WHERE recorded_at >= :since")
    suspend fun getSpoilageCountSince(since: Long): Int
}

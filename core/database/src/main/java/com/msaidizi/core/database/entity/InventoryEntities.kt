package com.msaidizi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for inventory items with perishability tracking.
 */
@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["item_name"]),
        Index(value = ["is_approaching_expiry"]),
        Index(value = ["is_expired"]),
        Index(value = ["category"])
    ]
)
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "item_name")
    val itemName: String = "",

    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    @ColumnInfo(name = "subcategory", defaultValue = "")
    val subcategory: String = "",

    @ColumnInfo(name = "supplier", defaultValue = "")
    val supplier: String = "",

    @ColumnInfo(name = "quantity", defaultValue = "0.0")
    val quantity: Double = 0.0,

    @ColumnInfo(name = "unit", defaultValue = "pieces")
    val unit: String = "pieces",

    @ColumnInfo(name = "reorder_level", defaultValue = "0.0")
    val reorderLevel: Double = 0.0,

    @ColumnInfo(name = "reorder_quantity", defaultValue = "0.0")
    val reorderQuantity: Double = 0.0,

    @ColumnInfo(name = "unit_cost", defaultValue = "0.0")
    val unitCost: Double = 0.0,

    @ColumnInfo(name = "selling_price", defaultValue = "0.0")
    val sellingPrice: Double = 0.0,

    @ColumnInfo(name = "total_stock_value", defaultValue = "0.0")
    val totalStockValue: Double = 0.0,

    @ColumnInfo(name = "shelf_life_days", defaultValue = "0")
    val shelfLifeDays: Int = 0,

    @ColumnInfo(name = "purchase_date")
    val purchaseDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expiry_date", defaultValue = "0")
    val expiryDate: Long = 0L,

    @ColumnInfo(name = "spoilage_alert_threshold", defaultValue = "0.8")
    val spoilageAlertThreshold: Double = 0.8,

    @ColumnInfo(name = "is_approaching_expiry", defaultValue = "0")
    val isApproachingExpiry: Boolean = false,

    @ColumnInfo(name = "is_expired", defaultValue = "0")
    val isExpired: Boolean = false,

    @ColumnInfo(name = "storage_location", defaultValue = "")
    val storageLocation: String = "",

    @ColumnInfo(name = "batch_id", defaultValue = "")
    val batchId: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

/**
 * Room entity for tool tracking with depreciation.
 */
@Entity(
    tableName = "tools",
    indices = [
        Index(value = ["tool_name"]),
        Index(value = ["category"]),
        Index(value = ["is_in_use"])
    ]
)
data class ToolEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "tool_name")
    val toolName: String = "",

    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    @ColumnInfo(name = "brand", defaultValue = "")
    val brand: String = "",

    @ColumnInfo(name = "serial_number", defaultValue = "")
    val serialNumber: String = "",

    @ColumnInfo(name = "purchase_price", defaultValue = "0.0")
    val purchasePrice: Double = 0.0,

    @ColumnInfo(name = "current_value", defaultValue = "0.0")
    val currentValue: Double = 0.0,

    @ColumnInfo(name = "depreciation_method", defaultValue = "straight_line")
    val depreciationMethod: String = "straight_line",

    @ColumnInfo(name = "useful_life_months", defaultValue = "24")
    val usefulLifeMonths: Int = 24,

    @ColumnInfo(name = "salvage_value", defaultValue = "0.0")
    val salvageValue: Double = 0.0,

    @ColumnInfo(name = "monthly_depreciation", defaultValue = "0.0")
    val monthlyDepreciation: Double = 0.0,

    @ColumnInfo(name = "condition", defaultValue = "good")
    val condition: String = "good",

    @ColumnInfo(name = "last_maintenance_date")
    val lastMaintenanceDate: Long? = null,

    @ColumnInfo(name = "next_maintenance_date")
    val nextMaintenanceDate: Long? = null,

    @ColumnInfo(name = "total_maintenance_cost", defaultValue = "0.0")
    val totalMaintenanceCost: Double = 0.0,

    @ColumnInfo(name = "is_in_use", defaultValue = "1")
    val isInUse: Boolean = true,

    @ColumnInfo(name = "location", defaultValue = "")
    val location: String = "",

    @ColumnInfo(name = "used_by", defaultValue = "")
    val usedBy: String = "",

    @ColumnInfo(name = "purchase_date")
    val purchaseDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

/**
 * Room entity for material inventory with reorder levels.
 */
@Entity(
    tableName = "material_inventory",
    indices = [
        Index(value = ["material_name"]),
        Index(value = ["is_below_reorder_level"]),
        Index(value = ["supplier"])
    ]
)
data class MaterialInventoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "material_name")
    val materialName: String = "",

    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    @ColumnInfo(name = "supplier", defaultValue = "")
    val supplier: String = "",

    @ColumnInfo(name = "supplier_phone", defaultValue = "")
    val supplierPhone: String = "",

    @ColumnInfo(name = "current_stock", defaultValue = "0.0")
    val currentStock: Double = 0.0,

    @ColumnInfo(name = "unit", defaultValue = "kg")
    val unit: String = "kg",

    @ColumnInfo(name = "reorder_level", defaultValue = "0.0")
    val reorderLevel: Double = 0.0,

    @ColumnInfo(name = "reorder_quantity", defaultValue = "0.0")
    val reorderQuantity: Double = 0.0,

    @ColumnInfo(name = "safety_stock", defaultValue = "0.0")
    val safetyStock: Double = 0.0,

    @ColumnInfo(name = "current_price", defaultValue = "0.0")
    val currentPrice: Double = 0.0,

    @ColumnInfo(name = "previous_price", defaultValue = "0.0")
    val previousPrice: Double = 0.0,

    @ColumnInfo(name = "avg_price_30_days", defaultValue = "0.0")
    val avgPrice30Days: Double = 0.0,

    @ColumnInfo(name = "price_trend", defaultValue = "stable")
    val priceTrend: String = "stable",

    @ColumnInfo(name = "avg_daily_usage", defaultValue = "0.0")
    val avgDailyUsage: Double = 0.0,

    @ColumnInfo(name = "days_of_stock_remaining", defaultValue = "0")
    val daysOfStockRemaining: Int = 0,

    @ColumnInfo(name = "last_restock_date")
    val lastRestockDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "lead_time_days", defaultValue = "1")
    val leadTimeDays: Int = 1,

    @ColumnInfo(name = "is_below_reorder_level", defaultValue = "0")
    val isBelowReorderLevel: Boolean = false,

    @ColumnInfo(name = "will_stock_out", defaultValue = "0")
    val willStockOut: Boolean = false,

    @ColumnInfo(name = "price_increased", defaultValue = "0")
    val priceIncreased: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

/**
 * Room entity for spoilage/waste records.
 */
@Entity(
    tableName = "spoilage_records",
    indices = [
        Index(value = ["inventory_item_id"]),
        Index(value = ["recorded_at"])
    ]
)
data class SpoilageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "inventory_item_id", defaultValue = "0")
    val inventoryItemId: Long = 0,

    @ColumnInfo(name = "item_name")
    val itemName: String = "",

    @ColumnInfo(name = "quantity_spoiled", defaultValue = "0.0")
    val quantitySpoiled: Double = 0.0,

    @ColumnInfo(name = "unit", defaultValue = "pieces")
    val unit: String = "pieces",

    @ColumnInfo(name = "unit_cost", defaultValue = "0.0")
    val unitCost: Double = 0.0,

    @ColumnInfo(name = "estimated_cost", defaultValue = "0.0")
    val estimatedCost: Double = 0.0,

    @ColumnInfo(name = "reason", defaultValue = "EXPIRED")
    val reason: String = "EXPIRED",

    @ColumnInfo(name = "reason_detail", defaultValue = "")
    val reasonDetail: String = "",

    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "location_name", defaultValue = "")
    val locationName: String = "",

    @ColumnInfo(name = "preventable", defaultValue = "1")
    val preventable: Boolean = true,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

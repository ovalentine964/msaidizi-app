package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemName: String,
    val quantity: Double = 0.0,
    val unit: String = "pieces",
    val unitCost: Double = 0.0,
    val reorderLevel: Double = 5.0,
    val category: String = "",
    val workerId: String = "default",
    val lastUpdated: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val vectorClockJson: String = "{}"
)

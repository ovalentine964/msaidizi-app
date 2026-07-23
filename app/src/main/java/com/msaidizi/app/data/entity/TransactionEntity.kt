package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val item: String,
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val amount: Double,
    val category: String = "",
    val notes: String = "",
    val mpesaCode: String? = null,
    val workerId: String = "default",
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val vectorClockJson: String = "{}"
)

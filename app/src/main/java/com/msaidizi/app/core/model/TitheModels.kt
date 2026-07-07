package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tithe_records",
    indices = [
        Index(value = ["date"]),
        Index(value = ["type"]),
        // Composite: queries filter by type + date range
        Index(value = ["type", "date"]),
        // Composite: date range queries with amount aggregation
        Index(value = ["date", "amount"])
    ]
)
data class TitheRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // TITHE, OFFERING, ZAKAT, SADAQAH, CHARITY, OTHER
    val amount: Double,
    val recipient: String = "",
    val date: Long,             // Unix timestamp (milliseconds)
    val category: String = "",
    val notes: String = "",
    val incomeAtTime: Double = 0.0,
    val inputMethod: String = "VOICE",
    val createdAt: Long = System.currentTimeMillis()
)

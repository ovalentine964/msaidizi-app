package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "giving")
data class GivingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amount: Double,
    val recipient: String = "",
    val notes: String = "",
    val workerId: String = "default",
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val vectorClockJson: String = "{}"
)

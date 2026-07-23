package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lender: String,
    val amount: Double,
    val remainingAmount: Double,
    val interestRate: Double = 0.0,
    val dueDate: Long? = null,
    val status: String = "active",
    val notes: String = "",
    val workerId: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val vectorClockJson: String = "{}"
)

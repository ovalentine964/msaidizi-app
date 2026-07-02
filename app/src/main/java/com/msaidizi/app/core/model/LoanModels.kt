package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "loan_records",
    indices = [
        Index(value = ["status"]),
        Index(value = ["purpose"])
    ]
)
data class LoanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val purpose: String,
    val lender: String = "",
    val interestRate: Double = 0.0,
    val totalDue: Double = 0.0,
    val startDate: Long,             // Unix timestamp in seconds
    val endDate: Long = 0,           // Unix timestamp in seconds
    val repaymentFrequency: String = "MONTHLY",  // DAILY, WEEKLY, MONTHLY
    val totalRepaid: Double = 0.0,
    val status: String = "ACTIVE",   // ACTIVE, COMPLETED, DEFAULTED, OVERDUE
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

@Entity(
    tableName = "loan_repayments",
    foreignKeys = [
        ForeignKey(
            entity = LoanRecord::class,
            parentColumns = ["id"],
            childColumns = ["loanId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["loanId"]),
        Index(value = ["status"])
    ]
)
data class LoanRepayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val amount: Double,
    val dueDate: Long,               // Unix timestamp in seconds
    val paidDate: Long? = null,
    val paidAmount: Double? = null,
    val status: String = "PENDING",  // PENDING, PAID, OVERDUE, PARTIAL
    val penalty: Double = 0.0
)

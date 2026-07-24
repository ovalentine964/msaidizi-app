package com.msaidizi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for client profiles (recurring customers).
 */
@Entity(
    tableName = "client_profiles",
    indices = [
        Index(value = ["name"]),
        Index(value = ["is_recurring"])
    ]
)
data class ClientProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "phone_number", defaultValue = "")
    val phoneNumber: String = "",

    @ColumnInfo(name = "relationship", defaultValue = "")
    val relationship: String = "",

    @ColumnInfo(name = "total_transactions", defaultValue = "0")
    val totalTransactions: Int = 0,

    @ColumnInfo(name = "total_spent", defaultValue = "0.0")
    val totalSpent: Double = 0.0,

    @ColumnInfo(name = "avg_transaction_amount", defaultValue = "0.0")
    val avgTransactionAmount: Double = 0.0,

    @ColumnInfo(name = "frequent_items", defaultValue = "")
    val frequentItems: String = "",

    @ColumnInfo(name = "preferred_payment", defaultValue = "cash")
    val preferredPayment: String = "cash",

    @ColumnInfo(name = "has_credit", defaultValue = "0")
    val hasCredit: Boolean = false,

    @ColumnInfo(name = "credit_balance", defaultValue = "0.0")
    val creditBalance: Double = 0.0,

    @ColumnInfo(name = "total_credit_given", defaultValue = "0.0")
    val totalCreditGiven: Double = 0.0,

    @ColumnInfo(name = "total_credit_repaid", defaultValue = "0.0")
    val totalCreditRepaid: Double = 0.0,

    @ColumnInfo(name = "credit_reliability", defaultValue = "0.0")
    val creditReliability: Double = 0.0,

    @ColumnInfo(name = "visit_frequency", defaultValue = "occasional")
    val visitFrequency: String = "occasional",

    @ColumnInfo(name = "typical_visit_day", defaultValue = "0")
    val typicalVisitDay: Int = 0,

    @ColumnInfo(name = "days_since_last_visit", defaultValue = "0")
    val daysSinceLastVisit: Int = 0,

    @ColumnInfo(name = "is_recurring", defaultValue = "0")
    val isRecurring: Boolean = false,

    @ColumnInfo(name = "first_transaction_at")
    val firstTransactionAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_transaction_at")
    val lastTransactionAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

/**
 * Room entity for projects (multi-day work).
 */
@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["client_name"])
    ]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "client_name", defaultValue = "")
    val clientName: String = "",

    @ColumnInfo(name = "client_phone", defaultValue = "")
    val clientPhone: String = "",

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    @ColumnInfo(name = "quoted_price", defaultValue = "0.0")
    val quotedPrice: Double = 0.0,

    @ColumnInfo(name = "total_paid", defaultValue = "0.0")
    val totalPaid: Double = 0.0,

    @ColumnInfo(name = "total_expenses", defaultValue = "0.0")
    val totalExpenses: Double = 0.0,

    @ColumnInfo(name = "estimated_profit", defaultValue = "0.0")
    val estimatedProfit: Double = 0.0,

    @ColumnInfo(name = "payment_method", defaultValue = "cash")
    val paymentMethod: String = "cash",

    @ColumnInfo(name = "start_date")
    val startDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expected_end_date", defaultValue = "0")
    val expectedEndDate: Long = 0L,

    @ColumnInfo(name = "actual_end_date")
    val actualEndDate: Long? = null,

    @ColumnInfo(name = "phase", defaultValue = "planning")
    val phase: String = "planning",

    @ColumnInfo(name = "milestones_completed", defaultValue = "")
    val milestonesCompleted: String = "",

    @ColumnInfo(name = "total_milestones", defaultValue = "0")
    val totalMilestones: Int = 0,

    @ColumnInfo(name = "progress_percent", defaultValue = "0")
    val progressPercent: Int = 0,

    @ColumnInfo(name = "total_visits", defaultValue = "0")
    val totalVisits: Int = 0,

    @ColumnInfo(name = "total_hours", defaultValue = "0.0")
    val totalHours: Double = 0.0,

    @ColumnInfo(name = "materials_used", defaultValue = "")
    val materialsUsed: String = "",

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @ColumnInfo(name = "has_outstanding_balance", defaultValue = "0")
    val hasOutstandingBalance: Boolean = false,

    @ColumnInfo(name = "outstanding_amount", defaultValue = "0.0")
    val outstandingAmount: Double = 0.0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

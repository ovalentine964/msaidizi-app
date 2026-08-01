package com.msaidizi.core.model

import androidx.room.*

// ──────────────────────────────────────────────
// Hire-Purchase Agreement Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "hire_purchase_agreements",
    indices = [
        Index(value = ["isActive"]),
        Index(value = ["ownerPhone"])
    ]
)
data class HirePurchaseAgreementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerName: String,                 // who owns the motorcycle
    val ownerPhone: String = "",
    val motorcycleDescription: String = "", // make/model/color
    val dailyFee: Double,                  // KES per day
    val depositPaid: Double = 0.0,         // initial deposit if any
    val startDate: String = "",            // YYYY-MM-DD
    val endDate: String? = null,           // if fixed term
    val totalPurchasePrice: Double? = null, // agreed buyout price
    val isActive: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Hire Payment Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "hire_payments",
    indices = [Index(value = ["agreementId", "date"])]
)
data class HirePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agreementId: Long,
    val amount: Double,                    // amount paid
    val paymentType: String = "daily_fee", // daily_fee | deposit | buyout | other
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

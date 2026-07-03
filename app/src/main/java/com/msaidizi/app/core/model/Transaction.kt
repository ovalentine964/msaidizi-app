package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Core transaction entity for business records.
 * Stores sales, purchases, expenses, and other financial events.
 *
 * Optimized for SQLite on 2GB devices:
 * - Uses Unix timestamps (4 bytes) instead of datetime strings
 * - Minimal string fields
 * - Indexed on date and type for fast queries
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["type"]),
        Index(value = ["item"]),
        Index(value = ["syncedAt"])
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Transaction type: SALE, PURCHASE, EXPENSE, OTHER */
    val type: TransactionType,

    /** Item name as spoken by user (e.g., "mandazi", "unga") */
    val item: String,

    /** Auto-classified category (e.g., "food", "transport") */
    val category: String = "",

    /** Quantity sold/purchased */
    val quantity: Double = 1.0,

    /** Unit price in KSh */
    val unitPrice: Double = 0.0,

    /** Total amount in KSh */
    val totalAmount: Double,

    /** Cost basis for profit calculation */
    val costBasis: Double = 0.0,

    /** Payment method: cash, mpesa, credit */
    val paymentMethod: String = "cash",

    /** Customer name (optional) */
    val customer: String = "",

    /** Additional notes */
    val notes: String = "",

    /** Unix timestamp in seconds */
    val createdAt: Long = System.currentTimeMillis() / 1000,

    /** Unix timestamp when synced to cloud, null if not synced */
    val syncedAt: Long? = null,

    /** ASR confidence score (0.0-1.0) if voice-recorded */
    val confidence: Float = 1.0f,

    /** Language used for this transaction */
    val language: String = "sw"
)

enum class TransactionType {
    SALE,
    PURCHASE,
    EXPENSE,
    WITHDRAWAL,
    DEPOSIT,
    FEE,
    REFUND,
    OTHER
}

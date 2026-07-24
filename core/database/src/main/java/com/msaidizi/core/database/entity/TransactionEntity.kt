package com.msaidizi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for financial transactions.
 *
 * Every transaction is a proof point in the M-KOPA model.
 * Indexed by createdAt for time-range queries and syncBatchId for sync.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["type"]),
        Index(value = ["sync_batch_id"]),
        Index(value = ["backend_transaction_id"]),
        Index(value = ["is_synced"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ═══ WHAT ═══
    @ColumnInfo(name = "type")
    val type: String,  // SALE, PURCHASE, EXPENSE, SPOILAGE

    @ColumnInfo(name = "item")
    val item: String,

    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    @ColumnInfo(name = "subcategory", defaultValue = "")
    val subcategory: String = "",

    @ColumnInfo(name = "product_code", defaultValue = "")
    val productCode: String = "",

    // ═══ HOW MANY ═══
    @ColumnInfo(name = "quantity", defaultValue = "1.0")
    val quantity: Double = 1.0,

    @ColumnInfo(name = "unit", defaultValue = "pieces")
    val unit: String = "pieces",

    // ═══ HOW MUCH ═══
    @ColumnInfo(name = "unit_price", defaultValue = "0.0")
    val unitPrice: Double = 0.0,

    @ColumnInfo(name = "total_amount")
    val totalAmount: Double,

    @ColumnInfo(name = "cost_basis", defaultValue = "0.0")
    val costBasis: Double = 0.0,

    @ColumnInfo(name = "margin", defaultValue = "0.0")
    val margin: Double = 0.0,

    @ColumnInfo(name = "margin_percent", defaultValue = "0.0")
    val marginPercent: Double = 0.0,

    @ColumnInfo(name = "currency", defaultValue = "KSh")
    val currency: String = "KSh",

    // ═══ WHEN ═══
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "time_of_day", defaultValue = "")
    val timeOfDay: String = "",

    @ColumnInfo(name = "day_of_week", defaultValue = "0")
    val dayOfWeek: Int = 0,

    @ColumnInfo(name = "is_weekend", defaultValue = "0")
    val isWeekend: Boolean = false,

    @ColumnInfo(name = "month", defaultValue = "0")
    val month: Int = 0,

    // ═══ WHERE ═══
    @ColumnInfo(name = "location_lat")
    val locationLat: Double? = null,

    @ColumnInfo(name = "location_lng")
    val locationLng: Double? = null,

    @ColumnInfo(name = "location_name", defaultValue = "")
    val locationName: String = "",

    @ColumnInfo(name = "market_id", defaultValue = "")
    val marketId: String = "",

    // ═══ WHO ═══
    @ColumnInfo(name = "customer", defaultValue = "")
    val customer: String = "",

    @ColumnInfo(name = "supplier", defaultValue = "")
    val supplier: String = "",

    @ColumnInfo(name = "is_recurring_customer", defaultValue = "0")
    val isRecurringCustomer: Boolean = false,

    // ═══ HOW ═══
    @ColumnInfo(name = "payment_method", defaultValue = "cash")
    val paymentMethod: String = "cash",

    @ColumnInfo(name = "mpesa_code", defaultValue = "")
    val mpesaCode: String = "",

    @ColumnInfo(name = "is_on_credit", defaultValue = "0")
    val isOnCredit: Boolean = false,

    @ColumnInfo(name = "credit_due_date")
    val creditDueDate: Long? = null,

    // ═══ PROOF ═══
    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "language", defaultValue = "sw")
    val language: String = "sw",

    @ColumnInfo(name = "dialect", defaultValue = "")
    val dialect: String = "",

    @ColumnInfo(name = "has_receipt", defaultValue = "0")
    val hasReceipt: Boolean = false,

    @ColumnInfo(name = "receipt_image_url", defaultValue = "")
    val receiptImageUrl: String = "",

    @ColumnInfo(name = "verification_source", defaultValue = "voice")
    val verificationSource: String = "voice",

    // ═══ SYNC ═══
    @ColumnInfo(name = "is_synced", defaultValue = "0")
    val isSynced: Boolean = false,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null,

    @ColumnInfo(name = "sync_batch_id", defaultValue = "")
    val syncBatchId: String = "",

    @ColumnInfo(name = "backend_transaction_id", defaultValue = "")
    val backendTransactionId: String = "",

    // ═══ NOTES ═══
    @ColumnInfo(name = "notes", defaultValue = "")
    val notes: String = ""
)

package com.msaidizi.app.superagent.mpesa

import androidx.room.*

/**
 * Room entity for storing parsed M-Pesa transactions.
 * Used for:
 * - Deduplication (by receipt number)
 * - Audit trail
 * - Reconciliation matching
 * - Historical analysis
 */
@Entity(
    tableName = "mpesa_transactions",
    indices = [
        Index(value = ["receipt"], unique = true),
        Index(value = ["phone"]),
        Index(value = ["transactionDate"]),
        Index(value = ["isReconciled"])
    ]
)
data class MpesaTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receipt: String,
    val type: String,
    val amount: Double,
    val counterparty: String,
    val phone: String,
    val transactionDate: String,
    val balance: Double?,
    val category: String,
    val confidence: Float,
    val rawSms: String,
    val isReconciled: Boolean = false,
    val reconciledRecordId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * DAO for M-Pesa transaction records.
 */
@androidx.room.Dao
interface MpesaTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: MpesaTransactionEntity): Long

    @Query("SELECT * FROM mpesa_transactions WHERE receipt = :receipt LIMIT 1")
    suspend fun findByReceipt(receipt: String): MpesaTransactionEntity?

    @Query("SELECT * FROM mpesa_transactions WHERE phone = :phone ORDER BY createdAt DESC")
    suspend fun findByPhone(phone: String): List<MpesaTransactionEntity>

    @Query("SELECT * FROM mpesa_transactions WHERE isReconciled = 0 ORDER BY createdAt DESC")
    suspend fun getUnreconciled(): List<MpesaTransactionEntity>

    @Query("SELECT * FROM mpesa_transactions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MpesaTransactionEntity>

    @Query("UPDATE mpesa_transactions SET isReconciled = 1, reconciledRecordId = :recordId WHERE id = :id")
    suspend fun markReconciled(id: Long)

    @Query("UPDATE mpesa_transactions SET confidence = :confidence WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float)

    @Query("SELECT COUNT(*) FROM mpesa_transactions WHERE createdAt > :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT SUM(amount) FROM mpesa_transactions WHERE type = 'RECEIVED' AND createdAt > :since")
    suspend fun totalReceivedSince(since: Long): Double?

    @Query("SELECT SUM(amount) FROM mpesa_transactions WHERE type IN ('SENT', 'PAID_GOODS', 'PAYBILL') AND createdAt > :since")
    suspend fun totalSentSince(since: Long): Double?
}

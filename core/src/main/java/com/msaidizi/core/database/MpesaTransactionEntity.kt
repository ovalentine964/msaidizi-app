package com.msaidizi.core.database

import androidx.room.*

/**
 * Room entity for storing parsed M-Pesa transactions.
 * Moved to core so MsaidiziDatabase can reference without circular dependency.
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
@Dao
interface MpesaTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: MpesaTransactionEntity): Long

    @Query("SELECT * FROM mpesa_transactions WHERE receipt = :receipt LIMIT 1")
    suspend fun findByReceipt(receipt: String): MpesaTransactionEntity?

    @Query("SELECT * FROM mpesa_transactions WHERE phone = :phone ORDER BY createdAt DESC")
    suspend fun getByPhone(phone: String): List<MpesaTransactionEntity>

    @Query("SELECT * FROM mpesa_transactions WHERE transactionDate BETWEEN :startDate AND :endDate ORDER BY transactionDate DESC")
    suspend fun getByDateRange(startDate: String, endDate: String): List<MpesaTransactionEntity>

    @Query("SELECT * FROM mpesa_transactions WHERE isReconciled = 0 ORDER BY createdAt DESC")
    suspend fun getUnreconciled(): List<MpesaTransactionEntity>

    @Query("UPDATE mpesa_transactions SET isReconciled = 1, reconciledRecordId = :recordId WHERE receipt = :receipt")
    suspend fun markReconciled(receipt: String, recordId: Long)

    @Query("SELECT COUNT(*) FROM mpesa_transactions")
    suspend fun count(): Int

    @Query("SELECT SUM(amount) FROM mpesa_transactions WHERE type = :type AND transactionDate BETWEEN :startDate AND :endDate")
    suspend fun sumByTypeAndDateRange(type: String, startDate: String, endDate: String): Double?
}

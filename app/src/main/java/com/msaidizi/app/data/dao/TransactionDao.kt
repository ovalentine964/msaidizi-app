package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.TransactionEntity

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE workerId = :workerId ORDER BY timestamp DESC")
    suspend fun getByWorker(workerId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("""
        SELECT * FROM transactions 
        WHERE workerId = :workerId 
        AND timestamp BETWEEN :startTime AND :endTime 
        ORDER BY timestamp DESC
    """)
    suspend fun getByTimeRange(workerId: String, startTime: Long, endTime: Long): List<TransactionEntity>

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE workerId = :workerId AND type = 'SALE' 
        AND timestamp BETWEEN :startTime AND :endTime
    """)
    suspend fun getTotalSales(workerId: String, startTime: Long, endTime: Long): Double?

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE workerId = :workerId AND type = 'PURCHASE' 
        AND timestamp BETWEEN :startTime AND :endTime
    """)
    suspend fun getTotalPurchases(workerId: String, startTime: Long, endTime: Long): Double?

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE workerId = :workerId AND type = 'EXPENSE' 
        AND timestamp BETWEEN :startTime AND :endTime
    """)
    suspend fun getTotalExpenses(workerId: String, startTime: Long, endTime: Long): Double?

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE workerId = :workerId AND type = 'SALE'
    """)
    suspend fun getAllTimeSales(workerId: String): Double?

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE workerId = :workerId AND type IN ('PURCHASE', 'EXPENSE')
    """)
    suspend fun getAllTimeExpenses(workerId: String): Double?

    @Query("SELECT * FROM transactions WHERE workerId = :workerId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastTransaction(workerId: String): TransactionEntity?

    @Query("""
        SELECT item, SUM(amount) as totalAmount, COUNT(*) as count 
        FROM transactions 
        WHERE workerId = :workerId AND type = 'SALE' 
        AND timestamp BETWEEN :startTime AND :endTime
        GROUP BY item 
        ORDER BY totalAmount DESC 
        LIMIT :limit
    """)
    suspend fun getTopSellingItems(workerId: String, startTime: Long, endTime: Long, limit: Int = 5): List<ItemSummary>

    @Query("""
        SELECT COUNT(*) FROM transactions 
        WHERE workerId = :workerId 
        AND timestamp BETWEEN :startTime AND :endTime
    """)
    suspend fun getTransactionCount(workerId: String, startTime: Long, endTime: Long): Int

    @Query("SELECT * FROM transactions WHERE synced = 0")
    suspend fun getUnsynced(): List<TransactionEntity>

    @Query("UPDATE transactions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

data class ItemSummary(
    val item: String,
    val totalAmount: Double,
    val count: Int
)

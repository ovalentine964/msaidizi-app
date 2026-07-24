package com.msaidizi.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msaidizi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for financial transactions.
 * Supports time-range queries, sync operations, and aggregation.
 */
@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY created_at DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<TransactionEntity>

    // ═══ TIME RANGE QUERIES ═══

    @Query("SELECT * FROM transactions WHERE created_at BETWEEN :start AND :end ORDER BY created_at DESC")
    suspend fun getByTimeRange(start: Long, end: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE created_at BETWEEN :start AND :end ORDER BY created_at DESC")
    fun getByTimeRangeFlow(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE created_at >= :todayStart ORDER BY created_at DESC")
    suspend fun getTodayTransactions(todayStart: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE created_at >= :weekStart ORDER BY created_at DESC")
    suspend fun getThisWeekTransactions(weekStart: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE created_at >= :monthStart ORDER BY created_at DESC")
    suspend fun getThisMonthTransactions(monthStart: Long): List<TransactionEntity>

    // ═══ TYPE FILTERED QUERIES ═══

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY created_at DESC")
    suspend fun getByType(type: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE type = 'SALE' AND created_at >= :since ORDER BY created_at DESC")
    suspend fun getSalesSince(since: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE type = 'PURCHASE' AND created_at >= :since ORDER BY created_at DESC")
    suspend fun getPurchasesSince(since: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE type = 'EXPENSE' AND created_at >= :since ORDER BY created_at DESC")
    suspend fun getExpensesSince(since: Long): List<TransactionEntity>

    // ═══ AGGREGATION ═══

    @Query("SELECT COALESCE(SUM(total_amount), 0.0) FROM transactions WHERE type = 'SALE' AND created_at >= :since")
    suspend fun getTotalSalesSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(total_amount), 0.0) FROM transactions WHERE type = 'PURCHASE' AND created_at >= :since")
    suspend fun getTotalPurchasesSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(total_amount), 0.0) FROM transactions WHERE type = 'EXPENSE' AND created_at >= :since")
    suspend fun getTotalExpensesSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(margin), 0.0) FROM transactions WHERE type = 'SALE' AND created_at >= :since")
    suspend fun getTotalProfitSince(since: Long): Double

    @Query("SELECT COUNT(*) FROM transactions WHERE created_at >= :since")
    suspend fun getTransactionCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getTotalTransactionCount(): Int

    @Query("SELECT COUNT(DISTINCT date(created_at, 'unixepoch', 'localtime')) FROM transactions")
    suspend fun getActiveDaysCount(): Int

    // ═══ ITEM QUERIES ═══

    @Query("SELECT * FROM transactions WHERE item LIKE '%' || :itemName || '%' ORDER BY created_at DESC")
    suspend fun getByItem(itemName: String): List<TransactionEntity>

    @Query("SELECT item, COUNT(*) as count FROM transactions WHERE type = 'SALE' GROUP BY item ORDER BY count DESC LIMIT :limit")
    suspend fun getTopSellingItems(limit: Int = 10): List<ItemCount>

    // ═══ SYNC ═══

    @Query("SELECT * FROM transactions WHERE is_synced = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 100): List<TransactionEntity>

    @Query("UPDATE transactions SET is_synced = 1, synced_at = :syncedAt, sync_batch_id = :batchId, backend_transaction_id = :backendId WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, batchId: String, backendId: String)

    @Query("UPDATE transactions SET is_synced = 1, synced_at = :syncedAt, sync_batch_id = :batchId WHERE id IN (:ids)")
    suspend fun markBatchSynced(ids: List<Long>, syncedAt: Long, batchId: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE is_synced = 0")
    suspend fun getUnsyncedCount(): Int

    // ═══ CREDIT ═══

    @Query("SELECT * FROM transactions WHERE is_on_credit = 1 AND credit_due_date IS NOT NULL AND credit_due_date <= :deadline ORDER BY credit_due_date ASC")
    suspend fun getCreditDueSoon(deadline: Long): List<TransactionEntity>

    @Query("SELECT COALESCE(SUM(total_amount), 0.0) FROM transactions WHERE is_on_credit = 1 AND credit_due_date IS NOT NULL")
    suspend fun getTotalOutstandingCredit(): Double
}

/**
 * Helper class for item count aggregation.
 */
data class ItemCount(
    val item: String,
    val count: Int
)

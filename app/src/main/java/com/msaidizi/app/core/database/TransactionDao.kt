package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for transactions.
 * All queries are optimized with proper indices for 2GB device performance.
 */
@Dao
interface TransactionDao {

    // === INSERT ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: Transaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<Transaction>): List<Long>

    // === UPDATE ===

    @Update
    suspend fun update(transaction: Transaction)

    // === DELETE ===

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // === QUERIES BY DATE ===

    /**
     * Get all transactions for a specific date.
     * Uses idx_transactions_date index.
     */
    @Query("""
        SELECT * FROM transactions 
        WHERE createdAt >= :startOfDay AND createdAt < :endOfDay 
        ORDER BY createdAt DESC
    """)
    fun getTransactionsForDate(startOfDay: Long, endOfDay: Long): Flow<List<Transaction>>

    /**
     * Get today's transactions (common query).
     */
    @Query("""
        SELECT * FROM transactions 
        WHERE createdAt >= :todayStart 
        ORDER BY createdAt DESC
    """)
    fun getTodayTransactions(todayStart: Long): Flow<List<Transaction>>

    /**
     * Get transactions for a date range.
     */
    @Query("""
        SELECT * FROM transactions 
        WHERE createdAt >= :startDate AND createdAt <= :endDate 
        ORDER BY createdAt DESC
    """)
    fun getTransactionsInRange(startDate: Long, endDate: Long): Flow<List<Transaction>>

    @Query("""
        SELECT * FROM transactions 
        WHERE createdAt >= :startDate AND createdAt <= :endDate 
        ORDER BY createdAt DESC
    """)
    suspend fun getTransactionsInRangeSuspend(startDate: Long, endDate: Long): List<Transaction>

    // === AGGREGATE QUERIES ===

    /**
     * Calculate total sales for a date range.
     * Core business query — must be fast.
     */
    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0.0) 
        FROM transactions 
        WHERE type = 'SALE' AND createdAt >= :startDate AND createdAt <= :endDate
    """)
    suspend fun getSalesTotal(startDate: Long, endDate: Long): Double

    /**
     * Calculate total purchases for a date range.
     */
    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0.0) 
        FROM transactions 
        WHERE type = 'PURCHASE' AND createdAt >= :startDate AND createdAt <= :endDate
    """)
    suspend fun getPurchasesTotal(startDate: Long, endDate: Long): Double

    /**
     * Calculate total expenses for a date range.
     */
    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0.0) 
        FROM transactions 
        WHERE type = 'EXPENSE' AND createdAt >= :startDate AND createdAt <= :endDate
    """)
    suspend fun getExpensesTotal(startDate: Long, endDate: Long): Double

    /**
     * Calculate profit for a date range (sales - purchases - expenses).
     */
    @Query("""
        SELECT COALESCE(SUM(CASE 
            WHEN type = 'SALE' THEN totalAmount 
            WHEN type = 'PURCHASE' THEN -totalAmount 
            WHEN type = 'EXPENSE' THEN -totalAmount 
            ELSE 0 
        END), 0.0) 
        FROM transactions 
        WHERE createdAt >= :startDate AND createdAt <= :endDate
    """)
    suspend fun getProfit(startDate: Long, endDate: Long): Double

    /**
     * Get transaction count for a date range.
     */
    @Query("""
        SELECT COUNT(*) 
        FROM transactions 
        WHERE createdAt >= :startDate AND createdAt <= :endDate
    """)
    suspend fun getTransactionCount(startDate: Long, endDate: Long): Int

    // === ITEM QUERIES ===

    /**
     * Get top selling items by revenue.
     */
    @Query("""
        SELECT item, SUM(quantity) as totalQty, SUM(totalAmount) as totalRev, COUNT(*) as txCount
        FROM transactions 
        WHERE type = 'SALE' AND createdAt >= :startDate AND createdAt <= :endDate
        GROUP BY item 
        ORDER BY totalRev DESC 
        LIMIT :limit
    """)
    suspend fun getTopSellingItems(
        startDate: Long,
        endDate: Long,
        limit: Int = 5
    ): List<ItemRankingTuple>

    /**
     * Get average cost for an item (from purchases).
     */
    @Query("""
        SELECT COALESCE(AVG(unitPrice), 0.0) 
        FROM transactions 
        WHERE type = 'PURCHASE' AND item = :item 
        ORDER BY createdAt DESC 
        LIMIT 10
    """)
    suspend fun getAverageCost(item: String): Double

    /**
     * Get sales data for an item by date (for trend analysis).
     */
    @Query("""
        SELECT createdAt, quantity, totalAmount 
        FROM transactions 
        WHERE type = 'SALE' AND item = :item AND createdAt >= :startDate 
        ORDER BY createdAt ASC
    """)
    suspend fun getItemSalesHistory(
        item: String,
        startDate: Long
    ): List<SalesHistoryTuple>

    /**
     * Get daily sales totals for trend analysis.
     */
    @Query("""
        SELECT createdAt / 86400 * 86400 as day, SUM(totalAmount) as total
        FROM transactions 
        WHERE type = 'SALE' AND createdAt >= :startDate 
        GROUP BY day 
        ORDER BY day ASC
    """)
    suspend fun getDailySalesTotals(startDate: Long): List<DailyTotalTuple>

    // === SYNC QUERIES ===

    /**
     * Get unsynced transactions for upload.
     */
    @Query("SELECT * FROM transactions WHERE syncedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getUnsyncedTransactions(): List<Transaction>

    /**
     * Mark transactions as synced.
     */
    @Query("UPDATE transactions SET syncedAt = :syncTime WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>, syncTime: Long)

    // === SEARCH ===

    @Query("SELECT * FROM transactions WHERE item LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchTransactions(query: String, limit: Int = 20): List<Transaction>
}

// Tuple classes moved to QueryTuples.kt for KSP compatibility

package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.LoanEntity

@Dao
interface LoanDao {
    @Insert
    suspend fun insert(loan: LoanEntity): Long

    @Update
    suspend fun update(loan: LoanEntity)

    @Delete
    suspend fun delete(loan: LoanEntity)

    @Query("SELECT * FROM loans WHERE workerId = :workerId ORDER BY createdAt DESC")
    suspend fun getByWorker(workerId: String): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun getById(id: Long): LoanEntity?

    @Query("SELECT * FROM loans WHERE workerId = :workerId AND status = 'active'")
    suspend fun getActiveLoans(workerId: String): List<LoanEntity>

    @Query("""
        SELECT SUM(remainingAmount) FROM loans 
        WHERE workerId = :workerId AND status = 'active'
    """)
    suspend fun getTotalOutstanding(workerId: String): Double?

    @Query("""
        UPDATE loans SET remainingAmount = remainingAmount - :amount, updatedAt = :timestamp
        WHERE id = :loanId AND status = 'active'
    """)
    suspend fun recordRepayment(loanId: Long, amount: Double, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE loans SET status = 'paid', remainingAmount = 0, updatedAt = :timestamp
        WHERE id = :loanId AND remainingAmount <= 0
    """)
    suspend fun markPaid(loanId: Long, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM loans WHERE synced = 0")
    suspend fun getUnsynced(): List<LoanEntity>

    @Query("UPDATE loans SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.LoanRecord
import com.msaidizi.app.core.model.LoanRepayment

@Dao
interface LoanDao {

    // ── Loan CRUD ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoan(loan: LoanRecord): Long

    @Update
    suspend fun updateLoan(loan: LoanRecord)

    @Query("SELECT * FROM loan_records WHERE status IN ('ACTIVE', 'OVERDUE') ORDER BY endDate ASC")
    suspend fun getActive(): List<LoanRecord>

    @Query("SELECT * FROM loan_records WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getByStatus(status: String): List<LoanRecord>

    @Query("SELECT * FROM loan_records ORDER BY createdAt DESC")
    suspend fun getAll(): List<LoanRecord>

    @Query("SELECT * FROM loan_records WHERE id = :loanId")
    suspend fun getById(loanId: Long): LoanRecord?

    @Query("UPDATE loan_records SET totalRepaid = totalRepaid + :amount, updatedAt = :now WHERE id = :loanId")
    suspend fun addRepayment(loanId: Long, amount: Double, now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE loan_records SET status = :status, updatedAt = :now WHERE id = :loanId")
    suspend fun updateStatus(loanId: Long, status: String, now: Long = System.currentTimeMillis() / 1000)

    // ── Repayment Schedule ─────────────────────────────────────

    @Insert
    suspend fun insertRepayment(repayment: LoanRepayment): Long

    @Update
    suspend fun updateRepayment(repayment: LoanRepayment)

    @Query("SELECT * FROM loan_repayments WHERE loanId = :loanId ORDER BY dueDate ASC")
    suspend fun getRepayments(loanId: Long): List<LoanRepayment>

    @Query("SELECT * FROM loan_repayments WHERE status = 'PENDING' AND dueDate < :now ORDER BY dueDate ASC")
    suspend fun getOverdueRepayments(now: Long): List<LoanRepayment>

    @Query("SELECT * FROM loan_repayments WHERE status = 'PENDING' ORDER BY dueDate ASC LIMIT 1")
    suspend fun getNextPendingRepayment(): LoanRepayment?

    // ── Aggregations ───────────────────────────────────────────

    @Query("SELECT SUM(totalDue - totalRepaid) FROM loan_records WHERE status IN ('ACTIVE', 'OVERDUE')")
    suspend fun getTotalOutstanding(): Double?

    @Query("SELECT SUM(totalRepaid) FROM loan_records WHERE status IN ('ACTIVE', 'OVERDUE')")
    suspend fun getTotalRepaid(): Double?

    @Query("SELECT COUNT(*) FROM loan_records WHERE status IN ('ACTIVE', 'OVERDUE')")
    suspend fun getActiveCount(): Int

    @Query("DELETE FROM loan_records")
    suspend fun deleteAll()
}

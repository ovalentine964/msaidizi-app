package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Hire-Purchase Agreement DAO
// ──────────────────────────────────────────────

@Dao
interface HirePurchaseAgreementDao {
    @Insert
    suspend fun insert(agreement: HirePurchaseAgreementEntity): Long

    @Update
    suspend fun update(agreement: HirePurchaseAgreementEntity)

    @Query("SELECT * FROM hire_purchase_agreements WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAgreement(): HirePurchaseAgreementEntity?

    @Query("SELECT * FROM hire_purchase_agreements WHERE id = :id")
    suspend fun getById(id: Long): HirePurchaseAgreementEntity?

    @Query("SELECT * FROM hire_purchase_agreements ORDER BY createdAt DESC")
    fun getAll(): Flow<List<HirePurchaseAgreementEntity>>

    @Query("UPDATE hire_purchase_agreements SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}

// ──────────────────────────────────────────────
// Hire Payment DAO
// ──────────────────────────────────────────────

@Dao
interface HirePaymentDao {
    @Insert
    suspend fun insert(payment: HirePaymentEntity): Long

    @Query("SELECT * FROM hire_payments WHERE agreementId = :agreementId ORDER BY date DESC")
    fun getByAgreement(agreementId: Long): Flow<List<HirePaymentEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId")
    suspend fun getTotalPaid(agreementId: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId AND paymentType = :type")
    suspend fun getTotalPaidByType(agreementId: Long, type: String): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId AND date BETWEEN :startDate AND :endDate")
    suspend fun getTotalPaidBetween(agreementId: Long, startDate: String, endDate: String): Double

    @Query("SELECT COUNT(DISTINCT date) FROM hire_payments WHERE agreementId = :agreementId AND paymentType = 'daily_fee'")
    suspend fun getDaysPaid(agreementId: Long): Int

    @Query("SELECT * FROM hire_payments WHERE agreementId = :agreementId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(agreementId: Long, limit: Int = 30): Flow<List<HirePaymentEntity>>

    @Delete
    suspend fun delete(payment: HirePaymentEntity)
}

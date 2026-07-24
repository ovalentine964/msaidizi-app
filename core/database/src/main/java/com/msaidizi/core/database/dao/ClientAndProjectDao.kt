package com.msaidizi.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msaidizi.core.database.entity.ClientProfileEntity
import com.msaidizi.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for client profiles (recurring customers).
 */
@Dao
interface ClientProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(client: ClientProfileEntity): Long

    @Update
    suspend fun update(client: ClientProfileEntity)

    @Delete
    suspend fun delete(client: ClientProfileEntity)

    @Query("SELECT * FROM client_profiles WHERE id = :id")
    suspend fun getById(id: Long): ClientProfileEntity?

    @Query("SELECT * FROM client_profiles ORDER BY last_transaction_at DESC")
    fun getAll(): Flow<List<ClientProfileEntity>>

    @Query("SELECT * FROM client_profiles WHERE name LIKE '%' || :name || '%'")
    suspend fun getByName(name: String): List<ClientProfileEntity>

    @Query("SELECT * FROM client_profiles WHERE is_recurring = 1 ORDER BY total_spent DESC")
    suspend fun getRecurringClients(): List<ClientProfileEntity>

    @Query("SELECT * FROM client_profiles WHERE has_credit = 1 AND credit_balance > 0")
    suspend fun getClientsWithCredit(): List<ClientProfileEntity>

    @Query("SELECT * FROM client_profiles WHERE is_recurring = 1 AND (last_transaction_at < :threshold)")
    suspend fun getChurningClients(threshold: Long): List<ClientProfileEntity>

    @Query("SELECT COUNT(*) FROM client_profiles")
    suspend fun getClientCount(): Int

    @Query("SELECT COUNT(*) FROM client_profiles WHERE is_recurring = 1")
    suspend fun getRecurringClientCount(): Int

    @Query("UPDATE client_profiles SET total_transactions = total_transactions + 1, total_spent = total_spent + :amount, last_transaction_at = :now, avg_transaction_amount = (total_spent + :amount) / (total_transactions + 1), updated_at = :now WHERE id = :id")
    suspend fun recordTransaction(id: Long, amount: Double, now: Long = System.currentTimeMillis())
}

/**
 * DAO for projects (multi-day work).
 */
@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE is_active = 1 ORDER BY updated_at DESC")
    fun getActiveProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE client_name LIKE '%' || :clientName || '%'")
    suspend fun getByClient(clientName: String): List<ProjectEntity>

    @Query("SELECT COALESCE(SUM(quoted_price - total_paid), 0.0) FROM projects WHERE is_active = 1")
    suspend fun getTotalOutstanding(): Double

    @Query("SELECT COALESCE(SUM(total_paid - total_expenses), 0.0) FROM projects WHERE is_active = 1")
    suspend fun getTotalActiveProfit(): Double

    @Query("SELECT COUNT(*) FROM projects WHERE is_active = 1")
    suspend fun getActiveProjectCount(): Int

    @Query("UPDATE projects SET total_paid = total_paid + :payment, updated_at = :now WHERE id = :id")
    suspend fun recordPayment(id: Long, payment: Double, now: Long = System.currentTimeMillis())
}

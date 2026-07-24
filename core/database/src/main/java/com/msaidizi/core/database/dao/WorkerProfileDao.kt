package com.msaidizi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msaidizi.core.database.entity.WorkerProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for worker profiles.
 * Single profile per worker — upsert semantics.
 */
@Dao
interface WorkerProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: WorkerProfileEntity): Long

    @Update
    suspend fun update(profile: WorkerProfileEntity)

    @Query("SELECT * FROM worker_profiles LIMIT 1")
    suspend fun getProfile(): WorkerProfileEntity?

    @Query("SELECT * FROM worker_profiles LIMIT 1")
    fun getProfileFlow(): Flow<WorkerProfileEntity?>

    @Query("UPDATE worker_profiles SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET business_type = :businessType, business_name = :businessName, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBusiness(id: Long, businessType: String, businessName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET location_name = :locationName, location_lat = :lat, location_lng = :lng, market_id = :marketId, region = :region, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLocation(id: Long, locationName: String, lat: Double?, lng: Double?, marketId: String, region: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET language = :language, dialect = :dialect, code_switches = :codeSwitches, response_style = :responseStyle, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLanguage(id: Long, language: String, dialect: String, codeSwitches: Boolean, responseStyle: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET avg_daily_revenue = :revenue, avg_daily_profit = :profit, typical_margin = :margin, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateFinancialPatterns(id: Long, revenue: Double, profit: Double, margin: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET work_pattern = :pattern, typical_working_days = :days, peak_day = :peakDay, peak_hour = :peakHour, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateWorkPattern(id: Long, pattern: String, days: Int, peakDay: Int, peakHour: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET days_active = :daysActive, current_streak = :streak, longest_streak = :longestStreak, total_transactions = :totalTx, last_interaction_at = :lastInteraction, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateEngagement(id: Long, daysActive: Int, streak: Int, longestStreak: Int, totalTx: Int, lastInteraction: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET alama_score = :score, alama_tier = :tier, total_proof_points = :proofPoints, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAlamaScore(id: Long, score: Double, tier: String, proofPoints: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET mpesa_connected = :connected, primary_payment_method = :method, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePaymentInfo(id: Long, connected: Boolean, method: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE worker_profiles SET synced_at = :syncedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, updatedAt: Long = System.currentTimeMillis())
}

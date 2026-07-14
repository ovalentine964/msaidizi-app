package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.GamificationEntity

/**
 * DAO for gamification data.
 * Single-row pattern: user's gamification state.
 */
@Dao
interface GamificationDao {

    @Query("SELECT * FROM gamification WHERE id = 1")
    suspend fun getGamification(): GamificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GamificationEntity)

    @Query("UPDATE gamification SET totalPoints = totalPoints + :points, updatedAt = :now WHERE id = 1")
    suspend fun addPoints(points: Int, now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE gamification SET totalSalesRecorded = totalSalesRecorded + 1, updatedAt = :now WHERE id = 1")
    suspend fun incrementSalesCount(now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE gamification SET totalBalanceChecks = totalBalanceChecks + 1, updatedAt = :now WHERE id = 1")
    suspend fun incrementBalanceChecks(now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE gamification SET currentStreak = :streak, longestStreak = MAX(longestStreak, :streak), lastActiveDay = :day, streakProtectionsUsed = :protections, protectionWeek = :week, updatedAt = :now WHERE id = 1")
    suspend fun updateStreak(streak: Int, day: Long, protections: Int, week: Int, now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE gamification SET level = :level, earnedBadges = :badges, updatedAt = :now WHERE id = 1")
    suspend fun updateLevelAndBadges(level: Int, badges: String, now: Long = System.currentTimeMillis() / 1000)

    // ═══════════════════════════════════════════════════════════════
    // STREAK RECOVERY (Fresh Start)
    // ═══════════════════════════════════════════════════════════════

    @Query("UPDATE gamification SET currentStreak = :streak, lastActiveDay = :day, updatedAt = :now WHERE id = 1")
    suspend fun restoreStreak(streak: Int, day: Long, now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE gamification SET streakRecoveriesUsedThisMonth = :recoveriesUsed, streakRecoveryMonth = :month, updatedAt = :now WHERE id = 1")
    suspend fun updateRecoveryUsage(recoveriesUsed: Int, month: Int, now: Long = System.currentTimeMillis() / 1000)
}

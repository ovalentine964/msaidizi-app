package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.GoalContributionEntity
import com.msaidizi.core.model.GoalEntity
import kotlinx.coroutines.flow.Flow

// ════════════════════════════════════════════════════════════
// GOAL DAO — Room persistence for savings goals
// ════════════════════════════════════════════════════════════

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity)

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY deadline ASC")
    fun getActiveGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals ORDER BY createdAt DESC")
    fun getAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY deadline ASC")
    suspend fun getActiveGoalsOnce(): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET currentAmount = currentAmount + :amount, updatedAt = :now WHERE id = :goalId")
    suspend fun addContribution(goalId: String, amount: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE goals SET currentAmount = :amount, updatedAt = :now WHERE id = :goalId")
    suspend fun setCurrentAmount(goalId: String, amount: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE goals SET status = :status, updatedAt = :now WHERE id = :goalId")
    suspend fun updateStatus(goalId: String, status: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM goals WHERE status = 'active'")
    fun getActiveGoalCount(): Flow<Int>

    @Query("SELECT SUM(currentAmount) FROM goals WHERE status = 'active'")
    fun getTotalSaved(): Flow<Double?>

    @Query("SELECT SUM(targetAmount) FROM goals WHERE status = 'active'")
    fun getTotalTarget(): Flow<Double?>

    @Query("SELECT * FROM goals WHERE status = 'active' AND deadline < :now")
    fun getOverdueGoals(now: Long): Flow<List<GoalEntity>>
}

@Dao
interface GoalContributionDao {
    @Insert
    suspend fun insert(contribution: GoalContributionEntity): Long

    @Query("SELECT * FROM goal_contributions WHERE goalId = :goalId ORDER BY timestamp DESC")
    fun getByGoal(goalId: String): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM goal_contributions WHERE goalId = :goalId ORDER BY timestamp DESC")
    suspend fun getByGoalOnce(goalId: String): List<GoalContributionEntity>

    @Query("SELECT SUM(amount) FROM goal_contributions WHERE goalId = :goalId")
    suspend fun getTotalContributed(goalId: String): Double?

    @Query("SELECT SUM(amount) FROM goal_contributions WHERE goalId = :goalId AND timestamp >= :since")
    suspend fun getContributedSince(goalId: String, since: Long): Double?

    @Query("SELECT * FROM goal_contributions WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getBetween(start: Long, end: Long): Flow<List<GoalContributionEntity>>

    @Query("SELECT COUNT(*) FROM goal_contributions WHERE goalId = :goalId")
    suspend fun getContributionCount(goalId: String): Int
}

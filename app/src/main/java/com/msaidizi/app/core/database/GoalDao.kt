package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.GoalRecord
import com.msaidizi.app.core.model.GoalProgressEntry
import com.msaidizi.app.core.model.GoalMilestone

@Dao
interface GoalDao {

    // ── Goal CRUD ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: GoalRecord): Long

    @Update
    suspend fun updateGoal(goal: GoalRecord)

    @Query("SELECT * FROM goal_records WHERE status = 'ACTIVE' ORDER BY createdAt DESC")
    suspend fun getActive(): List<GoalRecord>

    /** Paginated active goals */
    @Query("SELECT * FROM goal_records WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT :pageSize OFFSET :offset")
    suspend fun getActivePaginated(pageSize: Int = 10, offset: Int = 0): List<GoalRecord>

    @Query("SELECT * FROM goal_records WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    suspend fun getCompleted(): List<GoalRecord>

    @Query("SELECT * FROM goal_records ORDER BY createdAt DESC")
    suspend fun getAll(): List<GoalRecord>

    /** Paginated all goals */
    @Query("SELECT * FROM goal_records ORDER BY createdAt DESC LIMIT :pageSize OFFSET :offset")
    suspend fun getAllPaginated(pageSize: Int = 10, offset: Int = 0): List<GoalRecord>

    @Query("SELECT * FROM goal_records WHERE id = :goalId")
    suspend fun getById(goalId: Long): GoalRecord?

    @Query("UPDATE goal_records SET status = :status, updatedAt = :now WHERE id = :goalId")
    suspend fun updateStatus(goalId: Long, status: String, now: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE goal_records SET currentAmount = currentAmount + :amount, updatedAt = :now WHERE id = :goalId")
    suspend fun addProgress(goalId: Long, amount: Double, now: Long = System.currentTimeMillis() / 1000)

    // ── Progress Entries ───────────────────────────────────────

    @Insert
    suspend fun insertProgressEntry(entry: GoalProgressEntry): Long

    @Query("SELECT * FROM goal_progress_entries WHERE goalId = :goalId ORDER BY timestamp ASC")
    suspend fun getProgressEntries(goalId: Long): List<GoalProgressEntry>

    // ── Milestones ─────────────────────────────────────────────

    @Insert
    suspend fun insertMilestone(milestone: GoalMilestone): Long

    @Query("SELECT * FROM goal_milestones WHERE goalId = :goalId")
    suspend fun getMilestones(goalId: Long): List<GoalMilestone>

    // ── Aggregations ───────────────────────────────────────────

    @Query("SELECT SUM(currentAmount) FROM goal_records WHERE status = 'ACTIVE'")
    suspend fun getTotalSaved(): Double?

    @Query("SELECT SUM(targetAmount) FROM goal_records WHERE status = 'ACTIVE'")
    suspend fun getTotalTarget(): Double?

    @Query("SELECT COUNT(*) FROM goal_records WHERE status = 'ACTIVE'")
    suspend fun getActiveCount(): Int

    @Query("DELETE FROM goal_records")
    suspend fun deleteAll()
}

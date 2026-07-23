package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.GoalEntity

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE workerId = :workerId ORDER BY createdAt DESC")
    suspend fun getByWorker(workerId: String): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE workerId = :workerId AND status = 'active'")
    suspend fun getActiveGoals(workerId: String): List<GoalEntity>

    @Query("""
        UPDATE goals SET currentAmount = currentAmount + :amount, updatedAt = :timestamp
        WHERE id = :goalId AND status = 'active'
    """)
    suspend fun addProgress(goalId: Long, amount: Double, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE goals SET status = 'completed', updatedAt = :timestamp
        WHERE id = :goalId AND currentAmount >= targetAmount
    """)
    suspend fun checkCompletion(goalId: Long, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM goals WHERE synced = 0")
    suspend fun getUnsynced(): List<GoalEntity>

    @Query("UPDATE goals SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.GivingEntity

@Dao
interface GivingDao {
    @Insert
    suspend fun insert(giving: GivingEntity): Long

    @Query("SELECT * FROM giving WHERE workerId = :workerId ORDER BY timestamp DESC")
    suspend fun getByWorker(workerId: String): List<GivingEntity>

    @Query("""
        SELECT SUM(amount) FROM giving 
        WHERE workerId = :workerId 
        AND timestamp BETWEEN :startTime AND :endTime
    """)
    suspend fun getTotalGiving(workerId: String, startTime: Long, endTime: Long): Double?

    @Query("SELECT * FROM giving WHERE synced = 0")
    suspend fun getUnsynced(): List<GivingEntity>

    @Query("UPDATE giving SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

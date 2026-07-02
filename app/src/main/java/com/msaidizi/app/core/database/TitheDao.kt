package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.TitheRecord

@Dao
interface TitheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TitheRecord): Long

    @Query("SELECT * FROM tithe_records ORDER BY date DESC")
    suspend fun getAll(): List<TitheRecord>

    @Query("SELECT * FROM tithe_records WHERE date >= :since ORDER BY date DESC")
    suspend fun getSince(since: Long): List<TitheRecord>

    @Query("SELECT * FROM tithe_records WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getByDateRange(startDate: Long, endDate: Long): List<TitheRecord>

    @Query("SELECT * FROM tithe_records WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: String): List<TitheRecord>

    @Query("SELECT SUM(amount) FROM tithe_records WHERE date >= :since")
    suspend fun getTotalSince(since: Long): Double?

    @Query("SELECT SUM(amount) FROM tithe_records WHERE type = :type AND date >= :since")
    suspend fun getTotalByTypeSince(type: String, since: Long): Double?

    @Query("SELECT * FROM tithe_records WHERE type = :type AND date >= :since ORDER BY date DESC")
    suspend fun getByTypeSince(type: String, since: Long): List<TitheRecord>

    @Query("SELECT * FROM tithe_records ORDER BY date DESC LIMIT 1")
    suspend fun getLatest(): TitheRecord?

    @Query("""
        SELECT SUM(amount) FROM tithe_records
        WHERE date >= :monthStart AND date < :monthEnd
    """)
    suspend fun getMonthlyTotal(monthStart: Long, monthEnd: Long): Double?

    @Query("""
        SELECT COUNT(DISTINCT CAST(date / 86400000 AS INTEGER))
        FROM tithe_records WHERE date >= :since
    """)
    suspend fun getUniqueGivingDays(since: Long): Int

    @Query("""
        SELECT COUNT(*) FROM tithe_records
        WHERE date >= :since AND date < :until
    """)
    suspend fun getCountBetween(since: Long, until: Long): Int

    @Delete
    suspend fun delete(record: TitheRecord)

    @Query("DELETE FROM tithe_records")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tithe_records")
    suspend fun getCount(): Int
}

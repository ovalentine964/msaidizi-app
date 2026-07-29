package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Emergency Contact DAO
// ──────────────────────────────────────────────

@Dao
interface EmergencyContactDao {
    @Insert
    suspend fun insert(contact: EmergencyContactEntity): Long

    @Update
    suspend fun update(contact: EmergencyContactEntity)

    @Delete
    suspend fun delete(contact: EmergencyContactEntity)

    @Query("SELECT * FROM emergency_contacts WHERE isActive = 1 ORDER BY id ASC")
    fun getAllActive(): Flow<List<EmergencyContactEntity>>

    @Query("SELECT * FROM emergency_contacts WHERE isActive = 1")
    suspend fun getAllActiveOnce(): List<EmergencyContactEntity>

    @Query("SELECT * FROM emergency_contacts WHERE id = :id")
    suspend fun getById(id: Long): EmergencyContactEntity?

    @Query("SELECT COUNT(*) FROM emergency_contacts WHERE isActive = 1")
    suspend fun getActiveCount(): Int
}

// ──────────────────────────────────────────────
// SOS Event DAO
// ──────────────────────────────────────────────

@Dao
interface SOSEventDao {
    @Insert
    suspend fun insert(event: SOSEventEntity): Long

    @Update
    suspend fun update(event: SOSEventEntity)

    @Query("SELECT * FROM sos_events ORDER BY triggeredAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<SOSEventEntity>>

    @Query("SELECT * FROM sos_events WHERE id = :id")
    suspend fun getById(id: Long): SOSEventEntity?

    @Query("SELECT * FROM sos_events WHERE status = 'triggered' ORDER BY triggeredAt DESC LIMIT 1")
    suspend fun getActiveSOS(): SOSEventEntity?

    @Query("UPDATE sos_events SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, resolvedAt: Long = System.currentTimeMillis())
}

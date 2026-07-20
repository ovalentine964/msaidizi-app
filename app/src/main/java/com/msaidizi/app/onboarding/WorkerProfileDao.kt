package com.msaidizi.app.onboarding

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for WorkerProfile.
 *
 * WorkerProfile is a singleton per device (id = 1).
 * This DAO provides persistence for the onboarding conversation result.
 */
@Dao
interface WorkerProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: WorkerProfile)

    @Update
    suspend fun update(profile: WorkerProfile)

    @Query("SELECT * FROM worker_profile WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): WorkerProfile?

    @Query("SELECT * FROM worker_profile WHERE id = 1 LIMIT 1")
    fun observeProfile(): kotlinx.coroutines.flow.Flow<WorkerProfile?>

    @Query("DELETE FROM worker_profile")
    suspend fun deleteAll()
}

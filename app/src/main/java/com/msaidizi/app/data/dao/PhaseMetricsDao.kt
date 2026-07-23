package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.PhaseMetricEntity
import com.msaidizi.app.data.entity.PhaseMetricAlertEntity

@Dao
interface PhaseMetricsDao {
    @Insert
    suspend fun insert(entry: PhaseMetricEntity)

    @Insert
    suspend fun insertAlert(alert: PhaseMetricAlertEntity)

    @Query("SELECT * FROM phase_metrics WHERE phase = :phase AND timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getEntriesSince(phase: String, since: Long): List<PhaseMetricEntity>

    @Query("DELETE FROM phase_metrics WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM phase_metric_alerts WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getAlertsSince(since: Long): List<PhaseMetricAlertEntity>

    @Query("DELETE FROM phase_metric_alerts WHERE timestamp < :before")
    suspend fun deleteAlertsBefore(before: Long)
}

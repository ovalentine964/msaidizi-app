package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phase_metrics")
data class PhaseMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val latencyMs: Long,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "phase_metric_alerts")
data class PhaseMetricAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val level: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

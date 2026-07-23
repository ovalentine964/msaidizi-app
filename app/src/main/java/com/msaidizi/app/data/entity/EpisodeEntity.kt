package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val workerId: String,
    val query: String,
    val response: String,
    val outcome: String,
    val intent: String,
    val confidence: Double,
    val dialect: String = "sw",
    val toolUsed: String? = null,
    val sessionId: String = "",
    val relevanceScore: Double = 1.0,
    val timestamp: Long = System.currentTimeMillis()
)

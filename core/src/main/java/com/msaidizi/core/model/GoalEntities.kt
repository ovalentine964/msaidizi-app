package com.msaidizi.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ════════════════════════════════════════════════════════════
// GOAL TRACKER ENTITIES — Room DB persistence for savings goals
// ════════════════════════════════════════════════════════════

/**
 * A savings goal persisted to Room DB.
 * Replaces the in-memory mutableListOf<Goal>() in GoalTracker.
 */
@Entity(
    tableName = "goals",
    indices = [
        Index(value = ["status"]),
        Index(value = ["deadline"]),
        Index(value = ["status", "deadline"])
    ]
)
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val deadline: Long,
    val status: String = "active",       // active, completed, cancelled
    val goalType: String = "savings",    // savings, business_target, equipment
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A single contribution toward a goal.
 * Tracks when money was added and from what source.
 */
@Entity(
    tableName = "goal_contributions",
    indices = [
        Index(value = ["goalId"]),
        Index(value = ["timestamp"])
    ]
)
data class GoalContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: String,
    val amount: Double,
    val source: String = "manual",       // manual, auto_savings, chama, mpesa
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

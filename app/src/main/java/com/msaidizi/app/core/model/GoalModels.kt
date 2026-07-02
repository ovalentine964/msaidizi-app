package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "goal_records",
    indices = [
        Index(value = ["status"]),
        Index(value = ["category"])
    ]
)
data class GoalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val category: String,           // EQUIPMENT, INVENTORY, SAVINGS, DEBT, BUSINESS, EDUCATION, EMERGENCY, ASSET, OTHER
    val deadline: Long = 0,         // Unix timestamp in seconds
    val status: String = "ACTIVE",  // ACTIVE, COMPLETED, ABANDONED
    val weeklyTarget: Double = 0.0,
    val dailyTarget: Double = 0.0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val deeperPurpose: String = "",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

@Entity(
    tableName = "goal_progress_entries",
    foreignKeys = [
        ForeignKey(
            entity = GoalRecord::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["goalId"])]
)
data class GoalProgressEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amount: Double,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000
)

@Entity(
    tableName = "goal_milestones",
    foreignKeys = [
        ForeignKey(
            entity = GoalRecord::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["goalId"])]
)
data class GoalMilestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val percentage: Double,          // 0.25, 0.50, 0.75, 1.00
    val reachedAt: Long = System.currentTimeMillis() / 1000
)

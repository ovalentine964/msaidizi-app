package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Rich Habits tracking entity — one row per habit per day.
 * Tracks 10 daily habits for wealth mindset building.
 */
@Entity(
    tableName = "rich_habits",
    indices = [
        Index(value = ["date"]),
        Index(value = ["habitId"])
    ]
)
data class RichHabitEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Habit identifier (e.g., "record_sales", "check_balance") */
    val habitId: String,

    /** Date as YYYY-MM-DD string for easy querying */
    val date: String,

    /** Whether this habit was completed today */
    val completed: Boolean = false,

    /** Unix timestamp of completion */
    val completedAt: Long = 0,

    /** Optional notes */
    val notes: String = ""
)

/**
 * Mindset Academy lesson entity — tracks lesson delivery and completion.
 */
@Entity(
    tableName = "mindset_lessons",
    indices = [
        Index(value = ["category"]),
        Index(value = ["delivered"]),
        Index(value = ["completed"])
    ]
)
data class MindsetLessonEntity(
    @PrimaryKey
    val lessonId: String,

    /** Category: HABITS, GOALS, FINANCIAL_LITERACY, MINDSET, GIVING */
    val category: String,

    /** Swahili title */
    val titleSw: String,

    /** English title */
    val titleEn: String,

    /** Swahili content/transcript */
    val contentSw: String,

    /** English content/transcript */
    val contentEn: String,

    /** Source book */
    val sourceBook: String,

    /** Duration in seconds (120-180) */
    val durationSeconds: Int = 150,

    /** Whether lesson has been delivered to user */
    val delivered: Boolean = false,

    /** Whether user completed listening */
    val completed: Boolean = false,

    /** Unix timestamp of delivery */
    val deliveredAt: Long = 0,

    /** Unix timestamp of completion */
    val completedAt: Long = 0,

    /** Display order within category */
    val sortOrder: Int = 0
)

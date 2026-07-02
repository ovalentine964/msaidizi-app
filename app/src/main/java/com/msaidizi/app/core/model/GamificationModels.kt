package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Gamification entity — tracks user points, level, streak, and badges.
 * One row per user (singleton pattern: id = 1 for single-user device).
 *
 * Optimized for 2GB devices: minimal storage, integer fields.
 */
@Entity(
    tableName = "gamification",
    indices = [
        Index(value = ["level"]),
        Index(value = ["currentStreak"])
    ]
)
data class GamificationEntity(
    @PrimaryKey
    val id: Int = 1,

    /** Total accumulated points */
    val totalPoints: Int = 0,

    /** Current level index (0-5) */
    val level: Int = 0,

    /** Current consecutive daily streak */
    val currentStreak: Int = 0,

    /** Longest streak ever achieved */
    val longestStreak: Int = 0,

    /** Unix timestamp of last activity day */
    val lastActiveDay: Long = 0,

    /** Number of streak protections used this week */
    val streakProtectionsUsed: Int = 0,

    /** Week number for tracking protection resets */
    val protectionWeek: Int = 0,

    /** Total sales recorded (lifetime) */
    val totalSalesRecorded: Int = 0,

    /** Total balance checks (lifetime) */
    val totalBalanceChecks: Int = 0,

    /** Comma-separated list of earned badge IDs */
    val earnedBadges: String = "",

    /** Unix timestamp of last update */
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Badge definitions — stored as data class, not Room entity.
 * Badges are checked client-side against gamification state.
 */
data class Badge(
    val id: String,
    val nameSw: String,
    val nameEn: String,
    val descriptionSw: String,
    val descriptionEn: String,
    val emoji: String,
    val requirement: (GamificationEntity, Int, Int) -> Boolean
    // Parameters: entity, todaySalesCount, todayBalanceChecks
)

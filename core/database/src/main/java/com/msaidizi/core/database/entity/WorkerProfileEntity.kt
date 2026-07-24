package com.msaidizi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for worker profiles.
 * One profile per worker — the single source of truth for identity.
 */
@Entity(tableName = "worker_profiles")
data class WorkerProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ═══ IDENTITY ═══
    @ColumnInfo(name = "name", defaultValue = "")
    val name: String = "",

    @ColumnInfo(name = "phone_number", defaultValue = "")
    val phoneNumber: String = "",

    @ColumnInfo(name = "profile_image_url", defaultValue = "")
    val profileImageUrl: String = "",

    // ═══ BUSINESS ═══
    @ColumnInfo(name = "business_type", defaultValue = "")
    val businessType: String = "",

    @ColumnInfo(name = "business_name", defaultValue = "")
    val businessName: String = "",

    @ColumnInfo(name = "business_category", defaultValue = "")
    val businessCategory: String = "",

    @ColumnInfo(name = "primary_products", defaultValue = "")
    val primaryProducts: String = "",

    @ColumnInfo(name = "avg_daily_revenue", defaultValue = "0.0")
    val avgDailyRevenue: Double = 0.0,

    @ColumnInfo(name = "avg_daily_profit", defaultValue = "0.0")
    val avgDailyProfit: Double = 0.0,

    @ColumnInfo(name = "operating_hours", defaultValue = "all_day")
    val operatingHours: String = "all_day",

    // ═══ LOCATION ═══
    @ColumnInfo(name = "location_name", defaultValue = "")
    val locationName: String = "",

    @ColumnInfo(name = "location_lat")
    val locationLat: Double? = null,

    @ColumnInfo(name = "location_lng")
    val locationLng: Double? = null,

    @ColumnInfo(name = "market_id", defaultValue = "")
    val marketId: String = "",

    @ColumnInfo(name = "region", defaultValue = "")
    val region: String = "",

    // ═══ LANGUAGE & DIALECT ═══
    @ColumnInfo(name = "language", defaultValue = "sw")
    val language: String = "sw",

    @ColumnInfo(name = "dialect", defaultValue = "")
    val dialect: String = "",

    @ColumnInfo(name = "code_switches", defaultValue = "0")
    val codeSwitches: Boolean = false,

    @ColumnInfo(name = "response_style", defaultValue = "casual")
    val responseStyle: String = "casual",

    // ═══ WORK PATTERNS ═══
    @ColumnInfo(name = "work_pattern", defaultValue = "new")
    val workPattern: String = "new",

    @ColumnInfo(name = "typical_working_days", defaultValue = "5")
    val typicalWorkingDays: Int = 5,

    @ColumnInfo(name = "peak_day", defaultValue = "0")
    val peakDay: Int = 0,

    @ColumnInfo(name = "peak_hour", defaultValue = "0")
    val peakHour: Int = 0,

    // ═══ FINANCIAL PATTERNS ═══
    @ColumnInfo(name = "typical_margin", defaultValue = "0.0")
    val typicalMargin: Double = 0.0,

    @ColumnInfo(name = "primary_payment_method", defaultValue = "cash")
    val primaryPaymentMethod: String = "cash",

    @ColumnInfo(name = "mpesa_connected", defaultValue = "0")
    val mpesaConnected: Boolean = false,

    @ColumnInfo(name = "has_bank_account", defaultValue = "0")
    val hasBankAccount: Boolean = false,

    // ═══ ENGAGEMENT ═══
    @ColumnInfo(name = "days_active", defaultValue = "0")
    val daysActive: Int = 0,

    @ColumnInfo(name = "current_streak", defaultValue = "0")
    val currentStreak: Int = 0,

    @ColumnInfo(name = "longest_streak", defaultValue = "0")
    val longestStreak: Int = 0,

    @ColumnInfo(name = "total_transactions", defaultValue = "0")
    val totalTransactions: Int = 0,

    @ColumnInfo(name = "preferred_interaction_time", defaultValue = "any")
    val preferredInteractionTime: String = "any",

    // ═══ PROOF ═══
    @ColumnInfo(name = "alama_score", defaultValue = "0.0")
    val alamaScore: Double = 0.0,

    @ColumnInfo(name = "alama_tier", defaultValue = "MTOTO")
    val alamaTier: String = "MTOTO",

    @ColumnInfo(name = "total_proof_points", defaultValue = "0")
    val totalProofPoints: Int = 0,

    // ═══ TIMESTAMPS ═══
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_interaction_at")
    val lastInteractionAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

package com.msaidizi.app.social

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.msaidizi.app.agent.WorkerType

// ═══════════════════════════════════════════════════════════════
// PEER COMPARISON MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * Aggregated peer metrics for a specific location × business type.
 * Stored locally, synced from server. Always anonymized — no individual data.
 *
 * Privacy: Only aggregate statistics are stored. Minimum peer count of 5
 * required before any comparison is shown (k-anonymity).
 */
@Entity(
    tableName = "peer_metrics",
    indices = [
        Index(value = ["location", "businessType"]),
        Index(value = ["periodStart"])
    ]
)
data class PeerMetrics(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Location/area name (e.g., "Migori", "Kibera") */
    val location: String,

    /** Business type this aggregate covers */
    val businessType: String,

    /** Period start (Unix timestamp, start of day/week) */
    val periodStart: Long,

    /** Period type: "DAILY" or "WEEKLY" */
    val periodType: String = "DAILY",

    // ── Aggregate Sales ──
    /** Average daily sales across all peers */
    val avgDailySales: Double = 0.0,

    /** Median daily sales (more robust than mean) */
    val medianDailySales: Double = 0.0,

    /** 25th percentile daily sales */
    val p25DailySales: Double = 0.0,

    /** 75th percentile daily sales */
    val p75DailySales: Double = 0.0,

    /** 90th percentile daily sales */
    val p90DailySales: Double = 0.0,

    // ── Aggregate Profit ──
    val avgDailyProfit: Double = 0.0,
    val medianDailyProfit: Double = 0.0,

    // ── Aggregate Transactions ──
    val avgTransactionCount: Double = 0.0,

    // ── Aggregate Streak ──
    val avgStreak: Double = 0.0,
    val maxStreak: Int = 0,

    // ── Population ──
    /** Number of workers in this cohort (minimum 5 for privacy) */
    val peerCount: Int = 0,

    /** When this aggregate was last computed */
    val computedAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Cached comparison result for a single worker against their peers.
 * Stored locally so the comparison can be shown instantly.
 */
@Entity(
    tableName = "peer_comparisons",
    indices = [
        Index(value = ["comparedAt"])
    ]
)
data class PeerComparisonResult(
    @PrimaryKey
    val id: Int = 1,  // Singleton per device

    /** Worker's location used for comparison */
    val location: String = "",

    /** Worker's business type used for comparison */
    val businessType: String = "",

    /** Worker's daily sales amount */
    val workerDailySales: Double = 0.0,

    /** Percentile rank (0-100) for daily sales */
    val salesPercentile: Int = 0,

    /** Worker's daily profit */
    val workerDailyProfit: Double = 0.0,

    /** Percentile rank for daily profit */
    val profitPercentile: Int = 0,

    /** Worker's transaction count today */
    val workerTransactionCount: Int = 0,

    /** Percentile rank for transaction count */
    val transactionPercentile: Int = 0,

    /** Worker's streak */
    val workerStreak: Int = 0,

    /** Peer average daily sales for comparison message */
    val peerAvgDailySales: Double = 0.0,

    /** Peer count in cohort */
    val peerCount: Int = 0,

    /** When comparison was last computed */
    val comparedAt: Long = System.currentTimeMillis() / 1000
)

// ═══════════════════════════════════════════════════════════════
// LEADERBOARD MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * Leaderboard entry — one row per ranked worker (anonymized).
 * Stored locally; synced from server weekly.
 *
 * Privacy: No names, no phone numbers. Only anonymous rank identifiers.
 */
@Entity(
    tableName = "leaderboard_entries",
    indices = [
        Index(value = ["location", "businessType", "weekStart"]),
        Index(value = ["weekStart"]),
        Index(value = ["rank"])
    ]
)
data class LeaderboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Location this leaderboard covers */
    val location: String,

    /** Business type this leaderboard covers */
    val businessType: String,

    /** Week start (Monday, Unix timestamp) */
    val weekStart: Long,

    /** Anonymous rank (1 = top) */
    val rank: Int,

    /** Total weekly sales for this rank position */
    val weeklySales: Double = 0.0,

    /** Total weekly profit for this rank position */
    val weeklyProfit: Double = 0.0,

    /** Weekly transaction count */
    val transactionCount: Int = 0,

    /** Streak at time of ranking */
    val streak: Int = 0,

    /** Total points from gamification */
    val totalPoints: Int = 0,

    /** Whether this entry represents the current device user */
    val isCurrentUser: Boolean = false,

    /** Total number of participants this week */
    val totalParticipants: Int = 0,

    /** When this entry was synced */
    val syncedAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Leaderboard summary — the worker's position and nearby ranks.
 * Cached locally for instant display.
 */
@Entity(tableName = "leaderboard_summary")
data class LeaderboardSummary(
    @PrimaryKey
    val id: Int = 1,  // Singleton

    /** Worker's current rank */
    val myRank: Int = 0,

    /** Total participants */
    val totalParticipants: Int = 0,

    /** Worker's weekly sales */
    val myWeeklySales: Double = 0.0,

    /** Worker's weekly profit */
    val myWeeklyProfit: Double = 0.0,

    /** Rank change from last week (+N = improved, -N = dropped) */
    val rankChange: Int = 0,

    /** Week start this summary covers */
    val weekStart: Long = 0,

    /** Location used */
    val location: String = "",

    /** Business type used */
    val businessType: String = "",

    /** When summary was last synced */
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

// ═══════════════════════════════════════════════════════════════
// COMMUNITY TIPS MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * A community-sourced business tip, shared anonymously.
 * Workers submit tips; the best ones get featured in morning briefings.
 */
@Entity(
    tableName = "community_tips",
    indices = [
        Index(value = ["location", "businessType"]),
        Index(value = ["upvotes"]),
        Index(value = ["createdAt"]),
        Index(value = ["featured"])
    ]
)
data class CommunityTip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Tip content in Swahili (or worker's language) */
    val content: String,

    /** Location this tip is relevant to */
    val location: String,

    /** Business type this tip is relevant to */
    val businessType: String,

    /** Category: "pricing", "stocking", "marketing", "savings", "general" */
    val category: String = "general",

    /** Upvote count from community */
    val upvotes: Int = 0,

    /** Whether Msaidizi has featured this tip in a briefing */
    val featured: Boolean = false,

    /** Number of times this tip was shown in briefings */
    val featuredCount: Int = 0,

    /** Whether the current device user submitted this tip */
    val isOwnTip: Boolean = false,

    /** Whether the current device user has upvoted this tip */
    val hasUpvoted: Boolean = false,

    /** Server-side tip ID for sync */
    val serverId: String = "",

    /** When tip was created */
    val createdAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Tracks which tips have been shown to the worker
 * to avoid repetition in morning briefings.
 */
@Entity(
    tableName = "tip_delivery_log",
    indices = [
        Index(value = ["tipId"]),
        Index(value = ["deliveredAt"])
    ]
)
data class TipDeliveryLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Which tip was delivered */
    val tipId: Long,

    /** When it was delivered */
    val deliveredAt: Long = System.currentTimeMillis() / 1000,

    /** Whether the worker engaged (upvoted, shared, etc.) */
    val engaged: Boolean = false
)

// ═══════════════════════════════════════════════════════════════
// WHATSAPP COMMUNITY MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * WhatsApp group metadata for community groups.
 * Groups are organized by trade × location.
 */
@Entity(
    tableName = "whatsapp_groups",
    indices = [
        Index(value = ["location", "businessType"]),
        Index(value = ["groupId"])
    ]
)
data class WhatsAppGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** WhatsApp group ID from Business API */
    val groupId: String,

    /** Human-readable group name */
    val groupName: String,

    /** Location this group covers */
    val location: String,

    /** Business type this group covers */
    val businessType: String,

    /** Number of members */
    val memberCount: Int = 0,

    /** Whether the current user is a member */
    val isMember: Boolean = false,

    /** Whether the user has muted this group */
    val isMuted: Boolean = false,

    /** Last market brief shared to this group */
    val lastBriefSharedAt: Long = 0,

    /** Last challenge shared to this group */
    val lastChallengeAt: Long = 0,

    /** Group invite link */
    val inviteLink: String = "",

    /** When group was created/registered */
    val createdAt: Long = System.currentTimeMillis() / 1000
)

/**
 * A peer challenge shared via WhatsApp group.
 * E.g., "Who can record the most sales this week?"
 */
@Entity(
    tableName = "peer_challenges",
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["status"]),
        Index(value = ["endsAt"])
    ]
)
data class PeerChallenge(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Which WhatsApp group this challenge is for */
    val groupId: Long,

    /** Challenge type: "SALES_RACE", "STREAK_CONTEST", "TIP_SHARING" */
    val challengeType: String,

    /** Challenge description in Swahili */
    val description: String,

    /** Challenge metric (e.g., "weekly_sales", "streak_days") */
    val metric: String,

    /** Target value (e.g., 10 for "record 10 sales") */
    val targetValue: Double = 0.0,

    /** Current user's progress toward target */
    val currentProgress: Double = 0.0,

    /** Challenge status: "ACTIVE", "COMPLETED", "EXPIRED" */
    val status: String = "ACTIVE",

    /** When challenge starts */
    val startsAt: Long,

    /** When challenge ends */
    val endsAt: Long,

    /** Number of participants */
    val participantCount: Int = 0,

    /** When challenge was created */
    val createdAt: Long = System.currentTimeMillis() / 1000
)

// ═══════════════════════════════════════════════════════════════
// API MODELS (for server sync)
// ═══════════════════════════════════════════════════════════════

/** Request to fetch peer metrics from server */
data class PeerMetricsRequest(
    val location: String,
    val businessType: String,
    val periodType: String = "DAILY"
)

/** Response with peer metrics from server */
data class PeerMetricsResponse(
    val metrics: PeerMetrics?,
    val peerCount: Int,
    val updatedAt: Long
)

/** Request to fetch leaderboard from server */
data class LeaderboardRequest(
    val location: String,
    val businessType: String,
    val weekStart: Long? = null  // null = current week
)

/** Response with leaderboard data */
data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val myRank: Int,
    val totalParticipants: Int,
    val weekStart: Long
)

/** Request to submit a community tip */
data class SubmitTipRequest(
    val content: String,
    val location: String,
    val businessType: String,
    val category: String = "general",
    val language: String = "sw"
)

/** Request to upvote a tip */
data class UpvoteTipRequest(
    val tipId: String
)

/** Response with community tips */
data class CommunityTipsResponse(
    val tips: List<CommunityTip>,
    val totalCount: Int
)

/** Request to create a WhatsApp group */
data class CreateGroupRequest(
    val location: String,
    val businessType: String,
    val groupName: String
)

/** Request to share a market brief to WhatsApp group */
data class ShareBriefRequest(
    val groupId: Long,
    val briefText: String,
    val briefType: String = "DAILY_MARKET"
)

/** Request to create a peer challenge */
data class CreateChallengeRequest(
    val groupId: Long,
    val challengeType: String,
    val description: String,
    val metric: String,
    val targetValue: Double,
    val durationDays: Int = 7
)

/**
 * Social proof message — generated locally from peer comparison data.
 * Used in morning briefings and notifications.
 */
data class SocialProofMessage(
    val message: String,
    val type: SocialProofType,
    val priority: Int = 0  // Higher = more important, shown first
)

enum class SocialProofType {
    /** "Wewe ni katika 20% ya juu!" */
    PERCENTILE_RANK,

    /** "Mama mboga wengine Migori wanauza wastani wa KSh 4,000 leo" */
    PEER_AVERAGE,

    /** "Uko nafasi ya 15 kati ya 100!" */
    LEADERBOARD_POSITION,

    /** "Mama mboga mmoja Migori anasema: 'Nunua nyanya asubuhi'" */
    COMMUNITY_TIP,

    /** Weekly celebration for top performers */
    LEADERBOARD_CELEBRATION,

    /** Challenge update */
    CHALLENGE_UPDATE
}

package com.msaidizi.app.social

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.onboarding.WorkerProfile
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Leaderboard Service — weekly rankings by location and business type.
 *
 * Privacy-first design:
 * - Anonymous: "Mama mboga #1 Migori" (no names ever)
 * - Only aggregate rank positions, never individual data
 * - Worker sees their own rank + nearby ranks for context
 *
 * Encouraging tone:
 * - "Uko nafasi ya 15 kati ya 100!" (You're position 15 out of 100!)
 * - Celebrates improvements: "Umepanda nafasi 5!"
 * - Never shames — always frames rank positively
 *
 * Weekly cycle:
 * - Monday 00:00: New week starts, ranks reset
 * - Sunday 23:59: Final rankings locked
 * - Monday 7 AM: Weekly celebration message in morning briefing
 *
 * Scoring formula:
 *   score = (weeklySales × 0.4) + (weeklyProfit × 0.3) +
 *           (transactionCount × 10 × 0.2) + (streak × 5 × 0.1)
 *
 * @param socialDao Local storage for leaderboard data
 * @param transactionDao Worker's own transactions
 * @param gamificationEngine For streak and points data
 * @param leaderboardSource Server source for leaderboard data
 */
class LeaderboardService(
    private val socialDao: SocialDao,
    private val transactionDao: TransactionDao,
    private val gamificationEngine: GamificationEngine? = null,
    private val leaderboardSource: LeaderboardSource? = null
) {
    companion object {
        private const val TAG = "LeaderboardService"

        /** Scoring weights */
        private const val WEIGHT_SALES = 0.4
        private const val WEIGHT_PROFIT = 0.3
        private const val WEIGHT_TRANSACTIONS = 0.2
        private const val WEIGHT_STREAK = 0.1

        /** Points per transaction for scoring */
        private const val POINTS_PER_TRANSACTION = 10

        /** Points per streak day for scoring */
        private const val POINTS_PER_STREAK_DAY = 5

        /** How many entries to show in leaderboard display */
        private const val LEADERBOARD_DISPLAY_LIMIT = 20

        /** How many entries above/below user to show for context */
        private const val CONTEXT_RANGE = 5
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY LEADERBOARD — Core operations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the current week's leaderboard for display.
     *
     * Fetches from server (or cache), returns the worker's position
     * and nearby ranks for context.
     *
     * @param profile Worker's profile
     * @param language Language for messages
     * @return LeaderboardOutput with entries and messages
     */
    suspend fun getLeaderboard(
        profile: WorkerProfile,
        language: String = "sw"
    ): LeaderboardOutput {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) {
            return LeaderboardOutput.empty()
        }

        val weekStart = getCurrentWeekStart()

        // Try server first, fall back to cache
        val serverData = fetchLeaderboardFromServer(location, businessType, weekStart)
        if (serverData != null) {
            cacheLeaderboardData(serverData, location, businessType)
        }

        // Get leaderboard entries
        val entries = socialDao.getLeaderboard(
            location, businessType, weekStart, LEADERBOARD_DISPLAY_LIMIT
        )

        // Get user's position
        val userEntry = socialDao.getCurrentUserEntry(location, businessType, weekStart)
        val summary = socialDao.getLeaderboardSummary()

        // Generate messages
        val messages = generateLeaderboardMessages(
            entries, userEntry, summary, location, businessType, language
        )

        return LeaderboardOutput(
            entries = entries,
            userEntry = userEntry,
            summary = summary,
            messages = messages,
            weekStart = weekStart
        )
    }

    /**
     * Get a quick leaderboard position message for the morning briefing.
     * Uses cached data — no server call.
     */
    suspend fun getQuickPositionMessage(
        location: String,
        businessType: String,
        language: String = "sw"
    ): SocialProofMessage? {
        val summary = socialDao.getLeaderboardSummary() ?: return null

        if (summary.totalParticipants == 0) return null

        val rankMessage = formatRankMessage(
            summary.myRank, summary.totalParticipants, summary.rankChange, language
        )

        return SocialProofMessage(
            message = rankMessage,
            type = SocialProofType.LEADERBOARD_POSITION,
            priority = 2
        )
    }

    /**
     * Generate the weekly celebration message for top performers.
     * Called on Monday morning as part of the weekly briefing.
     */
    suspend fun getWeeklyCelebration(
        location: String,
        businessType: String,
        language: String = "sw"
    ): SocialProofMessage? {
        val weekStart = getCurrentWeekStart() - 7 * 86400  // Last week
        val entries = socialDao.getLeaderboard(location, businessType, weekStart, 3)

        if (entries.isEmpty()) return null

        val typeName = getBusinessTypeLabel(businessType, language)
        val message = if (language == "sw") {
            buildString {
                append("🏆 Wiki iliyopita, $typeName bora $location:\n")
                entries.forEachIndexed { index, entry ->
                    val medal = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${index + 1}." }
                    append("$medal Mauzo: KSh ${formatKSh(entry.weeklySales)}\n")
                }
                append("\nJe, wewe utakuwa wa kwanza wiki hii?")
            }
        } else {
            buildString {
                append("🏆 Last week's top ${typeName.lowercase()} in $location:\n")
                entries.forEachIndexed { index, entry ->
                    val medal = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${index + 1}." }
                    append("$medal Sales: KSh ${formatKSh(entry.weeklySales)}\n")
                }
                append("\nCan you be #1 this week?")
            }
        }

        return SocialProofMessage(
            message = message,
            type = SocialProofType.LEADERBOARD_CELEBRATION,
            priority = 3
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SCORE CALCULATION — Worker's weekly score
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate the worker's weekly score for leaderboard ranking.
     *
     * Formula:
     *   score = (weeklySales × 0.4) + (weeklyProfit × 0.3) +
     *           (transactionCount × 10 × 0.2) + (streak × 5 × 0.1)
     *
     * This balances revenue generation, profitability, consistency,
     * and long-term engagement.
     */
    suspend fun calculateWeeklyScore(): Double {
        val weekStart = getCurrentWeekStart()
        val now = System.currentTimeMillis() / 1000

        val transactions = transactionDao.getTransactionsInRangeSuspend(weekStart, now)

        val weeklySales = transactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }

        val weeklyProfit = weeklySales -
            transactions.filter { it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE }
                .sumOf { it.totalAmount }

        val transactionCount = transactions.size

        val streak = try {
            gamificationEngine?.getState()?.currentStreak ?: 0
        } catch (_: Throwable) { 0 }

        return (weeklySales * WEIGHT_SALES) +
            (weeklyProfit * WEIGHT_PROFIT) +
            (transactionCount * POINTS_PER_TRANSACTION * WEIGHT_TRANSACTIONS) +
            (streak * POINTS_PER_STREAK_DAY * WEIGHT_STREAK)
    }

    /**
     * Get the worker's weekly stats for leaderboard submission.
     */
    suspend fun getWeeklyStats(): WeeklyStats {
        val weekStart = getCurrentWeekStart()
        val now = System.currentTimeMillis() / 1000

        val transactions = transactionDao.getTransactionsInRangeSuspend(weekStart, now)

        val weeklySales = transactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }

        val weeklyProfit = weeklySales -
            transactions.filter { it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE }
                .sumOf { it.totalAmount }

        val transactionCount = transactions.size

        val streak = try {
            gamificationEngine?.getState()?.currentStreak ?: 0
        } catch (_: Throwable) { 0 }

        val totalPoints = try {
            gamificationEngine?.getState()?.totalPoints ?: 0
        } catch (_: Throwable) { 0 }

        return WeeklyStats(
            weeklySales = weeklySales,
            weeklyProfit = weeklyProfit,
            transactionCount = transactionCount,
            streak = streak,
            totalPoints = totalPoints,
            score = calculateWeeklyScore()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEK MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get current week start (Monday 00:00).
     */
    fun getCurrentWeekStart(): Long {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
    }

    /**
     * Check if it's time for weekly reset (Monday morning).
     */
    fun isWeeklyResetTime(): Boolean {
        val now = LocalDate.now()
        return now.dayOfWeek == DayOfWeek.MONDAY
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate leaderboard messages for the worker.
     */
    private fun generateLeaderboardMessages(
        entries: List<LeaderboardEntry>,
        userEntry: LeaderboardEntry?,
        summary: LeaderboardSummary?,
        location: String,
        businessType: String,
        language: String
    ): List<SocialProofMessage> {
        val messages = mutableListOf<SocialProofMessage>()

        // User's rank message
        if (userEntry != null && summary != null) {
            messages.add(SocialProofMessage(
                message = formatRankMessage(
                    summary.myRank, summary.totalParticipants,
                    summary.rankChange, language
                ),
                type = SocialProofType.LEADERBOARD_POSITION,
                priority = 3
            ))
        }

        // Top performer celebration (if user is in top 10)
        if (userEntry != null && userEntry.rank <= 10) {
            val typeName = getBusinessTypeLabel(businessType, language)
            messages.add(SocialProofMessage(
                message = if (language == "sw") {
                    "🏆 Wewe ni miongoni mwa $typeName 10 bora $location! Hongera!"
                } else {
                    "🏆 You're among the top 10 ${typeName.lowercase()} in $location! Congratulations!"
                },
                type = SocialProofType.LEADERBOARD_CELEBRATION,
                priority = 4
            ))
        }

        return messages.sortedByDescending { it.priority }
    }

    /**
     * Format a rank message.
     * "Uko nafasi ya 15 kati ya 100!"
     */
    private fun formatRankMessage(
        rank: Int,
        total: Int,
        rankChange: Int,
        language: String
    ): String {
        val changeText = if (rankChange > 0) {
            if (language == "sw") " Umepanda nafasi $rankChange! ⬆️" else " You moved up $rankChange spots! ⬆️"
        } else if (rankChange < 0) {
            if (language == "sw") " Umeshuka nafasi ${-rankChange}. Endelea kujaribu!" else " Down ${-rankChange} spots. Keep trying!"
        } else ""

        return if (language == "sw") {
            "📊 Uko nafasi ya $rank kati ya $total!$changeText"
        } else {
            "📊 You're ranked #$rank out of $total!$changeText"
        }
    }

    /**
     * Get a human-readable business type label.
     */
    private fun getBusinessTypeLabel(businessType: String, language: String): String {
        val type = try { WorkerType.valueOf(businessType) } catch (_: Throwable) { WorkerType.UNKNOWN }
        return if (language == "sw") {
            when (type) {
                WorkerType.TRADER -> "Wafanyabiashara"
                WorkerType.TRANSPORT -> "Wabebaji"
                WorkerType.FARMER -> "Wakulima"
                WorkerType.SERVICE -> "Watoa huduma"
                WorkerType.MANUFACTURING -> "Watengenezaji"
                WorkerType.DIGITAL -> "Wakala wa kidijitali"
                WorkerType.UNKNOWN -> "Wafanyabiashara"
            }
        } else {
            when (type) {
                WorkerType.TRADER -> "Traders"
                WorkerType.TRANSPORT -> "Transporters"
                WorkerType.FARMER -> "Farmers"
                WorkerType.SERVICE -> "Service providers"
                WorkerType.MANUFACTURING -> "Manufacturers"
                WorkerType.DIGITAL -> "Digital agents"
                WorkerType.UNKNOWN -> "Businesses"
            }
        }
    }

    /**
     * Format KSh amount.
     */
    private fun formatKSh(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVER INTERACTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch leaderboard data from server.
     */
    private suspend fun fetchLeaderboardFromServer(
        location: String,
        businessType: String,
        weekStart: Long
    ): LeaderboardResponse? {
        if (leaderboardSource == null) return null

        return try {
            leaderboardSource.fetchLeaderboard(location, businessType, weekStart)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to fetch leaderboard from server")
            null
        }
    }

    /**
     * Cache leaderboard data locally.
     */
    private suspend fun cacheLeaderboardData(
        data: LeaderboardResponse,
        location: String,
        businessType: String
    ) {
        try {
            // Insert entries
            socialDao.insertLeaderboardEntries(data.entries)

            // Update summary
            socialDao.upsertLeaderboardSummary(
                LeaderboardSummary(
                    myRank = data.myRank,
                    totalParticipants = data.totalParticipants,
                    myWeeklySales = data.entries.firstOrNull { it.isCurrentUser }?.weeklySales ?: 0.0,
                    myWeeklyProfit = data.entries.firstOrNull { it.isCurrentUser }?.weeklyProfit ?: 0.0,
                    rankChange = calculateRankChange(data.myRank),
                    weekStart = data.weekStart,
                    location = location,
                    businessType = businessType
                )
            )

            // Cleanup old entries (keep 8 weeks)
            val cutoff = System.currentTimeMillis() / 1000 - (8 * 7 * 86400)
            socialDao.deleteOldLeaderboardEntries(cutoff)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to cache leaderboard data")
        }
    }

    /**
     * Calculate rank change from previous week.
     */
    private suspend fun calculateRankChange(currentRank: Int): Int {
        val previous = socialDao.getLeaderboardSummary() ?: return 0
        return previous.myRank - currentRank  // Positive = improved
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Output of a leaderboard query.
 */
data class LeaderboardOutput(
    val entries: List<LeaderboardEntry>,
    val userEntry: LeaderboardEntry?,
    val summary: LeaderboardSummary?,
    val messages: List<SocialProofMessage>,
    val weekStart: Long
) {
    companion object {
        fun empty() = LeaderboardOutput(
            entries = emptyList(),
            userEntry = null,
            summary = null,
            messages = emptyList(),
            weekStart = 0
        )
    }

    val hasData: Boolean get() = entries.isNotEmpty()
}

/**
 * Worker's weekly statistics for leaderboard submission.
 */
data class WeeklyStats(
    val weeklySales: Double,
    val weeklyProfit: Double,
    val transactionCount: Int,
    val streak: Int,
    val totalPoints: Int,
    val score: Double
)

/**
 * Interface for fetching leaderboard data from the server.
 */
interface LeaderboardSource {
    /**
     * Fetch leaderboard for a location × business type × week.
     */
    suspend fun fetchLeaderboard(
        location: String,
        businessType: String,
        weekStart: Long
    ): LeaderboardResponse?
}

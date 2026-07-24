package com.msaidizi.app.superagent.gamification

import timber.log.Timber

/**
 * Social Engine — peer comparison, leaderboard, and community features.
 *
 * ## Privacy-First Design
 * - All comparisons are anonymized (no individual data exposed)
 * - Minimum peer count of 5 for any comparison (k-anonymity)
 * - Worker sees their percentile rank, not individual peer data
 * - Opt-in only — workers can disable social features
 *
 * ## Features
 * - **Percentile Rank**: "You're in the top 20% of food vendors in your area!"
 * - **Peer Average**: "Most food vendors record 5 sales/day. You recorded 8!"
 * - **Leaderboard**: Anonymous top performers (by streak, sales, points)
 * - **Community Tips**: Shared business advice from successful peers
 * - **Social Proof**: Motivational messages based on peer data
 *
 * @param socialProvider Provides access to social data storage
 */
class SocialEngine(
    private val socialProvider: SocialProvider
) {
    companion object {
        private const val TAG = "SocialEngine"

        /** Minimum peers for comparison (k-anonymity) */
        const val MIN_PEERS_FOR_COMPARISON = 5

        /** How often to show social proof (1 in N interactions) */
        const val SOCIAL_PROOF_FREQUENCY = 10
    }

    /**
     * Get peer comparison for the worker.
     *
     * @param workerStats The worker's current stats
     * @param language "sw" or "en"
     * @return PeerComparison with relative performance, or null if insufficient peers
     */
    suspend fun getPeerComparison(workerStats: WorkerStats, language: String = "sw"): PeerComparison? {
        val peers = socialProvider.getPeerMetrics(workerStats.location, workerStats.businessType)

        if (peers == null || peers.peerCount < MIN_PEERS_FOR_COMPARISON) {
            Timber.d(TAG, "Insufficient peers for comparison (%d)", peers?.peerCount ?: 0)
            return null
        }

        val salesPercentile = calculatePercentile(workerStats.dailySales, peers.avgDailySales, peers.p90DailySales)
        val streakPercentile = calculatePercentile(workerStats.streak.toDouble(), peers.avgStreak, peers.maxStreak.toDouble())

        val message = buildComparisonMessage(workerStats, peers, salesPercentile, language)

        return PeerComparison(
            salesPercentile = salesPercentile,
            streakPercentile = streakPercentile,
            peerCount = peers.peerCount,
            avgDailySales = peers.avgDailySales,
            avgStreak = peers.avgStreak,
            message = message
        )
    }

    /**
     * Get leaderboard (top performers in the worker's area).
     *
     * @param location The worker's location
     * @param businessType The worker's business type
     * @param language "sw" or "en"
     * @return List of anonymous top performers
     */
    suspend fun getLeaderboard(
        location: String,
        businessType: String,
        language: String = "sw"
    ): Leaderboard {
        val entries = socialProvider.getLeaderboardEntries(location, businessType)

        return Leaderboard(
            entries = entries.mapIndexed { index, entry ->
                LeaderboardEntry(
                    rank = index + 1,
                    streak = entry.streak,
                    level = entry.level,
                    badge = entry.topBadge,
                    isAnonymous = true
                )
            },
            workerRank = socialProvider.getWorkerRank(location, businessType),
            message = buildLeaderboardMessage(entries.size, language)
        )
    }

    /**
     * Get a social proof message — motivational comparison with peers.
     * Fires ~10% of the time to avoid spam.
     *
     * @param workerStats The worker's current stats
     * @param language "sw" or "en"
     * @return Social proof message, or null if not appropriate
     */
    suspend fun getSocialProof(workerStats: WorkerStats, language: String = "sw"): String? {
        if (Math.random() > (1.0 / SOCIAL_PROOF_FREQUENCY)) return null

        val peers = socialProvider.getPeerMetrics(workerStats.location, workerStats.businessType)
            ?: return null

        if (peers.peerCount < MIN_PEERS_FOR_COMPARISON) return null

        return if (language == "sw") {
            when {
                workerStats.dailySales >= 10 -> "🏆 Wafanyabiashara 10% bora wanarekodi mauzo 10+ kwa siku. Wewe ni mmoja wao!"
                workerStats.dailySales >= 5 -> "👥 Wafanyabiashara wengi wanarekodi 3-5 kwa siku. Wewe umefanya ${workerStats.dailySales}!"
                workerStats.streak >= 7 -> "🔥 Wafanyabiashara wenye mfululizo wa wiki 2+ wanapata faida 40% zaidi. Wewe uko njiani!"
                workerStats.streak >= 3 -> "📊 Wafanyabiashara wanaorekodi kila siku wanafanya vizuri zaidi. Endelea!"
                else -> null
            }
        } else {
            when {
                workerStats.dailySales >= 10 -> "🏆 Top 10% of businesses record 10+ sales daily. You're one of them!"
                workerStats.dailySales >= 5 -> "👥 Most businesses record 3-5 sales daily. You've done ${workerStats.dailySales}!"
                workerStats.streak >= 7 -> "🔥 Businesses with 2+ week streaks earn 40% more. You're on track!"
                workerStats.streak >= 3 -> "📊 Daily recorders do better. Keep going!"
                else -> null
            }
        }
    }

    /**
     * Get community tips shared by successful peers.
     */
    suspend fun getCommunityTips(
        businessType: String,
        language: String = "sw"
    ): List<CommunityTip> {
        return socialProvider.getCommunityTips(businessType).map { tip ->
            CommunityTip(
                id = tip.id,
                text = if (language == "sw") tip.textSw else tip.textEn,
                category = tip.category,
                likes = tip.likes
            )
        }
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun calculatePercentile(value: Double, avg: Double, p90: Double): Int {
        if (p90 <= 0) return 50
        return when {
            value >= p90 -> 90
            value >= avg -> 50 + ((value - avg) / (p90 - avg) * 40).toInt()
            else -> ((value / avg) * 50).toInt()
        }.coerceIn(0, 99)
    }

    private fun buildComparisonMessage(
        workerStats: WorkerStats,
        peers: PeerMetricsAggregate,
        salesPercentile: Int,
        language: String
    ): String {
        return if (language == "sw") {
            when {
                salesPercentile >= 80 -> "🏆 Wewe ni miongoni mwa wafanyabiashara bora 20% eneo lako! " +
                    "Umeuza mara zaidi ya wastani wa ${"%.0f".format(peers.avgDailySales)} kwa siku."
                salesPercentile >= 50 -> "📊 Uko juu ya wastani! Wastani wa eneo lako ni " +
                    "${"%.0f".format(peers.avgDailySales)} mauzo kwa siku. Wewe umefanya ${workerStats.dailySales}."
                else -> "💪 Endelea kujitahidi! Wastani wa eneo lako ni " +
                    "${"%.0f".format(peers.avgDailySales)} mauzo kwa siku. Unaweza kufika huko!"
            }
        } else {
            when {
                salesPercentile >= 80 -> "🏆 You're in the top 20% in your area! " +
                    "You sold more than the average of ${"%.0f".format(peers.avgDailySales)} per day."
                salesPercentile >= 50 -> "📊 You're above average! Area average is " +
                    "${"%.0f".format(peers.avgDailySales)} sales/day. You did ${workerStats.dailySales}."
                else -> "💪 Keep pushing! Area average is " +
                    "${"%.0f".format(peers.avgDailySales)} sales/day. You can get there!"
            }
        }
    }

    private fun buildLeaderboardMessage(entryCount: Int, language: String): String {
        return if (language == "sw") {
            "🏅 Orodha ya wafanyabiashara bora $entryCount eneo lako"
        } else {
            "🏅 Top $entryCount business performers in your area"
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Worker stats for comparison.
 */
data class WorkerStats(
    val location: String,
    val businessType: String,
    val dailySales: Int,
    val streak: Int,
    val totalPoints: Int,
    val level: Int
)

/**
 * Peer comparison result.
 */
data class PeerComparison(
    val salesPercentile: Int,
    val streakPercentile: Int,
    val peerCount: Int,
    val avgDailySales: Double,
    val avgStreak: Double,
    val message: String
)

/**
 * Leaderboard data.
 */
data class Leaderboard(
    val entries: List<LeaderboardEntry>,
    val workerRank: Int?,
    val message: String
)

/**
 * A leaderboard entry (anonymous).
 */
data class LeaderboardEntry(
    val rank: Int,
    val streak: Int,
    val level: Int,
    val badge: String?,
    val isAnonymous: Boolean
)

/**
 * Community tip from a peer.
 */
data class CommunityTip(
    val id: String,
    val text: String,
    val category: String,
    val likes: Int
)

/**
 * Peer metrics aggregate (anonymized).
 */
data class PeerMetricsAggregate(
    val peerCount: Int,
    val avgDailySales: Double,
    val avgStreak: Double,
    val maxStreak: Int,
    val p90DailySales: Double
)

/**
 * Internal leaderboard entry.
 */
internal data class LeaderboardEntryData(
    val streak: Int,
    val level: Int,
    val topBadge: String?
)

/**
 * Internal community tip.
 */
internal data class CommunityTipData(
    val id: String,
    val textSw: String,
    val textEn: String,
    val category: String,
    val likes: Int
)

/**
 * Interface for social data storage.
 */
interface SocialProvider {
    suspend fun getPeerMetrics(location: String, businessType: String): PeerMetricsAggregate?
    suspend fun getLeaderboardEntries(location: String, businessType: String): List<LeaderboardEntryData>
    suspend fun getWorkerRank(location: String, businessType: String): Int?
    suspend fun getCommunityTips(businessType: String): List<CommunityTipData>
}

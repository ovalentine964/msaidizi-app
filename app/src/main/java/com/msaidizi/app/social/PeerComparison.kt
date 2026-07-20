package com.msaidizi.app.social

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.onboarding.WorkerProfile
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Peer Comparison Engine — compares a worker's metrics against anonymized
 * peers in the same location and business type.
 *
 * Privacy guarantees:
 * - Only aggregate data (averages, percentiles) — never individual data
 * - Minimum cohort size of 5 (k-anonymity) before any comparison is shown
 * - All comparisons are relative ("you're in the top 20%"), not absolute
 * - No names, no phone numbers, no identifying information
 *
 * Messages are in Swahili to match how workers actually communicate:
 * "Mama mboga wengine Migori wanauza wastani wa KSh 4,000 leo"
 * "Wewe ni katika 20% ya juu!"
 *
 * Mathematical foundation:
 * - STA 101 (Descriptive Statistics): Mean, median, percentiles
 * - STA 242 (Sampling): k-anonymity for privacy
 *
 * @param socialDao Local storage for peer metrics and comparison results
 * @param transactionDao Worker's own transaction data
 * @param peerMetricsSource Server source for peer aggregate data
 */
class PeerComparison(
    private val socialDao: SocialDao,
    private val transactionDao: TransactionDao,
    private val peerMetricsSource: PeerMetricsSource? = null
) {
    companion object {
        private const val TAG = "PeerComparison"

        /** Minimum peers required for comparison (k-anonymity) */
        const val MIN_PEER_COUNT = 5

        /** How often to refresh peer metrics from server (hours) */
        private const val METRICS_REFRESH_HOURS = 6

        /** Days of local data to use for worker's own metrics */
        private const val LOCAL_METRICS_WINDOW_DAYS = 7
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE COMPARISON — Run daily or on-demand
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a full peer comparison for the worker.
     *
     * Fetches peer metrics (from cache or server), computes the worker's
     * own metrics, and generates localized comparison messages.
     *
     * @param profile Worker's profile (location, business type)
     * @param language Language for messages ("sw" or "en")
     * @return PeerComparisonOutput with messages and data
     */
    suspend fun generateComparison(
        profile: WorkerProfile,
        language: String = "sw"
    ): PeerComparisonOutput {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) {
            Timber.tag(TAG).d("No location set, skipping peer comparison")
            return PeerComparisonOutput.empty()
        }

        // Step 1: Get peer metrics (from cache or server)
        val peerMetrics = getOrFetchPeerMetrics(location, businessType)
        if (peerMetrics == null || peerMetrics.peerCount < MIN_PEER_COUNT) {
            Timber.tag(TAG).d(
                "Not enough peers for comparison (count=%d, min=%d)",
                peerMetrics?.peerCount ?: 0, MIN_PEER_COUNT
            )
            return PeerComparisonOutput.empty()
        }

        // Step 2: Compute worker's own metrics
        val workerMetrics = computeWorkerMetrics()

        // Step 3: Calculate percentiles
        val salesPercentile = calculatePercentile(
            workerMetrics.todaySales, peerMetrics.p25DailySales,
            peerMetrics.medianDailySales, peerMetrics.p75DailySales,
            peerMetrics.p90DailySales, peerMetrics.avgDailySales
        )
        val profitPercentile = calculatePercentile(
            workerMetrics.todayProfit, 0.0,
            peerMetrics.medianDailyProfit, peerMetrics.avgDailyProfit * 1.3,
            peerMetrics.avgDailyProfit * 2.0, peerMetrics.avgDailyProfit
        )
        val txnPercentile = calculatePercentileFromAvg(
            workerMetrics.todayTransactionCount.toDouble(),
            peerMetrics.avgTransactionCount
        )

        // Step 4: Cache comparison result
        val result = PeerComparisonResult(
            location = location,
            businessType = businessType,
            workerDailySales = workerMetrics.todaySales,
            salesPercentile = salesPercentile,
            workerDailyProfit = workerMetrics.todayProfit,
            profitPercentile = profitPercentile,
            workerTransactionCount = workerMetrics.todayTransactionCount,
            transactionPercentile = txnPercentile,
            workerStreak = workerMetrics.currentStreak,
            peerAvgDailySales = peerMetrics.avgDailySales,
            peerCount = peerMetrics.peerCount,
            comparedAt = System.currentTimeMillis() / 1000
        )
        socialDao.upsertPeerComparison(result)

        // Step 5: Generate messages
        val messages = generateMessages(result, peerMetrics, location, businessType, language)

        Timber.tag(TAG).d(
            "Comparison generated: sales=%d%% profit=%d%% peers=%d",
            salesPercentile, profitPercentile, peerMetrics.peerCount
        )

        return PeerComparisonOutput(
            comparison = result,
            messages = messages,
            peerMetrics = peerMetrics
        )
    }

    /**
     * Get the cached comparison result (for instant display).
     */
    suspend fun getCachedComparison(): PeerComparisonResult? {
        return socialDao.getPeerComparison()
    }

    /**
     * Generate a quick social proof message for the morning briefing.
     * Lightweight — uses cached data, no server call.
     *
     * @param location Worker's location
     * @param businessType Worker's business type
     * @param language Language preference
     * @return A single social proof message or null
     */
    suspend fun getQuickSocialProof(
        location: String,
        businessType: String,
        language: String = "sw"
    ): SocialProofMessage? {
        val cached = socialDao.getPeerComparison() ?: return null
        val peerMetrics = socialDao.getLatestPeerMetrics(location, businessType) ?: return null

        if (peerMetrics.peerCount < MIN_PEER_COUNT) return null

        // Pick the most impressive stat to show
        return when {
            cached.salesPercentile >= 80 -> SocialProofMessage(
                message = formatPercentileMessage(cached.salesPercentile, language),
                type = SocialProofType.PERCENTILE_RANK,
                priority = 3
            )
            cached.profitPercentile >= 70 -> SocialProofMessage(
                message = formatPercentileMessage(cached.profitPercentile, language),
                type = SocialProofType.PERCENTILE_RANK,
                priority = 2
            )
            else -> SocialProofMessage(
                message = formatPeerAverageMessage(
                    location, businessType, peerMetrics.avgDailySales, language
                ),
                type = SocialProofType.PEER_AVERAGE,
                priority = 1
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PEER METRICS — Fetch and cache
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get peer metrics from cache, or fetch from server if stale.
     */
    private suspend fun getOrFetchPeerMetrics(
        location: String,
        businessType: String
    ): PeerMetrics? {
        // Check cache first
        val cached = socialDao.getLatestPeerMetrics(location, businessType)
        if (cached != null) {
            val ageHours = (System.currentTimeMillis() / 1000 - cached.computedAt) / 3600
            if (ageHours < METRICS_REFRESH_HOURS) {
                Timber.tag(TAG).d("Using cached peer metrics (age=%dh)", ageHours)
                return cached
            }
        }

        // Fetch from server
        if (peerMetricsSource == null) {
            Timber.tag(TAG).d("No peer metrics source available, using cache")
            return cached
        }

        return try {
            val fresh = peerMetricsSource.fetchPeerMetrics(location, businessType)
            if (fresh != null && fresh.peerCount >= MIN_PEER_COUNT) {
                socialDao.insertPeerMetrics(fresh)
                // Cleanup old metrics
                val cutoff = System.currentTimeMillis() / 1000 - (30 * 86400)
                socialDao.deleteOldPeerMetrics(cutoff)
                fresh
            } else {
                cached
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to fetch peer metrics, using cache")
            cached
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WORKER METRICS — Compute from local transactions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute the worker's own metrics from local transaction data.
     */
    private suspend fun computeWorkerMetrics(): WorkerMetrics {
        val now = System.currentTimeMillis() / 1000
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val weekAgoStart = todayStart - (LOCAL_METRICS_WINDOW_DAYS * 86400)

        val todayTransactions = transactionDao.getTransactionsInRangeSuspend(todayStart, now)
        val weekTransactions = transactionDao.getTransactionsInRangeSuspend(weekAgoStart, now)

        val todaySales = todayTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }

        val todayProfit = todayTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount } -
            todayTransactions
                .filter { it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE }
                .sumOf { it.totalAmount }

        val todayTransactionCount = todayTransactions.size

        // Weekly averages for stable comparison
        val dailySalesValues = weekTransactions
            .groupBy { it.createdAt / 86400 }
            .mapValues { (_, txns) ->
                txns.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
            }
            .values.toList()

        val avgDailySales = if (dailySalesValues.isNotEmpty()) {
            dailySalesValues.average()
        } else 0.0

        return WorkerMetrics(
            todaySales = todaySales,
            todayProfit = todayProfit,
            todayTransactionCount = todayTransactionCount,
            avgDailySales = avgDailySales,
            currentStreak = 0  // Filled from gamification if available
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PERCENTILE CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate percentile rank using known percentile anchors.
     *
     * Given p25, median, p75, p90, and mean, we interpolate the
     * worker's position using piecewise linear interpolation.
     *
     * STA 101 (Percentiles): If we know the value at certain percentiles,
     * we can estimate where a new value falls.
     */
    private fun calculatePercentile(
        value: Double,
        p25: Double,
        median: Double,
        p75: Double,
        p90: Double,
        mean: Double
    ): Int {
        if (value <= 0) return 0

        return when {
            value >= p90 -> 90 + ((value - p90) / (mean * 2 - p90).coerceAtLeast(1.0) * 10).toInt().coerceAtMost(10)
            value >= p75 -> 75 + ((value - p75) / (p90 - p75).coerceAtLeast(1.0) * 15).toInt()
            value >= median -> 50 + ((value - median) / (p75 - median).coerceAtLeast(1.0) * 25).toInt()
            value >= p25 -> 25 + ((value - p25) / (median - p25).coerceAtLeast(1.0) * 25).toInt()
            value > 0 -> (value / p25.coerceAtLeast(1.0) * 25).toInt()
            else -> 0
        }.coerceIn(0, 99)
    }

    /**
     * Simplified percentile from average (when we don't have full distribution).
     * Assumes roughly normal distribution around the mean.
     */
    private fun calculatePercentileFromAvg(value: Double, avg: Double): Int {
        if (avg <= 0 || value <= 0) return 0
        val ratio = value / avg
        return when {
            ratio >= 2.0 -> 95
            ratio >= 1.5 -> 85
            ratio >= 1.2 -> 70
            ratio >= 1.0 -> 55
            ratio >= 0.8 -> 40
            ratio >= 0.5 -> 25
            else -> 10
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION — Swahili-first, encouraging
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate localized comparison messages.
     * Messages are encouraging and actionable, never discouraging.
     */
    private fun generateMessages(
        result: PeerComparisonResult,
        peerMetrics: PeerMetrics,
        location: String,
        businessType: String,
        language: String
    ): List<SocialProofMessage> {
        val messages = mutableListOf<SocialProofMessage>()
        val typeName = getBusinessTypeLabel(businessType, language)

        // 1. Percentile message (if impressive)
        if (result.salesPercentile >= 60) {
            messages.add(SocialProofMessage(
                message = formatPercentileMessage(result.salesPercentile, language),
                type = SocialProofType.PERCENTILE_RANK,
                priority = 3
            ))
        }

        // 2. Peer average comparison
        if (peerMetrics.avgDailySales > 0) {
            messages.add(SocialProofMessage(
                message = formatPeerAverageMessage(location, typeName, peerMetrics.avgDailySales, language),
                type = SocialProofType.PEER_AVERAGE,
                priority = 2
            ))
        }

        // 3. Encouraging comparison (if below average)
        if (result.salesPercentile in 20..50) {
            messages.add(SocialProofMessage(
                message = formatEncouragingMessage(result, peerMetrics, location, typeName, language),
                type = SocialProofType.PEER_AVERAGE,
                priority = 1
            ))
        }

        return messages.sortedByDescending { it.priority }
    }

    /**
     * Format a percentile rank message.
     */
    private fun formatPercentileMessage(percentile: Int, language: String): String {
        return if (language == "sw") {
            when {
                percentile >= 90 -> "🏆 Wewe ni katika 10% ya juu ya wafanyabiashara! Hongera!"
                percentile >= 75 -> "⭐ Wewe ni katika 25% ya juu! Biashara yako inafanya vizuri!"
                percentile >= 60 -> "💪 Wewe ni katika 40% ya juu! Endelea hivi!"
                else -> "📊 Uko katika $percentile% ya juu."
            }
        } else {
            when {
                percentile >= 90 -> "🏆 You're in the top 10% of businesses! Congratulations!"
                percentile >= 75 -> "⭐ You're in the top 25%! Your business is doing great!"
                percentile >= 60 -> "💪 You're in the top 40%! Keep it up!"
                else -> "📊 You're in the top $percentile%."
            }
        }
    }

    /**
     * Format a peer average comparison message.
     * "Mama mboga wengine Migori wanauza wastani wa KSh 4,000 leo"
     */
    private fun formatPeerAverageMessage(
        location: String,
        typeName: String,
        avgSales: Double,
        language: String
    ): String {
        val formattedAvg = formatKSh(avgSales)
        return if (language == "sw") {
            "${typeName} wengine $location wanauza wastani wa KSh $formattedAvg kwa siku."
        } else {
            "Other ${typeName.lowercase()} in $location sell an average of KSh $formattedAvg per day."
        }
    }

    /**
     * Format an encouraging message for workers below average.
     * Never discouraging — always frames it as an achievable target.
     */
    private fun formatEncouragingMessage(
        result: PeerComparisonResult,
        peerMetrics: PeerMetrics,
        location: String,
        typeName: String,
        language: String
    ): String {
        val gap = peerMetrics.avgDailySales - result.workerDailySales
        val formattedGap = formatKSh(gap)

        return if (language == "sw") {
            "Ongeza mauzo ya KSh $formattedGap tu ili kufikia wastani wa $typeName wengine $location. Unaweza!"
        } else {
            "You only need KSh $formattedGap more to reach the average for ${typeName.lowercase()} in $location. You can do it!"
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
     * Format KSh amount with thousands separator.
     */
    private fun formatKSh(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Output of a peer comparison generation.
 */
data class PeerComparisonOutput(
    val comparison: PeerComparisonResult,
    val messages: List<SocialProofMessage>,
    val peerMetrics: PeerMetrics
) {
    companion object {
        fun empty() = PeerComparisonOutput(
            comparison = PeerComparisonResult(),
            messages = emptyList(),
            peerMetrics = PeerMetrics(location = "", businessType = "", periodStart = 0L)
        )
    }

    val hasComparison: Boolean get() = peerMetrics.peerCount >= PeerComparison.MIN_PEER_COUNT
}

/**
 * Worker's own computed metrics for comparison.
 */
data class WorkerMetrics(
    val todaySales: Double,
    val todayProfit: Double,
    val todayTransactionCount: Int,
    val avgDailySales: Double,
    val currentStreak: Int
)

/**
 * Interface for fetching peer metrics from the server.
 * Implementations handle the actual API call.
 */
interface PeerMetricsSource {
    /**
     * Fetch aggregated peer metrics for a location × business type.
     * Returns null if server is unavailable or data is insufficient.
     */
    suspend fun fetchPeerMetrics(location: String, businessType: String): PeerMetrics?
}

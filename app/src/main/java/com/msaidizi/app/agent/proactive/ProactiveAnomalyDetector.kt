package com.msaidizi.app.agent.proactive

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Proactive Anomaly Detector — Z-score based detection for unusual patterns.
 *
 * Goes beyond the validation-focused AnomalyDetector in core/validation
 * to proactively identify suspicious business patterns that the worker
 * should know about.
 *
 * Detection categories:
 * 1. **Amount anomalies** — Transaction amount is statistically unusual (Z-score > 2.5)
 * 2. **Timing anomalies** — Transaction at unusual hour for this business
 * 3. **Volume anomalies** — Unusually many/few transactions for the day
 * 4. **Missing transactions** — Expected transactions that didn't happen
 * 5. **Price anomalies** — Unit price deviates from historical pattern
 *
 * Algorithm:
 *   Z-score = (x - μ) / σ
 *   Where μ = mean of historical data, σ = standard deviation
 *   |Z| > 2.5 → anomaly (99% confidence interval)
 *
 * All math is pure Kotlin — no LLM dependency.
 * Designed for 2GB devices: O(n) per check, minimal memory.
 *
 * @param transactionDao Transaction history for building baselines
 */
class ProactiveAnomalyDetector(
    private val transactionDao: TransactionDao
) {
    companion object {
        /** Z-score threshold for anomaly detection (2.5 = ~99% confidence) */
        private const val Z_THRESHOLD = 2.5

        /** Minimum transactions needed for meaningful statistics */
        private const val MIN_SAMPLES = 5

        /** Lookback period for building baselines (days) */
        private const val BASELINE_DAYS = 30L

        /** Hour considered "unusual" if outside business hours (configurable) */
        private const val EARLY_HOUR = 5   // Before 5 AM
        private const val LATE_HOUR = 22   // After 10 PM

        /** Minimum daily transaction count to flag as "missing" */
        private const val MIN_DAILY_TRANSACTIONS = 2
    }

    // ═══════════════ TRANSACTION-LEVEL DETECTION ═══════════════

    /**
     * Check a single transaction for anomalies against recent history.
     * Returns a list of detected anomalies (empty = normal).
     */
    suspend fun checkTransaction(transaction: Transaction): List<ProactiveAnomaly> =
        withContext(Dispatchers.IO) {
            val anomalies = mutableListOf<ProactiveAnomaly>()

            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(BASELINE_DAYS)
            val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            val history = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)

            // 1. Amount anomaly (Z-score)
            checkAmountAnomaly(transaction, history)?.let { anomalies.add(it) }

            // 2. Timing anomaly
            checkTimingAnomaly(transaction, history)?.let { anomalies.add(it) }

            // 3. Price anomaly for same item
            checkPriceAnomaly(transaction, history)?.let { anomalies.add(it) }

            anomalies
        }

    /**
     * Check a transaction against recent transactions (real-time context).
     * Used when we already have recent transactions loaded.
     */
    fun checkTransactionRealtime(
        transaction: Transaction,
        recentTransactions: List<Transaction>
    ): List<ProactiveAnomaly> {
        val anomalies = mutableListOf<ProactiveAnomaly>()

        checkAmountAnomalySync(transaction, recentTransactions)?.let { anomalies.add(it) }
        checkTimingAnomalySync(transaction, recentTransactions)?.let { anomalies.add(it) }
        checkPriceAnomalySync(transaction, recentTransactions)?.let { anomalies.add(it) }

        return anomalies
    }

    // ═══════════════ AMOUNT ANOMALY (Z-SCORE) ═══════════════

    /**
     * Detect if a transaction amount is statistically unusual.
     *
     * Uses Z-score: Z = (amount - mean) / stddev
     * If |Z| > 2.5, the amount is in the extreme 1% of the distribution.
     */
    private suspend fun checkAmountAnomaly(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        val similar = history.filter {
            it.type == transaction.type &&
                    it.item.equals(transaction.item, ignoreCase = true)
        }

        if (similar.size < MIN_SAMPLES) return null

        val amounts = similar.map { it.totalAmount }
        val (mean, stddev) = calculateStats(amounts)

        if (stddev <= 0) return null

        val zScore = (transaction.totalAmount - mean) / stddev

        if (abs(zScore) > Z_THRESHOLD) {
            val direction = if (zScore > 0) "kubwa" else "ndogo"
            val severity = if (abs(zScore) > 3.5) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING

            return ProactiveAnomaly(
                type = AnomalyType.AMOUNT_ANOMALY,
                severity = severity,
                message = "Umeuza ${transaction.item} kwa KSh ${formatKes(transaction.totalAmount)}! " +
                        "Hii ni $direction sana ikilinganishwa na wastani wako wa KSh ${formatKes(mean)}. " +
                        "Hii ni ya kawaida?",
                zScore = zScore,
                expectedValue = mean,
                actualValue = transaction.totalAmount,
                data = mapOf(
                    "item" to transaction.item,
                    "mean" to mean.toString(),
                    "stddev" to stddev.toString(),
                    "zScore" to zScore.toString()
                )
            )
        }

        return null
    }

    // ═══════════════ TIMING ANOMALY ═══════════════

    /**
     * Detect if a transaction happened at an unusual time.
     *
     * Checks:
     * 1. Outside typical business hours
     * 2. At an hour when this business rarely operates
     */
    private suspend fun checkTimingAnomaly(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        val salesHistory = history.filter { it.type == TransactionType.SALE }
        if (salesHistory.size < MIN_SAMPLES) return null

        val hour = Instant.ofEpochSecond(transaction.createdAt)
            .atZone(ZoneOffset.UTC)
            .hour

        // Check if outside absolute unusual hours
        if (hour in EARLY_HOUR..LATE_HOUR) return null

        // Check if this hour is unusual for THIS business
        val hourCounts = IntArray(24)
        for (tx in salesHistory) {
            val txHour = Instant.ofEpochSecond(tx.createdAt).atZone(ZoneOffset.UTC).hour
            hourCounts[txHour]++
        }

        val totalTx = salesHistory.size
        val thisHourPct = hourCounts[hour].toDouble() / totalTx
        val expectedPct = 1.0 / 24.0  // Uniform distribution

        // If this hour has < 2% of transactions and it's outside business hours
        if (thisHourPct < 0.02 && (hour < EARLY_HOUR || hour > LATE_HOUR)) {
            return ProactiveAnomaly(
                type = AnomalyType.TIMING_ANOMALY,
                severity = AnomalySeverity.WARNING,
                message = "Umerodi muamala saa $hour:00 — ni wakati wa kawaida wa biashara yako?",
                zScore = 0.0,
                expectedValue = expectedPct * totalTx,
                actualValue = hourCounts[hour].toDouble(),
                data = mapOf(
                    "hour" to hour.toString(),
                    "hourPercentage" to (thisHourPct * 100).toString()
                )
            )
        }

        return null
    }

    // ═══════════════ PRICE ANOMALY ═══════════════

    /**
     * Detect if the unit price of a transaction is unusual for that item.
     *
     * Compares against historical unit prices for the same item using Z-score.
     */
    private suspend fun checkPriceAnomaly(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        if (transaction.quantity <= 0) return null

        val sameItem = history.filter {
            it.item.equals(transaction.item, ignoreCase = true) &&
                    it.quantity > 0 &&
                    it.type == transaction.type
        }

        if (sameItem.size < MIN_SAMPLES) return null

        val unitPrices = sameItem.map { it.totalAmount / it.quantity }
        val (mean, stddev) = calculateStats(unitPrices)

        if (stddev <= 0 || mean <= 0) return null

        val currentUnitPrice = transaction.totalAmount / transaction.quantity
        val zScore = (currentUnitPrice - mean) / stddev

        if (abs(zScore) > Z_THRESHOLD) {
            val direction = if (zScore > 0) "imepanda" else "imeshuka"
            return ProactiveAnomaly(
                type = AnomalyType.PRICE_ANOMALY,
                severity = AnomalySeverity.WARNING,
                message = "Bei ya ${transaction.item} $direction! " +
                        "Sasa: KSh ${formatKes(currentUnitPrice)}, " +
                        "Wastani: KSh ${formatKes(mean)}. " +
                        "Je, bei imebadilika sokoni?",
                zScore = zScore,
                expectedValue = mean,
                actualValue = currentUnitPrice,
                data = mapOf(
                    "item" to transaction.item,
                    "currentPrice" to currentUnitPrice.toString(),
                    "averagePrice" to mean.toString(),
                    "zScore" to zScore.toString()
                )
            )
        }

        return null
    }

    // ═══════════════ DAILY VOLUME ANOMALY ═══════════════

    /**
     * Check if today's transaction volume is unusual.
     * Detects both unusually high and low activity days.
     */
    suspend fun checkDailyVolume(): ProactiveAnomaly? = withContext(Dispatchers.IO) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(BASELINE_DAYS)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val history = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)
        if (history.size < MIN_SAMPLES * BASELINE_DAYS / 3) return@withContext null

        // Count transactions per day
        val dailyCounts = mutableMapOf<Long, Int>()
        for (tx in history) {
            val dayEpoch = (tx.createdAt / 86400) * 86400
            dailyCounts[dayEpoch] = (dailyCounts[dayEpoch] ?: 0) + 1
        }

        if (dailyCounts.size < MIN_SAMPLES) return@withContext null

        val counts = dailyCounts.values.map { it.toDouble() }
        val (mean, stddev) = calculateStats(counts)

        if (stddev <= 0) return@withContext null

        // Today's count
        val todayEpoch = endDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val todayCount = dailyCounts[todayEpoch] ?: 0
        val zScore = (todayCount - mean) / stddev

        if (abs(zScore) > Z_THRESHOLD) {
            val isHigh = zScore > 0
            return@withContext ProactiveAnomaly(
                type = AnomalyType.VOLUME_ANOMALY,
                severity = AnomalySeverity.INFO,
                message = if (isHigh) {
                    "Leo umefanya miamala $todayCount — ni zaidi ya kawaida yako ya ${mean.toInt()}! Biashara iko vizuri!"
                } else {
                    "Leo miamala ni $todayCount tu — kawaida yako ni ${mean.toInt()}. Je, kuna tatizo?"
                },
                zScore = zScore,
                expectedValue = mean,
                actualValue = todayCount.toDouble(),
                data = mapOf(
                    "todayCount" to todayCount.toString(),
                    "averageCount" to mean.toString(),
                    "zScore" to zScore.toString()
                )
            )
        }

        null
    }

    /**
     * Check for missing expected transactions.
     * If the business typically has sales by this hour but none recorded today.
     */
    suspend fun checkMissingTransactions(): ProactiveAnomaly? = withContext(Dispatchers.IO) {
        val now = java.time.LocalTime.now().hour
        if (now < 8) return@withContext null  // Too early to check

        val endDate = LocalDate.now()
        val startEpoch = endDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val todayTxCount = transactionDao.getTransactionCount(startEpoch, endEpoch)
        if (todayTxCount > 0) return@withContext null  // Has transactions

        // Check historical: how often does this business have NO transactions by this hour?
        val historyStart = endDate.minusDays(BASELINE_DAYS)
        val historyStartEpoch = historyStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val history = transactionDao.getTransactionsInRangeSuspend(historyStartEpoch, endEpoch)

        // Count how many historical days had at least one transaction
        val daysWithSales = history.filter { it.type == TransactionType.SALE }
            .map { (it.createdAt / 86400) * 86400 }
            .distinct()
            .size

        val totalDays = endDate.toEpochDay() - historyStart.toEpochDay()
        val salesDayPct = if (totalDays > 0) daysWithSales.toDouble() / totalDays else 0.0

        // If >70% of days typically have sales, flag the missing day
        if (salesDayPct > 0.7) {
            return@withContext ProactiveAnomaly(
                type = AnomalyType.MISSING_TRANSACTIONS,
                severity = AnomalySeverity.INFO,
                message = "Leo bado hujarekodi mauzo yoyote. " +
                        "Kawaida una mauzo siku nyingi. Je, biashara iko sawa?",
                zScore = 0.0,
                expectedValue = salesDayPct * 100,
                actualValue = 0.0,
                data = mapOf(
                    "salesDayPercentage" to (salesDayPct * 100).toString(),
                    "currentHour" to now.toString()
                )
            )
        }

        null
    }

    // ═══════════════ BATCH ANALYSIS ═══════════════

    /**
     * Run a full anomaly scan on recent data.
     * Called by ProactiveEngine during daily checks.
     */
    suspend fun runDailyScan(): List<ProactiveAnomaly> = withContext(Dispatchers.IO) {
        val anomalies = mutableListOf<ProactiveAnomaly>()

        // Check daily volume
        checkDailyVolume()?.let { anomalies.add(it) }

        // Check missing transactions
        checkMissingTransactions()?.let { anomalies.add(it) }

        // Check today's transactions for anomalies
        val endDate = LocalDate.now()
        val startEpoch = endDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val todayTx = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)

        val historyStart = endDate.minusDays(BASELINE_DAYS)
        val historyStartEpoch = historyStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val history = transactionDao.getTransactionsInRangeSuspend(historyStartEpoch, endEpoch)

        for (tx in todayTx) {
            checkAmountAnomalySync(tx, history)?.let { anomalies.add(it) }
            checkPriceAnomalySync(tx, history)?.let { anomalies.add(it) }
        }

        Timber.d("Daily anomaly scan: %d anomalies found", anomalies.size)
        anomalies
    }

    // ═══════════════ SYNCHRONOUS VERSIONS (for real-time use) ═══════════════

    private fun checkAmountAnomalySync(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        val similar = history.filter {
            it.type == transaction.type &&
                    it.item.equals(transaction.item, ignoreCase = true)
        }

        if (similar.size < MIN_SAMPLES) return null

        val amounts = similar.map { it.totalAmount }
        val (mean, stddev) = calculateStats(amounts)

        if (stddev <= 0) return null

        val zScore = (transaction.totalAmount - mean) / stddev

        if (abs(zScore) > Z_THRESHOLD) {
            val direction = if (zScore > 0) "kubwa" else "ndogo"
            val severity = if (abs(zScore) > 3.5) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING

            return ProactiveAnomaly(
                type = AnomalyType.AMOUNT_ANOMALY,
                severity = severity,
                message = "Umeuza ${transaction.item} kwa KSh ${formatKes(transaction.totalAmount)}! " +
                        "Hii ni $direction sana ikilinganishwa na wastani wako wa KSh ${formatKes(mean)}. " +
                        "Hii ni ya kawaida?",
                zScore = zScore,
                expectedValue = mean,
                actualValue = transaction.totalAmount,
                data = mapOf(
                    "item" to transaction.item,
                    "mean" to mean.toString(),
                    "stddev" to stddev.toString(),
                    "zScore" to zScore.toString()
                )
            )
        }

        return null
    }

    private fun checkTimingAnomalySync(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        val salesHistory = history.filter { it.type == TransactionType.SALE }
        if (salesHistory.size < MIN_SAMPLES) return null

        val hour = Instant.ofEpochSecond(transaction.createdAt)
            .atZone(ZoneOffset.UTC)
            .hour

        if (hour in EARLY_HOUR..LATE_HOUR) return null

        val hourCounts = IntArray(24)
        for (tx in salesHistory) {
            val txHour = Instant.ofEpochSecond(tx.createdAt).atZone(ZoneOffset.UTC).hour
            hourCounts[txHour]++
        }

        val totalTx = salesHistory.size
        val thisHourPct = hourCounts[hour].toDouble() / totalTx

        if (thisHourPct < 0.02 && (hour < EARLY_HOUR || hour > LATE_HOUR)) {
            return ProactiveAnomaly(
                type = AnomalyType.TIMING_ANOMALY,
                severity = AnomalySeverity.WARNING,
                message = "Umerodi muamala saa $hour:00 — ni wakati wa kawaida wa biashara yako?",
                zScore = 0.0,
                expectedValue = 1.0 / 24.0 * totalTx,
                actualValue = hourCounts[hour].toDouble(),
                data = mapOf(
                    "hour" to hour.toString(),
                    "hourPercentage" to (thisHourPct * 100).toString()
                )
            )
        }

        return null
    }

    private fun checkPriceAnomalySync(
        transaction: Transaction,
        history: List<Transaction>
    ): ProactiveAnomaly? {
        if (transaction.quantity <= 0) return null

        val sameItem = history.filter {
            it.item.equals(transaction.item, ignoreCase = true) &&
                    it.quantity > 0 &&
                    it.type == transaction.type
        }

        if (sameItem.size < MIN_SAMPLES) return null

        val unitPrices = sameItem.map { it.totalAmount / it.quantity }
        val (mean, stddev) = calculateStats(unitPrices)

        if (stddev <= 0 || mean <= 0) return null

        val currentUnitPrice = transaction.totalAmount / transaction.quantity
        val zScore = (currentUnitPrice - mean) / stddev

        if (abs(zScore) > Z_THRESHOLD) {
            val direction = if (zScore > 0) "imepanda" else "imeshuka"
            return ProactiveAnomaly(
                type = AnomalyType.PRICE_ANOMALY,
                severity = AnomalySeverity.WARNING,
                message = "Bei ya ${transaction.item} $direction! " +
                        "Sasa: KSh ${formatKes(currentUnitPrice)}, " +
                        "Wastani: KSh ${formatKes(mean)}. " +
                        "Je, bei imebadilika sokoni?",
                zScore = zScore,
                expectedValue = mean,
                actualValue = currentUnitPrice,
                data = mapOf(
                    "item" to transaction.item,
                    "currentPrice" to currentUnitPrice.toString(),
                    "averagePrice" to mean.toString(),
                    "zScore" to zScore.toString()
                )
            )
        }

        return null
    }

    // ═══════════════ STATISTICAL HELPERS ═══════════════

    /**
     * Calculate mean and standard deviation of a list of values.
     * Returns (mean, stddev) pair.
     */
    private fun calculateStats(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return Pair(0.0, 0.0)

        val n = values.size
        val mean = values.average()
        // Sample variance (Bessel's correction: n-1 denominator) for unbiased estimate
        val variance = values.map { (it - mean) * (it - mean) }.sum() / (n - 1).coerceAtLeast(1)
        val stddev = sqrt(variance)

        return Pair(mean, stddev)
    }

    private fun formatKes(amount: Double): String {
        val rounded = Math.round(amount)
        return if (rounded >= 1000) {
            String.format("%,d", rounded)
        } else {
            rounded.toString()
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Proactive anomaly detection result.
 */
data class ProactiveAnomaly(
    val type: AnomalyType,
    val severity: AnomalySeverity,
    val message: String,
    val zScore: Double,
    val expectedValue: Double,
    val actualValue: Double,
    val data: Map<String, String> = emptyMap()
)

/**
 * Types of proactive anomalies.
 */
enum class AnomalyType {
    AMOUNT_ANOMALY,          // Transaction amount is statistically unusual
    TIMING_ANOMALY,          // Transaction at unusual time
    PRICE_ANOMALY,           // Unit price deviates from pattern
    VOLUME_ANOMALY,          // Unusual number of transactions today
    MISSING_TRANSACTIONS     // Expected transactions didn't happen
}

/**
 * Anomaly severity levels.
 */
enum class AnomalySeverity {
    INFO,       // FYI, probably fine
    WARNING,    // Check this
    CRITICAL    // Definitely unusual, verify immediately
}

package com.msaidizi.superagent.financial

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fraud Detector — duplicate and anomaly detection for transactions.
 *
 * Identifies suspicious transactions through statistical analysis:
 * - **Duplicate detection:** Same item, amount, and time window
 * - **Amount anomalies:** Z-score based outlier detection
 * - **Pattern breaks:** Unusual transaction patterns
 *
 * ## Detection Methods
 * 1. **Exact duplicates:** Same item + amount + time proximity
 * 2. **Fuzzy duplicates:** Similar item + similar amount + time proximity
 * 3. **Amount anomalies:** Z-score > 2.5 for transaction amounts
 * 4. **Pattern anomalies:** Unusual time, frequency, or category patterns
 *
 * ## Academic Foundations
 * - **STA 342 (Hypothesis Testing):** Z-score outlier detection
 * - **STA 341 (Estimation):** Rolling statistics for baseline
 * - **CS 401 (Machine Learning):** Anomaly detection patterns
 *
 * @author Msaidizi Financial Team
 */
class FraudDetector {

    companion object {
        private const val TAG = "FraudDetector"

        /** Time window for duplicate detection (seconds) */
        private const val DUPLICATE_TIME_WINDOW = 300L // 5 minutes

        /** Similarity threshold for fuzzy duplicates (0-1) */
        private const val FUZZY_DUPLICATE_THRESHOLD = 0.8

        /** Z-score threshold for amount anomalies */
        private const val ANOMALY_Z_THRESHOLD = 2.5

        /** Minimum transactions for statistical analysis */
        private const val MIN_TRANSACTIONS_FOR_ANALYSIS = 10
    }

    // ═══════════════════════════════════════════════════════════════
    // FRAUD DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze transactions for fraud indicators.
     *
     * @param transactions Recent transactions to analyze
     * @param historicalTransactions Historical baseline for comparison
     * @return [FraudReport] with detected anomalies and duplicates
     */
    fun analyze(
        transactions: List<Transaction>,
        historicalTransactions: List<Transaction> = emptyList()
    ): FraudReport {
        if (transactions.isEmpty()) {
            return FraudReport(
                anomalies = emptyList(),
                duplicateSuspects = emptyList(),
                message = "Hakuna miamala ya kuchunguza."
            )
        }

        // Detect duplicates
        val duplicates = detectDuplicates(transactions)

        // Detect amount anomalies
        val allTransactions = historicalTransactions + transactions
        val anomalies = if (allTransactions.size >= MIN_TRANSACTIONS_FOR_ANALYSIS) {
            detectAnomalies(transactions, allTransactions)
        } else {
            emptyList()
        }

        // Generate message
        val message = buildFraudMessage(duplicates, anomalies)

        return FraudReport(
            anomalies = anomalies,
            duplicateSuspects = duplicates,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // DUPLICATE DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect duplicate transactions.
     *
     * Checks for:
     * 1. Exact duplicates: Same item + same amount + time proximity
     * 2. Fuzzy duplicates: Similar item + similar amount + time proximity
     */
    private fun detectDuplicates(
        transactions: List<Transaction>
    ): List<DuplicateSuspect> {
        val suspects = mutableListOf<DuplicateSuspect>()
        val sorted = transactions.sortedByDescending { it.createdAt }

        for (i in sorted.indices) {
            for (j in (i + 1) until sorted.size) {
                val t1 = sorted[i]
                val t2 = sorted[j]

                // Skip if too far apart in time
                val timeDiff = abs(t1.createdAt - t2.createdAt)
                if (timeDiff > DUPLICATE_TIME_WINDOW) continue

                // Check for exact duplicate
                if (isExactDuplicate(t1, t2)) {
                    suspects.add(
                        DuplicateSuspect(
                            transaction1 = t1,
                            transaction2 = t2,
                            similarityScore = 1.0,
                            reason = "Miamala sawa kabisa: ${t1.item}, KSh ${t1.totalAmount}"
                        )
                    )
                    continue
                }

                // Check for fuzzy duplicate
                val similarity = calculateSimilarity(t1, t2)
                if (similarity >= FUZZY_DUPLICATE_THRESHOLD) {
                    suspects.add(
                        DuplicateSuspect(
                            transaction1 = t1,
                            transaction2 = t2,
                            similarityScore = similarity,
                            reason = "Miamala inayofanana: ${t1.item} na ${t2.item}"
                        )
                    )
                }
            }
        }

        return suspects.distinctBy {
            // Deduplicate by transaction pair
            minOf(it.transaction1.id, it.transaction2.id) to
                maxOf(it.transaction1.id, it.transaction2.id)
        }
    }

    /**
     * Check if two transactions are exact duplicates.
     */
    private fun isExactDuplicate(t1: Transaction, t2: Transaction): Boolean {
        return t1.type == t2.type &&
            t1.item.lowercase() == t2.item.lowercase() &&
            t1.totalAmount == t2.totalAmount &&
            abs(t1.createdAt - t2.createdAt) <= DUPLICATE_TIME_WINDOW
    }

    /**
     * Calculate similarity score between two transactions (0.0 - 1.0).
     */
    private fun calculateSimilarity(t1: Transaction, t2: Transaction): Double {
        var score = 0.0
        var factors = 0

        // Type match
        if (t1.type == t2.type) score += 0.3
        factors++

        // Item similarity (simple contains check)
        val item1 = t1.item.lowercase()
        val item2 = t2.item.lowercase()
        if (item1 == item2) {
            score += 0.3
        } else if (item1.contains(item2) || item2.contains(item1)) {
            score += 0.15
        }
        factors++

        // Amount similarity (within 10%)
        val amountDiff = abs(t1.totalAmount - t2.totalAmount)
        val avgAmount = (t1.totalAmount + t2.totalAmount) / 2
        if (avgAmount > 0) {
            val amountSimilarity = 1.0 - (amountDiff / avgAmount).coerceIn(0.0, 1.0)
            score += amountSimilarity * 0.3
        }
        factors++

        // Quantity similarity
        if (t1.quantity == t2.quantity) score += 0.1
        factors++

        return (score / factors) * factors // Normalize to 0-1 range
    }

    // ═══════════════════════════════════════════════════════════════
    // ANOMALY DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect amount anomalies using Z-score analysis.
     *
     * A transaction is anomalous if its amount is more than
     * [ANOMALY_Z_THRESHOLD] standard deviations from the mean.
     */
    private fun detectAnomalies(
        recentTransactions: List<Transaction>,
        allTransactions: List<Transaction>
    ): List<AnomalyDetection> {
        val anomalies = mutableListOf<AnomalyDetection>()

        // Group by transaction type for type-specific baselines
        val byType = allTransactions.groupBy { it.type }

        for (type in TransactionType.entries) {
            val typeTransactions = byType[type] ?: continue
            if (typeTransactions.size < 5) continue

            // Calculate statistics
            val amounts = typeTransactions.map { it.totalAmount }
            val mean = amounts.average()
            val stddev = calculateStdDev(amounts, mean)

            if (stddev == 0.0) continue

            // Check recent transactions against baseline
            val recentOfType = recentTransactions.filter { it.type == type }
            for (txn in recentOfType) {
                val zScore = (txn.totalAmount - mean) / stddev

                if (abs(zScore) >= ANOMALY_Z_THRESHOLD) {
                    val reason = if (zScore > 0) {
                        "Kiasi ni kikubwa sana (z=${String.format("%.1f", zScore)}). " +
                        "Wastani ni KSh ${formatAmount(mean)}, miamala hii ni KSh ${formatAmount(txn.totalAmount)}."
                    } else {
                        "Kiasi ni kidogo sana (z=${String.format("%.1f", zScore)}). " +
                        "Wastani ni KSh ${formatAmount(mean)}, miamala hii ni KSh ${formatAmount(txn.totalAmount)}."
                    }

                    anomalies.add(
                        AnomalyDetection(
                            transaction = txn,
                            reason = reason,
                            zScore = zScore,
                            severity = if (abs(zScore) >= 3.0) RiskSeverity.HIGH else RiskSeverity.MEDIUM
                        )
                    )
                }
            }
        }

        return anomalies
    }

    /**
     * Calculate standard deviation.
     */
    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.size <= 1) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a fraud detection message in Swahili.
     */
    private fun buildFraudMessage(
        duplicates: List<DuplicateSuspect>,
        anomalies: List<AnomalyDetection>
    ): String {
        if (duplicates.isEmpty() && anomalies.isEmpty()) {
            return "✅ Hakuna miamala ya kushangaza iliyogunduliwa."
        }

        return buildString {
            if (duplicates.isNotEmpty()) {
                append("🔍 Miamala inayofanana:\n")
                duplicates.take(3).forEach { dup ->
                    append("• ${dup.reason}\n")
                }
                if (duplicates.size > 3) {
                    append("  ... na ${duplicates.size - 3} zaidi\n")
                }
                append("\n")
            }

            if (anomalies.isNotEmpty()) {
                append("⚠️ Miamala ya kushangaza:\n")
                anomalies.take(3).forEach { anomaly ->
                    val emoji = when (anomaly.severity) {
                        RiskSeverity.HIGH -> "🚨"
                        RiskSeverity.MEDIUM -> "⚠️"
                        else -> "ℹ️"
                    }
                    append("$emoji ${anomaly.transaction.item}: KSh ${formatAmount(anomaly.transaction.totalAmount)}\n")
                    append("   ${anomaly.reason}\n")
                }
            }
        }
    }

    /**
     * Format amount for display.
     */
    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}

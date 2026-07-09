package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType

/**
 * Anomaly detection engine for Msaidizi.
 *
 * Flags suspicious patterns that could indicate:
 * - Data entry errors (wrong amount)
 * - Fraud (someone stealing)
 * - Duplicate entries
 * - M-Pesa SMS parsing errors
 *
 * Philosophy: FLAG, don't block. The worker decides what to do.
 * But NEVER show a suspicious number without warning.
 */
class AnomalyDetector(
    private val validator: FinancialValidator = FinancialValidator
) {

    companion object {
        /** Transaction > this multiplier of average is suspicious */
        private const val AMOUNT_SPIKE_MULTIPLIER = 10.0

        /** Balance change > this % in one day is suspicious */
        private const val BALANCE_CHANGE_THRESHOLD = 0.50

        /** Max transactions to same person per day before flagging */
        private const val MAX_SAME_PERSON_DAILY = 5

        /** Minimum transactions needed to calculate meaningful average */
        private const val MIN_HISTORY_FOR_AVERAGE = 5

        /** Maximum transaction amount for informal workers (KES) */
        private const val ABSOLUTE_MAX_AMOUNT = 1_000_000.0

        /** Minimum time between "same" transactions (seconds) */
        private const val DUPLICATE_TIME_WINDOW = 60 // 1 minute
    }

    // =====================================================================
    // ANOMALY TYPES
    // =====================================================================

    /**
     * Detected anomaly with severity and Swahili message.
     */
    data class Anomaly(
        val type: AnomalyType,
        val severity: AnomalySeverity,
        val message: String,        // Swahili for worker
        val details: String,        // Technical for logging
        val transactionId: Long? = null,
        val suggestedAction: String = ""
    )

    enum class AnomalyType {
        AMOUNT_SPIKE,           // Transaction > 10x average
        BALANCE_CHANGE,         // Balance changed > 50% in one day
        SMS_FORMAT_ERROR,       // M-Pesa SMS doesn't match expected format
        DUPLICATE_TRANSACTION,  // Same person, same amount, same day
        NEGATIVE_BALANCE,       // Balance went negative
        IMPOSSIBLE_DATE,        // Date is in future or before app existed
        ROUND_NUMBER_STREAK,    // Too many round numbers (data entry laziness)
        INCONSISTENT_PRICE,     // Price for same item varies wildly
        MAX_AMOUNT_EXCEEDED,    // Above absolute maximum
        SUSPICIOUS_PATTERN      // General suspicious pattern
    }

    enum class AnomalySeverity {
        INFO,       // FYI, probably fine
        WARNING,    // Check this
        CRITICAL    // Definitely wrong, fix now
    }

    // =====================================================================
    // SINGLE TRANSACTION CHECKS
    // =====================================================================

    /**
     * Run all anomaly checks on a new transaction.
     *
     * @param transaction The new transaction to check
     * @param recentHistory Recent transactions for comparison (last 30 days)
     * @return List of detected anomalies (empty = no issues)
     */
    fun checkTransaction(
        transaction: Transaction,
        recentHistory: List<Transaction>
    ): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        // 1. Amount spike check
        checkAmountSpike(transaction, recentHistory)?.let { anomalies.add(it) }

        // 2. Absolute maximum
        checkAbsoluteMaximum(transaction)?.let { anomalies.add(it) }

        // 3. Duplicate detection
        checkDuplicate(transaction, recentHistory)?.let { anomalies.add(it) }

        // 4. Same person frequency
        checkSamePersonFrequency(transaction, recentHistory)?.let { anomalies.add(it) }

        // 5. Date validity
        checkDateValidity(transaction)?.let { anomalies.add(it) }

        // 6. Price consistency
        checkPriceConsistency(transaction, recentHistory)?.let { anomalies.add(it) }

        // 7. Round number streak
        checkRoundNumberStreak(transaction, recentHistory)?.let { anomalies.add(it) }

        return anomalies
    }

    /**
     * Check if transaction amount is >10x the worker's average.
     */
    private fun checkAmountSpike(
        transaction: Transaction,
        history: List<Transaction>
    ): Anomaly? {
        val similar = history.filter {
            it.type == transaction.type && it.item.equals(transaction.item, ignoreCase = true)
        }

        if (similar.size < MIN_HISTORY_FOR_AVERAGE) return null

        val avgAmount = similar.map { it.totalAmount }.average()
        if (avgAmount <= 0) return null

        val ratio = transaction.totalAmount / avgAmount

        return if (ratio > AMOUNT_SPIKE_MULTIPLIER) {
            Anomaly(
                type = AnomalyType.AMOUNT_SPIKE,
                severity = AnomalySeverity.WARNING,
                message = "Kiasi cha KES ${formatKes(transaction.totalAmount)} ni kikubwa sana " +
                    "ikilinganishwa na wastani wako wa KES ${formatKes(avgAmount)}. " +
                    "Hakikisha ni sahihi.",
                details = "Amount ${transaction.totalAmount} is ${"%.1f".format(ratio)}x " +
                    "average $avgAmount for ${transaction.item}",
                transactionId = transaction.id,
                suggestedAction = "Verify amount with worker"
            )
        } else null
    }

    /**
     * Check if amount exceeds absolute maximum.
     */
    private fun checkAbsoluteMaximum(transaction: Transaction): Anomaly? {
        return if (transaction.totalAmount > ABSOLUTE_MAX_AMOUNT) {
            Anomaly(
                type = AnomalyType.MAX_AMOUNT_EXCEEDED,
                severity = AnomalySeverity.CRITICAL,
                message = "Kiasi cha KES ${formatKes(transaction.totalAmount)} ni kikubwa mno! " +
                    "Kiasi kikubwa cha kawaida ni KES ${formatKes(ABSOLUTE_MAX_AMOUNT)}. " +
                    "Hakikisha umeweka sahihi.",
                details = "Amount ${transaction.totalAmount} exceeds max $ABSOLUTE_MAX_AMOUNT",
                transactionId = transaction.id,
                suggestedAction = "Block and ask user to re-enter"
            )
        } else null
    }

    /**
     * Check for duplicate transactions (same amount, same person, within 1 minute).
     */
    private fun checkDuplicate(
        transaction: Transaction,
        history: List<Transaction>
    ): Anomaly? {
        val potentialDupes = history.filter { existing ->
            existing.id != transaction.id &&
                existing.totalAmount == transaction.totalAmount &&
                existing.item.equals(transaction.item, ignoreCase = true) &&
                Math.abs(existing.createdAt - transaction.createdAt) < DUPLICATE_TIME_WINDOW
        }

        return if (potentialDupes.isNotEmpty()) {
            Anomaly(
                type = AnomalyType.DUPLICATE_TRANSACTION,
                severity = AnomalySeverity.WARNING,
                message = "Muamala huu unaonekana kuwa mara mbili. " +
                    "Kiasi: KES ${formatKes(transaction.totalAmount)}, " +
                    "bidhaa: ${transaction.item}. " +
                    "Je, ni sawa?",
                details = "Potential duplicate of transaction(s): " +
                    "${potentialDupes.map { it.id }}",
                transactionId = transaction.id,
                suggestedAction = "Ask user if intentional"
            )
        } else null
    }

    /**
     * Check if too many transactions to same person in one day.
     */
    private fun checkSamePersonFrequency(
        transaction: Transaction,
        history: List<Transaction>
    ): Anomaly? {
        if (transaction.customer.isBlank()) return null

        val todayStart = (System.currentTimeMillis() / 1000) - (System.currentTimeMillis() / 1000 % 86400)
        val samePersonToday = history.filter {
            it.customer.equals(transaction.customer, ignoreCase = true) &&
                it.createdAt >= todayStart
        }

        return if (samePersonToday.size >= MAX_SAME_PERSON_DAILY) {
            Anomaly(
                type = AnomalyType.DUPLICATE_TRANSACTION,
                severity = AnomalySeverity.INFO,
                message = "Umefanya miamala ${samePersonToday.size + 1} na " +
                    "${transaction.customer} leo. Ni ya kawaida?",
                details = "${samePersonToday.size + 1} transactions with " +
                    "${transaction.customer} today",
                transactionId = transaction.id,
                suggestedAction = "No action needed, just FYI"
            )
        } else null
    }

    /**
     * Check date validity.
     */
    private fun checkDateValidity(transaction: Transaction): Anomaly? {
        val now = System.currentTimeMillis() / 1000

        return when {
            transaction.createdAt > now + 3600 -> Anomaly(
                type = AnomalyType.IMPOSSIBLE_DATE,
                severity = AnomalySeverity.CRITICAL,
                message = "Tarehe ya muamala ni ya baadaye — si sahihi.",
                details = "Transaction date ${transaction.createdAt} is in the future (now=$now)",
                transactionId = transaction.id,
                suggestedAction = "Fix date to now"
            )

            transaction.createdAt < 0 -> Anomaly(
                type = AnomalyType.IMPOSSIBLE_DATE,
                severity = AnomalySeverity.CRITICAL,
                message = "Tarehe ya muamala si sahihi.",
                details = "Negative timestamp: ${transaction.createdAt}",
                transactionId = transaction.id,
                suggestedAction = "Fix date"
            )

            else -> null
        }
    }

    /**
     * Check price consistency for the same item.
     */
    private fun checkPriceConsistency(
        transaction: Transaction,
        history: List<Transaction>
    ): Anomaly? {
        if (transaction.quantity <= 0) return null

        val sameItem = history.filter {
            it.item.equals(transaction.item, ignoreCase = true) &&
                it.quantity > 0 &&
                it.type == transaction.type
        }

        if (sameItem.size < 3) return null

        val unitPrices = sameItem.map { it.totalAmount / it.quantity }
        val avgPrice = unitPrices.average()
        val stdDev = calculateStdDev(unitPrices)

        if (avgPrice <= 0 || stdDev <= 0) return null

        val currentUnitPrice = transaction.totalAmount / transaction.quantity
        val zScore = Math.abs(currentUnitPrice - avgPrice) / stdDev

        return if (zScore > 3.0) {
            Anomaly(
                type = AnomalyType.INCONSISTENT_PRICE,
                severity = AnomalySeverity.WARNING,
                message = "Bei ya ${transaction.item} ni tofauti sana na kawaida. " +
                    "Wastani: KES ${formatKes(avgPrice)}, " +
                    "sasa: KES ${formatKes(currentUnitPrice)}.",
                details = "Unit price z-score: ${"%.2f".format(zScore)} " +
                    "(mean=$avgPrice, std=$stdDev)",
                transactionId = transaction.id,
                suggestedAction = "Verify price with worker"
            )
        } else null
    }

    /**
     * Check for round number streak (suggests lazy data entry).
     */
    private fun checkRoundNumberStreak(
        transaction: Transaction,
        history: List<Transaction>
    ): Anomaly? {
        val todayStart = (System.currentTimeMillis() / 1000) - (System.currentTimeMillis() / 1000 % 86400)
        val todayTx = history.filter { it.createdAt >= todayStart } + transaction

        if (todayTx.size < 5) return null

        val roundCount = todayTx.count { tx ->
            tx.totalAmount % 100 == 0.0 || tx.totalAmount % 50 == 0.0
        }

        val roundPct = roundCount.toDouble() / todayTx.size

        return if (roundPct > 0.8 && todayTx.size >= 5) {
            Anomaly(
                type = AnomalyType.ROUND_NUMBER_STREAK,
                severity = AnomalySeverity.INFO,
                message = "Miamala mingi leo ni nambari za duara. " +
                    "Je, unaweka kiasi halisi?",
                details = "${(roundPct * 100).toInt()}% of today's transactions " +
                    "are round numbers ($roundCount/${todayTx.size})",
                transactionId = transaction.id,
                suggestedAction = "Remind user to enter exact amounts"
            )
        } else null
    }

    // =====================================================================
    // BALANCE CHECKS
    // =====================================================================

    /**
     * Check for suspicious balance changes.
     *
     * @param previousBalance Yesterday's balance
     * @param currentBalance Today's balance
     * @param todayTransactions Today's transactions (to verify the math)
     */
    fun checkBalanceChange(
        previousBalance: Double,
        currentBalance: Double,
        todayTransactions: List<Transaction>
    ): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        // Calculate expected balance
        val expectedChange = todayTransactions.sumOf { tx ->
            when (tx.type) {
                TransactionType.SALE, TransactionType.DEPOSIT, TransactionType.REFUND -> tx.totalAmount
                TransactionType.PURCHASE, TransactionType.EXPENSE,
                TransactionType.WITHDRAWAL, TransactionType.FEE -> -tx.totalAmount
                else -> 0.0
            }
        }
        val expectedBalance = previousBalance + expectedChange

        // 1. Check if balance math doesn't add up
        val balanceDiff = Math.abs(currentBalance - expectedBalance)
        if (balanceDiff > 10.0) {
            anomalies.add(
                Anomaly(
                    type = AnomalyType.BALANCE_CHANGE,
                    severity = AnomalySeverity.WARNING,
                    message = "Salio halijakokotoa vizuri. " +
                        "Inapaswa kuwa KES ${formatKes(expectedBalance)} " +
                        "kulingana na miamala yako, lakini ni KES ${formatKes(currentBalance)}. " +
                        "Tofauti: KES ${formatKes(balanceDiff)}.",
                    details = "Expected balance $expectedBalance, actual $currentBalance, " +
                        "diff $balanceDiff",
                    suggestedAction = "Reconcile with M-Pesa"
                )
            )
        }

        // 2. Check for large % change
        if (previousBalance > 0) {
            val changePct = Math.abs(currentBalance - previousBalance) / previousBalance
            if (changePct > BALANCE_CHANGE_THRESHOLD) {
                anomalies.add(
                    Anomaly(
                        type = AnomalyType.BALANCE_CHANGE,
                        severity = AnomalySeverity.WARNING,
                        message = "Salio limebadilika sana leo " +
                            "(${(changePct * 100).toInt()}%). " +
                            "Kutoka KES ${formatKes(previousBalance)} hadi " +
                            "KES ${formatKes(currentBalance)}.",
                        details = "Balance changed ${(changePct * 100).toInt()}% " +
                            "($previousBalance -> $currentBalance)",
                        suggestedAction = "Review today's transactions"
                    )
                )
            }
        }

        // 3. Negative balance check
        if (currentBalance < 0) {
            anomalies.add(
                Anomaly(
                    type = AnomalyType.NEGATIVE_BALANCE,
                    severity = AnomalySeverity.CRITICAL,
                    message = "Salio ni hasi: -KES ${formatKes(Math.abs(currentBalance))}. " +
                        "Hii inamaanisha unadaiwa pesa.",
                    details = "Negative balance: $currentBalance",
                    suggestedAction = "Review overdraft or recording errors"
                )
            )
        }

        return anomalies
    }

    // =====================================================================
    // M-PESA SMS FORMAT CHECK
    // =====================================================================

    /**
     * Validate M-Pesa SMS format.
     * Returns anomaly if SMS doesn't match expected Safaricom format.
     */
    fun checkMpesaSmsFormat(smsText: String): Anomaly? {
        val normalized = smsText.trim()

        // Must contain KSh or KES
        if (!normalized.contains("KSh", ignoreCase = true) &&
            !normalized.contains("KES", ignoreCase = true)) {
            return Anomaly(
                type = AnomalyType.SMS_FORMAT_ERROR,
                severity = AnomalySeverity.WARNING,
                message = "Ujumbe huu haionekani kuwa wa M-Pesa — hakuna kiasi cha pesa.",
                details = "SMS missing KSh/KES amount indicator",
                suggestedAction = "Verify SMS is from M-Pesa"
            )
        }

        // Must have a 10-char transaction code
        val codePattern = Regex("[A-Z0-9]{10}")
        if (!codePattern.containsMatchIn(normalized)) {
            return Anomaly(
                type = AnomalyType.SMS_FORMAT_ERROR,
                severity = AnomalySeverity.WARNING,
                message = "Ujumbe wa M-Pesa haujulikani — hakuna msimbo wa muamala.",
                details = "SMS missing 10-char transaction code",
                suggestedAction = "Verify SMS is complete"
            )
        }

        // Must have "Confirmed" keyword
        if (!normalized.contains("Confirmed", ignoreCase = true)) {
            return Anomaly(
                type = AnomalyType.SMS_FORMAT_ERROR,
                severity = AnomalySeverity.INFO,
                message = "Ujumbe huu haujaoneshwa kama umethibitishwa.",
                details = "SMS missing 'Confirmed' keyword",
                suggestedAction = "Transaction may be pending"
            )
        }

        return null
    }

    // =====================================================================
    // BATCH ANALYSIS
    // =====================================================================

    /**
     * Analyze a batch of transactions for patterns.
     * Used for daily/weekly review.
     */
    fun analyzeBatch(transactions: List<Transaction>): BatchAnalysis {
        if (transactions.isEmpty()) {
            return BatchAnalysis(
                transactionCount = 0,
                anomalies = emptyList(),
                summary = "Hakuna miamala ya kuchunguza"
            )
        }

        val anomalies = mutableListOf<Anomaly>()

        // Check each transaction against the full history
        for (tx in transactions) {
            anomalies.addAll(checkTransaction(tx, transactions))
        }

        // Check for unusual patterns in the batch
        checkBatchPatterns(transactions)?.let { anomalies.addAll(it) }

        // Sort by severity
        val sorted = anomalies.sortedByDescending {
            when (it.severity) {
                AnomalySeverity.CRITICAL -> 3
                AnomalySeverity.WARNING -> 2
                AnomalySeverity.INFO -> 1
            }
        }

        val criticalCount = sorted.count { it.severity == AnomalySeverity.CRITICAL }
        val warningCount = sorted.count { it.severity == AnomalySeverity.WARNING }

        val summary = when {
            criticalCount > 0 -> "🚨 $criticalCount hitilafu muhimu na $warningCount maonyo"
            warningCount > 0 -> "⚠️ $warningCount maonyo ya kuchunguza"
            else -> "✓ Miamala yote inaonekana sawa"
        }

        return BatchAnalysis(
            transactionCount = transactions.size,
            anomalies = sorted,
            summary = summary
        )
    }

    private fun checkBatchPatterns(transactions: List<Transaction>): List<Anomaly>? {
        val anomalies = mutableListOf<Anomaly>()

        // Check if all transactions are from same hour (bulk entry)
        val hourGroups = transactions.groupBy {
            (it.createdAt / 3600) % 24
        }
        if (hourGroups.size == 1 && transactions.size > 10) {
            anomalies.add(
                Anomaly(
                    type = AnomalyType.SUSPICIOUS_PATTERN,
                    severity = AnomalySeverity.INFO,
                    message = "Miamala ${transactions.size} yote imerekodiwa saa moja. " +
                        "Je, ni za siku nzima?",
                    details = "All ${transactions.size} transactions in same hour block",
                    suggestedAction = "Verify timestamps are correct"
                )
            )
        }

        return anomalies.ifEmpty { null }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return Math.sqrt(variance)
    }

    private fun formatKes(amount: Double): String {
        val rounded = Math.round(amount)
        return if (rounded >= 1000) {
            String.format("%,d", rounded)
        } else {
            rounded.toString()
        }
    }

    // =====================================================================
    // RESULT TYPES
    // =====================================================================

    data class BatchAnalysis(
        val transactionCount: Int,
        val anomalies: List<Anomaly>,
        val summary: String
    ) {
        val hasCritical: Boolean get() = anomalies.any { it.severity == AnomalySeverity.CRITICAL }
        val hasWarnings: Boolean get() = anomalies.any { it.severity == AnomalySeverity.WARNING }
        val criticalCount: Int get() = anomalies.count { it.severity == AnomalySeverity.CRITICAL }
        val warningCount: Int get() = anomalies.count { it.severity == AnomalySeverity.WARNING }
    }
}

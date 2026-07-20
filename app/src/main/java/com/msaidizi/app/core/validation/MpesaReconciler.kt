package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M-Pesa Reconciliation Engine for Msaidizi.
 *
 * Reconciles M-Pesa SMS records against Msaidizi's internal transaction records.
 * Catches discrepancies before the worker sees wrong numbers.
 *
 * M-Pesa SMS format (Safaricom):
 *   QH34AB5CD6 Confirmed. KSh 500.00 received from JOHN DOE 0712345678
 *   on 9/7/26 at 2:30 PM. New M-PESA balance is KSh 12,500.00.
 *
 * Reconciliation checks:
 * 1. SMS amount vs recorded amount
 * 2. Daily totals (in + out)
 * 3. Balance consistency
 * 4. Missing transactions
 */
class MpesaReconciler(
    private val validator: FinancialValidator = FinancialValidator
) {

    companion object {
        /** Discrepancy threshold in KES — above this, alert the worker */
        private const val ALERT_THRESHOLD_KES = 10.0

        /** Maximum acceptable discrepancy before flagging as critical */
        private const val CRITICAL_THRESHOLD_KES = 1000.0

        private val dateFormatter = DateTimeFormatter.ofPattern("d/M/yy")
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }

    // =====================================================================
    // DATA TYPES
    // =====================================================================

    /**
     * A parsed M-Pesa SMS transaction.
     */
    data class MpesaSmsTransaction(
        val code: String,           // QH34AB5CD6
        val type: MpesaSmsType,     // SENT, RECEIVED, WITHDRAW, PAYMENT, etc.
        val amount: Double,         // KES amount
        val counterparty: String,   // Phone number or name
        val counterpartyName: String = "",
        val timestamp: Long,        // Unix seconds
        val newBalance: Double,     // M-Pesa balance after transaction
        val rawSms: String          // Original SMS text
    )

    enum class MpesaSmsType {
        SENT, RECEIVED, WITHDRAW, PAYMENT, PAY_BILL, BUY_GOODS,
        FULIZA, KCB_MPESA, FEE, REVERSAL, UNKNOWN
    }

    /**
     * Reconciliation result for a single transaction.
     */
    data class ReconciliationResult(
        val status: ReconciliationStatus,
        val mpesaAmount: Double,
        val msaidiziAmount: Double,
        val discrepancy: Double,
        val message: String,        // Swahili message for worker
        val details: String = ""    // Technical details for logging
    )

    enum class ReconciliationStatus {
        MATCHED,            // Amounts match
        MINOR_DIFF,         // Small rounding difference (< KES 1)
        MISMATCH,           // Amounts don't match
        MISSING_IN_APP,     // M-Pesa has it, Msaidizi doesn't
        MISSING_IN_MPESA,   // Msaidizi has it, M-Pesa doesn't
        BALANCE_ERROR       // Balance doesn't add up
    }

    /**
     * Daily reconciliation summary.
     */
    data class DailyReconciliation(
        val date: LocalDate,
        val totalIn: ReconciliationPair,
        val totalOut: ReconciliationPair,
        val balanceCheck: ReconciliationPair,
        val transactionResults: List<ReconciliationResult>,
        val overallStatus: ReconciliationStatus,
        val message: String,        // Swahili summary for worker
        val alertRequired: Boolean
    )

    data class ReconciliationPair(
        val mpesaValue: Double,
        val msaidiziValue: Double,
        val difference: Double,
        val formatted: String       // Human-readable Swahili
    )

    // =====================================================================
    // SINGLE TRANSACTION RECONCILIATION
    // =====================================================================

    /**
     * Reconcile a single M-Pesa SMS against Msaidizi records.
     */
    fun reconcileTransaction(
        sms: MpesaSmsTransaction,
        matchingTransaction: Transaction?
    ): ReconciliationResult {
        // Validate SMS amount
        val smsAmountResult = validator.validateAmount(sms.amount, "kiasi cha M-Pesa")
        val smsAmount = smsAmountResult.getOrDefault()

        if (matchingTransaction == null) {
            return ReconciliationResult(
                status = ReconciliationStatus.MISSING_IN_APP,
                mpesaAmount = smsAmount,
                msaidiziAmount = 0.0,
                discrepancy = smsAmount,
                message = "M-Pesa inaonyesha KES ${formatKes(smsAmount)} " +
                    "(${sms.type}) lakini haiko kwenye Msaidizi",
                details = "SMS code: ${sms.code}, no matching transaction found"
            )
        }

        val appAmount = matchingTransaction.totalAmount
        val discrepancy = Math.abs(smsAmount - appAmount)

        return when {
            discrepancy == 0.0 -> ReconciliationResult(
                status = ReconciliationStatus.MATCHED,
                mpesaAmount = smsAmount,
                msaidiziAmount = appAmount,
                discrepancy = 0.0,
                message = "Sawa ✓",
                details = "Exact match for ${sms.code}"
            )

            discrepancy < 1.0 -> ReconciliationResult(
                status = ReconciliationStatus.MINOR_DIFF,
                mpesaAmount = smsAmount,
                msaidiziAmount = appAmount,
                discrepancy = discrepancy,
                message = "Tofauti ndogo: KES ${formatKes(discrepancy)}",
                details = "Rounding difference for ${sms.code}"
            )

            discrepancy <= ALERT_THRESHOLD_KES -> ReconciliationResult(
                status = ReconciliationStatus.MISMATCH,
                mpesaAmount = smsAmount,
                msaidiziAmount = appAmount,
                discrepancy = discrepancy,
                message = "M-Pesa inasema KES ${formatKes(smsAmount)}, " +
                    "Msaidizi inasema KES ${formatKes(appAmount)}, " +
                    "tofauti: KES ${formatKes(discrepancy)}",
                details = "Mismatch within alert threshold for ${sms.code}"
            )

            else -> ReconciliationResult(
                status = ReconciliationStatus.MISMATCH,
                mpesaAmount = smsAmount,
                msaidiziAmount = appAmount,
                discrepancy = discrepancy,
                message = "⚠️ Tofauti kubwa! M-Pesa: KES ${formatKes(smsAmount)}, " +
                    "Msaidizi: KES ${formatKes(appAmount)}, " +
                    "tofauti: KES ${formatKes(discrepancy)}",
                details = "CRITICAL mismatch for ${sms.code}: ${discrepancy} KES difference"
            )
        }
    }

    // =====================================================================
    // DAILY RECONCILIATION
    // =====================================================================

    /**
     * Perform full daily reconciliation.
     *
     * @param smsTransactions M-Pesa SMS transactions for the day
     * @param appTransactions Msaidizi transactions for the day
     * @param mpesaBalance Reported M-Pesa balance at end of day
     * @param appBalance Msaidizi's calculated balance
     */
    fun reconcileDaily(
        smsTransactions: List<MpesaSmsTransaction>,
        appTransactions: List<Transaction>,
        mpesaBalance: Double,
        appBalance: Double
    ): DailyReconciliation {
        val today = LocalDate.now(ZoneId.of("Africa/Nairobi"))

        // 1. Match transactions and reconcile each
        val matchedSms = mutableSetOf<String>() // SMS codes already matched
        val txResults = mutableListOf<ReconciliationResult>()

        for (sms in smsTransactions) {
            val match = findBestMatch(sms, appTransactions, matchedSms)
            val result = reconcileTransaction(sms, match)
            txResults.add(result)

            if (match != null) {
                matchedSms.add(sms.code)
            }
        }

        // Find transactions in app but not in M-Pesa SMS
        for (appTx in appTransactions) {
            val hasMatch = smsTransactions.any { sms ->
                matchedSms.contains(sms.code) &&
                    Math.abs(sms.amount - appTx.totalAmount) < 1.0
            }
            if (!hasMatch && appTx.paymentMethod == "mpesa") {
                txResults.add(
                    ReconciliationResult(
                        status = ReconciliationStatus.MISSING_IN_MPESA,
                        mpesaAmount = 0.0,
                        msaidiziAmount = appTx.totalAmount,
                        discrepancy = appTx.totalAmount,
                        message = "Kiasi cha KES ${formatKes(appTx.totalAmount)} " +
                            "kiko kwenye Msaidizi lakini hakina SMS ya M-Pesa",
                        details = "App tx id=${appTx.id} has no matching SMS"
                    )
                )
            }
        }

        // 2. Daily totals
        val mpesaTotalIn = smsTransactions
            .filter { it.type == MpesaSmsType.RECEIVED }
            .sumOf { it.amount }
        val mpesaTotalOut = smsTransactions
            .filter { it.type in setOf(
                MpesaSmsType.SENT, MpesaSmsType.PAYMENT,
                MpesaSmsType.PAY_BILL, MpesaSmsType.BUY_GOODS,
                MpesaSmsType.WITHDRAW
            ) }
            .sumOf { it.amount }

        val appTotalIn = appTransactions
            .filter { it.type == TransactionType.SALE || it.type == TransactionType.DEPOSIT }
            .sumOf { it.totalAmount }
        val appTotalOut = appTransactions
            .filter { it.type in setOf(
                TransactionType.PURCHASE, TransactionType.EXPENSE,
                TransactionType.WITHDRAWAL, TransactionType.FEE
            ) }
            .sumOf { it.totalAmount }

        val totalInDiff = mpesaTotalIn - appTotalIn
        val totalOutDiff = mpesaTotalOut - appTotalOut

        // 3. Balance check
        val balanceDiff = mpesaBalance - appBalance

        // 4. Build summary
        val totalInPair = ReconciliationPair(
            mpesaValue = mpesaTotalIn,
            msaidiziValue = appTotalIn,
            difference = totalInDiff,
            formatted = "Kuingia: M-Pesa KES ${formatKes(mpesaTotalIn)}, " +
                "Msaidizi KES ${formatKes(appTotalIn)}, " +
                "tofauti KES ${formatKes(Math.abs(totalInDiff))}"
        )

        val totalOutPair = ReconciliationPair(
            mpesaValue = mpesaTotalOut,
            msaidiziValue = appTotalOut,
            difference = totalOutDiff,
            formatted = "Kutoka: M-Pesa KES ${formatKes(mpesaTotalOut)}, " +
                "Msaidizi KES ${formatKes(appTotalOut)}, " +
                "tofauti KES ${formatKes(Math.abs(totalOutDiff))}"
        )

        val balancePair = ReconciliationPair(
            mpesaValue = mpesaBalance,
            msaidiziValue = appBalance,
            difference = balanceDiff,
            formatted = "Salio: M-Pesa KES ${formatKes(mpesaBalance)}, " +
                "Msaidizi KES ${formatKes(appBalance)}, " +
                "tofauti KES ${formatKes(Math.abs(balanceDiff))}"
        )

        // Overall status
        val maxDiscrepancy = maxOf(
            Math.abs(totalInDiff),
            Math.abs(totalOutDiff),
            Math.abs(balanceDiff)
        )

        val overallStatus = when {
            maxDiscrepancy == 0.0 -> ReconciliationStatus.MATCHED
            maxDiscrepancy < 1.0 -> ReconciliationStatus.MINOR_DIFF
            maxDiscrepancy <= ALERT_THRESHOLD_KES -> ReconciliationStatus.MISMATCH
            else -> ReconciliationStatus.BALANCE_ERROR
        }

        val alertRequired = maxDiscrepancy > ALERT_THRESHOLD_KES

        // Swahili summary
        val summaryMessage = when (overallStatus) {
            ReconciliationStatus.MATCHED ->
                "M-Pesa na Msaidizi zinasema kitu kimoja. Salio ni sawa ✓"

            ReconciliationStatus.MINOR_DIFF ->
                "Tofauti ndogo ya KES ${formatKes(maxDiscrepancy)}. " +
                "Si tatizo kubwa."

            ReconciliationStatus.MISMATCH ->
                "⚠️ Tofauti ya KES ${formatKes(maxDiscrepancy)}. " +
                "${totalInPair.formatted}. " +
                "${totalOutPair.formatted}. " +
                "${balancePair.formatted}"

            ReconciliationStatus.BALANCE_ERROR ->
                "🚨 Tofauti kubwa ya KES ${formatKes(maxDiscrepancy)}! " +
                "M-Pesa inasema salio ni KES ${formatKes(mpesaBalance)}, " +
                "Msaidizi inasema KES ${formatKes(appBalance)}. " +
                "Angalia miamala yako leo."

            else -> "Angalia miamala yako"
        }

        return DailyReconciliation(
            date = today,
            totalIn = totalInPair,
            totalOut = totalOutPair,
            balanceCheck = balancePair,
            transactionResults = txResults,
            overallStatus = overallStatus,
            message = summaryMessage,
            alertRequired = alertRequired
        )
    }

    // =====================================================================
    // MATCHING LOGIC
    // =====================================================================

    /**
     * Find the best matching app transaction for an M-Pesa SMS.
     * Uses fuzzy matching on amount, time, and counterparty.
     */
    private fun findBestMatch(
        sms: MpesaSmsTransaction,
        appTransactions: List<Transaction>,
        alreadyMatched: Set<String>
    ): Transaction? {
        var bestMatch: Transaction? = null
        var bestScore = 0.0

        for (tx in appTransactions) {
            // Skip already matched
            if (tx.id.toString() in alreadyMatched) continue

            // Only match M-Pesa transactions
            if (tx.paymentMethod != "mpesa") continue

            var score = 0.0

            // Amount match (exact = 1.0, within 1% = 0.8, within 10% = 0.5)
            val amountDiff = Math.abs(tx.totalAmount - sms.amount)
            score += when {
                amountDiff == 0.0 -> 1.0
                amountDiff / sms.amount < 0.01 -> 0.8
                amountDiff / sms.amount < 0.10 -> 0.5
                else -> 0.0
            }

            // Time match (within 5 min = 1.0, within 30 min = 0.7, within 2h = 0.3)
            val timeDiff = Math.abs(tx.createdAt - sms.timestamp)
            score += when {
                timeDiff < 300 -> 1.0      // 5 min
                timeDiff < 1800 -> 0.7     // 30 min
                timeDiff < 7200 -> 0.3     // 2 hours
                else -> 0.0
            }

            // Type match
            val typeMatch = when (sms.type) {
                MpesaSmsType.RECEIVED -> tx.type == TransactionType.SALE ||
                    tx.type == TransactionType.DEPOSIT
                MpesaSmsType.SENT -> tx.type == TransactionType.PURCHASE ||
                    tx.type == TransactionType.EXPENSE
                MpesaSmsType.PAYMENT, MpesaSmsType.PAY_BILL,
                MpesaSmsType.BUY_GOODS -> tx.type == TransactionType.EXPENSE
                MpesaSmsType.WITHDRAW -> tx.type == TransactionType.WITHDRAWAL
                else -> true
            }
            if (typeMatch) score += 0.5

            // Counterparty match (if available)
            if (sms.counterparty.isNotBlank() && tx.customer.isNotBlank()) {
                if (tx.customer.contains(sms.counterparty, ignoreCase = true) ||
                    sms.counterparty.contains(tx.customer, ignoreCase = true)) {
                    score += 0.5
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = tx
            }
        }

        // Require minimum score to consider it a match
        return if (bestScore >= 1.5) bestMatch else null
    }

    // =====================================================================
    // SMS PARSING
    // =====================================================================

    /**
     * Parse an M-Pesa SMS into structured data.
     * Handles the most common Safaricom M-Pesa SMS formats.
     */
    fun parseMpesaSms(sms: String): MpesaSmsTransaction? {
        val normalized = sms.replace("\n", " ").trim()

        // Extract code (first 10 alphanumeric chars)
        val codeMatch = Regex("^([A-Z0-9]{10})").find(normalized)
        val code = codeMatch?.groupValues?.getOrNull(1) ?: ""

        // Extract amount
        val amountMatch = Regex("KSh\\s*([\\d,]+\\.?\\d*)").find(normalized)
        val amountStr = amountMatch?.groupValues?.getOrNull(1)
            ?.replace(",", "") ?: return null
        val amount = amountStr.toDoubleOrNull() ?: return null

        // Determine type
        val type = when {
            normalized.contains("received from", ignoreCase = true) -> MpesaSmsType.RECEIVED
            normalized.contains("sent to", ignoreCase = true) -> MpesaSmsType.SENT
            normalized.contains("withdrawn", ignoreCase = true) -> MpesaSmsType.WITHDRAW
            normalized.contains("paid to", ignoreCase = true) -> MpesaSmsType.PAYMENT
            normalized.contains("Pay Bill", ignoreCase = true) -> MpesaSmsType.PAY_BILL
            normalized.contains("Buy Goods", ignoreCase = true) -> MpesaSmsType.BUY_GOODS
            normalized.contains("Fuliza", ignoreCase = true) -> MpesaSmsType.FULIZA
            normalized.contains("reversed", ignoreCase = true) -> MpesaSmsType.REVERSAL
            else -> MpesaSmsType.UNKNOWN
        }

        // Extract counterparty phone/name
        val phoneMatch = Regex("(?:from|to)\\s+([A-Z\\s]+?)\\s+(\\d{10})").find(normalized)
        val counterpartyName = phoneMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
        val counterparty = phoneMatch?.groupValues?.getOrNull(2) ?: ""

        // Extract balance
        val balanceMatch = Regex("balance is KSh\\s*([\\d,]+\\.?\\d*)").find(normalized)
        val balanceStr = balanceMatch?.groupValues?.getOrNull(1)
            ?.replace(",", "") ?: "0"
        val newBalance = balanceStr.toDoubleOrNull() ?: 0.0

        // Extract timestamp (approximate from SMS date/time)
        // Format: "on 9/7/26 at 2:30 PM"
        val dateMatch = Regex("on (\\d{1,2}/\\d{1,2}/\\d{2,4}) at (\\d{1,2}:\\d{2} [AP]M)")
            .find(normalized)
        val timestamp = if (dateMatch != null) {
            parseSmsTimestamp(
                dateMatch.groupValues[1],
                dateMatch.groupValues[2]
            ) ?: System.currentTimeMillis() / 1000
        } else {
            System.currentTimeMillis() / 1000
        }

        return MpesaSmsTransaction(
            code = code,
            type = type,
            amount = amount,
            counterparty = counterparty,
            counterpartyName = counterpartyName,
            timestamp = timestamp,
            newBalance = newBalance,
            rawSms = sms
        )
    }

    private fun parseSmsTimestamp(date: String, time: String): Long? {
        return try {
            // Safaricom uses d/M/yy or d/M/yyyy
            val parts = date.split("/")
            if (parts.size != 3) return null

            val day = parts[0].toInt()
            val month = parts[1].toInt()
            var year = parts[2].toInt()
            if (year < 100) year += 2000

            // Parse time: "2:30 PM"
            val timeParts = time.replace(" AM", "").replace(" PM", "").split(":")
            var hour = timeParts[0].toInt()
            val minute = timeParts[1].toInt()

            if (time.contains("PM") && hour != 12) hour += 12
            if (time.contains("AM") && hour == 12) hour = 0

            LocalDate.of(year, month, day)
                .atTime(hour, minute)
                .atZone(ZoneId.of("Africa/Nairobi"))
                .toEpochSecond()
        } catch (e: Throwable) {
            null
        }
    }

    // =====================================================================
    // FORMATTING HELPERS
    // =====================================================================

    private fun formatKes(amount: Double): String {
        val rounded = Math.round(amount)
        return if (rounded >= 1000) {
            String.format("%,d", rounded)
        } else {
            rounded.toString()
        }
    }
}

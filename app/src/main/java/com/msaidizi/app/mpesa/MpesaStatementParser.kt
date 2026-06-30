package com.msaidizi.app.mpesa

import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses M-Pesa statement exports (CSV format).
 *
 * M-Pesa provides statement downloads via:
 * 1. Safaricom App → M-Pesa → Statement → Download
 * 2. USSD *234# → M-Pesa Statement → Email
 * 3. Safaricom Portal → M-Pesa → Statements
 *
 * CSV columns (standard Safaricom format):
 * "Receipt No.","Completion Time","Details","Transaction Status",
 * "Paid In","Withdrawn","Balance","Account Number"
 *
 * Example rows:
 * "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment to 254712345678",COMPLETED,"100.00","","1,500.00",""
 * "QHK71G4YS1","2026-06-30 13:00:00","Pay Bill to KPLC via Paybill 222222",COMPLETED,"","500.00","1,000.00","ACC001"
 *
 * Features:
 * - Handles quoted fields with commas inside
 * - Skips non-completed transactions
 * - Classifies transaction types from details text
 * - Converts to Msaidizi Transaction objects
 * - Handles various date formats
 * - Validates amounts and balances
 */
class MpesaStatementParser {

    companion object {
        /** Supported date formats in M-Pesa statements */
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US)
        )

        private const val EXPECTED_MIN_COLUMNS = 6
        private const val EXPECTED_COLUMNS = 8

        /** Minimum reasonable amount (filter out noise) */
        private const val MIN_AMOUNT = 0.01

        /** Maximum reasonable single M-Pesa transaction */
        private const val MAX_AMOUNT = 500_000.0
    }

    // ────────────────────── Public API ──────────────────────

    /**
     * Parse an M-Pesa CSV statement into transaction records.
     *
     * @param inputStream The CSV file input stream
     * @return List of parsed transactions (only COMPLETED ones)
     */
    fun parseCsv(inputStream: InputStream): List<ParsedMpesaTransaction> {
        val transactions = mutableListOf<ParsedMpesaTransaction>()
        val reader = BufferedReader(InputStreamReader(inputStream))

        // Read and validate header
        val header = reader.readLine()
        if (header == null || !header.contains("Receipt", ignoreCase = true)) {
            Timber.w("Invalid M-Pesa CSV: missing or invalid header")
            return emptyList()
        }

        var lineNum = 1
        var skippedCount = 0

        reader.forEachLine { line ->
            lineNum++
            try {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine

                val parsed = parseLine(trimmed)
                if (parsed != null) {
                    transactions.add(parsed)
                } else {
                    skippedCount++
                }
            } catch (e: Exception) {
                Timber.w("Failed to parse M-Pesa line %d: %s (%s)", lineNum, e.message, line.take(80))
                skippedCount++
            }
        }

        Timber.d(
            "Parsed %d M-Pesa transactions (%d skipped) from %d lines",
            transactions.size, skippedCount, lineNum
        )
        return transactions
    }

    /**
     * Parse M-Pesa CSV from a string.
     */
    fun parseCsvString(csvContent: String): List<ParsedMpesaTransaction> {
        return csvContent.byteInputStream().use { parseCsv(it) }
    }

    /**
     * Parse and return summary statistics.
     */
    fun parseWithSummary(inputStream: InputStream): ParseResult {
        val transactions = parseCsv(inputStream)

        val totalCredit = transactions.filter { it.isCredit }.sumOf { it.amount }
        val totalDebit = transactions.filter { !it.isCredit }.sumOf { it.amount }
        val dateRange = if (transactions.isNotEmpty()) {
            val sorted = transactions.sortedBy { it.timestamp }
            DateRange(sorted.first().timestamp, sorted.last().timestamp)
        } else null

        return ParseResult(
            transactions = transactions,
            totalCount = transactions.size,
            totalCredit = totalCredit,
            totalDebit = totalDebit,
            netAmount = totalCredit - totalDebit,
            dateRange = dateRange
        )
    }

    // ────────────────────── Line Parsing ──────────────────────

    /**
     * Parse a single CSV line into a transaction.
     * Returns null if the line should be skipped (non-completed, invalid, etc.)
     */
    private fun parseLine(line: String): ParsedMpesaTransaction? {
        val fields = parseCsvLine(line)
        if (fields.size < EXPECTED_MIN_COLUMNS) {
            Timber.d("Skipping line with %d fields (expected %d+)", fields.size, EXPECTED_MIN_COLUMNS)
            return null
        }

        val receipt = fields[0].trim()
        val completionTime = fields[1].trim()
        val details = fields[2].trim()
        val status = fields[3].trim()

        // Only process COMPLETED transactions
        if (!status.equals("COMPLETED", ignoreCase = true)) {
            return null
        }

        // Parse amounts (handle comma-separated thousands: "1,500.00")
        val paidIn = fields.getOrNull(4)?.trim()
            ?.replace(",", "")
            ?.replace("\"", "")
            ?.toDoubleOrNull()
        val withdrawn = fields.getOrNull(5)?.trim()
            ?.replace(",", "")
            ?.replace("\"", "")
            ?.toDoubleOrNull()
        val balance = fields.getOrNull(6)?.trim()
            ?.replace(",", "")
            ?.replace("\"", "")
            ?.toDoubleOrNull()
        val accountNumber = fields.getOrNull(7)?.trim()?.replace("\"", "")

        // Must have either paid-in or withdrawn amount
        val amount = paidIn ?: withdrawn ?: return null

        // Validate amount
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            Timber.d("Skipping transaction with invalid amount: %.2f", amount)
            return null
        }

        // Parse timestamp
        val timestamp = parseTimestamp(completionTime) ?: return null

        // Determine transaction type
        val isCredit = paidIn != null && paidIn > 0
        val type = classifyTransaction(details, isCredit)

        return ParsedMpesaTransaction(
            receipt = receipt.replace("\"", ""),
            timestamp = timestamp,
            details = details,
            type = type,
            amount = amount,
            balance = balance ?: 0.0,
            isCredit = isCredit,
            accountNumber = accountNumber?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Parse CSV line handling quoted fields with commas inside.
     *
     * Examples:
     * - "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment",COMPLETED,"100.00","","1,500.00",""
     * - Handles: "Details with, comma inside"
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        fields.add(current.toString())

        return fields
    }

    /**
     * Parse timestamp from various M-Pesa date formats.
     * @return Unix timestamp in seconds, or null if unparseable
     */
    private fun parseTimestamp(dateStr: String): Long? {
        val cleaned = dateStr.replace("\"", "").trim()

        for (format in DATE_FORMATS) {
            try {
                val date = format.parse(cleaned)
                if (date != null) {
                    return date.time / 1000  // Convert ms to seconds
                }
            } catch (_: Exception) {
                // Try next format
            }
        }

        Timber.w("Failed to parse M-Pesa date: %s", cleaned)
        return null
    }

    /**
     * Classify transaction type from M-Pesa details text.
     *
     * Categories:
     * - SALE: Customer payments, received money
     * - PURCHASE: Buy goods, pay bill (business expenses)
     * - EXPENSE: Sent money, transfers
     * - WITHDRAWAL: Cash withdrawal
     * - DEPOSIT: Cash deposit
     * - OTHER: Unrecognized
     */
    private fun classifyTransaction(details: String, isCredit: Boolean): TransactionType {
        val lower = details.lowercase()

        return when {
            // Credits (money in)
            lower.contains("customer payment") -> TransactionType.SALE
            lower.contains("received from") -> TransactionType.SALE
            lower.contains("business payment from") -> TransactionType.SALE
            lower.contains("reversal") -> TransactionType.REFUND

            // Debits (money out)
            lower.contains("pay bill") || lower.contains("paybill") -> TransactionType.EXPENSE
            lower.contains("buy goods") -> TransactionType.PURCHASE
            lower.contains("sent to") || lower.contains("transfer to") -> TransactionType.EXPENSE
            lower.contains("withdraw") -> TransactionType.WITHDRAWAL
            lower.contains("atm") -> TransactionType.WITHDRAWAL
            lower.contains("deposit") -> TransactionType.DEPOSIT
            lower.contains("charge") || lower.contains("fee") -> TransactionType.FEE

            // Fallback based on direction
            isCredit -> TransactionType.SALE
            else -> TransactionType.EXPENSE
        }
    }

    // ────────────────────── Conversion ──────────────────────

    /**
     * Convert parsed M-Pesa transactions to Msaidizi Transaction objects.
     * Maps M-Pesa fields to the internal Transaction model.
     */
    fun toTransactions(parsed: List<ParsedMpesaTransaction>): List<Map<String, Any>> {
        return parsed.map { p ->
            mapOf(
                "type" to p.type.name,
                "item" to inferItemName(p.details, p.type),
                "totalAmount" to p.amount,
                "paymentMethod" to "mpesa",
                "mpesaReceipt" to p.receipt,
                "notes" to p.details,
                "createdAt" to p.timestamp,
                "isCredit" to p.isCredit,
                "accountNumber" to (p.accountNumber ?: ""),
                "balance" to p.balance
            )
        }
    }

    /**
     * Infer a human-readable item name from M-Pesa details.
     */
    private fun inferItemName(details: String, type: TransactionType): String {
        val lower = details.lowercase()

        return when (type) {
            TransactionType.SALE -> {
                // Try to extract counterparty
                val nameMatch = Regex("(?:from|by)\\s+(.+?)(?:\\s+on\\s|\\s*$)", RegexOption.IGNORE_CASE)
                    .find(details)
                nameMatch?.groupValues?.get(1)?.trim() ?: "mpesa_received"
            }
            TransactionType.PURCHASE -> {
                // Try to extract what was bought
                val itemMatch = Regex("buy goods.+?to\\s+(.+?)(?:\\s+via|\\s*$)", RegexOption.IGNORE_CASE)
                    .find(details)
                itemMatch?.groupValues?.get(1)?.trim() ?: "mpesa_purchase"
            }
            TransactionType.EXPENSE -> {
                val nameMatch = Regex("(?:to|pay)\\s+(.+?)(?:\\s+via|\\s*$)", RegexOption.IGNORE_CASE)
                    .find(details)
                nameMatch?.groupValues?.get(1)?.trim() ?: "mpesa_sent"
            }
            TransactionType.WITHDRAWAL -> "mpesa_withdrawal"
            TransactionType.DEPOSIT -> "mpesa_deposit"
            TransactionType.FEE -> "mpesa_fee"
            TransactionType.REFUND -> "mpesa_refund"
            TransactionType.OTHER -> "mpesa_transaction"
        }
    }
}

// ────────────────────── Data Classes ──────────────────────

/**
 * Transaction type classification.
 */
enum class TransactionType {
    SALE,        // Money received from customers
    PURCHASE,    // Buy goods, pay suppliers
    EXPENSE,     // Sent money, general expenses
    WITHDRAWAL,  // Cash withdrawal
    DEPOSIT,     // Cash deposit
    FEE,         // Transaction fees, charges
    REFUND,      // Reversals, refunds
    OTHER        // Unclassified
}

/**
 * A parsed M-Pesa transaction from CSV.
 */
data class ParsedMpesaTransaction(
    val receipt: String,
    val timestamp: Long,
    val details: String,
    val type: TransactionType,
    val amount: Double,
    val balance: Double,
    val isCredit: Boolean,
    val accountNumber: String? = null
)

/**
 * Summary result of parsing an M-Pesa statement.
 */
data class ParseResult(
    val transactions: List<ParsedMpesaTransaction>,
    val totalCount: Int,
    val totalCredit: Double,
    val totalDebit: Double,
    val netAmount: Double,
    val dateRange: DateRange?
)

/**
 * Date range for a set of transactions.
 */
data class DateRange(
    val startTimestamp: Long,
    val endTimestamp: Long
) {
    val durationDays: Long
        get() = (endTimestamp - startTimestamp) / 86400
}

/**
 * Parser for M-Pesa SMS notifications.
 *
 * SMS formats:
 * "QHK71G4YS0 Confirmed. Ksh100.00 received from JOHN DOE 254712345678 on 30/6/26 at 12:00 PM. New M-PESA balance is Ksh1,500.00."
 * "QHK71G4YS0 Confirmed. Ksh200.00 sent to JANE DOE 254798765432 on 30/6/26 at 1:00 PM for account ACC001. New M-PESA balance is Ksh1,300.00."
 * "QHK71G4YS0 Confirmed. Ksh50.00 paid to KPLC. Account: ACC001. New M-PESA balance is Ksh950.00."
 */
class MpesaSmsParser {

    companion object {
        /** Pattern: "RECEIPT Confirmed. KshAMOUNT received from NAME PHONE on DATE" */
        private val RECEIVE_PATTERN = Regex(
            """(\w+)\s+Confirmed\.\s+Ksh([\d,]+\.?\d*)\s+received\s+from\s+(.+?)\s+(\d{10,12})\s+on\s+(.+?)\.""",
            RegexOption.IGNORE_CASE
        )

        /** Pattern: "RECEIPT Confirmed. KshAMOUNT sent to NAME PHONE on DATE" */
        private val SEND_PATTERN = Regex(
            """(\w+)\s+Confirmed\.\s+Ksh([\d,]+\.?\d*)\s+sent\s+to\s+(.+?)\s+(\d{10,12})\s+on\s+(.+?)\.""",
            RegexOption.IGNORE_CASE
        )

        /** Pattern: "RECEIPT Confirmed. KshAMOUNT paid to PAYEE" */
        private val PAYBILL_PATTERN = Regex(
            """(\w+)\s+Confirmed\.\s+Ksh([\d,]+\.?\d*)\s+paid\s+to\s+(.+?)\.""",
            RegexOption.IGNORE_CASE
        )

        /** Pattern: "RECEIPT Confirmed. KshAMOUNT withdrawn from" */
        private val WITHDRAW_PATTERN = Regex(
            """(\w+)\s+Confirmed\.\s+Ksh([\d,]+\.?\d*)\s+withdrawn""",
            RegexOption.IGNORE_CASE
        )

        /** Balance extraction */
        private val BALANCE_PATTERN = Regex(
            """balance\s+is\s+Ksh([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Parse an M-Pesa SMS notification.
     *
     * @param smsText The full SMS text
     * @return Parsed transaction or null if not an M-Pesa SMS
     */
    fun parse(smsText: String): MpesaSmsTransaction? {
        // Try receive pattern
        RECEIVE_PATTERN.find(smsText)?.let { match ->
            return MpesaSmsTransaction(
                receipt = match.groupValues[1],
                amount = parseAmount(match.groupValues[2]),
                counterparty = match.groupValues[3].trim(),
                phone = match.groupValues[4],
                isCredit = true,
                transactionType = TransactionType.SALE,
                timestamp = parseSmsDate(match.groupValues[5]),
                balance = extractBalance(smsText)
            )
        }

        // Try send pattern
        SEND_PATTERN.find(smsText)?.let { match ->
            return MpesaSmsTransaction(
                receipt = match.groupValues[1],
                amount = parseAmount(match.groupValues[2]),
                counterparty = match.groupValues[3].trim(),
                phone = match.groupValues[4],
                isCredit = false,
                transactionType = TransactionType.EXPENSE,
                timestamp = parseSmsDate(match.groupValues[5]),
                balance = extractBalance(smsText)
            )
        }

        // Try pay bill pattern
        PAYBILL_PATTERN.find(smsText)?.let { match ->
            return MpesaSmsTransaction(
                receipt = match.groupValues[1],
                amount = parseAmount(match.groupValues[2]),
                counterparty = match.groupValues[3].trim(),
                phone = null,
                isCredit = false,
                transactionType = TransactionType.EXPENSE,
                timestamp = null,
                balance = extractBalance(smsText)
            )
        }

        // Try withdrawal pattern
        WITHDRAW_PATTERN.find(smsText)?.let { match ->
            return MpesaSmsTransaction(
                receipt = match.groupValues[1],
                amount = parseAmount(match.groupValues[2]),
                counterparty = "ATM Withdrawal",
                phone = null,
                isCredit = false,
                transactionType = TransactionType.WITHDRAWAL,
                timestamp = null,
                balance = extractBalance(smsText)
            )
        }

        return null
    }

    /**
     * Check if text looks like an M-Pesa SMS.
     */
    fun isMpesaSms(text: String): Boolean {
        return text.contains("Confirmed", ignoreCase = true) &&
            (text.contains("Ksh", ignoreCase = true) || text.contains("M-PESA", ignoreCase = true))
    }

    private fun parseAmount(amountStr: String): Double {
        return amountStr.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    private fun extractBalance(text: String): Double? {
        return BALANCE_PATTERN.find(text)?.groupValues?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
    }

    /**
     * Parse SMS date format: "30/6/26 at 12:00 PM"
     * Returns Unix timestamp or null.
     */
    private fun parseSmsDate(dateStr: String): Long? {
        return try {
            // Try "d/M/yy at h:mm a" format
            val format = SimpleDateFormat("d/M/yy 'at' h:mm a", Locale.US)
            format.parse(dateStr.trim())?.time?.div(1000)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Transaction parsed from M-Pesa SMS.
 */
data class MpesaSmsTransaction(
    val receipt: String,
    val amount: Double,
    val counterparty: String,
    val phone: String?,
    val isCredit: Boolean,
    val transactionType: TransactionType,
    val timestamp: Long?,
    val balance: Double?
)

package com.msaidizi.agent.mpesa

import timber.log.Timber
import java.util.regex.Pattern

/**
 * MpesaSmsParser — Parse M-Pesa confirmation SMS messages.
 *
 * Handles ALL M-Pesa SMS formats:
 * 1. Received money (C2B / STK Push)
 * 2. Sent money (person to person)
 * 3. Paid (buy goods / paybill)
 * 4. Withdrawal (agent)
 * 5. Deposit
 * 6. Airtime purchase
 * 7. Fuliza (overdraft)
 * 8. Reversed transaction
 *
 * This is the #1 requested feature across ALL worker segments.
 * Workers receive M-Pesa SMS but manually re-enter into the app.
 * Auto-parsing eliminates double entry.
 *
 * Example SMS formats:
 *   "QHK71K4RT6 Confirmed. Ksh500.00 received from JOHN DOE 254712345678 on 25/12/23 at 2:30 PM. New M-PESA balance is Ksh12,500.00."
 *   "QHK71K4RT6 Confirmed. Ksh1,000.00 sent to JANE SMITH 254798765432 on 25/12/23 at 3:00 PM. New M-PESA balance is Ksh11,500.00."
 *   "QHK71K4RT6 Confirmed. Ksh200.00 paid to SHOP NAME 123456 on 25/12/23 at 4:00 PM."
 *
 * Design:
 * - Pure regex parsing — no LLM needed, instant, offline
 * - Handles comma-separated amounts (1,000.00)
 * - Extracts: type, amount, counterparty, phone, receipt, date, balance
 * - Confidence score for each parse (0.0-1.0)
 * - Supports both English and Swahili SMS formats
 */
object MpesaSmsParser {

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    /**
     * Parse an M-Pesa SMS into a structured MpesaTransaction.
     *
     * @param smsBody The full SMS text
     * @return MpesaTransaction if parsed successfully, null if not an M-Pesa SMS
     */
    fun parse(smsBody: String): MpesaTransaction? {
        if (!isMpesaSms(smsBody)) return null

        return try {
            val type = detectTransactionType(smsBody)
            val receipt = extractReceipt(smsBody)
            val amount = extractAmount(smsBody)
            val counterparty = extractCounterparty(smsBody, type)
            val phone = extractPhone(smsBody)
            val date = extractDate(smsBody)
            val balance = extractBalance(smsBody)
            val confidence = calculateConfidence(type, amount, receipt, counterparty)

            MpesaTransaction(
                receipt = receipt,
                type = type,
                amount = amount,
                counterparty = counterparty,
                phone = phone,
                date = date,
                balance = balance,
                rawSms = smsBody,
                confidence = confidence,
                category = autoCategory(type, counterparty)
            )
        } catch (e: Exception) {
            Timber.w(e, "MpesaSmsParser: Failed to parse SMS")
            null
        }
    }

    /**
     * Check if an SMS is from M-Pesa.
     */
    fun isMpesaSms(smsBody: String): Boolean {
        val lower = smsBody.lowercase()
        return (lower.contains("confirmed") || lower.contains("imekubaliwa") ||
                lower.contains("mpesa") || lower.contains("m-pesa")) &&
               (lower.contains("ksh") || lower.contains("kes"))
    }

    /**
     * Batch parse multiple SMS messages.
     * Returns only successfully parsed transactions.
     */
    fun parseAll(smsMessages: List<String>): List<MpesaTransaction> {
        return smsMessages.mapNotNull { parse(it) }
    }

    // ═══════════════════════════════════════════════════════════
    //  TRANSACTION TYPE DETECTION
    // ═══════════════════════════════════════════════════════════

    private fun detectTransactionType(sms: String): MpesaTransactionType {
        val lower = sms.lowercase()
        return when {
            lower.contains("received from") || lower.contains("kutoka kwa") ->
                MpesaTransactionType.RECEIVED
            lower.contains("sent to") || lower.contains("imetumwa kwa") ->
                MpesaTransactionType.SENT
            lower.contains("paid to") || lower.contains("imelipiwa") ->
                MpesaTransactionType.PAID_GOODS
            lower.contains("pay bill") || lower.contains("paybill") || lower.contains("lipa bill") ->
                MpesaTransactionType.PAYBILL
            lower.contains("withdraw") || lower.contains("kutoa") ->
                MpesaTransactionType.WITHDRAWAL
            lower.contains("deposit") || lower.contains("kuweka") || lower.contains("deposited") ->
                MpesaTransactionType.DEPOSIT
            lower.contains("airtime") || lower.contains("hewa") ->
                MpesaTransactionType.AIRTIME
            lower.contains("fuliza") || lower.contains("overdraft") ->
                MpesaTransactionType.FULIZA
            lower.contains("reversed") || lower.contains("reversal") || lower.contains("kurejeshwa") ->
                MpesaTransactionType.REVERSED
            lower.contains("received") -> MpesaTransactionType.RECEIVED
            lower.contains("sent") -> MpesaTransactionType.SENT
            lower.contains("paid") -> MpesaTransactionType.PAID_GOODS
            else -> MpesaTransactionType.UNKNOWN
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  FIELD EXTRACTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Extract M-Pesa receipt number (e.g., "QHK71K4RT6").
     * Always at the start of the SMS after optional whitespace.
     */
    private fun extractReceipt(sms: String): String {
        val pattern = Pattern.compile("([A-Z0-9]{10,12})\\s+Confirmed", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(sms)
        return if (matcher.find()) matcher.group(1) else ""
    }

    /**
     * Extract transaction amount.
     * Handles: "Ksh500.00", "Ksh1,000.00", "KES 500", "Ksh 500"
     */
    private fun extractAmount(sms: String): Double {
        // Match Ksh/KES followed by amount with optional commas
        val pattern = Pattern.compile(
            "(?:ksh|kes)\\s*([\\d,]+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(sms)
        return if (matcher.find()) {
            matcher.group(1)
                .replace(",", "")
                .toDoubleOrNull() ?: 0.0
        } else 0.0
    }

    /**
     * Extract counterparty name.
     * For received: "from JOHN DOE"
     * For sent: "to JANE SMITH"
     * For paid: "to SHOP NAME"
     */
    private fun extractCounterparty(sms: String, type: MpesaTransactionType): String {
        val pattern = when (type) {
            MpesaTransactionType.RECEIVED ->
                Pattern.compile("(?:from|kutoka kwa)\\s+(.+?)\\s+\\d{10,}", Pattern.CASE_INSENSITIVE)
            MpesaTransactionType.SENT ->
                Pattern.compile("(?:to|kwa)\\s+(.+?)\\s+\\d{10,}", Pattern.CASE_INSENSITIVE)
            MpesaTransactionType.PAID_GOODS, MpesaTransactionType.PAYBILL ->
                Pattern.compile("(?:to|kwa)\\s+(.+?)\\s+\\d{5,}", Pattern.CASE_INSENSITIVE)
            else -> Pattern.compile("(?:from|to|kutoka|kwa)\\s+(.+?)(?:\\s+on|\\s+\\d{1,2}/)", Pattern.CASE_INSENSITIVE)
        }

        val matcher = pattern.matcher(sms)
        return if (matcher.find()) {
            matcher.group(1).trim()
        } else ""
    }

    /**
     * Extract phone number from SMS.
     * Formats: 254712345678, 0712345678, +254712345678
     */
    private fun extractPhone(sms: String): String {
        val pattern = Pattern.compile("(?:\\+?254|0)[17]\\d{8}")
        val matcher = pattern.matcher(sms)
        return if (matcher.find()) matcher.group() else ""
    }

    /**
     * Extract transaction date/time.
     * Format: "on 25/12/23 at 2:30 PM"
     */
    private fun extractDate(sms: String): String {
        val pattern = Pattern.compile(
            "on\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(sms)
        return if (matcher.find()) {
            "${matcher.group(1)} ${matcher.group(2)}"
        } else ""
    }

    /**
     * Extract M-PESA balance after transaction.
     * Format: "New M-PESA balance is Ksh12,500.00"
     */
    private fun extractBalance(sms: String): Double? {
        val pattern = Pattern.compile(
            "(?:balance|salio)\\s+(?:is\\s+)?(?:ksh|kes)\\s*([\\d,]+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(sms)
        return if (matcher.find()) {
            matcher.group(1).replace(",", "").toDoubleOrNull()
        } else null
    }

    // ═══════════════════════════════════════════════════════════
    //  AUTO-CATEGORIZATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Auto-categorize the transaction for Msaidizi's business tracking.
     *
     * Mapping:
     *   RECEIVED → sale (most common: customer paying for goods/services)
     *   SENT → expense (paying supplier, transport, etc.)
     *   PAID_GOODS → purchase (buying stock)
     *   PAYBILL → expense (utilities, rent, etc.)
     *   WITHDRAWAL → cash_withdrawal
     *   DEPOSIT → cash_deposit
     *   AIRTIME → expense:airtime
     *   FULIZA → loan
     *   REVERSED → reversal
     */
    fun autoCategory(type: MpesaTransactionType, counterparty: String): TransactionCategory {
        return when (type) {
            MpesaTransactionType.RECEIVED -> TransactionCategory.SALE
            MpesaTransactionType.SENT -> TransactionCategory.EXPENSE
            MpesaTransactionType.PAID_GOODS -> TransactionCategory.PURCHASE
            MpesaTransactionType.PAYBILL -> TransactionCategory.EXPENSE
            MpesaTransactionType.WITHDRAWAL -> TransactionCategory.CASH_WITHDRAWAL
            MpesaTransactionType.DEPOSIT -> TransactionCategory.CASH_DEPOSIT
            MpesaTransactionType.AIRTIME -> TransactionCategory.EXPENSE_AIRTIME
            MpesaTransactionType.FULIZA -> TransactionCategory.LOAN
            MpesaTransactionType.REVERSED -> TransactionCategory.REVERSAL
            MpesaTransactionType.UNKNOWN -> TransactionCategory.UNKNOWN
        }
    }

    /**
     * Suggest a more specific category based on counterparty name patterns.
     */
    fun suggestDetailedCategory(counterparty: String, type: MpesaTransactionType): String? {
        val lower = counterparty.lowercase()
        return when {
            // Common supplier patterns
            lower.contains("wholesale") || lower.contains("supplier") -> "stock_purchase"
            lower.contains("farm") || lower.contains("mkulima") -> "farm_purchase"
            // Transport
            lower.contains("matatu") || lower.contains("boda") || lower.contains("uber") -> "transport"
            // Utilities
            lower.contains("kenya power") || lower.contains("kplc") -> "electricity"
            lower.contains("nairobi water") || lower.contains("water") -> "water"
            lower.contains("safaricom") || lower.contains("airtime") -> "airtime"
            // Rent
            lower.contains("rent") || lower.contains("kodi") -> "rent"
            // Food
            lower.contains("hotel") || lower.contains("restaurant") || lower.contains("food") -> "food"
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONFIDENCE SCORING
    // ═══════════════════════════════════════════════════════════

    private fun calculateConfidence(
        type: MpesaTransactionType,
        amount: Double,
        receipt: String,
        counterparty: String
    ): Float {
        var score = 0.0f

        // Type detected
        if (type != MpesaTransactionType.UNKNOWN) score += 0.3f

        // Amount extracted
        if (amount > 0) score += 0.3f

        // Receipt number present
        if (receipt.isNotEmpty()) score += 0.2f

        // Counterparty name extracted
        if (counterparty.isNotEmpty()) score += 0.2f

        return score.coerceIn(0.0f, 1.0f)
    }
}

// ═══════════════════════════════════════════════════════════
//  DATA TYPES
// ═══════════════════════════════════════════════════════════

/**
 * Parsed M-Pesa transaction.
 */
data class MpesaTransaction(
    val receipt: String,
    val type: MpesaTransactionType,
    val amount: Double,
    val counterparty: String,
    val phone: String,
    val date: String,
    val balance: Double?,
    val rawSms: String,
    val confidence: Float,
    val category: TransactionCategory,
    val detailedCategory: String? = null,
    val isMatched: Boolean = false,
    val matchedRecordId: Long? = null
)

enum class MpesaTransactionType {
    RECEIVED,        // Money received (sale)
    SENT,            // Money sent (expense)
    PAID_GOODS,      // Buy goods (purchase)
    PAYBILL,         // Pay bill (expense)
    WITHDRAWAL,      // Cash withdrawal
    DEPOSIT,         // Cash deposit
    AIRTIME,         // Airtime purchase
    FULIZA,          // Fuliza overdraft
    REVERSED,        // Transaction reversed
    UNKNOWN
}

enum class TransactionCategory {
    SALE,               // Customer payment received
    EXPENSE,            // Money sent / paid out
    PURCHASE,           // Stock/goods purchased
    CASH_WITHDRAWAL,    // Agent withdrawal
    CASH_DEPOSIT,       // Agent deposit
    EXPENSE_AIRTIME,    // Airtime purchase
    LOAN,               // Fuliza / overdraft
    REVERSAL,           // Transaction reversed
    UNKNOWN
}

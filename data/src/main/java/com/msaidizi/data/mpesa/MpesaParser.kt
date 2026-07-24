package com.msaidizi.data.mpesa

import com.msaidizi.core.common.model.TransactionType

/**
 * M-Pesa SMS parser for automatic transaction detection.
 *
 * Parses M-Pesa confirmation SMS messages to extract:
 * - Transaction type (send, receive, pay, withdraw)
 * - Amount
 * - Transaction code
 * - Recipient/sender name
 * - Date/time
 *
 * ## SMS Formats (Kenya M-Pesa)
 * ```
 * Confirmed. Ksh500.00 sent to JOHN DOE 0722000000 on 24/7/26 at 2:30 PM.
 * New M-Pesa balance is Ksh1,234.56. Transaction cost Ksh0.00.
 * Transaction code: QHK71H3F4P.
 * ```
 *
 * ```
 * You have received Ksh1,000.00 from JANE SMITH 0733000000 on 24/7/26.
 * New M-Pesa balance is Ksh2,234.56. Transaction code: RJK82G4E5Q.
 * ```
 */
class MpesaParser {

    /**
     * Parse an M-Pesa SMS message.
     *
     * @param smsBody The SMS text
     * @return Parsed M-Pesa transaction, or null if not an M-Pesa SMS
     */
    fun parse(smsBody: String): MpesaTransaction? {
        val body = smsBody.trim()

        // Check if this is an M-Pesa SMS
        if (!isMpesaSms(body)) return null

        return try {
            val type = detectType(body)
            val amount = extractAmount(body)
            val code = extractCode(body)
            val name = extractName(body, type)
            val phone = extractPhone(body)
            val balance = extractBalance(body)
            val cost = extractCost(body)

            MpesaTransaction(
                type = type,
                amount = amount,
                transactionCode = code,
                counterpartyName = name,
                counterpartyPhone = phone,
                balance = balance,
                transactionCost = cost,
                rawSms = body,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if SMS is from M-Pesa.
     */
    private fun isMpesaSms(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("mpesa") || lower.contains("m-pesa") ||
                (lower.contains("confirmed") && lower.contains("ksh")) ||
                (lower.contains("received") && lower.contains("transaction code"))
    }

    /**
     * Detect transaction type from SMS content.
     */
    private fun detectType(body: String): MpesaTransactionType {
        val lower = body.lowercase()
        return when {
            lower.contains("sent to") -> MpesaTransactionType.SEND
            lower.contains("received from") || lower.contains("you have received") -> MpesaTransactionType.RECEIVE
            lower.contains("paid to") || lower.contains("pay bill") -> MpesaTransactionType.PAY
            lower.contains("withdrawn") -> MpesaTransactionType.WITHDRAW
            lower.contains("deposited") || lower.contains("deposit") -> MpesaTransactionType.DEPOSIT
            lower.contains("reversed") -> MpesaTransactionType.REVERSAL
            lower.contains("funds received") -> MpesaTransactionType.RECEIVE
            else -> MpesaTransactionType.OTHER
        }
    }

    /**
     * Extract amount from SMS.
     * Handles: "Ksh500.00", "Ksh1,234.56", "KES 500"
     */
    private fun extractAmount(body: String): Double {
        val patterns = listOf(
            Regex("""Ksh[\s]*([0-9,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""KES[\s]*([0-9,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9,]+\.?\d*)\s*(?:sent|received|paid|withdrawn)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(body)
            if (match != null) {
                val amountStr = match.groupValues[1].replace(",", "")
                return amountStr.toDoubleOrNull() ?: 0.0
            }
        }
        return 0.0
    }

    /**
     * Extract transaction code.
     * Format: 10 alphanumeric characters (e.g., "QHK71H3F4P")
     */
    private fun extractCode(body: String): String {
        val pattern = Regex("""transaction\s*code[:\s]*([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
        return pattern.find(body)?.groupValues?.get(1) ?: ""
    }

    /**
     * Extract counterparty name.
     */
    private fun extractName(body: String, type: MpesaTransactionType): String {
        val pattern = when (type) {
            MpesaTransactionType.SEND -> Regex("""sent to\s+([A-Z\s]+?)(?:\s+\d{10}|\s+on\s)""", RegexOption.IGNORE_CASE)
            MpesaTransactionType.RECEIVE -> Regex("""from\s+([A-Z\s]+?)(?:\s+\d{10}|\s+on\s)""", RegexOption.IGNORE_CASE)
            MpesaTransactionType.PAY -> Regex("""paid to\s+([A-Z\s]+?)(?:\s+\d{10}|\s+on\s)""", RegexOption.IGNORE_CASE)
            else -> Regex("""(?:to|from|paid to)\s+([A-Z\s]+?)(?:\s+\d{10}|\s+on\s)""", RegexOption.IGNORE_CASE)
        }
        return pattern.find(body)?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * Extract phone number.
     */
    private fun extractPhone(body: String): String {
        val pattern = Regex("""(\d{10})""")
        return pattern.find(body)?.groupValues?.get(1) ?: ""
    }

    /**
     * Extract new balance.
     */
    private fun extractBalance(body: String): Double {
        val pattern = Regex("""(?:new\s+)?(?:m-pesa\s+)?balance\s+(?:is\s+)?(?:Ksh|KES)\s*([0-9,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(body) ?: return 0.0
        return match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /**
     * Extract transaction cost.
     */
    private fun extractCost(body: String): Double {
        val pattern = Regex("""(?:transaction\s+)?cost\s+(?:is\s+)?(?:Ksh|KES)\s*([0-9,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(body) ?: return 0.0
        return match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /**
     * Convert M-Pesa transaction type to Msaidizi TransactionType.
     */
    fun toTransactionType(mpesaType: MpesaTransactionType): TransactionType {
        return when (mpesaType) {
            MpesaTransactionType.RECEIVE -> TransactionType.SALE
            MpesaTransactionType.SEND -> TransactionType.EXPENSE
            MpesaTransactionType.PAY -> TransactionType.EXPENSE
            MpesaTransactionType.WITHDRAW -> TransactionType.EXPENSE
            MpesaTransactionType.DEPOSIT -> TransactionType.PURCHASE
            MpesaTransactionType.REVERSAL -> TransactionType.EXPENSE
            MpesaTransactionType.OTHER -> TransactionType.EXPENSE
        }
    }
}

/**
 * Parsed M-Pesa transaction.
 */
data class MpesaTransaction(
    val type: MpesaTransactionType,
    val amount: Double,
    val transactionCode: String,
    val counterpartyName: String,
    val counterpartyPhone: String,
    val balance: Double,
    val transactionCost: Double,
    val rawSms: String,
    val timestamp: Long
)

/**
 * M-Pesa transaction types.
 */
enum class MpesaTransactionType {
    SEND,
    RECEIVE,
    PAY,
    WITHDRAW,
    DEPOSIT,
    REVERSAL,
    OTHER
}

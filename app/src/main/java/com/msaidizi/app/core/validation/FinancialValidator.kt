package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.AfricanCurrency
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Central financial validation for Msaidizi.
 *
 * Every number that reaches a user's screen passes through here.
 * A mama mboga seeing KES -500 or KES 92,000,000,000,000,000 would panic.
 * This class prevents that.
 *
 * Design principles:
 * - Fail SILENTLY to safe defaults (never show raw errors to user)
 * - Return ValidationResult with Swahili error messages
 * - All amounts in KES (main units, not subunits)
 * - Timestamps in Unix seconds (matching Transaction model)
 */
object FinancialValidator {

    // === THRESHOLDS ===

    /** Maximum single transaction amount for informal workers (KES 1,000,000) */
    private const val MAX_TRANSACTION_AMOUNT = 1_000_000.0

    /** Maximum M-Pesa transaction (KES 999,999 per transaction) */
    private const val MAX_MPESA_AMOUNT = 999_999.0

    /** Maximum daily balance change before flagging (50%) */
    private const val MAX_DAILY_CHANGE_PCT = 0.50

    /** Minimum plausible transaction amount (KES 1) */
    private const val MIN_TRANSACTION_AMOUNT = 1.0

    /** Maximum number of digits for M-Pesa amounts */
    private const val MPESA_MAX_DIGITS = 7

    /** Earliest plausible date for Msaidizi (app launch) */
    private val APP_LAUNCH_DATE: LocalDate = LocalDate.of(2024, 1, 1)

    // === REGEX ===

    /** M-Pesa amount: digits only, 1-7 digits, no leading zeros except "0" itself */
    private val MPESA_AMOUNT_REGEX = Regex("^(0|[1-9]\\d{0,6})$")

    /** M-Pesa phone number: 07XXXXXXXX or +2547XXXXXXXX */
    private val MPESA_PHONE_REGEX = Regex("^(?:254|\\+254|0)?(7\\d{8})$")

    /** M-Pesa transaction code: 10 alphanumeric characters (e.g., QH34AB5CD6) */
    private val MPESA_TX_CODE_REGEX = Regex("^[A-Z0-9]{10}$")

    // =====================================================================
    // AMOUNT VALIDATION
    // =====================================================================

    /**
     * Validate a transaction amount before display.
     *
     * @return ValidationResult with safe value or Swahili error message
     */
    fun validateAmount(amount: Double, context: String = "kiasi"): ValidationResult<Double> {
        // NaN or Infinity — floating point corruption
        if (amount.isNaN() || amount.isInfinite()) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Kiasi si sahihi (hitilafu ya nambari)"
            )
        }

        // Negative amounts
        if (amount < 0) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Kiasi haliwezi kuwa hasi"
            )
        }

        // Zero — valid for balance display but not for transactions
        if (amount == 0.0) {
            return ValidationResult.Valid(0.0)
        }

        // Below minimum
        if (amount < MIN_TRANSACTION_AMOUNT) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Kiasi ni kidogo sana"
            )
        }

        // Above maximum — likely data corruption
        if (amount > MAX_TRANSACTION_AMOUNT) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Kiasi ni kubwa sana — hakikisha tena"
            )
        }

        // Floating point cleanup: round to 2 decimal places
        val cleaned = Math.round(amount * 100.0) / 100.0
        return ValidationResult.Valid(cleaned)
    }

    /**
     * Validate a balance amount.
     * Balance CAN be negative if overdraft is tracked, but we flag it.
     */
    fun validateBalance(
        balance: Double,
        allowOverdraft: Boolean = false
    ): ValidationResult<Double> {
        if (balance.isNaN() || balance.isInfinite()) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Salio si sahihi"
            )
        }

        if (balance < 0 && !allowOverdraft) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "Salio haliwezi kuwa hasi — angalia tena"
            )
        }

        // Suspiciously large balance for informal worker
        if (balance > 10_000_000.0) {
            return ValidationResult.Warning(
                value = balance,
                warning = "Salio ni kubwa sana — hakikisha ni sahihi"
            )
        }

        val cleaned = Math.round(balance * 100.0) / 100.0
        return ValidationResult.Valid(cleaned)
    }

    // =====================================================================
    // PERCENTAGE VALIDATION
    // =====================================================================

    /**
     * Validate a percentage value (0-100 range).
     */
    fun validatePercentage(pct: Double, name: String = "asilimia"): ValidationResult<Double> {
        if (pct.isNaN() || pct.isInfinite()) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "$name si sahihi"
            )
        }

        if (pct < 0 || pct > 100) {
            return ValidationResult.Invalid(
                value = pct.coerceIn(0.0, 100.0),
                error = "$name lazima iwe kati ya 0 na 100"
            )
        }

        return ValidationResult.Valid(Math.round(pct * 10.0) / 10.0) // 1 decimal
    }

    /**
     * Validate a ratio/proportion (0.0-1.0 range).
     */
    fun validateRatio(ratio: Double, name: String = "uwiano"): ValidationResult<Double> {
        if (ratio.isNaN() || ratio.isInfinite()) {
            return ValidationResult.Invalid(
                value = 0.0,
                error = "$name si sahihi"
            )
        }

        if (ratio < 0.0 || ratio > 1.0) {
            return ValidationResult.Invalid(
                value = ratio.coerceIn(0.0, 1.0),
                error = "$name lazima iwe kati ya 0 na 1"
            )
        }

        return ValidationResult.Valid(ratio)
    }

    // =====================================================================
    // M-PESA VALIDATION
    // =====================================================================

    /**
     * Validate an M-Pesa amount string.
     * M-Pesa uses integer amounts only, max 7 digits.
     */
    fun validateMpesaAmount(amountStr: String): ValidationResult<Long> {
        val trimmed = amountStr.trim()

        if (!MPESA_AMOUNT_REGEX.matches(trimmed)) {
            return ValidationResult.Invalid(
                value = 0,
                error = "Kiasi cha M-Pesa si sahihi — tumia nambari tu"
            )
        }

        val amount = trimmed.toLong()

        if (amount == 0L) {
            return ValidationResult.Valid(0L)
        }

        if (amount > MAX_MPESA_AMOUNT) {
            return ValidationResult.Invalid(
                value = 0,
                error = "Kiasi cha M-Pesa ni kikubwa sana (max KES 999,999)"
            )
        }

        return ValidationResult.Valid(amount)
    }

    /**
     * Validate an M-Pesa phone number.
     * Returns normalized format: 2547XXXXXXXX
     */
    fun validateMpesaPhone(phone: String): ValidationResult<String> {
        val cleaned = phone.replace("\\s".toRegex(), "").replace("-", "")

        val match = MPESA_PHONE_REGEX.find(cleaned)
            ?: return ValidationResult.Invalid(
                value = "",
                error = "Nambari ya simu si sahihi — mfano: 0712345678"
            )

        val normalized = "254${match.groupValues[1]}"
        return ValidationResult.Valid(normalized)
    }

    /**
     * Validate an M-Pesa transaction code.
     */
    fun validateMpesaCode(code: String): ValidationResult<String> {
        val cleaned = code.trim().uppercase()

        if (!MPESA_TX_CODE_REGEX.matches(cleaned)) {
            return ValidationResult.Invalid(
                value = "",
                error = "Msimbo wa M-Pesa si sahihi — lazima ziwe herufi na nambari 10"
            )
        }

        return ValidationResult.Valid(cleaned)
    }

    // =====================================================================
    // DATE VALIDATION
    // =====================================================================

    /**
     * Validate a transaction date.
     * - Cannot be in the future
     * - Cannot be before the app existed
     * - Cannot be more than 7 days old (likely data entry error)
     */
    fun validateTransactionDate(
        timestampSeconds: Long,
        workerStartDate: Long? = null
    ): ValidationResult<Long> {
        if (timestampSeconds <= 0) {
            return ValidationResult.Invalid(
                value = System.currentTimeMillis() / 1000,
                error = "Tarehe si sahihi"
            )
        }

        val now = System.currentTimeMillis() / 1000

        // Future date
        if (timestampSeconds > now + 3600) { // 1 hour tolerance for clock drift
            return ValidationResult.Invalid(
                value = now,
                error = "Tarehe haiwezi kuwa ya baadaye"
            )
        }

        // Before app launch
        val appLaunchSeconds = APP_LAUNCH_DATE
            .atStartOfDay(ZoneId.of("Africa/Nairobi"))
            .toEpochSecond()
        if (timestampSeconds < appLaunchSeconds) {
            return ValidationResult.Invalid(
                value = appLaunchSeconds,
                error = "Tarehe ni ya zamani sana"
            )
        }

        // Before worker started (if known)
        if (workerStartDate != null && timestampSeconds < workerStartDate) {
            return ValidationResult.Invalid(
                value = workerStartDate,
                error = "Tarehe ni kabla ya kuanza kwa biashara yako"
            )
        }

        return ValidationResult.Valid(timestampSeconds)
    }

    // =====================================================================
    // PRICE STABILITY CHECK
    // =====================================================================

    /**
     * Check if a price change is suspicious.
     * Prices shouldn't change by >50% in one day for the same item.
     *
     * @return ValidationResult with warning if suspicious
     */
    fun validatePriceChange(
        item: String,
        newPrice: Double,
        previousPrice: Double?,
        previousDate: Long?
    ): ValidationResult<Double> {
        val priceValidation = validateAmount(newPrice, "bei")
        if (priceValidation is ValidationResult.Invalid) {
            return priceValidation
        }

        if (previousPrice == null || previousPrice <= 0) {
            return ValidationResult.Valid(newPrice)
        }

        // Check if previous price was from today
        if (previousDate != null) {
            val now = System.currentTimeMillis() / 1000
            val oneDayAgo = now - 86400

            if (previousDate >= oneDayAgo) {
                val changePct = Math.abs(newPrice - previousPrice) / previousPrice

                if (changePct > MAX_DAILY_CHANGE_PCT) {
                    return ValidationResult.Warning(
                        value = newPrice,
                        warning = "Bei ya $item imebadilika sana " +
                            "(kutoka KES ${previousPrice.toLong()} hadi KES ${newPrice.toLong()}). " +
                            "Hakikisha ni sahihi."
                    )
                }
            }
        }

        return ValidationResult.Valid(newPrice)
    }

    // =====================================================================
    // TRANSACTION VALIDATION
    // =====================================================================

    /**
     * Full validation of a Transaction entity before saving/displaying.
     */
    fun validateTransaction(transaction: Transaction): TransactionValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Amount
        val amountResult = validateAmount(transaction.totalAmount, "kiasi")
        if (amountResult is ValidationResult.Invalid) {
            errors.add(amountResult.error)
        }
        if (amountResult is ValidationResult.Warning) {
            warnings.add(amountResult.warning)
        }

        // Unit price
        if (transaction.unitPrice < 0) {
            errors.add("Bei ya kitengo haliwezi kuwa hasi")
        }

        // Quantity
        if (transaction.quantity <= 0) {
            errors.add("Idadi lazima iwe zaidi ya sifuri")
        }

        // Date
        val dateResult = validateTransactionDate(transaction.createdAt)
        if (dateResult is ValidationResult.Invalid) {
            errors.add(dateResult.error)
        }

        // Item name
        if (transaction.item.isBlank()) {
            errors.add("Jina la bidhaa halipaswi kuwa tupu")
        }

        // Cost basis for purchases
        if (transaction.type == TransactionType.PURCHASE && transaction.costBasis < 0) {
            errors.add("Gharama haliwezi kuwa hasi")
        }

        // Consistency check: totalAmount ≈ quantity × unitPrice
        if (transaction.quantity > 0 && transaction.unitPrice > 0) {
            val expectedTotal = transaction.quantity * transaction.unitPrice
            val diff = Math.abs(transaction.totalAmount - expectedTotal)
            if (diff > 1.0 && diff / expectedTotal > 0.01) {
                warnings.add(
                    "Kiasi kinapaswa kuwa KES ${expectedTotal.toLong()} " +
                    "(idadi × bei ya kitengo)"
                )
            }
        }

        return TransactionValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    // =====================================================================
    // DAILY BALANCE CHANGE CHECK
    // =====================================================================

    /**
     * Check if balance change in one day is suspicious.
     */
    fun validateDailyBalanceChange(
        previousBalance: Double,
        currentBalance: Double
    ): ValidationResult<Double> {
        if (previousBalance == 0.0) {
            return ValidationResult.Valid(currentBalance)
        }

        val changePct = Math.abs(currentBalance - previousBalance) / Math.abs(previousBalance)

        if (changePct > MAX_DAILY_CHANGE_PCT) {
            return ValidationResult.Warning(
                value = currentBalance,
                warning = "Salio limebadilika sana leo " +
                    "(kutoka KES ${previousBalance.toLong()} hadi KES ${currentBalance.toLong()}). " +
                    "Angalia miamala yako."
            )
        }

        return ValidationResult.Valid(currentBalance)
    }
}

// =====================================================================
// RESULT TYPES
// =====================================================================

/**
 * Sealed class for validation results.
 * Every result carries either a safe value or a Swahili error message.
 */
sealed class ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>()
    data class Invalid<T>(val value: T, val error: String) : ValidationResult<T>()
    data class Warning<T>(val value: T, val warning: String) : ValidationResult<T>()

    /** Get the value regardless of validation state (for safe display) */
    fun getOrDefault(): T = when (this) {
        is Valid -> value
        is Invalid -> value
        is Warning -> value
    }

    /** Check if result has any issues */
    fun hasIssues(): Boolean = this is Invalid || this is Warning

    /** Get error/warning message if any */
    fun getMessage(): String? = when (this) {
        is Valid -> null
        is Invalid -> error
        is Warning -> warning
    }
}

/**
 * Result of full transaction validation.
 */
data class TransactionValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
) {
    fun allMessages(): List<String> = errors + warnings
}

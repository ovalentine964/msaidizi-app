package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * FinancialValidator tests — critical for mama mbogas who PANIC if numbers are wrong.
 *
 * Tests cover:
 * - Amount validation (negative, zero, NaN, Infinity, very large)
 * - Balance validation
 * - Percentage validation
 * - M-Pesa validation (amount, phone, code)
 * - Date validation
 * - Price change validation
 * - Full transaction validation
 */
@RunWith(JUnit4::class)
class FinancialValidatorTest {

    // ═══════════════════════════════════════════════════════════════════
    // Amount Validation — These are KSh amounts that real people enter
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateAmount accepts normal transaction amounts`() {
        val r1 = FinancialValidator.validateAmount(100.0)
        assertTrue("KSh 100 should be valid", r1 is ValidationResult.Valid)

        val r2 = FinancialValidator.validateAmount(50.0)
        assertTrue("KSh 50 should be valid", r2 is ValidationResult.Valid)

        val r3 = FinancialValidator.validateAmount(1000.0)
        assertTrue("KSh 1,000 should be valid", r3 is ValidationResult.Valid)

        val r4 = FinancialValidator.validateAmount(50000.0)
        assertTrue("KSh 50,000 should be valid", r4 is ValidationResult.Valid)

        val r5 = FinancialValidator.validateAmount(999999.0)
        assertTrue("KSh 999,999 should be valid", r5 is ValidationResult.Valid)
    }

    @Test
    fun `validateAmount accepts zero amount`() {
        val result = FinancialValidator.validateAmount(0.0)
        assertTrue("Zero amount should be valid for balance display",
            result is ValidationResult.Valid)
    }

    @Test
    fun `validateAmount rejects negative amounts`() {
        val r1 = FinancialValidator.validateAmount(-1.0)
        assertTrue("Negative amount should be invalid", r1 is ValidationResult.Invalid)

        val r2 = FinancialValidator.validateAmount(-100.0)
        assertTrue("Negative amount should be invalid", r2 is ValidationResult.Invalid)

        val r3 = FinancialValidator.validateAmount(-999999.0)
        assertTrue("Large negative should be invalid", r3 is ValidationResult.Invalid)
    }

    @Test
    fun `validateAmount rejects NaN`() {
        val result = FinancialValidator.validateAmount(Double.NaN)
        assertTrue("NaN should be invalid", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateAmount rejects positive Infinity`() {
        val result = FinancialValidator.validateAmount(Double.POSITIVE_INFINITY)
        assertTrue("Positive Infinity should be invalid", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateAmount rejects negative Infinity`() {
        val result = FinancialValidator.validateAmount(Double.NEGATIVE_INFINITY)
        assertTrue("Negative Infinity should be invalid", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateAmount rejects amounts exceeding max limit`() {
        val r1 = FinancialValidator.validateAmount(1_000_001.0)
        assertTrue("Amount over 1M should be invalid (default max)",
            r1 is ValidationResult.Invalid)

        val r2 = FinancialValidator.validateAmount(Double.MAX_VALUE)
        assertTrue("Very large amount should be invalid", r2 is ValidationResult.Invalid)
    }

    @Test
    fun `validateAmount rounds to 2 decimal places`() {
        val result = FinancialValidator.validateAmount(100.567)
        assertTrue(result is ValidationResult.Valid)
        assertEquals(100.57, (result as ValidationResult.Valid).value, 0.001)
    }

    @Test
    fun `validateAmount returns safe value on invalid`() {
        val result = FinancialValidator.validateAmount(-500.0)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals(0.0, (result as ValidationResult.Invalid).value, 0.001)
        assertNotNull(result.error)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Balance Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateBalance accepts positive balance`() {
        val result = FinancialValidator.validateBalance(5000.0)
        assertTrue("Positive balance should be valid", result is ValidationResult.Valid)
    }

    @Test
    fun `validateBalance rejects negative balance by default`() {
        val result = FinancialValidator.validateBalance(-100.0)
        assertTrue("Negative balance should be invalid by default", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateBalance allows negative when overdraft permitted`() {
        val result = FinancialValidator.validateBalance(-100.0, allowOverdraft = true)
        assertTrue("Negative balance should be valid with overdraft", result is ValidationResult.Valid)
    }

    @Test
    fun `validateBalance warns on suspiciously large balance`() {
        val result = FinancialValidator.validateBalance(15_000_000.0)
        assertTrue("Very large balance should generate warning", result is ValidationResult.Warning)
    }

    @Test
    fun `validateBalance rejects NaN`() {
        val result = FinancialValidator.validateBalance(Double.NaN)
        assertTrue(result is ValidationResult.Invalid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Percentage Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validatePercentage accepts values in range`() {
        val r1 = FinancialValidator.validatePercentage(0.0)
        assertTrue(r1 is ValidationResult.Valid)

        val r2 = FinancialValidator.validatePercentage(50.0)
        assertTrue(r2 is ValidationResult.Valid)

        val r3 = FinancialValidator.validatePercentage(100.0)
        assertTrue(r3 is ValidationResult.Valid)
    }

    @Test
    fun `validatePercentage clamps out-of-range values`() {
        val r1 = FinancialValidator.validatePercentage(-10.0)
        assertTrue(r1 is ValidationResult.Invalid)
        assertEquals(0.0, (r1 as ValidationResult.Invalid).value, 0.001)

        val r2 = FinancialValidator.validatePercentage(150.0)
        assertTrue(r2 is ValidationResult.Invalid)
        assertEquals(100.0, (r2 as ValidationResult.Invalid).value, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════════
    // M-Pesa Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateMpesaAmount accepts valid amounts`() {
        val r1 = FinancialValidator.validateMpesaAmount("100")
        assertTrue(r1 is ValidationResult.Valid)
        assertEquals(100L, (r1 as ValidationResult.Valid).value)

        val r2 = FinancialValidator.validateMpesaAmount("999999")
        assertTrue(r2 is ValidationResult.Valid)
    }

    @Test
    fun `validateMpesaAmount rejects negative amounts`() {
        val result = FinancialValidator.validateMpesaAmount("-100")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateMpesaAmount rejects amounts exceeding max`() {
        val result = FinancialValidator.validateMpesaAmount("1000000")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateMpesaAmount accepts zero`() {
        val result = FinancialValidator.validateMpesaAmount("0")
        assertTrue(result is ValidationResult.Valid)
        assertEquals(0L, (result as ValidationResult.Valid).value)
    }

    @Test
    fun `validateMpesaPhone normalizes valid numbers`() {
        val r1 = FinancialValidator.validateMpesaPhone("0712345678")
        assertTrue(r1 is ValidationResult.Valid)
        assertEquals("254712345678", (r1 as ValidationResult.Valid).value)

        val r2 = FinancialValidator.validateMpesaPhone("+254712345678")
        assertTrue(r2 is ValidationResult.Valid)
        assertEquals("254712345678", (r2 as ValidationResult.Valid).value)
    }

    @Test
    fun `validateMpesaPhone rejects invalid numbers`() {
        val r1 = FinancialValidator.validateMpesaPhone("12345")
        assertTrue(r1 is ValidationResult.Invalid)

        val r2 = FinancialValidator.validateMpesaPhone("0812345678")
        assertTrue(r2 is ValidationResult.Invalid)
    }

    @Test
    fun `validateMpesaCode accepts valid codes`() {
        val result = FinancialValidator.validateMpesaCode("QH34AB5CD6")
        assertTrue(result is ValidationResult.Valid)
        assertEquals("QH34AB5CD6", (result as ValidationResult.Valid).value)
    }

    @Test
    fun `validateMpesaCode rejects invalid codes`() {
        val r1 = FinancialValidator.validateMpesaCode("short")
        assertTrue(r1 is ValidationResult.Invalid)

        val r2 = FinancialValidator.validateMpesaCode("TOOLONGCODE123")
        assertTrue(r2 is ValidationResult.Invalid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Date Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateTransactionDate accepts recent valid timestamp`() {
        val now = System.currentTimeMillis() / 1000
        val result = FinancialValidator.validateTransactionDate(now - 3600) // 1 hour ago
        assertTrue("Recent timestamp should be valid", result is ValidationResult.Valid)
    }

    @Test
    fun `validateTransactionDate rejects future timestamps`() {
        val now = System.currentTimeMillis() / 1000
        val result = FinancialValidator.validateTransactionDate(now + 7200) // 2 hours in future
        assertTrue("Future timestamp should be invalid", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateTransactionDate rejects zero timestamp`() {
        val result = FinancialValidator.validateTransactionDate(0)
        assertTrue("Zero timestamp should be invalid", result is ValidationResult.Invalid)
    }

    @Test
    fun `validateTransactionDate rejects negative timestamp`() {
        val result = FinancialValidator.validateTransactionDate(-100)
        assertTrue("Negative timestamp should be invalid", result is ValidationResult.Invalid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Price Change Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validatePriceChange accepts stable price`() {
        val now = System.currentTimeMillis() / 1000
        val result = FinancialValidator.validatePriceChange(
            item = "tomatoes",
            newPrice = 100.0,
            previousPrice = 95.0,
            previousDate = now - 3600
        )
        assertTrue("Small price change should be valid", result is ValidationResult.Valid)
    }

    @Test
    fun `validatePriceChange warns on large sudden change`() {
        val now = System.currentTimeMillis() / 1000
        val result = FinancialValidator.validatePriceChange(
            item = "tomatoes",
            newPrice = 200.0,
            previousPrice = 100.0,
            previousDate = now - 3600
        )
        assertTrue("Large price change should generate warning", result is ValidationResult.Warning)
    }

    @Test
    fun `validatePriceChange accepts when no previous price`() {
        val result = FinancialValidator.validatePriceChange(
            item = "tomatoes",
            newPrice = 100.0,
            previousPrice = null,
            previousDate = null
        )
        assertTrue("First price entry should be valid", result is ValidationResult.Valid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Transaction Validation (full object)
    // ═══════════════════════════════════════════════════════════════════

    private fun makeTransaction(
        amount: Double = 500.0,
        item: String = "Tomatoes",
        quantity: Double = 10.0,
        unitPrice: Double = 50.0,
        type: TransactionType = TransactionType.SALE
    ): Transaction {
        return Transaction(
            id = 1L,
            totalAmount = amount,
            item = item,
            quantity = quantity,
            unitPrice = unitPrice,
            type = type,
            customer = "Test Customer",
            createdAt = System.currentTimeMillis() / 1000,
            costBasis = 0.0
        )
    }

    @Test
    fun `validateTransaction accepts valid transaction`() {
        val tx = makeTransaction()
        val result = FinancialValidator.validateTransaction(tx)
        assertTrue("Valid transaction should pass", result.isValid)
        assertTrue("Should have no errors", result.errors.isEmpty())
    }

    @Test
    fun `validateTransaction flags inconsistent amount`() {
        // totalAmount != quantity * unitPrice
        val tx = makeTransaction(amount = 999.0, quantity = 10.0, unitPrice = 50.0)
        val result = FinancialValidator.validateTransaction(tx)
        assertTrue("Should have warnings for inconsistent amount", result.warnings.isNotEmpty())
    }

    @Test
    fun `validateTransaction rejects blank item name`() {
        val tx = makeTransaction(item = "")
        val result = FinancialValidator.validateTransaction(tx)
        assertFalse("Blank item should fail validation", result.isValid)
        assertTrue("Should have error for blank item", result.errors.any { it.contains("bidhaa") })
    }

    @Test
    fun `validateTransaction rejects negative unit price`() {
        val tx = makeTransaction(unitPrice = -10.0)
        val result = FinancialValidator.validateTransaction(tx)
        assertFalse("Negative unit price should fail", result.isValid)
    }

    @Test
    fun `validateTransaction rejects zero quantity`() {
        val tx = makeTransaction(quantity = 0.0)
        val result = FinancialValidator.validateTransaction(tx)
        assertFalse("Zero quantity should fail", result.isValid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Daily Balance Change
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateDailyBalanceChange accepts small change`() {
        val result = FinancialValidator.validateDailyBalanceChange(10000.0, 10500.0)
        assertTrue("5% change should be valid", result is ValidationResult.Valid)
    }

    @Test
    fun `validateDailyBalanceChange warns on large change`() {
        val result = FinancialValidator.validateDailyBalanceChange(10000.0, 20000.0)
        assertTrue("100% change should generate warning", result is ValidationResult.Warning)
    }

    // ═══════════════════════════════════════════════════════════════════
    // ValidationResult helpers
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ValidationResult getOrDefault returns value for all states`() {
        val valid: ValidationResult<Int> = ValidationResult.Valid(42)
        assertEquals(42, valid.getOrDefault())

        val invalid: ValidationResult<Int> = ValidationResult.Invalid(0, "error")
        assertEquals(0, invalid.getOrDefault())

        val warning: ValidationResult<Int> = ValidationResult.Warning(42, "warn")
        assertEquals(42, warning.getOrDefault())
    }

    @Test
    fun `ValidationResult hasIssues reflects state correctly`() {
        assertFalse(ValidationResult.Valid(1).hasIssues())
        assertTrue(ValidationResult.Invalid(1, "err").hasIssues())
        assertTrue(ValidationResult.Warning(1, "warn").hasIssues())
    }

    @Test
    fun `ValidationResult getMessage returns null for valid`() {
        assertNull(ValidationResult.Valid(1).getMessage())
        assertEquals("err", ValidationResult.Invalid(1, "err").getMessage())
        assertEquals("warn", ValidationResult.Warning(1, "warn").getMessage())
    }
}

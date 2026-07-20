package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.testutil.TestModels
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [FinancialValidator] — the financial safety net.
 *
 * This is the most critical test class: every KES amount displayed to a
 * mama mboga passes through this validator. Bad data here = panicked user.
 */
@DisplayName("FinancialValidator")
class FinancialValidatorTest {

    // =====================================================================
    // AMOUNT VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("validateAmount")
    inner class AmountTests {

        @Test
        fun `valid amount returns Valid with rounded value`() {
            val result = FinancialValidator.validateAmount(150.50)
            assertTrue(result is ValidationResult.Valid)
            assertEquals(150.50, (result as ValidationResult.Valid).value)
        }

        @Test
        fun `rounds floating point to 2 decimal places`() {
            val result = FinancialValidator.validateAmount(100.999)
            assertTrue(result is ValidationResult.Valid)
            assertEquals(101.0, (result as ValidationResult.Valid).value)
        }

        @Test
        fun `NaN returns Invalid with zero default`() {
            val result = FinancialValidator.validateAmount(Double.NaN)
            assertTrue(result is ValidationResult.Invalid)
            assertEquals(0.0, (result as ValidationResult.Invalid).value)
        }

        @Test
        fun `Infinity returns Invalid`() {
            val result = FinancialValidator.validateAmount(Double.POSITIVE_INFINITY)
            assertTrue(result is ValidationResult.Invalid)
            assertEquals(0.0, (result as ValidationResult.Invalid).value)
        }

        @Test
        fun `negative amount returns Invalid`() {
            val result = FinancialValidator.validateAmount(-500.0)
            assertTrue(result is ValidationResult.Invalid)
            assertEquals(0.0, (result as ValidationResult.Invalid).value)
        }

        @Test
        fun `zero is valid for balance display`() {
            val result = FinancialValidator.validateAmount(0.0)
            assertTrue(result is ValidationResult.Valid)
            assertEquals(0.0, (result as ValidationResult.Valid).value)
        }

        @Test
        fun `amount below minimum (0 < x < 1) returns Invalid`() {
            val result = FinancialValidator.validateAmount(0.5)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `amount above max transaction (1M KES) returns Invalid`() {
            val result = FinancialValidator.validateAmount(1_000_001.0)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `exactly at max transaction (1M KES) is valid`() {
            val result = FinancialValidator.validateAmount(1_000_000.0)
            assertTrue(result is ValidationResult.Valid)
        }

        @ParameterizedTest
        @CsvSource(
            "1.0, 1.0",
            "999999.0, 999999.0",
            "50.25, 50.25",
            "0.01, 0.01"
        )
        fun `boundary amounts are validated correctly`(input: Double, expected: Double) {
            val result = FinancialValidator.validateAmount(input)
            assertTrue(result is ValidationResult.Valid)
            assertEquals(expected, (result as ValidationResult.Valid).value, 0.001)
        }
    }

    // =====================================================================
    // BALANCE VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("validateBalance")
    inner class BalanceTests {

        @Test
        fun `positive balance is valid`() {
            val result = FinancialValidator.validateBalance(5000.0)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `negative balance without overdraft is Invalid`() {
            val result = FinancialValidator.validateBalance(-100.0, allowOverdraft = false)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `negative balance with overdraft allowed is Valid`() {
            val result = FinancialValidator.validateBalance(-100.0, allowOverdraft = true)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `suspiciously large balance returns Warning`() {
            val result = FinancialValidator.validateBalance(50_000_000.0)
            assertTrue(result is ValidationResult.Warning)
        }

        @Test
        fun `NaN balance returns Invalid`() {
            val result = FinancialValidator.validateBalance(Double.NaN)
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // M-PESA VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("M-Pesa validation")
    inner class MpesaTests {

        @ParameterizedTest
        @ValueSource(strings = ["100", "500", "999999", "0", "1"])
        fun `valid M-Pesa amounts accepted`(amount: String) {
            val result = FinancialValidator.validateMpesaAmount(amount)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `M-Pesa amount over 999999 is Invalid`() {
            val result = FinancialValidator.validateMpesaAmount("1000000")
            assertTrue(result is ValidationResult.Invalid)
        }

        @ParameterizedTest
        @ValueSource(strings = ["-100", "abc", "12.50", "", "007"])
        fun `invalid M-Pesa amounts rejected`(amount: String) {
            val result = FinancialValidator.validateMpesaAmount(amount)
            assertTrue(result is ValidationResult.Invalid)
        }

        @ParameterizedTest
        @CsvSource(
            "0712345678, 254712345678",
            "+254712345678, 254712345678",
            "254712345678, 254712345678",
            "712345678, 254712345678",
            "0700000000, 254700000000"
        )
        fun `valid phone numbers normalize to 254 format`(input: String, expected: String) {
            val result = FinancialValidator.validateMpesaPhone(input)
            assertTrue(result is ValidationResult.Valid)
            assertEquals(expected, (result as ValidationResult.Valid).value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["071234", "12345678901", "+15551234567", "abc", ""])
        fun `invalid phone numbers rejected`(phone: String) {
            val result = FinancialValidator.validateMpesaPhone(phone)
            assertTrue(result is ValidationResult.Invalid)
        }

        @ParameterizedTest
        @ValueSource(strings = ["QH34AB5CD6", "ABCDEF1234", "1234567890"])
        fun `valid M-Pesa codes accepted`(code: String) {
            val result = FinancialValidator.validateMpesaCode(code)
            assertTrue(result is ValidationResult.Valid)
        }

        @ParameterizedTest
        @ValueSource(strings = ["ABC", "ABCDEFGHIJK", "abc1234567", ""])
        fun `invalid M-Pesa codes rejected`(code: String) {
            val result = FinancialValidator.validateMpesaCode(code)
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // PERCENTAGE & RATIO VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("Percentage and ratio")
    inner class PercentageTests {

        @Test
        fun `valid percentage 0-100 accepted`() {
            val result = FinancialValidator.validatePercentage(75.5)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `percentage over 100 clamped with error`() {
            val result = FinancialValidator.validatePercentage(150.0)
            assertTrue(result is ValidationResult.Invalid)
            // Value is clamped to 100
            assertEquals(100.0, (result as ValidationResult.Invalid).value)
        }

        @Test
        fun `negative percentage clamped with error`() {
            val result = FinancialValidator.validatePercentage(-10.0)
            assertTrue(result is ValidationResult.Invalid)
            assertEquals(0.0, (result as ValidationResult.Invalid).value)
        }

        @Test
        fun `valid ratio 0-1 accepted`() {
            val result = FinancialValidator.validateRatio(0.75)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `ratio over 1 clamped with error`() {
            val result = FinancialValidator.validateRatio(1.5)
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // PRICE STABILITY CHECK
    // =====================================================================

    @Nested
    @DisplayName("Price change detection")
    inner class PriceChangeTests {

        @Test
        fun `no previous price is always valid`() {
            val result = FinancialValidator.validatePriceChange("mandazi", 20.0, null, null)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `small price change is valid`() {
            val now = System.currentTimeMillis() / 1000
            val result = FinancialValidator.validatePriceChange("mandazi", 22.0, 20.0, now - 3600)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `large same-day price change returns warning`() {
            val now = System.currentTimeMillis() / 1000
            val result = FinancialValidator.validatePriceChange("mandazi", 50.0, 20.0, now - 3600)
            assertTrue(result is ValidationResult.Warning)
        }

        @Test
        fun `large price change on different day is valid`() {
            val threeDaysAgo = System.currentTimeMillis() / 1000 - 3 * 86400
            val result = FinancialValidator.validatePriceChange("mandazi", 50.0, 20.0, threeDaysAgo)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `invalid amount in price check is caught`() {
            val result = FinancialValidator.validatePriceChange("mandazi", -10.0, 20.0, null)
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // TRANSACTION VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("Full transaction validation")
    inner class TransactionTests {

        @Test
        fun `valid sale transaction passes all checks`() {
            val tx = TestModels.sale(item = "mandazi", quantity = 10.0, unitPrice = 20.0, totalAmount = 200.0)
            val result = FinancialValidator.validateTransaction(tx)
            assertTrue(result.isValid, "Errors: ${result.errors}")
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `transaction with blank item name fails`() {
            val tx = TestModels.sale(item = "", totalAmount = 100.0)
            val result = FinancialValidator.validateTransaction(tx)
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.contains("bidhaa") })
        }

        @Test
        fun `transaction with negative amount fails`() {
            val tx = TestModels.sale(totalAmount = -500.0)
            val result = FinancialValidator.validateTransaction(tx)
            assertFalse(result.isValid)
        }

        @Test
        fun `transaction with zero quantity fails`() {
            val tx = TestModels.sale(quantity = 0.0, totalAmount = 0.0)
            val result = FinancialValidator.validateTransaction(tx)
            assertFalse(result.isValid)
        }

        @Test
        fun `mismatched total and unit price x quantity triggers warning`() {
            // totalAmount = 200 but quantity * unitPrice = 10 * 30 = 300
            val tx = TestModels.sale(quantity = 10.0, unitPrice = 30.0, totalAmount = 200.0)
            val result = FinancialValidator.validateTransaction(tx)
            assertTrue(result.warnings.isNotEmpty(), "Should warn about price mismatch")
        }

        @Test
        fun `purchase with negative cost basis fails`() {
            val tx = TestModels.purchase()
            // Manually set negative costBasis via copy
            val badTx = tx.copy(costBasis = -100.0)
            val result = FinancialValidator.validateTransaction(badTx)
            assertFalse(result.isValid)
        }
    }

    // =====================================================================
    // DAILY BALANCE CHANGE
    // =====================================================================

    @Nested
    @DisplayName("Daily balance change")
    inner class BalanceChangeTests {

        @Test
        fun `small balance change is valid`() {
            val result = FinancialValidator.validateDailyBalanceChange(10000.0, 12000.0)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `large balance change returns warning`() {
            val result = FinancialValidator.validateDailyBalanceChange(10000.0, 20000.0)
            assertTrue(result is ValidationResult.Warning)
        }

        @Test
        fun `zero previous balance skips check`() {
            val result = FinancialValidator.validateDailyBalanceChange(0.0, 50000.0)
            assertTrue(result is ValidationResult.Valid)
        }
    }

    // =====================================================================
    // DATE VALIDATION
    // =====================================================================

    @Nested
    @DisplayName("Transaction date validation")
    inner class DateTests {

        @Test
        fun `current timestamp is valid`() {
            val now = System.currentTimeMillis() / 1000
            val result = FinancialValidator.validateTransactionDate(now)
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `future timestamp is invalid`() {
            val future = System.currentTimeMillis() / 1000 + 86400 * 30 // 30 days ahead
            val result = FinancialValidator.validateTransactionDate(future)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `timestamp before app launch is invalid`() {
            val beforeLaunch = java.time.LocalDate.of(2023, 1, 1)
                .atStartOfDay(java.time.ZoneId.of("Africa/Nairobi"))
                .toEpochSecond()
            val result = FinancialValidator.validateTransactionDate(beforeLaunch)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `zero timestamp is invalid`() {
            val result = FinancialValidator.validateTransactionDate(0)
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // VALIDATION RESULT HELPERS
    // =====================================================================

    @Nested
    @DisplayName("ValidationResult helpers")
    inner class ResultHelperTests {

        @Test
        fun `getOrDefault returns value for Valid`() {
            val result: ValidationResult<Double> = ValidationResult.Valid(42.0)
            assertEquals(42.0, result.getOrDefault())
        }

        @Test
        fun `getOrDefault returns value for Invalid`() {
            val result: ValidationResult<Double> = ValidationResult.Invalid(0.0, "error")
            assertEquals(0.0, result.getOrDefault())
        }

        @Test
        fun `hasIssues is false for Valid`() {
            val result: ValidationResult<Double> = ValidationResult.Valid(1.0)
            assertFalse(result.hasIssues())
        }

        @Test
        fun `hasIssues is true for Warning`() {
            val result: ValidationResult<Double> = ValidationResult.Warning(1.0, "watch out")
            assertTrue(result.hasIssues())
        }

        @Test
        fun `getMessage returns null for Valid`() {
            val result: ValidationResult<Double> = ValidationResult.Valid(1.0)
            assertNull(result.getMessage())
        }

        @Test
        fun `getMessage returns error for Invalid`() {
            val result: ValidationResult<Double> = ValidationResult.Invalid(0.0, "batili")
            assertEquals("batili", result.getMessage())
        }
    }
}

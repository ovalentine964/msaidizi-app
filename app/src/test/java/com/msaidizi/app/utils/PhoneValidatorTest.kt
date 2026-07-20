package com.msaidizi.app.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [PhoneValidator] — Kenyan phone number validation.
 *
 * Critical for M-Pesa integration: invalid phone numbers = failed payments.
 * Covers all common input formats from Kenyan users.
 */
@DisplayName("PhoneValidator")
class PhoneValidatorTest {

    // =====================================================================
    // VALID PHONE NUMBERS
    // =====================================================================

    @Nested
    @DisplayName("Valid phone numbers")
    inner class ValidTests {

        @ParameterizedTest
        @CsvSource(
            "0712345678, +254712345678",
            "0700000000, +254700000000",
            "0799999999, +254799999999",
            "0110123456, +254110123456",
            "0111123456, +254111123456"
        )
        fun `local 10-digit format normalizes correctly`(input: String, expected: String) {
            val result = PhoneValidator.validate(input)
            assertTrue(result is PhoneValidator.ValidationResult.Valid,
                "Expected Valid for '$input', got: ${(result as? PhoneValidator.ValidationResult.Invalid)?.reason}")
            assertEquals(expected, (result as PhoneValidator.ValidationResult.Valid).normalized)
        }

        @ParameterizedTest
        @CsvSource(
            "+254712345678, +254712345678",
            "+254110123456, +254110123456"
        )
        fun `international with plus normalizes correctly`(input: String, expected: String) {
            val result = PhoneValidator.validate(input)
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
            assertEquals(expected, (result as PhoneValidator.ValidationResult.Valid).normalized)
        }

        @ParameterizedTest
        @CsvSource(
            "254712345678, +254712345678",
            "254110123456, +254110123456"
        )
        fun `international without plus normalizes correctly`(input: String, expected: String) {
            val result = PhoneValidator.validate(input)
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
            assertEquals(expected, (result as PhoneValidator.ValidationResult.Valid).normalized)
        }

        @ParameterizedTest
        @CsvSource(
            "712345678, +254712345678",
            "110123456, +254110123456"
        )
        fun `bare 9-digit format normalizes correctly`(input: String, expected: String) {
            val result = PhoneValidator.validate(input)
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
            assertEquals(expected, (result as PhoneValidator.ValidationResult.Valid).normalized)
        }

        @Test
        fun `phone with spaces is accepted`() {
            val result = PhoneValidator.validate("0712 345 678")
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
        }

        @Test
        fun `phone with dashes is accepted`() {
            val result = PhoneValidator.validate("0712-345-678")
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
        }

        @Test
        fun `phone with parentheses is accepted`() {
            val result = PhoneValidator.validate("(0712) 345 678")
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
        }
    }

    // =====================================================================
    // INVALID PHONE NUMBERS
    // =====================================================================

    @Nested
    @DisplayName("Invalid phone numbers")
    inner class InvalidTests {

        @ParameterizedTest
        @ValueSource(strings = [
            "",
            "   ",
            "123",
            "071234",
            "07123456789012",
            "abcdefghij",
            "+15551234567",
            "0812345678",  // Not a valid Kenyan prefix
            "0612345678"   // Not a valid Kenyan prefix
        ])
        fun `invalid formats rejected`(input: String) {
            val result = PhoneValidator.validate(input)
            assertTrue(result is PhoneValidator.ValidationResult.Invalid,
                "Expected Invalid for '$input'")
        }

        @Test
        fun `letters in number rejected`() {
            val result = PhoneValidator.validate("0712abc678")
            assertTrue(result is PhoneValidator.ValidationResult.Invalid)
        }
    }

    // =====================================================================
    // CONVENIENCE METHODS
    // =====================================================================

    @Nested
    @DisplayName("Convenience methods")
    inner class ConvenienceTests {

        @Test
        fun `isValid returns true for valid number`() {
            assertTrue(PhoneValidator.isValid("0712345678"))
        }

        @Test
        fun `isValid returns false for invalid number`() {
            assertFalse(PhoneValidator.isValid("abc"))
        }

        @Test
        fun `normalizeOrNull returns normalized for valid`() {
            assertEquals("+254712345678", PhoneValidator.normalizeOrNull("0712345678"))
        }

        @Test
        fun `normalizeOrNull returns null for invalid`() {
            assertNull(PhoneValidator.normalizeOrNull("abc"))
        }

        @Test
        fun `toDigits strips plus sign`() {
            assertEquals("254712345678", PhoneValidator.toDigits("0712345678"))
        }

        @Test
        fun `toDigits returns null for invalid`() {
            assertNull(PhoneValidator.toDigits("abc"))
        }

        @Test
        fun `formatForDisplay shows local format`() {
            val display = PhoneValidator.formatForDisplay("0712345678")
            assertTrue(display.contains("0712"), "Should contain local prefix")
            assertTrue(display.contains(" "), "Should have space separators")
        }

        @Test
        fun `formatForDisplay returns raw for invalid`() {
            val raw = "notaphone"
            assertEquals(raw, PhoneValidator.formatForDisplay(raw))
        }
    }

    // =====================================================================
    // NORMALIZED FORMAT
    // =====================================================================

    @Nested
    @DisplayName("Output format")
    inner class FormatTests {

        @Test
        fun `all valid numbers normalize to +254 prefix`() {
            val inputs = listOf("0712345678", "254712345678", "+254712345678", "712345678")
            for (input in inputs) {
                val result = PhoneValidator.validate(input)
                assertTrue(result is PhoneValidator.ValidationResult.Valid,
                    "'$input' should be valid")
                val normalized = (result as PhoneValidator.ValidationResult.Valid).normalized
                assertTrue(normalized.startsWith("+254"),
                    "'$input' should normalize to +254 prefix, got: $normalized")
            }
        }

        @Test
        fun `normalized number is always 13 characters`() {
            val result = PhoneValidator.validate("0712345678")
            assertTrue(result is PhoneValidator.ValidationResult.Valid)
            val normalized = (result as PhoneValidator.ValidationResult.Valid).normalized
            assertEquals(13, normalized.length, "Normalized should be +254XXXXXXXXX (13 chars)")
        }
    }
}

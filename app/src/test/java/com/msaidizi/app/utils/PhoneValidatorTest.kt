package com.msaidizi.app.utils

import org.junit.Assert.*
import org.junit.Test

class PhoneValidatorTest {

    @Test
    fun `valid local Safaricom number`() {
        val result = PhoneValidator.validate("0712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid local Airtel number`() {
        val result = PhoneValidator.validate("0112345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254112345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid international with plus`() {
        val result = PhoneValidator.validate("+254712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid international without plus`() {
        val result = PhoneValidator.validate("254712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid bare 9-digit number`() {
        val result = PhoneValidator.validate("712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid with spaces`() {
        val result = PhoneValidator.validate("0712 345 678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `valid with dashes`() {
        val result = PhoneValidator.validate("0712-345-678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `empty input returns invalid`() {
        val result = PhoneValidator.validate("")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `null input returns invalid`() {
        val result = PhoneValidator.validate(null.toString())
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `non-numeric input returns invalid`() {
        val result = PhoneValidator.validate("abc123")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `too short number returns invalid`() {
        val result = PhoneValidator.validate("071234")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `too long number returns invalid`() {
        val result = PhoneValidator.validate("071234567890")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `wrong prefix returns invalid`() {
        val result = PhoneValidator.validate("0512345678")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `isValid returns true for valid number`() {
        assertTrue(PhoneValidator.isValid("0712345678"))
    }

    @Test
    fun `isValid returns false for invalid number`() {
        assertFalse(PhoneValidator.isValid("123"))
    }

    @Test
    fun `formatForDisplay formats local number`() {
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("0712345678"))
    }

    @Test
    fun `formatForDisplay formats international number`() {
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("+254712345678"))
    }

    @Test
    fun `toDigits extracts digits from local`() {
        assertEquals("254712345678", PhoneValidator.toDigits("0712345678"))
    }

    @Test
    fun `toDigits returns null for invalid`() {
        assertNull(PhoneValidator.toDigits("123"))
    }
}

package com.msaidizi.app.utils

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * PhoneValidator tests — because every mama mboga has a phone number.
 *
 * Tests cover:
 * - All valid Kenyan phone formats
 * - Normalization to +254XXXXXXXXX
 * - Invalid formats (letters, too short, too long)
 * - Display formatting
 * - Edge cases
 */
@RunWith(JUnit4::class)
class PhoneValidatorTest {

    // ═══════════════════════════════════════════════════════════════════
    // Valid Phone Numbers — All formats Kenyans actually use
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validates local Safaricom number 07XXXXXXXX`() {
        assertTrue(PhoneValidator.isValid("0712345678"))
        assertTrue(PhoneValidator.isValid("0722345678"))
        assertTrue(PhoneValidator.isValid("0798765432"))
    }

    @Test
    fun `validates local Airtel number 01XXXXXXXX`() {
        assertTrue(PhoneValidator.isValid("0112345678"))
    }

    @Test
    fun `validates international format +254XXXXXXXXX`() {
        assertTrue(PhoneValidator.isValid("+254712345678"))
        assertTrue(PhoneValidator.isValid("+254112345678"))
    }

    @Test
    fun `validates international without plus 254XXXXXXXXX`() {
        assertTrue(PhoneValidator.isValid("254712345678"))
    }

    @Test
    fun `validates bare 9-digit number`() {
        assertTrue(PhoneValidator.isValid("712345678"))
    }

    @Test
    fun `validates number with spaces`() {
        assertTrue(PhoneValidator.isValid("0712 345 678"))
        assertTrue(PhoneValidator.isValid("+254 712 345 678"))
    }

    @Test
    fun `validates number with dashes`() {
        assertTrue(PhoneValidator.isValid("0712-345-678"))
    }

    @Test
    fun `validates number with parentheses`() {
        assertTrue(PhoneValidator.isValid("(0712) 345 678"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Normalization — All formats → +254XXXXXXXXX
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `normalizes 07XXXXXXXX to +254XXXXXXXXX`() {
        val result = PhoneValidator.validate("0712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `normalizes +254XXXXXXXXX correctly`() {
        val result = PhoneValidator.validate("+254712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `normalizes 254XXXXXXXXX to +254XXXXXXXXX`() {
        val result = PhoneValidator.validate("254712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `normalizes bare 9-digit to +254XXXXXXXXX`() {
        val result = PhoneValidator.validate("712345678")
        assertTrue(result is PhoneValidator.ValidationResult.Valid)
        assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
    }

    @Test
    fun `normalizeOrNull strips spaces and normalizes`() {
        assertEquals("+254712345678", PhoneValidator.normalizeOrNull("0712 345 678"))
        assertEquals("+254712345678", PhoneValidator.normalizeOrNull("+254 712 345 678"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Invalid Phone Numbers
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `rejects empty string`() {
        assertFalse(PhoneValidator.isValid(""))
        val result = PhoneValidator.validate("")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects letters`() {
        assertFalse(PhoneValidator.isValid("abcdefghij"))
        assertFalse(PhoneValidator.isValid("0712abc678"))
    }

    @Test
    fun `rejects too short numbers`() {
        assertFalse(PhoneValidator.isValid("071234567"))   // 9 digits
        assertFalse(PhoneValidator.isValid("07123456"))    // 8 digits
        assertFalse(PhoneValidator.isValid("123"))         // 3 digits
    }

    @Test
    fun `rejects too long numbers`() {
        assertFalse(PhoneValidator.isValid("07123456789"))  // 11 digits
        assertFalse(PhoneValidator.isValid("+2547123456789")) // 14 chars
    }

    @Test
    fun `rejects numbers not starting with valid prefix`() {
        assertFalse(PhoneValidator.isValid("0512345678"))  // 05 prefix
        assertFalse(PhoneValidator.isValid("0812345678"))  // 08 prefix
        assertFalse(PhoneValidator.isValid("0612345678"))  // 06 prefix
    }

    @Test
    fun `rejects non-Kenyan international format`() {
        assertFalse(PhoneValidator.isValid("+1234567890"))  // US format
        assertFalse(PhoneValidator.isValid("+44712345678")) // UK format
    }

    // ═══════════════════════════════════════════════════════════════════
    // Display Formatting
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `formatForDisplay formats correctly`() {
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("0712345678"))
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("+254712345678"))
    }

    @Test
    fun `formatForDisplay returns raw for invalid numbers`() {
        assertEquals("invalid", PhoneValidator.formatForDisplay("invalid"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // toDigits — For M-Pesa API calls
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `toDigits returns digits without plus`() {
        assertEquals("254712345678", PhoneValidator.toDigits("0712345678"))
        assertEquals("254712345678", PhoneValidator.toDigits("+254712345678"))
    }

    @Test
    fun `toDigits returns null for invalid input`() {
        assertNull(PhoneValidator.toDigits("invalid"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Validation Result Messages — In Swahili!
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validation error messages are in Swahili`() {
        val emptyResult = PhoneValidator.validate("")
        assertTrue(emptyResult is PhoneValidator.ValidationResult.Invalid)
        val msg = (emptyResult as PhoneValidator.ValidationResult.Invalid).reason
        assertTrue("Error message should be in Swahili", msg.contains("simu"))
    }

    @Test
    fun `validation error for letters is in Swahili`() {
        val result = PhoneValidator.validate("abc")
        assertTrue(result is PhoneValidator.ValidationResult.Invalid)
        val msg = (result as PhoneValidator.ValidationResult.Invalid).reason
        assertTrue("Error should mention herufi (letters)", msg.contains("herufi"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 020 Prefix (Nairobi landline-style)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validates 020 prefix numbers`() {
        assertTrue(PhoneValidator.isValid("0201234567"))
    }
}

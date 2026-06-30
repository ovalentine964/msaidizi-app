/**
 * WhatsApp Connection Tests
 * 
 * Tests for the WhatsApp connection onboarding flow.
 */

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import com.msaidizi.app.utils.PhoneValidator;

class PhoneValidatorTest {

    @Test
    @DisplayName("Valid local Safaricom number")
    void testValidLocalSafaricom() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("0712345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid local Airtel number")
    void testValidLocalAirtel() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("0112345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254112345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid international with plus")
    void testValidInternationalWithPlus() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("+254712345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid international without plus")
    void testValidInternationalWithoutPlus() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("254712345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid bare 9-digit number")
    void testValidBare9Digit() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("712345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid number with spaces")
    void testValidWithSpaces() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("0712 345 678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Valid number with dashes")
    void testValidWithDashes() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("0712-345-678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Valid);
        
        PhoneValidator.ValidationResult.Valid valid = (PhoneValidator.ValidationResult.Valid) result;
        assertEquals("+254712345678", valid.getNormalized());
    }

    @Test
    @DisplayName("Empty input returns invalid")
    void testEmptyInput() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("Null input returns invalid")
    void testNullInput() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate(null);
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("Non-numeric input returns invalid")
    void testNonNumericInput() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("abc123");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("Too short number returns invalid")
    void testTooShortNumber() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("071234");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("Too long number returns invalid")
    void testTooLongNumber() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("071234567890");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("Wrong prefix returns invalid")
    void testWrongPrefix() {
        PhoneValidator.ValidationResult result = PhoneValidator.validate("0512345678");
        assertTrue(result instanceof PhoneValidator.ValidationResult.Invalid);
    }

    @Test
    @DisplayName("isValid returns true for valid number")
    void testIsValidTrue() {
        assertTrue(PhoneValidator.isValid("0712345678"));
    }

    @Test
    @DisplayName("isValid returns false for invalid number")
    void testIsValidFalse() {
        assertFalse(PhoneValidator.isValid("123"));
    }

    @Test
    @DisplayName("Format for display")
    void testFormatForDisplay() {
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("0712345678"));
        assertEquals("0712 345 678", PhoneValidator.formatForDisplay("+254712345678"));
    }

    @Test
    @DisplayName("To digits")
    void testToDigits() {
        assertEquals("254712345678", PhoneValidator.toDigits("0712345678"));
        assertEquals("254712345678", PhoneValidator.toDigits("+254712345678"));
    }

    @Test
    @DisplayName("To digits returns null for invalid")
    void testToDigitsInvalid() {
        assertNull(PhoneValidator.toDigits("123"));
    }
}

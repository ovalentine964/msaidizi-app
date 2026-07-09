package com.msaidizi.app.security.validation

import timber.log.Timber
import java.util.regex.Pattern

/**
 * Input sanitization to prevent injection attacks.
 *
 * Per SECURITY_ARCHITECTURE.md Section 5.2 (Prompt Injection) and OWASP M4:
 * - Sanitize all user inputs before processing
 * - Prevent SQL injection, XSS, prompt injection
 * - Validate format, length, and content
 */
object InputSanitizer {

    // SQL injection patterns
    private val SQL_PATTERNS = listOf(
        Pattern.compile("(?i)(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|EXEC|EXECUTE|UNION|TRUNCATE)\\b)"),
        Pattern.compile("(?i)(--|;|/\\*|\\*/|@@|@)"),
        Pattern.compile("(?i)(\\b(OR|AND)\\b\\s+\\d+\\s*=\\s*\\d+)"),
        Pattern.compile("(?i)'\\s*(OR|AND)\\s+'"),
        Pattern.compile("(?i)(CHAR|CONCAT|SUBSTRING|CAST|CONVERT)\\s*\\(")
    )

    // XSS patterns
    private val XSS_PATTERNS = listOf(
        Pattern.compile("<script[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("data:\\s*text/html", Pattern.CASE_INSENSITIVE)
    )

    // Prompt injection patterns (per SECURITY_ARCHITECTURE.md Section 5.2)
    private val PROMPT_INJECTION_PATTERNS = listOf(
        Pattern.compile("(?i)ignore\\s+(previous|all)\\s+instructions"),
        Pattern.compile("(?i)you\\s+are\\s+now"),
        Pattern.compile("(?i)system\\s*:\\s*"),
        Pattern.compile("<\\|im_start\\|>"),
        Pattern.compile("(?i)Human:\\s*"),
        Pattern.compile("(?i)Assistant:\\s*"),
        Pattern.compile("(?i)forget\\s+(everything|all|previous)"),
        Pattern.compile("(?i)act\\s+as\\s+(a\\s+)?"),
        Pattern.compile("(?i)pretend\\s+you\\s+are"),
        Pattern.compile("(?i)new\\s+instructions"),
        Pattern.compile("(?i)override\\s+(your|system)")
    )

    // Swahili-specific patterns (prevent injection via Bantu language constructs)
    // Swahili uses Latin script with special characters (ng', dh, th, ch, sh)
    // Block Unicode control chars and homoglyph attacks
    private val SWAHILI_SAFE_PATTERN = Regex("^[\\p{L}\\p{M}\\p{N}\\s.,!?'\"()@#&:;+=_/-]+$")
    private val HOMOGLYPH_CHARS = setOf(
        '\u0430', // Cyrillic а vs Latin a
        '\u0435', // Cyrillic е vs Latin e
        '\u043E', // Cyrillic о vs Latin o
        '\u0440', // Cyrillic р vs Latin p
        '\u0441', // Cyrillic с vs Latin c
        '\u0443', // Cyrillic у vs Latin y
        '\u0445', // Cyrillic х vs Latin x
        '\uFF0E', // Fullwidth period
        '\uFF10', // Fullwidth 0
        '\u200B', // Zero-width space
        '\u200C', // Zero-width non-joiner
        '\u200D', // Zero-width joiner
        '\uFEFF', // BOM / zero-width no-break space
        '\u2028', // Line separator
        '\u2029', // Paragraph separator
    )

    // Max input lengths
    const val MAX_PHONE_LENGTH = 15
    const val MAX_NAME_LENGTH = 100
    const val MAX_MESSAGE_LENGTH = 5000
    const val MAX_OTP_LENGTH = 6
    const val MAX_AMOUNT_DIGITS = 15
    const val MAX_SWAHILI_TEXT_LENGTH = 10000

    /**
     * Sanitize general text input.
     * Removes potentially dangerous content while preserving normal text.
     * Handles Swahili-specific characters safely.
     */
    fun sanitizeText(input: String, maxLength: Int = MAX_MESSAGE_LENGTH): String {
        if (input.isBlank()) return ""

        var sanitized = input
            .take(maxLength)
            .trim()

        // Remove null bytes
        sanitized = sanitized.replace("\u0000", "")

        // Remove control characters except newlines and tabs
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")

        // Remove zero-width and invisible Unicode characters (homoglyph defense)
        sanitized = sanitized.replace(Regex("[\\u200B-\\u200D\\u2060-\\u206F\\uFEFF\\u2028\\u2029]"), "")

        // Normalize Unicode to NFC (prevents encoding-based bypasses)
        sanitized = java.text.Normalizer.normalize(sanitized, java.text.Normalizer.Form.NFC)

        return sanitized
    }

    /**
     * Sanitize Swahili text for voice transcription and message input.
     * Allows Swahili-specific characters (ng', dh, th) while blocking injection.
     */
    fun sanitizeSwahiliText(input: String, maxLength: Int = MAX_SWAHILI_TEXT_LENGTH): String {
        if (input.isBlank()) return ""

        val sanitized = sanitizeText(input, maxLength)

        // Verify the text only contains safe characters
        // Swahili uses standard Latin + a few digraphs, no special Unicode needed
        if (!SWAHILI_SAFE_PATTERN.matches(sanitized)) {
            // Strip anything not in the safe pattern
            return sanitized.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s.,!?'\"()@#&:;+=_/-]"), "").take(maxLength)
        }

        return sanitized
    }

    /**
     * Check for homoglyph attacks (Cyrillic/fullwidth chars impersonating Latin).
     * Critical for preventing username impersonation and phishing.
     */
    fun containsHomoglyphs(input: String): Boolean {
        return input.any { it in HOMOGLYPH_CHARS ||
            it.code in 0xFF01..0xFF5E || // Fullwidth ASCII variants
            it.code in 0x0400..0x04FF ||  // Cyrillic block
            (it.code in 0x2000..0x200F)   // General punctuation (invisible)
        }
    }

    /**
     * Sanitize and validate phone number (E.164 format).
     */
    fun sanitizePhone(phone: String): String? {
        val stripped = phone.replace(Regex("[\\s\\-()]+"), "")
        val e164Pattern = Regex("^\\+[1-9]\\d{9,14}$")
        return if (e164Pattern.matches(stripped)) stripped else null
    }

    /**
     * Sanitize and validate OTP input.
     */
    fun sanitizeOtp(otp: String): String? {
        val stripped = otp.replace(Regex("[\\s-]"), "")
        return if (stripped.length == MAX_OTP_LENGTH && stripped.all { it.isDigit() }) stripped else null
    }

    /**
     * Sanitize user name input.
     */
    fun sanitizeName(name: String): String? {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) return null
        // Allow letters, spaces, hyphens, apostrophes (covers African names)
        val validPattern = Regex("^[\\p{L}\\s\\-']+$")
        return if (validPattern.matches(name.trim())) name.trim() else null
    }

    /**
     * Detect SQL injection attempts.
     */
    fun containsSqlInjection(input: String): Boolean {
        return SQL_PATTERNS.any { it.matcher(input).find() }
    }

    /**
     * Detect XSS attempts.
     */
    fun containsXss(input: String): Boolean {
        return XSS_PATTERNS.any { it.matcher(input).find() }
    }

    /**
     * Detect prompt injection attempts.
     */
    fun containsPromptInjection(input: String): Boolean {
        return PROMPT_INJECTION_PATTERNS.any { it.matcher(input).find() }
    }

    /**
     * Full security check — returns sanitized input or null if dangerous.
     */
    fun validateAndSanitize(input: String, type: InputType): String? {
        // Check for injection attempts first
        if (containsSqlInjection(input)) {
            Timber.w("SQL injection attempt detected: %s", input.take(50))
            return null
        }
        if (containsXss(input)) {
            Timber.w("XSS attempt detected: %s", input.take(50))
            return null
        }
        if (containsPromptInjection(input)) {
            Timber.w("Prompt injection attempt detected: %s", input.take(50))
            return null
        }

        return when (type) {
            InputType.PHONE -> sanitizePhone(input)
            InputType.OTP -> sanitizeOtp(input)
            InputType.NAME -> sanitizeName(input)
            InputType.TEXT -> sanitizeText(input)
            InputType.SWAHILI_TEXT -> sanitizeSwahiliText(input)
            InputType.AMOUNT -> sanitizeAmount(input)
            InputType.NUMERIC -> {
                val num = input.replace(Regex("[^\\d.]"), "")
                if (num.isNotEmpty() && num.toDoubleOrNull() != null) num else null
            }
        }
    }

    /**
     * Sanitize and validate a monetary amount.
     * Prevents negative, NaN, Infinity, and excessively large values.
     * Handles KES and NGN currency formatting.
     */
    fun sanitizeAmount(input: String): String? {
        // Strip currency symbols, commas, spaces
        val stripped = input.replace(Regex("[^\\d.]"), "")
        if (stripped.isEmpty()) return null

        // Prevent multiple decimal points
        val parts = stripped.split(".")
        if (parts.size > 2) return null

        val amount = stripped.toDoubleOrNull() ?: return null

        // Validate: positive, finite, reasonable range (max 10M KES / 100M NGN)
        if (amount <= 0 || amount.isNaN() || amount.isInfinite()) return null
        if (amount > 10_000_000.0) return null // Max 10M per transaction

        // Max 2 decimal places for currency
        if (parts.size == 2 && parts[1].length > 2) return null

        return stripped
    }

    /**
     * Validate a geohash (location data for fraud detection).
     * Geohashes use base32: 0-9, a-z (excluding a, i, l, o).
     */
    fun sanitizeGeohash(input: String): String? {
        if (input.length !in 1..12) return null
        val validPattern = Regex("^[0-9b-hjkmnp-z]+$")
        return if (validPattern.matches(input.lowercase())) input else null
    }

    /**
     * Sanitize LLM output — remove any leaked PII or internal info.
     * Per SECURITY_ARCHITECTURE.md Section 5.3.
     */
    fun sanitizeLlmOutput(output: String): String {
        var sanitized = output

        // Mask phone numbers
        sanitized = sanitized.replace(Regex("\\+\\d{10,15}")) { match ->
            val phone = match.value
            phone.take(4) + "***" + phone.takeLast(3)
        }

        // Mask national ID patterns (various African formats)
        sanitized = sanitized.replace(Regex("\\b\\d{8,10}\\b")) { match ->
            val id = match.value
            if (id.length in 8..10) id.take(2) + "***" + id.takeLast(2) else match.value
        }

        // Mask account numbers
        sanitized = sanitized.replace(Regex("\\b\\d{10,16}\\b")) { match ->
            val acct = match.value
            "****" + acct.takeLast(4)
        }

        // Remove internal API endpoints
        sanitized = sanitized.replace(Regex("https?://[\\w.-]+/(api|internal|admin)/[\\w/.-]+"), "[REDACTED]")

        // Remove database schema info
        sanitized = sanitized.replace(Regex("(?i)(CREATE TABLE|ALTER TABLE|SELECT .+ FROM)"), "[REDACTED]")

        return sanitized
    }

    enum class InputType {
        PHONE,
        OTP,
        NAME,
        TEXT,
        SWAHILI_TEXT,
        AMOUNT,
        NUMERIC
    }
}

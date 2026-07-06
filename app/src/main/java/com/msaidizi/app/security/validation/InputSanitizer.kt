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

    // Max input lengths
    const val MAX_PHONE_LENGTH = 15
    const val MAX_NAME_LENGTH = 100
    const val MAX_MESSAGE_LENGTH = 5000
    const val MAX_OTP_LENGTH = 6

    /**
     * Sanitize general text input.
     * Removes potentially dangerous content while preserving normal text.
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

        return sanitized
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
            InputType.NUMERIC -> {
                val num = input.replace(Regex("[^\\d.]"), "")
                if (num.isNotEmpty() && num.toDoubleOrNull() != null) num else null
            }
        }
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
        NUMERIC
    }
}

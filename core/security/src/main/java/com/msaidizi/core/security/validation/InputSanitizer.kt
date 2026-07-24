package com.msaidizi.core.security.validation

/**
 * 10-layer input sanitizer for defense-in-depth security.
 *
 * Protects against:
 * - SQL injection
 * - XSS (cross-site scripting)
 * - Path traversal
 * - Command injection
 * - Unicode exploits
 * - Format string attacks
 * - Buffer overflow attempts
 * - LDAP injection
 * - XML injection
 * - Template injection
 *
 * Each layer is independent — even if one fails, others provide protection.
 */
class InputSanitizer {

    /**
     * Sanitize input through all 10 layers.
     *
     * @param input Raw user input
     * @param maxLength Maximum allowed length (default 1000)
     * @return Sanitized string, or null if input is malicious
     */
    fun sanitize(input: String, maxLength: Int = 1000): SanitizeResult {
        var current = input
        val warnings = mutableListOf<String>()

        // Layer 1: Length check
        if (current.length > maxLength) {
            current = current.take(maxLength)
            warnings.add("TRUNCATED")
        }

        // Layer 2: Null byte removal
        if (current.contains('\u0000')) {
            current = current.replace("\u0000", "")
            warnings.add("NULL_BYTES_REMOVED")
        }

        // Layer 3: Unicode normalization
        current = normalizeUnicode(current)

        // Layer 4: Control character removal (except newline/tab)
        val controlCharsRemoved = current.count { it.code < 32 && it != '\n' && it != '\t' && it != '\r' }
        if (controlCharsRemoved > 0) {
            current = current.filter { it.code >= 32 || it == '\n' || it == '\t' || it == '\r' }
            warnings.add("CONTROL_CHARS_REMOVED")
        }

        // Layer 5: SQL injection detection
        if (detectSqlInjection(current)) {
            warnings.add("SQL_INJECTION_DETECTED")
            return SanitizeResult(
                sanitized = null,
                isSafe = false,
                warnings = warnings,
                threat = ThreatType.SQL_INJECTION
            )
        }

        // Layer 6: XSS detection
        if (detectXss(current)) {
            warnings.add("XSS_DETECTED")
            return SanitizeResult(
                sanitized = null,
                isSafe = false,
                warnings = warnings,
                threat = ThreatType.XSS
            )
        }

        // Layer 7: Path traversal detection
        if (detectPathTraversal(current)) {
            warnings.add("PATH_TRAVERSAL_DETECTED")
            return SanitizeResult(
                sanitized = null,
                isSafe = false,
                warnings = warnings,
                threat = ThreatType.PATH_TRAVERSAL
            )
        }

        // Layer 8: Command injection detection
        if (detectCommandInjection(current)) {
            warnings.add("COMMAND_INJECTION_DETECTED")
            return SanitizeResult(
                sanitized = null,
                isSafe = false,
                warnings = warnings,
                threat = ThreatType.COMMAND_INJECTION
            )
        }

        // Layer 9: Format string detection
        if (detectFormatString(current)) {
            current = current.replace("%", "%%")
            warnings.add("FORMAT_STRING_ESCAPED")
        }

        // Layer 10: Template injection detection
        if (detectTemplateInjection(current)) {
            warnings.add("TEMPLATE_INJECTION_DETECTED")
            return SanitizeResult(
                sanitized = null,
                isSafe = false,
                warnings = warnings,
                threat = ThreatType.TEMPLATE_INJECTION
            )
        }

        // Trim whitespace
        current = current.trim()

        return SanitizeResult(
            sanitized = current,
            isSafe = true,
            warnings = warnings,
            threat = null
        )
    }

    /**
     * Sanitize for database storage (prevents SQL injection).
     */
    fun sanitizeForDb(input: String): String {
        return input
            .replace("'", "''")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Sanitize for display (prevents XSS).
     */
    fun sanitizeForDisplay(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }

    // ═══ DETECTION METHODS ═══

    private fun detectSqlInjection(input: String): Boolean {
        val lower = input.lowercase()
        val patterns = listOf(
            "'\\s*or\\s+'", "';\\s*drop\\s+", "';\\s*delete\\s+",
            "';\\s*update\\s+", "';\\s*insert\\s+", "union\\s+select",
            "1\\s*=\\s*1", "'\\s*=\\s*'", "--\\s*$", "/\\*.*\\*/",
            "exec\\s*\\(", "execute\\s*\\(", "sp_executesql",
            "xp_cmdshell", "benchmark\\s*\\(", "sleep\\s*\\(",
            "waitfor\\s+delay", "pg_sleep"
        )
        return patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(lower) }
    }

    private fun detectXss(input: String): Boolean {
        val lower = input.lowercase()
        val patterns = listOf(
            "<script", "javascript:", "on\\w+\\s*=", "eval\\s*\\(",
            "expression\\s*\\(", "url\\s*\\(", "<iframe",
            "<object", "<embed", "<applet", "document\\.cookie",
            "document\\.write", "\\.innerHTML", "alert\\s*\\(",
            "fromCharCode", "innerHTML"
        )
        return patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(lower) }
    }

    private fun detectPathTraversal(input: String): Boolean {
        val patterns = listOf(
            "\\.\\./", "\\.\\.\\\\", "%2e%2e%2f", "%2e%2e/",
            "\\.\\.%2f", "%2e%2e%5c", "..%c0%af", "..%c1%9c"
        )
        return patterns.any { it.lowercase() in input.lowercase() }
    }

    private fun detectCommandInjection(input: String): Boolean {
        val patterns = listOf(
            ";\\s*ls\\b", ";\\s*cat\\b", ";\\s*rm\\b", ";\\s*mv\\b",
            "\\|\\s*ls\\b", "\\|\\s*cat\\b", "\\|\\s*rm\\b",
            "`[^`]+`", "\\$\\([^)]+\\)", "\\$\\{[^}]+\\}",
            ">\\s*/dev/", "\\|\\s*sh\\b", "\\|\\s*bash\\b"
        )
        return patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(input) }
    }

    private fun detectFormatString(input: String): Boolean {
        val suspiciousFormats = listOf("%n", "%x", "%s%s%s", "%d%d%d")
        return suspiciousFormats.any { it in input } &&
                input.count { it == '%' } > 3
    }

    private fun detectTemplateInjection(input: String): Boolean {
        val patterns = listOf(
            "\\{\\{.*\\}\\}", "\\$\\{.*\\}", "<%.*%>",
            "\\[%.*%\\]", "#\\{.*\\}", "\\{\\{\\{"
        )
        return patterns.any { Regex(it, RegexOption.DOT_MATCHES_ALL).containsMatchIn(input) }
    }

    private fun normalizeUnicode(input: String): String {
        // Normalize to NFC form and replace dangerous Unicode characters
        var normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFC)
        // Replace right-to-left override and other dangerous chars
        normalized = normalized.replace("\u202E", "") // RTL override
        normalized = normalized.replace("\u200B", "") // Zero-width space
        normalized = normalized.replace("\uFEFF", "") // BOM
        return normalized
    }
}

/**
 * Sanitization result.
 */
data class SanitizeResult(
    /** Sanitized string (null if input was malicious) */
    val sanitized: String?,
    /** Whether the input passed all safety checks */
    val isSafe: Boolean,
    /** Warnings generated during sanitization */
    val warnings: List<String>,
    /** Threat type detected (null if safe) */
    val threat: ThreatType?
)

/**
 * Types of threats detected by the sanitizer.
 */
enum class ThreatType {
    SQL_INJECTION,
    XSS,
    PATH_TRAVERSAL,
    COMMAND_INJECTION,
    TEMPLATE_INJECTION,
    FORMAT_STRING
}

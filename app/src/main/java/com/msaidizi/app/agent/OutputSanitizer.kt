package com.msaidizi.app.agent

import timber.log.Timber

/**
 * Multi-layer output sanitization for agent responses.
 *
 * Implements defense-in-depth to prevent:
 * - Prompt injection via user-facing output
 * - Information leakage (internal paths, keys, stack traces)
 * - Cross-site scripting (XSS) in web views
 * - Oversized responses causing UI/memory issues
 * - Harmful or inappropriate content
 *
 * Based on OWASP Output Encoding Cheat Sheet and
 * NIST SP 800-53 SI-10 (Information Input Validation).
 */
object OutputSanitizer {

    /** Maximum response length in characters */
    private const val MAX_RESPONSE_LENGTH = 2000

    /** Maximum number of lines in a response */
    private const val MAX_LINES = 50

    // Patterns that indicate internal/system information leakage
    private val LEAKAGE_PATTERNS = listOf(
        Regex("""(?i)(api[_-]?key|secret[_-]?key|token|password|passwd|credential)\s*[=:]\s*\S+"""),
        Regex("""(?i)(/home/\w+|/root/|/var/|C:\\Users|/data/data/)"""),
        Regex("""(?i)(Exception|Error|Traceback|at\s+com\.|at\s+org\.)"""),
        Regex("""(?i)(stacktrace|stack\s*trace|caused\s+by)"""),
        Regex("""(?i)(BEGIN\s+(RSA|EC|OPENSSH)\s+PRIVATE\s+KEY)"""),
        Regex("""(?i)(sk-[a-zA-Z0-9]{20,})"""),  // API key patterns
        Regex("""(?i)(ghp_[a-zA-Z0-9]{36})"""),   // GitHub tokens
    )

    // Patterns that indicate prompt injection attempts in output
    private val INJECTION_PATTERNS = listOf(
        Regex("""(?i)(ignore\s+(previous|above|all)\s+(instructions?|prompts?))"""),
        Regex("""(?i)(you\s+are\s+now\s+(a|an)\s+\w+)"""),
        Regex("""(?i)(system\s*:\s*you\s+are)"""),
        Regex("""(?i)(\bnew\s+instructions?\s*:)"""),
        Regex("""(?i)(jailbreak|DAN\s+mode|developer\s+mode)"""),
        Regex("""(?i)(act\s+as\s+if\s+you\s+are)"""),
    )

    // Dangerous HTML/JS patterns for web view rendering
    private val XSS_PATTERNS = listOf(
        Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""javascript\s*:"""),
        Regex("""on\w+\s*=""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<iframe[^>]*>"""),
        Regex("""<object[^>]*>"""),
        Regex("""<embed[^>]*>"""),
    )

    /**
     * Sanitize an agent response through multiple defense layers.
     *
     * @param text Raw response text from handler
     * @param language Response language for localized error messages
     * @return Sanitized response text
     */
    fun sanitize(text: String, language: String = "sw"): String {
        if (text.isBlank()) {
            return if (language == "sw") "Hakuna jibu." else "No response."
        }

        var result = text

        // Layer 1: Length limiting
        result = enforceLengthLimit(result)

        // Layer 2: Line count limiting
        result = enforceLineLimit(result)

        // Layer 3: Internal information leakage removal
        result = removeInformationLeakage(result)

        // Layer 4: Prompt injection pattern removal from output
        result = removeInjectionPatterns(result)

        // Layer 5: XSS pattern neutralization
        result = neutralizeXss(result)

        // Layer 6: Null byte removal
        result = result.replace("\u0000", "")

        // Layer 7: Control character removal (except newline/tab)
        result = result.filter { it == '\n' || it == '\t' || it == '\r' || !it.isISOControl() }

        // Layer 8: Unicode direction override attack prevention
        result = removeDirectionOverrides(result)

        // Layer 9: Excessive whitespace normalization
        result = normalizeWhitespace(result)

        // Layer 10: Empty response fallback
        if (result.isBlank()) {
            result = if (language == "sw") "Jibu halijapatikana." else "Response unavailable."
        }

        return result
    }

    /**
     * Layer 1: Enforce maximum response length.
     */
    private fun enforceLengthLimit(text: String): String {
        if (text.length <= MAX_RESPONSE_LENGTH) return text
        val truncated = text.take(MAX_RESPONSE_LENGTH)
        // Try to cut at a word boundary
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > MAX_RESPONSE_LENGTH * 0.8) {
            truncated.take(lastSpace) + "…"
        } else {
            truncated + "…"
        }
    }

    /**
     * Layer 2: Enforce maximum line count.
     */
    private fun enforceLineLimit(text: String): String {
        val lines = text.lines()
        if (lines.size <= MAX_LINES) return text
        return lines.take(MAX_LINES).joinToString("\n") + "\n…"
    }

    /**
     * Layer 3: Remove internal information leakage.
     */
    private fun removeInformationLeakage(text: String): String {
        var result = text
        for (pattern in LEAKAGE_PATTERNS) {
            result = pattern.replace(result) { match ->
                Timber.w("Sanitized information leakage: %s", match.value.take(30))
                "[REDACTED]"
            }
        }
        return result
    }

    /**
     * Layer 4: Remove prompt injection patterns from output.
     */
    private fun removeInjectionPatterns(text: String): String {
        var result = text
        for (pattern in INJECTION_PATTERNS) {
            result = pattern.replace(result) { match ->
                Timber.w("Sanitized injection pattern in output: %s", match.value.take(40))
                ""
            }
        }
        return result
    }

    /**
     * Layer 5: Neutralize XSS patterns for web view rendering.
     */
    private fun neutralizeXss(text: String): String {
        var result = text
        for (pattern in XSS_PATTERNS) {
            result = pattern.replace(result) { match ->
                Timber.w("Sanitized XSS pattern: %s", match.value.take(30))
                "[FILTERED]"
            }
        }
        return result
    }

    /**
     * Layer 8: Remove Unicode direction override characters
     * (used in trojan source attacks).
     */
    private fun removeDirectionOverrides(text: String): String {
        // Remove Unicode direction override/embedding/isolate characters
        val directionChars = charArrayOf(
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',  // LRE, RLE, PDF, LRO, RLO
            '\u2066', '\u2067', '\u2068', '\u2069',            // LRI, RLI, FSI, PDI
        )
        return text.filter { it !in directionChars }
    }

    /**
     * Layer 9: Normalize excessive whitespace.
     */
    private fun normalizeWhitespace(text: String): String {
        // Replace 3+ consecutive newlines with 2
        var result = text.replace(Regex("\n{3,}"), "\n\n")
        // Replace 4+ consecutive spaces with 2
        result = result.replace(Regex(" {4,}"), "  ")
        return result.trim()
    }
}

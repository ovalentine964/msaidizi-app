package com.msaidizi.app.security.validation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber

/**
 * API response validation — ensures server responses are safe to process.
 *
 * Per SECURITY_ARCHITECTURE.md OWASP M4:
 * - Validate all API responses before processing
 * - Detect response tampering
 * - Validate data types, ranges, and formats
 * - Prevent prototype pollution / JSON injection
 */
object ApiValidator {

    /**
     * Validate that an API response is well-formed and safe.
     */
    fun validateResponse(responseBody: String?): ValidationResult {
        if (responseBody.isNullOrBlank()) {
            return ValidationResult.Invalid("Empty response body")
        }

        // Check for excessively large responses (potential DoS)
        if (responseBody.length > 1_000_000) {
            return ValidationResult.Invalid("Response exceeds maximum size")
        }

        // Validate JSON structure
        return try {
            val json = JsonParser.parseString(responseBody)
            if (!json.isJsonObject && !json.isJsonArray) {
                return ValidationResult.Invalid("Response is not a valid JSON object or array")
            }
            ValidationResult.Valid
        } catch (e: Throwable) {
            ValidationResult.Invalid("Malformed JSON: ${e.message}")
        }
    }

    /**
     * Validate a numeric field is within expected range.
     */
    fun validateNumericField(
        json: JsonObject,
        field: String,
        min: Double = Double.MIN_VALUE,
        max: Double = Double.MAX_VALUE,
        required: Boolean = true
    ): Boolean {
        if (!json.has(field)) {
            if (required) {
                Timber.w("Missing required field: %s", field)
                return false
            }
            return true
        }

        val element = json.get(field)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            Timber.w("Field '%s' is not a number", field)
            return false
        }

        val value = element.asDouble
        if (value < min || value > max) {
            Timber.w("Field '%s' out of range: %f (expected %f–%f)", field, value, min, max)
            return false
        }

        return true
    }

    /**
     * Validate a string field format.
     */
    fun validateStringField(
        json: JsonObject,
        field: String,
        maxLength: Int = 1000,
        pattern: Regex? = null,
        required: Boolean = true
    ): Boolean {
        if (!json.has(field) || json.get(field).isJsonNull) {
            if (required) {
                Timber.w("Missing required field: %s", field)
                return false
            }
            return true
        }

        val element = json.get(field)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            Timber.w("Field '%s' is not a string", field)
            return false
        }

        val value = element.asString
        if (value.length > maxLength) {
            Timber.w("Field '%s' exceeds max length: %d > %d", field, value.length, maxLength)
            return false
        }

        if (pattern != null && !pattern.matches(value)) {
            Timber.w("Field '%s' doesn't match expected pattern", field)
            return false
        }

        return true
    }

    /**
     * Validate transaction amount — prevent negative, zero, or overflow values.
     */
    fun validateAmount(amount: Double, maxAmount: Double = 1_000_000.0): Boolean {
        return amount > 0 && amount <= maxAmount && !amount.isNaN() && !amount.isInfinite()
    }

    /**
     * Sanitize JSON keys — prevent prototype pollution.
     * Remove __proto__, constructor, prototype keys.
     */
    fun sanitizeJsonKeys(json: JsonObject): JsonObject {
        val sanitized = JsonObject()
        val dangerousKeys = setOf("__proto__", "constructor", "prototype", "__defineGetter__",
            "__defineSetter__", "__lookupGetter__", "__lookupSetter__")

        for (key in json.keySet()) {
            if (key in dangerousKeys) {
                Timber.w("Removed dangerous JSON key: %s", key)
                continue
            }
            val value = json.get(key)
            if (value.isJsonObject) {
                sanitized.add(key, sanitizeJsonKeys(value.asJsonObject))
            } else {
                sanitized.add(key, value)
            }
        }
        return sanitized
    }

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}

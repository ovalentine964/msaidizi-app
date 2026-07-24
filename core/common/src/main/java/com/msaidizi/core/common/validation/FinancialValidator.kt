package com.msaidizi.core.common.validation

/**
 * Validation utilities for financial data.
 * Ensures transaction data is valid before processing and syncing.
 */
object FinancialValidator {

    /**
     * Validate a transaction amount.
     * Returns null if valid, error message if invalid.
     */
    fun validateAmount(amount: Double): String? {
        return when {
            amount < 0 -> "Amount cannot be negative"
            amount == 0.0 -> "Amount cannot be zero"
            amount > 100_000_000 -> "Amount seems too large. Did you mean ${amount / 1_000_000} million?"
            amount != amount -> "Amount is not a valid number" // NaN check
            else -> null
        }
    }

    /**
     * Validate quantity.
     */
    fun validateQuantity(quantity: Double): String? {
        return when {
            quantity < 0 -> "Quantity cannot be negative"
            quantity == 0.0 -> "Quantity cannot be zero"
            quantity > 1_000_000 -> "Quantity seems too large"
            else -> null
        }
    }

    /**
     * Validate unit price.
     */
    fun validateUnitPrice(price: Double): String? {
        return when {
            price < 0 -> "Price cannot be negative"
            price > 10_000_000 -> "Price seems too large"
            else -> null
        }
    }

    /**
     * Validate item name.
     */
    fun validateItemName(name: String): String? {
        return when {
            name.isBlank() -> "Item name cannot be empty"
            name.length > 100 -> "Item name is too long"
            else -> null
        }
    }

    /**
     * Validate phone number (Kenya format).
     * Accepts: 07XXXXXXXX, +254XXXXXXXXX, 254XXXXXXXXX
     */
    fun validatePhoneNumber(phone: String): String? {
        val cleaned = phone.replace(Regex("[\\s-]"), "")
        return when {
            cleaned.isBlank() -> "Phone number cannot be empty"
            cleaned.matches(Regex("^07[0-9]{8}$")) -> null // 07XXXXXXXX
            cleaned.matches(Regex("^\\+254[0-9]{9}$")) -> null // +254XXXXXXXXX
            cleaned.matches(Regex("^254[0-9]{9}$")) -> null // 254XXXXXXXXX
            cleaned.matches(Regex("^01[0-9]{8}$")) -> null // 01XXXXXXXX
            else -> "Invalid phone number format"
        }
    }

    /**
     * Validate M-Pesa transaction code.
     * Format: 10 alphanumeric characters (e.g., "QHK71H3F4P")
     */
    fun validateMpesaCode(code: String): String? {
        val cleaned = code.trim().uppercase()
        return when {
            cleaned.isBlank() -> null // M-Pesa code is optional
            cleaned.matches(Regex("^[A-Z0-9]{10}$")) -> null
            else -> "Invalid M-Pesa code format"
        }
    }

    /**
     * Validate credit amount and due date.
     */
    fun validateCredit(amount: Double, dueDate: Long?): String? {
        val amountError = validateAmount(amount)
        if (amountError != null) return amountError

        if (dueDate != null && dueDate < System.currentTimeMillis()) {
            return "Due date cannot be in the past"
        }

        return null
    }

    /**
     * Validate profit margin (should be between -100% and 100%).
     */
    fun validateMargin(marginPercent: Double): String? {
        return when {
            marginPercent < -1.0 -> "Margin cannot be less than -100%"
            marginPercent > 1.0 -> "Margin cannot be more than 100%"
            else -> null
        }
    }

    /**
     * Validate location coordinates.
     */
    fun validateCoordinates(lat: Double?, lng: Double?): String? {
        if (lat == null && lng == null) return null // Location is optional
        if (lat == null || lng == null) return "Both latitude and longitude are required"
        return when {
            lat < -90 || lat > 90 -> "Latitude must be between -90 and 90"
            lng < -180 || lng > 180 -> "Longitude must be between -180 and 180"
            else -> null
        }
    }
}

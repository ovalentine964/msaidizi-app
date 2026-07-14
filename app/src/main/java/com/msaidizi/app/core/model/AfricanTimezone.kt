package com.msaidizi.app.core.model

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * African timezone support for Msaidizi.
 *
 * Africa spans multiple time zones. Msaidizi workers operate
 * across East Africa (EAT), West Africa (WAT), and Southern Africa (CAT).
 *
 * Business implications:
 * - M-Pesa operates on EAT (UTC+3)
 * - Nigerian banks close at 4pm WAT (UTC+1)
 * - Market days vary by timezone and culture
 * - Morning briefings must respect local time
 */
enum class AfricanTimezone(
    val id: String,
    val zoneId: ZoneId,
    val displayName: String,
    val nameLocal: String,
    val utcOffset: String,
    val countries: List<String>,
    val businessHours: Pair<Int, Int> = 8 to 18,  // Typical business hours (24h)
    val marketDays: List<Int> = listOf(2, 4, 6),  // Tue, Thu, Sat (1=Mon)
    val peakHours: List<Int> = listOf(8, 9, 12, 13, 16, 17)  // Common peak trading hours
) {
    EAT(
        id = "EAT",
        zoneId = ZoneId.of("Africa/Nairobi"),
        displayName = "East Africa Time",
        nameLocal = "Saa za Afrika Mashariki",
        utcOffset = "UTC+3",
        countries = listOf("Kenya", "Tanzania", "Uganda", "Ethiopia", "Somalia", "Eritrea", "Djibouti"),
        businessHours = 8 to 18,
        marketDays = listOf(2, 4, 6),  // Tue, Thu, Sat
        peakHours = listOf(7, 8, 12, 13, 16, 17)
    ),
    WAT(
        id = "WAT",
        zoneId = ZoneId.of("Africa/Lagos"),
        displayName = "West Africa Time",
        nameLocal = "Saa za Afrika Magharibi",
        utcOffset = "UTC+1",
        countries = listOf("Nigeria", "Ghana", "Cameroon", "Senegal", "Mali", "Niger", "Benin", "Togo"),
        businessHours = 8 to 18,
        marketDays = listOf(1, 3, 5, 6),  // Mon, Wed, Fri, Sat (Nigerian markets)
        peakHours = listOf(8, 9, 12, 13, 16, 17)
    ),
    CAT(
        id = "CAT",
        zoneId = ZoneId.of("Africa/Johannesburg"),
        displayName = "Central Africa Time",
        nameLocal = "Saa za Afrika Kati",
        utcOffset = "UTC+2",
        countries = listOf("South Africa", "Zimbabwe", "Zambia", "Mozambique", "Malawi", "Botswana"),
        businessHours = 8 to 17,
        marketDays = listOf(2, 4, 6),  // Tue, Thu, Sat
        peakHours = listOf(8, 9, 12, 13, 15, 16)
    );

    /**
     * Get current local time in this timezone.
     */
    fun now(): ZonedDateTime = ZonedDateTime.now(zoneId)

    /**
     * Format current time for display.
     */
    fun formatNow(pattern: String = "HH:mm"): String {
        return now().format(DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * Format current date for display.
     */
    fun formatToday(pattern: String = "dd MMM yyyy"): String {
        return now().format(DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * Check if current time is within business hours.
     */
    fun isBusinessHours(): Boolean {
        val hour = now().hour
        return hour in businessHours.first until businessHours.second
    }

    /**
     * Check if today is a market day.
     */
    fun isMarketDay(): Boolean {
        return now().dayOfWeek.value in marketDays
    }

    /**
     * Check if current hour is a peak trading hour.
     */
    fun isPeakHour(): Boolean {
        return now().hour in peakHours
    }

    /**
     * Get a greeting appropriate for the current time.
     */
    fun getGreeting(language: String = "sw"): String {
        val hour = now().hour
        return when (language) {
            "sw" -> when {
                hour < 12 -> "Habari za asubuhi"
                hour < 17 -> "Habari za mchana"
                else -> "Habari za jioni"
            }
            "yo" -> when {
                hour < 12 -> "Ẹ kú àárọ̀"
                hour < 17 -> "Ẹ kú ọ̀sán"
                else -> "Ẹ kú irọ́lẹ́"
            }
            "ha" -> when {
                hour < 12 -> "Barka da safe"
                hour < 17 -> "Barka da rana"
                else -> "Barka da yamma"
            }
            "en" -> when {
                hour < 12 -> "Good morning"
                hour < 17 -> "Good afternoon"
                else -> "Good evening"
            }
            else -> when {
                hour < 12 -> "Habari za asubuhi"
                hour < 17 -> "Habari za mchana"
                else -> "Habari za jioni"
            }
        }
    }

    /**
     * Get time until business opens (if currently closed).
     */
    fun minutesUntilOpen(): Int? {
        if (isBusinessHours()) return null
        val hour = now().hour
        val openHour = businessHours.first
        return if (hour < openHour) {
            (openHour - hour) * 60 - now().minute
        } else {
            // Next day
            (24 - hour + openHour) * 60 - now().minute
        }
    }

    companion object {
        /**
         * Detect timezone from country name.
         */
        fun forCountry(country: String): AfricanTimezone {
            return entries.find { tz ->
                tz.countries.any { it.equals(country, ignoreCase = true) }
            } ?: EAT  // Default to EAT (Kenya is primary market)
        }

        /**
         * Detect timezone from ISO timezone ID.
         */
        fun fromZoneId(zoneId: String): AfricanTimezone? {
            return entries.find { it.zoneId.id == zoneId }
        }

        /**
         * Get the timezone for the device's current location.
         * Falls back to EAT if the device is in an African timezone.
         */
        fun detect(): AfricanTimezone {
            val deviceZone = ZoneId.systemDefault().id
            return fromZoneId(deviceZone)
                ?: if (deviceZone.startsWith("Africa/")) {
                    // Device is in Africa but not in our list — try country match
                    val city = deviceZone.substringAfter("Africa/")
                    entries.find { tz ->
                        tz.countries.any { it.contains(city, ignoreCase = true) }
                    } ?: EAT
                } else {
                    EAT  // Default for non-African devices (diaspora users)
                }
        }
    }
}

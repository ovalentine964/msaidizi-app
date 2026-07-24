package com.msaidizi.core.common.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date and time utilities for transaction processing.
 * Handles temporal data extraction from timestamps.
 */
object DateUtils {

    /**
     * Time of day classification.
     * "morning" (6-12), "afternoon" (12-17), "evening" (17-21), "night" (21-6)
     */
    fun getTimeOfDay(timestamp: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return when (calendar.get(Calendar.HOUR_OF_DAY)) {
            in 6..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }

    /**
     * Day of week (1=Monday, 7=Sunday).
     */
    fun getDayOfWeek(timestamp: Long = System.currentTimeMillis()): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /**
     * Whether the timestamp falls on a weekend.
     */
    fun isWeekend(timestamp: Long = System.currentTimeMillis()): Boolean {
        val dow = getDayOfWeek(timestamp)
        return dow == 6 || dow == 7 // Saturday or Sunday
    }

    /**
     * Month (1-12).
     */
    fun getMonth(timestamp: Long = System.currentTimeMillis()): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.MONTH) + 1
    }

    /**
     * Days between two timestamps.
     */
    fun daysBetween(start: Long, end: Long): Int {
        val diffMs = end - start
        return (diffMs / (1000 * 60 * 60 * 24)).toInt()
    }

    /**
     * Start of day (midnight) for a given timestamp.
     */
    fun startOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Format timestamp as "HH:mm" (e.g., "14:30").
     */
    fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Format timestamp as "dd/MM/yyyy" (e.g., "24/07/2026").
     */
    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Format timestamp as "dd MMM yyyy" (e.g., "24 Jul 2026").
     */
    fun formatDateShort(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Get day name in Swahili.
     */
    fun getDayNameSwahili(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            1 -> "Jumatatu"
            2 -> "Jumanne"
            3 -> "Jumatano"
            4 -> "Alhamisi"
            5 -> "Ijumaa"
            6 -> "Jumamosi"
            7 -> "Jumapili"
            else -> "Siku"
        }
    }

    /**
     * Get month name in Swahili.
     */
    fun getMonthNameSwahili(month: Int): String {
        return when (month) {
            1 -> "Januari"
            2 -> "Februari"
            3 -> "Machi"
            4 -> "Aprili"
            5 -> "Mei"
            6 -> "Juni"
            7 -> "Julai"
            8 -> "Agosti"
            9 -> "Septemba"
            10 -> "Oktoba"
            11 -> "Novemba"
            12 -> "Desemba"
            else -> "Mwezi"
        }
    }
}

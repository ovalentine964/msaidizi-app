package com.msaidizi.app.core.util

import java.text.SimpleDateFormat
import java.util.*

object DateTimeUtil {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("h:mm a", Locale.US)

    fun today(): String = dateFormat.format(Date())

    fun now(): Long = System.currentTimeMillis()

    fun startOfDay(timestamp: Long = now()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun endOfDay(timestamp: Long = now()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }

    fun startOfWeek(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun startOfMonth(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun formatDate(timestamp: Long): String = displayDateFormat.format(Date(timestamp))

    fun formatTime(timestamp: Long): String = displayTimeFormat.format(Date(timestamp))

    fun formatDateTime(timestamp: Long): String = dateTimeFormat.format(Date(timestamp))

    fun formatCurrency(amount: Double, currency: String = "KES"): String {
        return when (currency) {
            "KES" -> "Ksh ${"%,.0f".format(amount)}"
            "USD" -> "$${"%,.2f".format(amount)}"
            "TZS" -> "TSh ${"%,.0f".format(amount)}"
            "UGX" -> "USh ${"%,.0f".format(amount)}"
            else -> "$currency ${"%,.2f".format(amount)}"
        }
    }

    /**
     * Get time-based greeting in Kiswahili.
     */
    fun getGreeting(language: String = "sw"): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (language) {
            "sw" -> when {
                hour < 12 -> "Habari za asubuhi"
                hour < 17 -> "Habari za mchana"
                else -> "Habari za jioni"
            }
            else -> when {
                hour < 12 -> "Good morning"
                hour < 17 -> "Good afternoon"
                else -> "Good evening"
            }
        }
    }
}

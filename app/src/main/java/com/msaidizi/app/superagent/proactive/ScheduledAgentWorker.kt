package com.msaidizi.app.superagent.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.SuperagentHarness
import com.msaidizi.app.superagent.harness.UserIntent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * ScheduledAgentWorker — Runs the Superagent proactively via WorkManager.
 *
 * Supports three scheduling modes:
 *   1. DAILY_CFO_BRIEFING: Runs at user-configured time (default 7 AM)
 *      → Generates cash flow summary, overdue debts, stock alerts
 *   2. WEEKLY_SUMMARY: Runs every Sunday evening (default 6 PM)
 *      → Full week performance: revenue, expenses, profit trends
 *   3. MONTHLY_REVIEW: Runs on 1st of each month (default 8 AM)
 *      → Deep business review: MoM growth, top products, credit readiness
 *
 * Uses existing IntentRouter → OODA Loop pipeline to generate responses.
 * Results are posted via ProactiveNotificationManager.
 *
 * Design:
 * - One-off workers scheduled via enqueueUniqueWork (not periodic, to allow
 *   user-configurable times — reschedule after each run)
 * - Expedited for daily briefings (user expects morning notification)
 * - Constraints: battery > 15%, any connectivity
 */
@HiltWorker
class ScheduledAgentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val harness: SuperagentHarness,
    private val notificationManager: ProactiveNotificationManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduleType = inputData.getString(KEY_SCHEDULE_TYPE)
            ?: return Result.failure()

        Timber.i("ScheduledAgentWorker: Running $scheduleType")

        return try {
            val prompt = buildPrompt(scheduleType)
            val response = harness.processInput(prompt, isVoice = false)

            // Post notification with the briefing
            notificationManager.postBriefing(
                type = scheduleType,
                title = getNotificationTitle(scheduleType),
                body = response.text
            )

            // Reschedule for next occurrence
            reschedule(scheduleType)

            Timber.i("ScheduledAgentWorker: $scheduleType completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ScheduledAgentWorker: $scheduleType failed")
            // Reschedule even on failure (don't miss next briefing)
            reschedule(scheduleType)
            Result.retry()
        }
    }

    /**
     * Build the proactive prompt that triggers the agent's OODA loop.
     * The IntentRouter will classify these as DAILY_REPORT / WEEKLY_REPORT / MONTHLY_REPORT.
     */
    private fun buildPrompt(scheduleType: String): String {
        return when (scheduleType) {
            TYPE_DAILY_CFO -> {
                "Generate my daily CFO briefing. Include: " +
                "1) Yesterday's total sales and expenses, " +
                "2) Current cash position estimate, " +
                "3) Any overdue customer debts, " +
                "4) Low stock alerts, " +
                "5) Top actionable recommendation for today."
            }
            TYPE_WEEKLY_SUMMARY -> {
                "Generate my weekly business summary. Include: " +
                "1) Total revenue this week vs last week, " +
                "2) Total expenses and profit margin, " +
                "3) Best and worst performing products, " +
                "4) Customer payment trends, " +
                "5) Key recommendations for next week."
            }
            TYPE_MONTHLY_REVIEW -> {
                "Generate my monthly business review. Include: " +
                "1) Monthly revenue, expenses, and net profit, " +
                "2) Month-over-month growth comparison, " +
                "3) Top 5 products by revenue, " +
                "4) Inventory turnover analysis, " +
                "5) Credit readiness assessment, " +
                "6) Strategic recommendations for next month."
            }
            else -> "Generate a business summary report."
        }
    }

    private fun getNotificationTitle(scheduleType: String): String {
        return when (scheduleType) {
            TYPE_DAILY_CFO -> "☀️ Habari! Your Daily Briefing"
            TYPE_WEEKLY_SUMMARY -> "📊 Weekly Business Summary"
            TYPE_MONTHLY_REVIEW -> "📈 Monthly Business Review"
            else -> "📋 Business Report"
        }
    }

    /**
     * Reschedule for the next occurrence.
     * Uses one-off WorkManager requests (not periodic) so times are user-configurable.
     */
    private fun reschedule(scheduleType: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val delayMs = calculateDelay(scheduleType, prefs)

        val data = workDataOf(KEY_SCHEDULE_TYPE to scheduleType)

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_PREFIX + scheduleType)
            .build()

        androidx.work.WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                WORK_NAME_PREFIX + scheduleType,
                ExistingWorkPolicy.REPLACE,
                request
            )

        Timber.d("ScheduledAgentWorker: Rescheduled $scheduleType in ${delayMs / 1000 / 60} minutes")
    }

    companion object {
        const val KEY_SCHEDULE_TYPE = "schedule_type"
        const val TYPE_DAILY_CFO = "daily_cfo"
        const val TYPE_WEEKLY_SUMMARY = "weekly_summary"
        const val TYPE_MONTHLY_REVIEW = "monthly_review"
        const val TAG_PREFIX = "scheduled_agent_"
        const val WORK_NAME_PREFIX = "scheduled_agent_work_"
        const val PREFS_NAME = "scheduled_agent_prefs"

        // Default times (hour of day in 24h format)
        const val DEFAULT_DAILY_HOUR = 7
        const val DEFAULT_WEEKLY_HOUR = 18
        const val DEFAULT_MONTHLY_HOUR = 8

        /**
         * Schedule all proactive agent runs.
         * Called from app startup or when user changes preferences.
         */
        fun scheduleAll(context: Context) {
            scheduleDaily(context, DEFAULT_DAILY_HOUR)
            scheduleWeekly(context, DEFAULT_WEEKLY_HOUR)
            scheduleMonthly(context, DEFAULT_MONTHLY_HOUR)
        }

        fun scheduleDaily(context: Context, hourOfDay: Int) {
            enqueueScheduled(context, TYPE_DAILY_CFO, hourOfDay, 0)
        }

        fun scheduleWeekly(context: Context, hourOfDay: Int) {
            enqueueScheduled(context, TYPE_WEEKLY_SUMMARY, hourOfDay, 0)
        }

        fun scheduleMonthly(context: Context, hourOfDay: Int) {
            enqueueScheduled(context, TYPE_MONTHLY_REVIEW, hourOfDay, 0)
        }

        /**
         * Cancel a specific scheduled briefing.
         */
        fun cancel(context: Context, scheduleType: String) {
            androidx.work.WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME_PREFIX + scheduleType)
            Timber.i("ScheduledAgentWorker: Cancelled $scheduleType")
        }

        /**
         * Cancel all scheduled briefings.
         */
        fun cancelAll(context: Context) {
            androidx.work.WorkManager.getInstance(context)
                .cancelAllWorkByTag(TAG_PREFIX)
            Timber.i("ScheduledAgentWorker: Cancelled all scheduled briefings")
        }

        private fun enqueueScheduled(
            context: Context,
            scheduleType: String,
            hourOfDay: Int,
            minuteOfHour: Int
        ) {
            val delayMs = calculateDelay(scheduleType, hourOfDay, minuteOfHour)
            val data = workDataOf(KEY_SCHEDULE_TYPE to scheduleType)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(TAG_PREFIX + scheduleType)
                .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_PREFIX + scheduleType,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            Timber.i("ScheduledAgentWorker: Scheduled $scheduleType in ${delayMs / 1000 / 60} minutes")
        }
    }
}

/**
 * Calculate milliseconds until the next occurrence of a schedule type.
 */
internal fun calculateDelay(scheduleType: String, prefs: android.content.SharedPreferences): Long {
    val hour = prefs.getInt("${scheduleType}_hour", when (scheduleType) {
        ScheduledAgentWorker.TYPE_DAILY_CFO -> ScheduledAgentWorker.DEFAULT_DAILY_HOUR
        ScheduledAgentWorker.TYPE_WEEKLY_SUMMARY -> ScheduledAgentWorker.DEFAULT_WEEKLY_HOUR
        ScheduledAgentWorker.TYPE_MONTHLY_REVIEW -> ScheduledAgentWorker.DEFAULT_MONTHLY_HOUR
        else -> 7
    })
    val minute = prefs.getInt("${scheduleType}_minute", 0)
    return calculateDelay(scheduleType, hour, minute)
}

internal fun calculateDelay(scheduleType: String, hourOfDay: Int, minuteOfHour: Int): Long {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hourOfDay)
        set(Calendar.MINUTE, minuteOfHour)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    when (scheduleType) {
        ScheduledAgentWorker.TYPE_WEEKLY_SUMMARY -> {
            // Next Sunday
            while (target.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        ScheduledAgentWorker.TYPE_MONTHLY_REVIEW -> {
            // 1st of next month
            target.set(Calendar.DAY_OF_MONTH, 1)
            if (target.before(now)) {
                target.add(Calendar.MONTH, 1)
            }
        }
    }

    // If target is in the past, add 1 day (for daily) or handle per type
    if (target.before(now)) {
        when (scheduleType) {
            ScheduledAgentWorker.TYPE_DAILY_CFO -> target.add(Calendar.DAY_OF_MONTH, 1)
            ScheduledAgentWorker.TYPE_WEEKLY_SUMMARY -> target.add(Calendar.WEEK_OF_YEAR, 1)
            ScheduledAgentWorker.TYPE_MONTHLY_REVIEW -> target.add(Calendar.MONTH, 1)
        }
    }

    return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0)
}

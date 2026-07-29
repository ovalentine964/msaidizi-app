package com.msaidizi.agent.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
// App launch intent resolved dynamically — no direct app dependency needed
import com.msaidizi.agent.council.CouncilEvent
import com.msaidizi.agent.council.CouncilEventBus
import com.msaidizi.agent.council.CouncilEventType
import com.msaidizi.agent.council.CouncilType
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProactiveNotificationManager — Push notifications for proactive agent alerts.
 *
 * Notification types:
 *   1. BRIEFING: Daily/weekly/monthly scheduled reports
 *   2. CASH_FLOW_SHORTFALL: Cash position below threshold
 *   3. INVENTORY_REORDER: Stock below minimum level
 *   4. MARKET_PRICE_CHANGE: Significant market price movement
 *   5. PAYMENT_RECEIVED: Customer payment recorded
 *   6. ANOMALY: Unusual business pattern detected
 *
 * Integration:
 *   - Subscribes to CouncilEventBus for real-time alerts from OODA loop
 *   - Respects user notification preferences (per-type toggles)
 *   - Groups related notifications to avoid spam
 *   - Uses Android notification channels for user control
 */
@Singleton
class ProactiveNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: CouncilEventBus
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createNotificationChannels()
        subscribeToEvents()
    }

    // ═══════════════════════════════════════════════════════════
    //  NOTIFICATION CHANNELS
    // ═══════════════════════════════════════════════════════════

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            val briefingChannel = NotificationChannel(
                CHANNEL_BRIEFING,
                "Business Briefings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily, weekly, and monthly business reports"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Business Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cash flow, inventory, and market alerts"
            }

            val paymentChannel = NotificationChannel(
                CHANNEL_PAYMENTS,
                "Payment Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Customer payment confirmations"
            }

            manager.createNotificationChannels(
                listOf(briefingChannel, alertChannel, paymentChannel)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  EVENT SUBSCRIPTIONS (CouncilEventBus → Notifications)
    // ═══════════════════════════════════════════════════════════

    private fun subscribeToEvents() {
        // Cash flow alerts
        eventBus.subscribe(CouncilEventType.CASH_FLOW_UPDATED) { event ->
            val shortfall = event.payload["shortfall"] as? Double ?: return@subscribe
            if (shortfall > 0 && isEnabled(PREF_CASH_FLOW_ALERTS)) {
                postAlert(
                    type = TYPE_CASH_FLOW,
                    title = "⚠️ Cash Flow Alert",
                    body = "Estimated shortfall of KES ${"%,.0f".format(shortfall)} expected. " +
                           "Consider collecting outstanding debts.",
                    priority = NotificationCompat.PRIORITY_HIGH
                )
            }
        }

        // Low stock alerts
        eventBus.subscribe(CouncilEventType.STOCK_LOW) { event ->
            if (!isEnabled(PREF_INVENTORY_ALERTS)) return@subscribe
            val productName = event.payload["productName"] as? String ?: "Unknown"
            val currentStock = event.payload["currentStock"] as? Double ?: 0.0
            val minStock = event.payload["minStock"] as? Double ?: 0.0

            postAlert(
                type = TYPE_INVENTORY,
                title = "📦 Low Stock: $productName",
                body = "Only ${currentStock.toInt()} remaining (min: ${minStock.toInt()}). " +
                       "Time to reorder!",
                priority = NotificationCompat.PRIORITY_DEFAULT
            )
        }

        // Market price changes
        eventBus.subscribe(CouncilEventType.PRICE_CHANGED) { event ->
            if (!isEnabled(PREF_MARKET_ALERTS)) return@subscribe
            val product = event.payload["product"] as? String ?: return@subscribe
            val changePct = event.payload["changePct"] as? Double ?: return@subscribe

            // Only alert for significant changes (>10%)
            if (kotlin.math.abs(changePct) < 10.0) return@subscribe

            val direction = if (changePct > 0) "📈 up" else "📉 down"
            postAlert(
                type = TYPE_MARKET,
                title = "Market Price Change",
                body = "$product prices $direction ${kotlin.math.abs(changePct).toInt()}%. " +
                       if (changePct < 0) "Good time to stock up!" else "Consider adjusting prices.",
                priority = NotificationCompat.PRIORITY_DEFAULT
            )
        }

        // Payment received
        eventBus.subscribe(CouncilEventType.PAYMENT_RECEIVED) { event ->
            if (!isEnabled(PREF_PAYMENT_ALERTS)) return@subscribe
            val amount = event.payload["amount"] as? Double ?: return@subscribe
            val customer = event.payload["customerName"] as? String ?: "Customer"
            val method = event.payload["paymentMethod"] as? String ?: "cash"

            postAlert(
                type = TYPE_PAYMENT,
                title = "💰 Payment Received",
                body = "KES ${"%,.0f".format(amount)} from $customer via $method",
                priority = NotificationCompat.PRIORITY_DEFAULT
            )
        }

        // Anomaly detection
        eventBus.subscribe(CouncilEventType.ANOMALY_DETECTED) { event ->
            if (!isEnabled(PREF_ANOMALY_ALERTS)) return@subscribe
            val description = event.payload["description"] as? String ?: "Unusual pattern detected"
            val severity = event.payload["severity"] as? String ?: "medium"

            val emoji = when (severity) {
                "critical" -> "🚨"
                "high" -> "⚠️"
                else -> "ℹ️"
            }

            postAlert(
                type = TYPE_ANOMALY,
                title = "$emoji Business Alert",
                body = description,
                priority = if (severity == "critical") NotificationCompat.PRIORITY_HIGH
                           else NotificationCompat.PRIORITY_DEFAULT
            )
        }

        Timber.i("ProactiveNotificationManager: Event subscriptions registered")
    }

    // ═══════════════════════════════════════════════════════════
    //  NOTIFICATION POSTING
    // ═══════════════════════════════════════════════════════════

    /**
     * Post a scheduled briefing notification (daily/weekly/monthly report).
     */
    fun postBriefing(type: String, title: String, body: String) {
        if (!isEnabled(PREF_BRIEFING_ALERTS)) return

        val channelId = CHANNEL_BRIEFING
        val notificationId = BRIEFING_NOTIFICATION_BASE_ID + type.hashCode() and 0x7FFFFFFF

        postNotification(
            id = notificationId,
            channelId = channelId,
            title = title,
            body = body,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = GROUP_BRIEFING
        )
    }

    /**
     * Post a proactive alert notification.
     */
    fun postAlert(type: String, title: String, body: String, priority: Int) {
        val channelId = when (type) {
            TYPE_PAYMENT -> CHANNEL_PAYMENTS
            else -> CHANNEL_ALERTS
        }
        val notificationId = ALERT_NOTIFICATION_BASE_ID + type.hashCode() and 0x7FFFFFFF

        postNotification(
            id = notificationId,
            channelId = channelId,
            title = title,
            body = body,
            priority = priority,
            group = GROUP_ALERTS
        )
    }

    private fun postNotification(
        id: Int,
        channelId: String,
        title: String,
        body: String,
        priority: Int,
        group: String? = null
    ) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: return
            val pendingIntent = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            group?.let { builder.setGroup(it) }

            NotificationManagerCompat.from(context).notify(id, builder.build())
            Timber.d("Notification posted: $title")
        } catch (e: SecurityException) {
            Timber.w(e, "Notification permission not granted")
        } catch (e: Exception) {
            Timber.e(e, "Failed to post notification")
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  USER PREFERENCES
    // ═══════════════════════════════════════════════════════════

    fun isEnabled(prefKey: String): Boolean {
        return prefs.getBoolean(prefKey, true) // Default: all enabled
    }

    fun setEnabled(prefKey: String, enabled: Boolean) {
        prefs.edit().putBoolean(prefKey, enabled).apply()
        Timber.d("Notification pref $prefKey = $enabled")
    }

    /**
     * Get all notification preference states for settings UI.
     */
    fun getPreferences(): Map<String, Boolean> {
        return mapOf(
            PREF_BRIEFING_ALERTS to isEnabled(PREF_BRIEFING_ALERTS),
            PREF_CASH_FLOW_ALERTS to isEnabled(PREF_CASH_FLOW_ALERTS),
            PREF_INVENTORY_ALERTS to isEnabled(PREF_INVENTORY_ALERTS),
            PREF_MARKET_ALERTS to isEnabled(PREF_MARKET_ALERTS),
            PREF_PAYMENT_ALERTS to isEnabled(PREF_PAYMENT_ALERTS),
            PREF_ANOMALY_ALERTS to isEnabled(PREF_ANOMALY_ALERTS)
        )
    }

    companion object {
        const val PREFS_NAME = "notification_prefs"

        // Channel IDs
        const val CHANNEL_BRIEFING = "msaidizi_briefing"
        const val CHANNEL_ALERTS = "msaidizi_alerts"
        const val CHANNEL_PAYMENTS = "msaidizi_payments"

        // Notification groups
        const val GROUP_BRIEFING = "msaidizi_group_briefing"
        const val GROUP_ALERTS = "msaidizi_group_alerts"

        // Notification type keys
        const val TYPE_CASH_FLOW = "cash_flow"
        const val TYPE_INVENTORY = "inventory"
        const val TYPE_MARKET = "market"
        const val TYPE_PAYMENT = "payment"
        const val TYPE_ANOMALY = "anomaly"

        // Preference keys
        const val PREF_BRIEFING_ALERTS = "pref_briefing_alerts"
        const val PREF_CASH_FLOW_ALERTS = "pref_cash_flow_alerts"
        const val PREF_INVENTORY_ALERTS = "pref_inventory_alerts"
        const val PREF_MARKET_ALERTS = "pref_market_alerts"
        const val PREF_PAYMENT_ALERTS = "pref_payment_alerts"
        const val PREF_ANOMALY_ALERTS = "pref_anomaly_alerts"

        // Notification IDs
        const val BRIEFING_NOTIFICATION_BASE_ID = 10000
        const val ALERT_NOTIFICATION_BASE_ID = 20000
    }
}

package com.msaidizi.app.superagent.communication

import timber.log.Timber

/**
 * Alert Voice — voice-based proactive alerts.
 *
 * ## Alert Types
 * - **STOCK_LOW** — Inventory running low
 * - **PROFIT_DROP** — Profit below average
 * - **STREAK_RISK** — Streak about to break
 * - **PRICE_CHANGE** — Market price significant change
 * - **SAVINGS_MILESTONE** — Savings goal reached
 * - **CREDIT_READY** — Credit readiness assessment available
 *
 * ## Design
 * Alerts are voice-first, short (5-15 seconds), and actionable.
 * Each alert includes what happened, what it means, and what to do.
 * Never spammy — alerts have cooldown periods and priority filtering.
 */
class AlertVoice(
    private val voicePersonality: VoicePersonality
) {
    companion object {
        private const val TAG = "AlertVoice"
    }

    /**
     * Generate a voice alert for the worker.
     *
     * @param alert The alert to voice
     * @param language "sw" or "en"
     * @return Voice-ready alert text
     */
    fun generateAlert(alert: Alert, language: String = "sw"): String {
        val baseText = when (alert.type) {
            AlertType.STOCK_LOW -> generateStockLowAlert(alert, language)
            AlertType.PROFIT_DROP -> generateProfitDropAlert(alert, language)
            AlertType.STREAK_RISK -> generateStreakRiskAlert(alert, language)
            AlertType.PRICE_CHANGE -> generatePriceChangeAlert(alert, language)
            AlertType.SAVINGS_MILESTONE -> generateSavingsMilestoneAlert(alert, language)
            AlertType.CREDIT_READY -> generateCreditReadyAlert(alert, language)
            AlertType.DEBT_DUE -> generateDebtDueAlert(alert, language)
            AlertType.GOAL_PROGRESS -> generateGoalProgressAlert(alert, language)
        }

        return voicePersonality.wrapResponse(baseText, ResponseType.INFORMATION, language = language)
    }

    /**
     * Check if an alert should be delivered based on priority and cooldown.
     */
    fun shouldDeliver(alert: Alert, lastAlertTime: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        val cooldownSeconds = when (alert.priority) {
            AlertPriority.HIGH -> 300      // 5 minutes
            AlertPriority.MEDIUM -> 1800   // 30 minutes
            AlertPriority.LOW -> 3600      // 1 hour
        }
        return (now - lastAlertTime) > cooldownSeconds
    }

    // ═══════════════ ALERT GENERATORS ═══════════════

    private fun generateStockLowAlert(alert: Alert, language: String): String {
        val item = alert.data["item"] ?: "bidhaa"
        val daysLeft = alert.data["daysLeft"] ?: "2"

        return if (language == "sw") {
            "⚠️ $item inakaribia kuisha! Bado siku $daysLeft tu. Nunua leo usikose wateja!"
        } else {
            "⚠️ $item is running low! Only $daysLeft days left. Buy today to avoid losing customers!"
        }
    }

    private fun generateProfitDropAlert(alert: Alert, language: String): String {
        val percent = alert.data["percent"] ?: "30"

        return if (language == "sw") {
            "📉 Faida yako imepungua $percent% kuliko kawaida. Angalia bei zako na gharama zako."
        } else {
            "📉 Your profit dropped $percent% below average. Check your prices and costs."
        }
    }

    private fun generateStreakRiskAlert(alert: Alert, language: String): String {
        val streak = alert.data["streak"] ?: "7"

        return if (language == "sw") {
            "🔥 Streak yako ya siku $streak inaweza kupotea! Rekodi mauzo yako leo!"
        } else {
            "🔥 Your $streak-day streak is at risk! Record your sales today!"
        }
    }

    private fun generatePriceChangeAlert(alert: Alert, language: String): String {
        val item = alert.data["item"] ?: "bidhaa"
        val direction = alert.data["direction"] ?: "up"
        val percent = alert.data["percent"] ?: "15"

        return if (language == "sw") {
            if (direction == "up") {
                "📈 Bei ya $item imepanda $percent%! Fursa ya faida zaidi!"
            } else {
                "📉 Bei ya $item imepungua $percent%. Adjust bei yako!"
            }
        } else {
            if (direction == "up") {
                "📈 $item price went up $percent%! Opportunity for more profit!"
            } else {
                "📉 $item price dropped $percent%. Adjust your prices!"
            }
        }
    }

    private fun generateSavingsMilestoneAlert(alert: Alert, language: String): String {
        val amount = alert.data["amount"] ?: "1000"

        return if (language == "sw") {
            "🏦 Hongera! Umefikia akiba ya KSh $amount! Endelea hivi — lengo lako linakaribia!"
        } else {
            "🏦 Congratulations! You've saved KSh $amount! Keep going — your goal is within reach!"
        }
    }

    private fun generateCreditReadyAlert(alert: Alert, language: String): String {
        return if (language == "sw") {
            "⭐ Alama yako ya mkopo imeboreshwa! Sasa unastahili huduma zaidi. Angalia taarifa zako."
        } else {
            "⭐ Your credit score improved! You now qualify for more services. Check your details."
        }
    }

    private fun generateDebtDueAlert(alert: Alert, language: String): String {
        val amount = alert.data["amount"] ?: "0"
        val daysLeft = alert.data["daysLeft"] ?: "3"

        return if (language == "sw") {
            "💳 Deni lako la KSh $amount linafaa kulipwa baada ya siku $daysLeft. Lipa mapema!"
        } else {
            "💳 Your debt of KSh $amount is due in $daysLeft days. Pay early!"
        }
    }

    private fun generateGoalProgressAlert(alert: Alert, language: String): String {
        val goalName = alert.data["goalName"] ?: "lengo"
        val percent = alert.data["percent"] ?: "75"

        return if (language == "sw") {
            "🎯 $goalName: umefikia $percent%! Karibu sana! Endelea!"
        } else {
            "🎯 $goalName: you've reached $percent%! Almost there! Keep going!"
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Alert types.
 */
enum class AlertType {
    STOCK_LOW, PROFIT_DROP, STREAK_RISK, PRICE_CHANGE,
    SAVINGS_MILESTONE, CREDIT_READY, DEBT_DUE, GOAL_PROGRESS
}

/**
 * Alert priority levels.
 */
enum class AlertPriority {
    HIGH, MEDIUM, LOW
}

/**
 * An alert to be voiced.
 */
data class Alert(
    val type: AlertType,
    val priority: AlertPriority = AlertPriority.MEDIUM,
    val data: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis() / 1000
)

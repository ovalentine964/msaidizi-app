package com.msaidizi.app.agent.proactive

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.PatternType
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.core.validation.AnomalyDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Proactive Alert Engine — monitors business health and fires alerts.
 *
 * Wires together existing intelligence components into a proactive
 * monitoring pipeline that surfaces issues before the worker asks:
 *
 * ┌───────────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ BusinessPattern   │────▶│ ProactiveAlert   │────▶│  AgentEvent  │
 * │ Tracker           │     │ Engine           │     │  Bus         │
 * │ AnomalyDetector   │     │ (monitoring loop)│     │  (alerts)    │
 * └───────────────────┘     └──────────────────┘     └──────────────┘
 *                                   │
 *                                   ▼
 *                           ┌──────────────┐
 *                           │ Notification │
 *                           │ System       │
 *                           └──────────────┘
 *
 * ## Alert Types
 * 1. **Price Anomaly** — Significant price deviation from tracked average
 * 2. **Cash Flow Warning** — Expenses exceeding income trajectory
 * 3. **Sales Drop** — Sudden decline in sales volume/rate
 * 4. **Fraud Detection** — Unusual transaction patterns
 * 5. **Stock Alert** — Best-selling items running low
 * 6. **Trend Shift** — Business trend direction changed
 *
 * @param patternTracker Business pattern analysis
 * @param anomalyDetector Transaction anomaly detection
 * @param transactionDao Direct transaction queries
 * @param eventBus Event bus for publishing alerts
 */
class ProactiveAlertEngine(
    private val patternTracker: BusinessPatternTracker,
    private val anomalyDetector: AnomalyDetector,
    private val transactionDao: TransactionDao,
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProactiveAlertEngine"

        // Alert thresholds
        private const val PRICE_DEVIATION_THRESHOLD = 0.25    // 25% from average
        private const val SALES_DROP_THRESHOLD = 0.40          // 40% below recent average
        private const val CASH_FLOW_DANGER_DAYS = 3            // 3+ consecutive negative days
        private const val MONITORING_INTERVAL_MS = 30 * 60 * 1000L  // 30 minutes

        // Alert cooldowns (avoid spam)
        private val ALERT_COOLDOWNS = mapOf(
            AlertType.PRICE_ANOMALY to 4 * 60 * 60 * 1000L,    // 4 hours
            AlertType.CASH_FLOW_WARNING to 12 * 60 * 60 * 1000L, // 12 hours
            AlertType.SALES_DROP to 6 * 60 * 60 * 1000L,        // 6 hours
            AlertType.FRAUD_DETECTION to 1 * 60 * 60 * 1000L,   // 1 hour
            AlertType.STOCK_ALERT to 24 * 60 * 60 * 1000L,      // 24 hours
            AlertType.TREND_SHIFT to 24 * 60 * 60 * 1000L       // 24 hours
        )
    }

    /** Last alert timestamps for cooldown */
    private val lastAlertTimes = mutableMapOf<AlertType, Long>()

    /** Alert stream for UI subscription */
    private val _alerts = MutableSharedFlow<ProactiveAlertData>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val alerts: SharedFlow<ProactiveAlertData> = _alerts.asSharedFlow()

    /** Whether the monitoring loop is running */
    @Volatile
    private var isMonitoring = false

    // ═══════════════ MONITORING LIFECYCLE ═══════════════

    /**
     * Start the proactive monitoring loop.
     * Runs periodically to check for alert conditions.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        scope.launch {
            Timber.d("Proactive alert monitoring started (interval=%dms)", MONITORING_INTERVAL_MS)
            while (isMonitoring) {
                try {
                    runAlertChecks()
                } catch (e: Exception) {
                    Timber.e(e, "Alert check cycle failed")
                }
                delay(MONITORING_INTERVAL_MS)
            }
        }

        // Also subscribe to transaction events for real-time checks
        scope.launch {
            eventBus.filterEvents<AgentEvent.TransactionRecorded>().collect { event ->
                checkTransactionAnomaly(event)
                checkPriceAnomaly(event.item, event.amount / event.quantity)
            }
        }

        Timber.d("Proactive alert engine started")
    }

    /**
     * Stop the monitoring loop.
     */
    fun stopMonitoring() {
        isMonitoring = false
        Timber.d("Proactive alert engine stopped")
    }

    // ═══════════════ ALERT CHECKS ═══════════════

    /**
     * Run all alert checks in a single cycle.
     */
    private suspend fun runAlertChecks() {
        checkCashFlowWarnings()
        checkSalesDrop()
        checkTrendShift()
        checkStockAlerts()
    }

    /**
     * Check for cash flow warnings — expenses trending above income.
     */
    private suspend fun checkCashFlowWarnings() {
        if (isOnCooldown(AlertType.CASH_FLOW_WARNING)) return

        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(7)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val transactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)
        if (transactions.isEmpty()) return

        // Count consecutive negative-profit days
        var negativeDays = 0
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayStart = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val dayEnd = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val dayTxns = transactions.filter { it.createdAt in dayStart until dayEnd }

            val sales = dayTxns.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
            val costs = dayTxns.filter { it.type != TransactionType.SALE }.sumOf { it.totalAmount }
            if (sales - costs < 0) negativeDays++ else negativeDays = 0

            date = date.plusDays(1)
        }

        if (negativeDays >= CASH_FLOW_DANGER_DAYS) {
            fireAlert(
                type = AlertType.CASH_FLOW_WARNING,
                severity = AlertSeverity.WARNING,
                title = "Cash Flow Warning",
                message = "You've had $negativeDays days with more expenses than income. " +
                        "Consider reducing spending or increasing sales.",
                data = mapOf("negativeDays" to negativeDays.toString())
            )
        }
    }

    /**
     * Check for sudden sales drop compared to recent average.
     */
    private suspend fun checkSalesDrop() {
        if (isOnCooldown(AlertType.SALES_DROP)) return

        val trend = patternTracker.detectWeeklyTrend()
        if (trend.direction == com.msaidizi.app.agent.Trend.FALLING &&
            trend.changePercent < -SALES_DROP_THRESHOLD * 100 &&
            trend.confidence > 0.5
        ) {
            fireAlert(
                type = AlertType.SALES_DROP,
                severity = AlertSeverity.WARNING,
                title = "Sales Declining",
                message = "Your sales dropped ${kotlin.math.abs(trend.changePercent).toInt()}% " +
                        "compared to last week. Average: KSh ${"%.0f".format(trend.currentWeekAvg)}/day.",
                data = mapOf(
                    "changePercent" to trend.changePercent.toString(),
                    "currentAvg" to trend.currentWeekAvg.toString(),
                    "previousAvg" to trend.previousWeekAvg.toString()
                )
            )
        }
    }

    /**
     * Check for significant trend direction changes.
     */
    private suspend fun checkTrendShift() {
        if (isOnCooldown(AlertType.TREND_SHIFT)) return

        val trend = patternTracker.detectWeeklyTrend()
        val healthScore = patternTracker.calculateBusinessHealthScore()

        // Alert if business health is concerning
        if (healthScore.totalScore < 30 && trend.confidence > 0.5) {
            fireAlert(
                type = AlertType.TREND_SHIFT,
                severity = AlertSeverity.CRITICAL,
                title = "Business Health Low",
                message = "Your business health score is ${healthScore.totalScore.toInt()}/100. " +
                        "Trend: ${trend.direction.name.lowercase()}. " +
                        "Let's review your sales strategy.",
                data = mapOf(
                    "healthScore" to healthScore.totalScore.toString(),
                    "marginScore" to healthScore.marginScore.toString(),
                    "trendScore" to healthScore.trendScore.toString()
                )
            )
        }
    }

    /**
     * Check for price anomalies on a transaction.
     */
    private fun checkPriceAnomaly(item: String, unitPrice: Double) {
        scope.launch {
            if (isOnCooldown(AlertType.PRICE_ANOMALY)) return@launch

            val priceInsight = patternTracker.getPriceInsight(item) ?: return@launch
            if (priceInsight.observationCount < 3) return@launch

            val deviation = kotlin.math.abs(unitPrice - priceInsight.averagePrice) / priceInsight.averagePrice
            if (deviation > PRICE_DEVIATION_THRESHOLD) {
                fireAlert(
                    type = AlertType.PRICE_ANOMALY,
                    severity = AlertSeverity.INFO,
                    title = "Price Change: $item",
                    message = "The price of $item (KSh ${"%.0f".format(unitPrice)}) is " +
                            "${(deviation * 100).toInt()}% ${if (unitPrice > priceInsight.averagePrice) "higher" else "lower"} " +
                            "than your usual KSh ${"%.0f".format(priceInsight.averagePrice)}.",
                    data = mapOf(
                        "item" to item,
                        "currentPrice" to unitPrice.toString(),
                        "averagePrice" to priceInsight.averagePrice.toString(),
                        "deviation" to deviation.toString()
                    )
                )
            }
        }
    }

    /**
     * Check for transaction anomalies using the AnomalyDetector.
     */
    private fun checkTransactionAnomaly(event: AgentEvent.TransactionRecorded) {
        scope.launch {
            if (isOnCooldown(AlertType.FRAUD_DETECTION)) return@launch

            // Get recent transactions for anomaly context
            val now = System.currentTimeMillis() / 1000
            val dayAgo = now - 86400
            val recentTxns = transactionDao.getTransactionsInRangeSuspend(dayAgo, now)

            val anomalyResult = anomalyDetector.checkTransaction(
                transaction = com.msaidizi.app.core.model.Transaction(
                    id = 0,
                    type = when (event.type) {
                        "SALE" -> TransactionType.SALE
                        "PURCHASE" -> TransactionType.PURCHASE
                        else -> TransactionType.EXPENSE
                    },
                    item = event.item,
                    quantity = event.quantity,
                    unitPrice = event.amount / event.quantity.coerceAtLeast(1.0),
                    totalAmount = event.amount,
                    createdAt = event.timestamp / 1000
                ),
                recentTransactions = recentTxns
            )

            if (anomalyResult.isAnomaly) {
                fireAlert(
                    type = AlertType.FRAUD_DETECTION,
                    severity = if (anomalyResult.severity > 0.8) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                    title = "Unusual Transaction Detected",
                    message = anomalyResult.description,
                    data = mapOf(
                        "item" to event.item,
                        "amount" to event.amount.toString(),
                        "anomalyScore" to anomalyResult.severity.toString()
                    )
                )
            }
        }
    }

    /**
     * Check stock levels for best-selling items.
     */
    private suspend fun checkStockAlerts() {
        if (isOnCooldown(AlertType.STOCK_ALERT)) return

        val products = patternTracker.analyzeProductPerformance(14)
        val topSellers = products.filter { it.isTopSeller }

        for (product in topSellers) {
            // Check if sales velocity suggests stock might run out
            // This is a heuristic: if selling > 5/day and no recent restock
            if (product.salesVelocity > 5.0) {
                val recentPurchases = transactionDao.getTransactionsInRangeSuspend(
                    System.currentTimeMillis() / 1000 - 7 * 86400,
                    System.currentTimeMillis() / 1000
                ).filter {
                    it.type == TransactionType.PURCHASE && it.item.equals(product.item, ignoreCase = true)
                }

                if (recentPurchases.isEmpty()) {
                    fireAlert(
                        type = AlertType.STOCK_ALERT,
                        severity = AlertSeverity.INFO,
                        title = "Restock: ${product.item}",
                        message = "${product.item} is selling well (${product.salesVelocity.toInt()}/day) " +
                                "but you haven't restocked in 7 days. Consider buying more.",
                        data = mapOf(
                            "item" to product.item,
                            "velocity" to product.salesVelocity.toString()
                        )
                    )
                }
            }
        }
    }

    // ═══════════════ ALERT DISPATCH ═══════════════

    /**
     * Fire a proactive alert. Publishes to event bus and alert stream.
     */
    private fun fireAlert(
        type: AlertType,
        severity: AlertSeverity,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap()
    ) {
        // Update cooldown
        lastAlertTimes[type] = System.currentTimeMillis()

        val alertData = ProactiveAlertData(
            alertId = UUID.randomUUID().toString(),
            type = type,
            severity = severity,
            title = title,
            message = message,
            data = data,
            timestamp = System.currentTimeMillis()
        )

        // Publish to event bus
        eventBus.publish(AgentEvent.ProactiveAlert(
            eventId = alertData.alertId,
            timestamp = alertData.timestamp,
            source = "ProactiveAlertEngine",
            alertType = type.name,
            severity = severity.name,
            title = title,
            message = message,
            data = data
        ))

        // Emit to alert stream
        scope.launch {
            _alerts.emit(alertData)
        }

        Timber.i("Proactive alert fired: [%s] %s — %s", severity.name, title, message.take(80))
    }

    // ═══════════════ HELPERS ═══════════════

    private fun isOnCooldown(type: AlertType): Boolean {
        val lastTime = lastAlertTimes[type] ?: return false
        val cooldown = ALERT_COOLDOWNS[type] ?: return false
        return (System.currentTimeMillis() - lastTime) < cooldown
    }

    /**
     * Get recent alerts for display.
     */
    fun getRecentAlerts(): List<ProactiveAlertData> {
        return eventBus.getRecentEventsOfType<AgentEvent.ProactiveAlert>(20).map { event ->
            ProactiveAlertData(
                alertId = event.eventId,
                type = AlertType.valueOf(event.alertType),
                severity = AlertSeverity.valueOf(event.severity),
                title = event.title,
                message = event.message,
                data = event.data,
                timestamp = event.timestamp
            )
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Proactive alert data.
 */
data class ProactiveAlertData(
    val alertId: String,
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val data: Map<String, String> = emptyMap(),
    val timestamp: Long
)

/**
 * Alert types.
 */
enum class AlertType {
    PRICE_ANOMALY,
    CASH_FLOW_WARNING,
    SALES_DROP,
    FRAUD_DETECTION,
    STOCK_ALERT,
    TREND_SHIFT
}

/**
 * Alert severity levels.
 */
enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

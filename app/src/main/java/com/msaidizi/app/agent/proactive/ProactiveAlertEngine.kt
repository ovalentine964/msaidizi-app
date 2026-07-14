package com.msaidizi.app.agent.proactive

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.PatternType
import com.msaidizi.app.core.model.TransactionType
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
 * Wires together all intelligence components into a proactive
 * monitoring pipeline that surfaces issues before the worker asks:
 *
 * ┌───────────────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ BusinessPattern       │────▶│ ProactiveAlert   │────▶│  AgentEvent  │
 * │ Tracker               │     │ Engine           │     │  Bus         │
 * │ ProactiveAnomalyDet.  │     │ (monitoring loop)│     │  (alerts)    │
 * │ StockOutPredictor     │     │                  │     │              │
 * │ CashFlowPredictor     │     └──────────────────┘     └──────────────┘
 * └───────────────────────┘             │
 *                                       ▼
 *                               ┌──────────────┐
 *                               │ Notification │
 *                               │ / TTS System │
 *                               └──────────────┘
 *
 * ## Alert Types
 * 1. **Price Anomaly** — Significant price deviation (Z-score based)
 * 2. **Cash Flow Warning** — Expenses exceeding income trajectory
 * 3. **Cash Flow Prediction** — Tomorrow's expected income/expense
 * 4. **Sales Drop** — Sudden decline in sales volume/rate
 * 5. **Fraud Detection** — Unusual transaction patterns (Z-score)
 * 6. **Stock Alert** — Items predicted to stock out soon
 * 7. **Trend Shift** — Business trend direction changed
 * 8. **Volume Anomaly** — Unusual transaction count
 * 9. **Missing Transactions** — Expected sales didn't happen
 *
 * @param patternTracker Business pattern analysis
 * @param anomalyDetector Z-score based anomaly detection
 * @param stockOutPredictor Stock-out prediction (Holt-Winters)
 * @param cashFlowPredictor Cash flow prediction (Holt's)
 * @param transactionDao Direct transaction queries
 * @param inventoryDao Inventory data for stock predictions
 * @param eventBus Event bus for publishing alerts
 */
class ProactiveAlertEngine(
    private val patternTracker: BusinessPatternTracker,
    private val anomalyDetector: ProactiveAnomalyDetector,
    private val stockOutPredictor: StockOutPredictor,
    private val cashFlowPredictor: CashFlowPredictor,
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao,
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
            AlertType.CASH_FLOW_PREDICTION to 20 * 60 * 60 * 1000L, // 20 hours (once/day)
            AlertType.SALES_DROP to 6 * 60 * 60 * 1000L,        // 6 hours
            AlertType.FRAUD_DETECTION to 1 * 60 * 60 * 1000L,   // 1 hour
            AlertType.STOCK_ALERT to 12 * 60 * 60 * 1000L,      // 12 hours
            AlertType.TREND_SHIFT to 24 * 60 * 60 * 1000L,      // 24 hours
            AlertType.VOLUME_ANOMALY to 6 * 60 * 60 * 1000L,    // 6 hours
            AlertType.MISSING_TRANSACTIONS to 4 * 60 * 60 * 1000L // 4 hours
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

        // Periodic check cycle
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

        // Real-time transaction checks (Z-score anomaly detection)
        scope.launch {
            eventBus.filterEvents<AgentEvent.TransactionRecorded>().collect { event ->
                checkTransactionAnomaly(event)
                checkPriceAnomaly(event.item, event.amount / event.quantity.coerceAtLeast(1.0))
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
        checkCashFlowPrediction()
        checkSalesDrop()
        checkTrendShift()
        checkStockAlerts()
        checkAnomalyScan()
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
                message = "Umekuwa na siku $negativeDays mfululizo na matumizi zaidi ya mapato. " +
                        "Punguza matumizi au ongeza mauzo.",
                data = mapOf("negativeDays" to negativeDays.toString()),
                ttsMessage = "Onyo: Umekuwa na siku $negativeDays na matumizi kuliko mapato."
            )
        }
    }

    /**
     * Predict tomorrow's cash flow using Holt's exponential smoothing.
     * Fires an alert with the prediction.
     */
    private suspend fun checkCashFlowPrediction() {
        if (isOnCooldown(AlertType.CASH_FLOW_PREDICTION)) return

        val prediction = cashFlowPredictor.predictTomorrow()

        // Only alert if we have reasonable confidence
        if (prediction.confidence < 0.3) return

        fireAlert(
            type = AlertType.CASH_FLOW_PREDICTION,
            severity = if (prediction.trend == CashFlowTrend.DECLINING) AlertSeverity.WARNING else AlertSeverity.INFO,
            title = "Cash Flow Prediction",
            message = prediction.message,
            data = mapOf(
                "predictedIncome" to prediction.predictedIncome.toString(),
                "predictedExpenses" to prediction.predictedExpenses.toString(),
                "predictedNet" to prediction.predictedNet.toString(),
                "trend" to prediction.trend.name,
                "confidence" to prediction.confidence.toString()
            ),
            ttsMessage = prediction.message
        )
    }

    /**
     * Check for sudden sales drop compared to recent average.
     */
    private suspend fun checkSalesDrop() {
        if (isOnCooldown(AlertType.SALES_DROP)) return

        val trend = patternTracker.detectWeeklyTrend()
        if (trend.direction == com.msaidizi.app.core.model.Trend.FALLING &&
            trend.changePercent < -SALES_DROP_THRESHOLD * 100 &&
            trend.confidence > 0.5
        ) {
            fireAlert(
                type = AlertType.SALES_DROP,
                severity = AlertSeverity.WARNING,
                title = "Sales Declining",
                message = "Mauzo yako yameshuka kwa ${kotlin.math.abs(trend.changePercent).toInt()}% " +
                        "ikilinganishwa na wiki iliyopita. Wastani: KSh ${"%.0f".format(trend.currentWeekAvg)}/siku.",
                data = mapOf(
                    "changePercent" to trend.changePercent.toString(),
                    "currentAvg" to trend.currentWeekAvg.toString(),
                    "previousAvg" to trend.previousWeekAvg.toString()
                ),
                ttsMessage = "Mauzo yako yameshuka kwa asilimia ${kotlin.math.abs(trend.changePercent).toInt()}."
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

        if (healthScore.totalScore < 30 && trend.confidence > 0.5) {
            fireAlert(
                type = AlertType.TREND_SHIFT,
                severity = AlertSeverity.CRITICAL,
                title = "Business Health Low",
                message = "Alama ya afya ya biashara yako ni ${healthScore.totalScore.toInt()}/100. " +
                        "Mwelekeo: ${trend.direction.name.lowercase()}. " +
                        "Hebu tuangalie mkakati wako wa mauzo.",
                data = mapOf(
                    "healthScore" to healthScore.totalScore.toString(),
                    "marginScore" to healthScore.marginScore.toString(),
                    "trendScore" to healthScore.trendScore.toString()
                ),
                ttsMessage = "Afya ya biashara yako ni chini. Alama ni ${healthScore.totalScore.toInt()} kati ya 100."
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
                val direction = if (unitPrice > priceInsight.averagePrice) "imepanda" else "imeshuka"
                fireAlert(
                    type = AlertType.PRICE_ANOMALY,
                    severity = AlertSeverity.INFO,
                    title = "Price Change: $item",
                    message = "Bei ya $item (KSh ${"%.0f".format(unitPrice)}) $direction " +
                            "kwa ${(deviation * 100).toInt()}% ukilinganishwa na wastani wako wa " +
                            "KSh ${"%.0f".format(priceInsight.averagePrice)}.",
                    data = mapOf(
                        "item" to item,
                        "currentPrice" to unitPrice.toString(),
                        "averagePrice" to priceInsight.averagePrice.toString(),
                        "deviation" to deviation.toString()
                    ),
                    ttsMessage = "Bei ya $item $direction kwa asilimia ${(deviation * 100).toInt()}."
                )
            }
        }
    }

    /**
     * Check for transaction anomalies using Z-score based ProactiveAnomalyDetector.
     */
    private fun checkTransactionAnomaly(event: AgentEvent.TransactionRecorded) {
        scope.launch {
            if (isOnCooldown(AlertType.FRAUD_DETECTION)) return@launch

            // Get recent transactions for anomaly context
            val now = System.currentTimeMillis() / 1000
            val dayAgo = now - 86400
            val recentTxns = transactionDao.getTransactionsInRangeSuspend(dayAgo, now)

            val transaction = com.msaidizi.app.core.model.Transaction(
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
            )

            val anomalies = anomalyDetector.checkTransactionRealtime(transaction, recentTxns)

            // Fire alert for the most severe anomaly
            val mostSevere = anomalies.maxByOrNull {
                when (it.severity) {
                    AnomalySeverity.CRITICAL -> 3
                    AnomalySeverity.WARNING -> 2
                    AnomalySeverity.INFO -> 1
                }
            }

            if (mostSevere != null) {
                fireAlert(
                    type = AlertType.FRAUD_DETECTION,
                    severity = when (mostSevere.severity) {
                        AnomalySeverity.CRITICAL -> AlertSeverity.CRITICAL
                        AnomalySeverity.WARNING -> AlertSeverity.WARNING
                        AnomalySeverity.INFO -> AlertSeverity.INFO
                    },
                    title = "Unusual Transaction Detected",
                    message = mostSevere.message,
                    data = mostSevere.data + mapOf(
                        "item" to event.item,
                        "amount" to event.amount.toString(),
                        "zScore" to mostSevere.zScore.toString()
                    ),
                    ttsMessage = mostSevere.message
                )
            }
        }
    }

    /**
     * Check stock levels using Holt-Winters based StockOutPredictor.
     */
    private suspend fun checkStockAlerts() {
        if (isOnCooldown(AlertType.STOCK_ALERT)) return

        val predictions = stockOutPredictor.predictAll()

        for (prediction in predictions) {
            if (prediction.isCritical) {
                fireAlert(
                    type = AlertType.STOCK_ALERT,
                    severity = if (prediction.daysUntilStockout < 1.0) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                    title = "Stock Alert: ${prediction.item}",
                    message = prediction.alertMessage,
                    data = mapOf(
                        "item" to prediction.item,
                        "currentStock" to prediction.currentStock.toString(),
                        "dailyDemand" to prediction.dailyDemand.toString(),
                        "daysUntilStockout" to prediction.daysUntilStockout.toString(),
                        "confidence" to prediction.confidence.toString()
                    ),
                    ttsMessage = prediction.alertMessage
                )
            }
        }
    }

    /**
     * Run Z-score anomaly scan on today's data.
     */
    private suspend fun checkAnomalyScan() {
        val anomalies = anomalyDetector.runDailyScan()

        for (anomaly in anomalies) {
            val alertType = when (anomaly.type) {
                AnomalyType.VOLUME_ANOMALY -> AlertType.VOLUME_ANOMALY
                AnomalyType.MISSING_TRANSACTIONS -> AlertType.MISSING_TRANSACTIONS
                AnomalyType.AMOUNT_ANOMALY -> AlertType.FRAUD_DETECTION
                AnomalyType.TIMING_ANOMALY -> AlertType.FRAUD_DETECTION
                AnomalyType.PRICE_ANOMALY -> AlertType.PRICE_ANOMALY
            }

            if (isOnCooldown(alertType)) continue

            fireAlert(
                type = alertType,
                severity = when (anomaly.severity) {
                    AnomalySeverity.CRITICAL -> AlertSeverity.CRITICAL
                    AnomalySeverity.WARNING -> AlertSeverity.WARNING
                    AnomalySeverity.INFO -> AlertSeverity.INFO
                },
                title = when (anomaly.type) {
                    AnomalyType.VOLUME_ANOMALY -> "Unusual Activity"
                    AnomalyType.MISSING_TRANSACTIONS -> "No Sales Today"
                    AnomalyType.AMOUNT_ANOMALY -> "Unusual Amount"
                    AnomalyType.TIMING_ANOMALY -> "Unusual Timing"
                    AnomalyType.PRICE_ANOMALY -> "Price Anomaly"
                },
                message = anomaly.message,
                data = anomaly.data,
                ttsMessage = anomaly.message
            )
        }
    }

    // ═══════════════ ALERT DISPATCH ═══════════════

    /**
     * Fire a proactive alert. Publishes to event bus and alert stream.
     *
     * @param type Alert type for cooldown tracking
     * @param severity Alert severity level
     * @param title Short title for display
     * @param message Full message in Swahili
     * @param data Additional data for analytics
     * @param ttsMessage Optional TTS-friendly message (shorter, spoken form)
     */
    private fun fireAlert(
        type: AlertType,
        severity: AlertSeverity,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap(),
        ttsMessage: String = message
    ) {
        // Update cooldown
        lastAlertTimes[type] = System.currentTimeMillis()

        val alertData = ProactiveAlertData(
            alertId = UUID.randomUUID().toString(),
            type = type,
            severity = severity,
            title = title,
            message = message,
            ttsMessage = ttsMessage,
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
                ttsMessage = event.message,
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
    val ttsMessage: String = message,
    val data: Map<String, String> = emptyMap(),
    val timestamp: Long
)

/**
 * Alert types.
 */
enum class AlertType {
    PRICE_ANOMALY,
    CASH_FLOW_WARNING,
    CASH_FLOW_PREDICTION,
    SALES_DROP,
    FRAUD_DETECTION,
    STOCK_ALERT,
    TREND_SHIFT,
    VOLUME_ANOMALY,
    MISSING_TRANSACTIONS
}

/**
 * Alert severity levels.
 */
enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

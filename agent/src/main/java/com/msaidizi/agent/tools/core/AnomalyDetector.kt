package com.msaidizi.agent.tools.core

import com.msaidizi.core.database.AnomalyHistoryDao
import com.msaidizi.core.model.AnomalyHistoryEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AnomalyDetector — Detect anomalous transaction amounts using z-score analysis.
 *
 * All detection history is persisted in SQLite via Room DAOs.
 * Survives app restarts; maintains rolling statistical baseline.
 */
@Singleton
class AnomalyDetector @Inject constructor(
    private val anomalyHistoryDao: AnomalyHistoryDao
) : Tool {

    override val name = "anomaly_detector"
    override val description = "Detect anomalous transaction amounts using statistical analysis"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("detect", "reset", "status"), required = false)
        number("amount", "Transaction amount to check for anomalies", required = false)
    }

    companion object {
        private const val BASELINE_MIN_SIZE = 5
        private const val Z_SCORE_THRESHOLD = 3.0
        private const val MAX_HISTORY_SIZE = 1000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "detect"
        return when (action.lowercase()) {
            "detect" -> {
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
                detect(amount)
            }
            "reset" -> {
                anomalyHistoryDao.deleteAll()
                ToolResult.success(name, message = "History reset")
            }
            "status" -> {
                val count = anomalyHistoryDao.getCount()
                val anomalyCount = anomalyHistoryDao.getAnomalyCount()
                ToolResult.success(
                    name,
                    mapOf(
                        "history_size" to count,
                        "anomaly_count" to anomalyCount,
                        "baseline_ready" to (count >= BASELINE_MIN_SIZE)
                    ),
                    "History: $count entries ($anomalyCount anomalies). Baseline ${if (count >= BASELINE_MIN_SIZE) "ready" else "building"}"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Detect if an amount is anomalous using z-score analysis.
     * Reads baseline from SQLite, computes z-score, persists the result.
     */
    suspend fun detect(amount: Double): ToolResult {
        val historyCount = anomalyHistoryDao.getCount()

        // Still building baseline
        if (historyCount < BASELINE_MIN_SIZE) {
            persistEntry(amount, isAnomaly = false, zScore = 0.0, mean = amount, stdDev = 0.0)
            return ToolResult.success(
                name,
                mapOf("amount" to amount, "baseline" to false, "history_size" to historyCount + 1),
                "Normal - building baseline (${historyCount + 1}/$BASELINE_MIN_SIZE)"
            )
        }

        // Compute statistics from persisted history
        val recentEntries = anomalyHistoryDao.getLastN(MAX_HISTORY_SIZE)
        val amounts = recentEntries.map { it.amount }
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        // Compute z-score
        val zScore = if (stdDev > 0) (amount - mean) / stdDev else 0.0
        val isAnomaly = abs(zScore) > Z_SCORE_THRESHOLD

        // Persist this detection result
        persistEntry(amount, isAnomaly, zScore, mean, stdDev)

        return if (isAnomaly) {
            Timber.w("Anomaly detected: amount=$amount, z-score=${"%.2f".format(zScore)}, mean=${"%.2f".format(mean)}")
            ToolResult.error(
                name,
                "ANOMALY: z-score=${"%.2f".format(zScore)}, amount=$amount, mean=${"%.2f".format(mean)}, stdDev=${"%.2f".format(stdDev)}",
                "ANOMALY_DETECTED"
            )
        } else {
            ToolResult.success(
                name,
                mapOf(
                    "z_score" to zScore,
                    "amount" to amount,
                    "mean" to mean,
                    "std_dev" to stdDev,
                    "is_anomaly" to false
                ),
                "Normal: z-score=${"%.2f".format(zScore)}"
            )
        }
    }

    /**
     * Persist a detection entry to SQLite.
     * Prunes old entries if history exceeds max size.
     */
    private suspend fun persistEntry(
        amount: Double,
        isAnomaly: Boolean,
        zScore: Double,
        mean: Double,
        stdDev: Double
    ) {
        anomalyHistoryDao.insert(
            AnomalyHistoryEntity(
                amount = amount,
                isAnomaly = isAnomaly,
                zScore = zScore,
                meanAtTime = mean,
                stdDevAtTime = stdDev,
                timestamp = System.currentTimeMillis()
            )
        )

        // Prune old entries if needed
        val count = anomalyHistoryDao.getCount()
        if (count > MAX_HISTORY_SIZE * 2) {
            val cutoff = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000) // 90 days
            anomalyHistoryDao.deleteOlderThan(cutoff)
            Timber.d("Pruned anomaly history older than 90 days")
        }
    }
}

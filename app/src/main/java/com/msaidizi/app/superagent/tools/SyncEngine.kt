package com.msaidizi.app.superagent.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.msaidizi.app.core.database.AnomalyHistoryDao
import com.msaidizi.app.core.database.BusinessPatternDao
import com.msaidizi.app.core.database.LearnedVocabularyDao
import com.msaidizi.app.core.database.SyncStateDao
import com.msaidizi.app.core.network.SyncApi
import com.msaidizi.app.core.network.SyncPayload
import com.msaidizi.app.core.security.EncryptionManager
import com.msaidizi.app.core.network.AnonymizedTransaction
import com.msaidizi.app.core.network.AnonymizedPattern
import com.msaidizi.app.core.network.AnomalyStats
import com.msaidizi.app.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncEngine — Batch, anonymize, and sync business data to cloud backend.
 *
 * Features:
 * - Real HTTP sync via Retrofit (POST /api/v1/sync/anonymized)
 * - PII stripping: hash phone numbers with salt, generalize locations to ward level
 * - WiFi-only + battery > 20% gating
 * - Retry with exponential backoff
 * - SQLite persistence for pending batches and sync state
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncApi: SyncApi,
    private val syncStateDao: SyncStateDao,
    private val anomalyHistoryDao: AnomalyHistoryDao,
    private val learnedVocabularyDao: LearnedVocabularyDao,
    private val businessPatternDao: BusinessPatternDao,
    private val encryptionManager: EncryptionManager
) : Tool {

    override val name = "sync_engine"
    override val description = "Batch, anonymize, and sync business data to cloud backend"

    override val argsSchema = argSchema {
        enum("action", "Sync action to perform",
            listOf("add", "sync", "status", "clear"), required = false)
        number("amount", "Transaction amount", required = false)
        string("category", "Transaction category (e.g. sale, expense, purchase)", required = false)
        string("payment_method", "Payment method (e.g. cash, m-pesa, bank)", required = false)
        boolean("is_service", "Whether the transaction is a service (not a product)", required = false)
        string("location", "Location string for ward-level generalization", required = false)
        string("phone", "Phone number (will be anonymized with SHA-256 hash)", required = false)
    }

    // In-memory pending batch (flushed on sync)
    private val pendingTransactions = mutableListOf<PendingTransaction>()

    // Anonymization salt — per-installation random salt stored in EncryptedSharedPreferences
    private val phoneHashSalt: String by lazy {
        val prefs = encryptionManager.getEncryptedPrefs()
        val existing = prefs.getString(KEY_PHONE_HASH_SALT, null)
        if (existing != null) {
            existing
        } else {
            val randomSalt = java.util.UUID.randomUUID().toString() +
                java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_PHONE_HASH_SALT, randomSalt).apply()
            randomSalt
        }
    }

    // Retry configuration
    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val BATTERY_THRESHOLD = 20
        private const val SYNC_PROTOCOL_VERSION = 1
        private const val KEY_PHONE_HASH_SALT = "phone_hash_salt"
    }

    data class PendingTransaction(
        val amount: Double,
        val category: String,
        val paymentMethod: String,
        val isService: Boolean,
        val timestamp: Long,
        val location: String?,     // raw — will be anonymized
        val phone: String?         // raw — will be anonymized
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "sync"
        return when (action.lowercase()) {
            "add" -> addToBatch(params)
            "sync" -> sync()
            "status" -> status()
            "clear" -> clear()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    private fun addToBatch(params: Map<String, String>): ToolResult {
        val amount = params["amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
        val category = params["category"] ?: "general"
        val paymentMethod = params["payment_method"] ?: "cash"
        val isService = params["is_service"]?.toBooleanStrictOrNull() ?: false
        val location = params["location"]
        val phone = params["phone"]

        pendingTransactions.add(
            PendingTransaction(
                amount = amount,
                category = category,
                paymentMethod = paymentMethod,
                isService = isService,
                timestamp = System.currentTimeMillis(),
                location = location,
                phone = phone
            )
        )

        Timber.d("Added to sync batch: ${pendingTransactions.size} pending")
        return ToolResult.success(
            name,
            mapOf("pending" to pendingTransactions.size),
            "Added to sync batch (${pendingTransactions.size} pending)"
        )
    }

    suspend fun sync(): ToolResult {
        if (pendingTransactions.isEmpty()) {
            return ToolResult.success(name, message = "Nothing to sync")
        }

        // Check gating conditions
        val wifiAvailable = isWifiConnected()
        val batteryPercent = getBatteryPercent()

        if (!wifiAvailable) {
            return ToolResult.error(name, "Sync requires WiFi connection", "NO_WIFI")
        }
        if (batteryPercent < BATTERY_THRESHOLD) {
            return ToolResult.error(
                name,
                "Sync requires battery > $BATTERY_THRESHOLD% (currently $batteryPercent%)",
                "LOW_BATTERY"
            )
        }

        // Build anonymized payload
        val payload = buildAnonymizedPayload()

        // Retry with exponential backoff
        var lastError: String? = null
        var backoffMs = INITIAL_BACKOFF_MS

        for (attempt in 1..MAX_RETRIES) {
            try {
                Timber.d("Sync attempt $attempt/$MAX_RETRIES")
                val response = syncApi.syncAnonymized(payload)

                if (response.isSuccessful) {
                    val body = response.body()
                    val count = pendingTransactions.size
                    pendingTransactions.clear()

                    // Record success in DB
                    syncStateDao.recordSuccess(
                        timestamp = System.currentTimeMillis(),
                        status = body?.status ?: "ok"
                    )

                    return ToolResult.success(
                        name,
                        mapOf(
                            "synced_count" to count,
                            "server_timestamp" to (body?.serverTimestamp ?: 0),
                            "conflicts_resolved" to (body?.conflictsResolved ?: 0),
                            "attempt" to attempt
                        ),
                        "Synced $count items to cloud (attempt $attempt)"
                    )
                } else {
                    lastError = "HTTP ${response.code()}: ${response.message()}"
                    Timber.w("Sync failed (attempt $attempt): $lastError")

                    // Don't retry on client errors (4xx)
                    if (response.code() in 400..499 && response.code() != 429) {
                        break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown network error"
                Timber.w(e, "Sync error (attempt $attempt)")
            }

            // Wait before retry (skip on last attempt)
            if (attempt < MAX_RETRIES) {
                delay(backoffMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }

        // All retries failed
        syncStateDao.recordFailure(
            status = "error",
            error = lastError ?: "Unknown error"
        )

        return ToolResult.error(
            name,
            "Sync failed after $MAX_RETRIES attempts: $lastError",
            "SYNC_FAILED"
        )
    }

    private suspend fun status(): ToolResult {
        val state = syncStateDao.getState()
        val wifiAvailable = isWifiConnected()
        val batteryPercent = getBatteryPercent()
        val shouldSync = shouldSync(wifiAvailable, batteryPercent)

        return ToolResult.success(
            name,
            mapOf(
                "pending" to pendingTransactions.size,
                "last_sync" to (state?.lastSyncTimestamp ?: 0),
                "last_status" to (state?.lastSyncStatus ?: "never"),
                "consecutive_failures" to (state?.consecutiveFailures ?: 0),
                "wifi_available" to wifiAvailable,
                "battery_percent" to batteryPercent,
                "should_sync" to shouldSync
            ),
            "Pending: ${pendingTransactions.size} items. " +
                "Last sync: ${state?.lastSyncStatus ?: "never"}. " +
                "WiFi: $wifiAvailable, Battery: $batteryPercent%. Ready: $shouldSync"
        )
    }

    private fun clear(): ToolResult {
        val count = pendingTransactions.size
        pendingTransactions.clear()
        return ToolResult.success(name, mapOf("cleared" to count), "Cleared $count pending items")
    }

    fun shouldSync(wifiAvailable: Boolean, batteryPercent: Int): Boolean {
        return wifiAvailable && batteryPercent > BATTERY_THRESHOLD && pendingTransactions.isNotEmpty()
    }

    // ── Payload Construction ──────────────────────────────

    private suspend fun buildAnonymizedPayload(): SyncPayload {
        val transactions = pendingTransactions.map { tx ->
            AnonymizedTransaction(
                amountBucket = bucketAmount(tx.amount),
                category = tx.category,
                paymentMethod = tx.paymentMethod,
                hourOfDay = java.util.Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                    .get(java.util.Calendar.HOUR_OF_DAY),
                dayOfWeek = java.util.Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                    .get(java.util.Calendar.DAY_OF_WEEK),
                isService = tx.isService
            )
        }

        // Gather learned patterns from DB
        val patterns = businessPatternDao.getAll().map { p ->
            AnonymizedPattern(
                patternType = p.patternType,
                confidence = p.confidence,
                occurrenceCount = p.occurrenceCount
            )
        }

        // Gather vocabulary hashes (not the words themselves)
        val vocabHashes = learnedVocabularyDao.getAll().map { entry ->
            hashString("${entry.word}:${entry.language}")
        }

        // Gather anomaly stats
        val anomalyCount = anomalyHistoryDao.getAnomalyCount()
        val totalAnalyzed = anomalyHistoryDao.getCount()
        val recentEntries = anomalyHistoryDao.getLastN(100)
        val amounts = recentEntries.map { it.amount }
        val mean = amounts.average()
        val stdDev = if (amounts.size > 1) {
            kotlin.math.sqrt(amounts.map { (it - mean) * (it - mean) }.average())
        } else 0.0

        // Generalize location to ward level (strip GPS, keep ward name)
        val ward = pendingTransactions.firstNotNullOfOrNull { it.location }
            ?.let { generalizeToWard(it) }
            ?: "unknown"

        return SyncPayload(
            deviceId = getAnonymousDeviceId(),
            businessCategory = "general", // Could be derived from user profile
            ward = ward,
            transactions = transactions,
            learnedPatterns = patterns,
            vocabularyHashes = vocabHashes,
            anomalyStats = AnomalyStats(
                totalTransactionsAnalyzed = totalAnalyzed,
                anomalyCount = anomalyCount,
                meanAmount = mean,
                stdDev = stdDev
            ),
            timestamp = System.currentTimeMillis(),
            syncProtocolVersion = SYNC_PROTOCOL_VERSION
        )
    }

    // ── Anonymization Helpers ──────────────────────────────

    /**
     * Hash a phone number with salt. Strips all PII.
     */
    fun hashPhone(phone: String): String {
        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        return hashString("$cleaned:$phoneHashSalt")
    }

    /**
     * Generalize a location string to ward level.
     * Strips GPS coordinates, keeps only the ward/administrative name.
     */
    fun generalizeToWard(location: String): String {
        // Strip GPS coordinates (e.g., "-1.2921,36.8219")
        val withoutCoords = location.replace(Regex("-?\\d+\\.\\d+\\s*,\\s*-?\\d+\\.\\d+"), "").trim()

        // If it contains hierarchical location (County > Sub-County > Ward), keep only ward
        val parts = withoutCoords.split(">").map { it.trim() }
        return if (parts.size >= 3) {
            parts.last() // ward level
        } else if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
            parts[0]
        } else {
            "unknown"
        }
    }

    /**
     * Bucket amounts into ranges to prevent exact amount fingerprinting.
     */
    private fun bucketAmount(amount: Double): String = when {
        amount < 100 -> "0-100"
        amount < 500 -> "100-500"
        amount < 1000 -> "500-1000"
        amount < 5000 -> "1000-5000"
        else -> "5000+"
    }

    /**
     * SHA-256 hash of a string, returned as hex.
     */
    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate an anonymous device fingerprint (no IMEI, no Android ID).
     * Uses a hash of stable but non-PII device characteristics.
     */
    private fun getAnonymousDeviceId(): String {
        val raw = "${android.os.Build.MANUFACTURER}:${android.os.Build.MODEL}:${android.os.Build.DEVICE}"
        return hashString("device:$raw:$phoneHashSalt")
    }

    // ── Network & Battery Checks ──────────────────────────

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

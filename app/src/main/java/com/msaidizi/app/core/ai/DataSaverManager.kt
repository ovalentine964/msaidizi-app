package com.msaidizi.app.core.ai

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.text.DecimalFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages data-saver mode for users with limited mobile data plans.
 *
 * ## African Data Plan Reality
 * - Average user: 1-2GB/month data plan
 * - Cost: ~KES 200-500/GB ($1.50-3.50 USD/GB)
 * - 580MB model = 30-60% of monthly data!
 * - 1.5GB model = entire monthly plan!
 *
 * ## Data-Saver Features
 * - Warns before downloading on mobile data
 * - Shows estimated data cost in local currency
 * - Offers Q2_K (smaller) models as alternative
 * - WiFi-only mode for large downloads
 * - Progressive download: start small, upgrade on WiFi
 * - Data usage tracking per model download
 *
 * ## Connection Types
 * - WiFi (unmetered): download anything, no warning
 * - 4G/LTE: warn for models >100MB
 * - 3G: warn for models >50MB, suggest Q2_K
 * - 2G/EDGE: block large downloads entirely
 *
 * @see ModelDownloader for the actual download logic
 * @see BundledModelManager for progressive loading strategy
 */
@Singleton
class DataSaverManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DataSaverManager"
        private const val PREFS_NAME = "data_saver_prefs"
        private const val KEY_DATA_SAVER_ENABLED = "data_saver_enabled"
        private const val KEY_WIFI_ONLY_LARGE = "wifi_only_large_models"
        private const val KEY_WARN_MOBILE_DATA = "warn_mobile_data"
        private const val KEY_DATA_USED_BYTES = "total_data_used_bytes"
        private const val KEY_DOWNLOAD_COUNT = "download_count"

        // Size thresholds (bytes)
        private const val SMALL_MODEL_THRESHOLD = 50_000_000L     // 50MB
        private const val MEDIUM_MODEL_THRESHOLD = 200_000_000L   // 200MB
        private const val LARGE_MODEL_THRESHOLD = 500_000_000L    // 500MB

        // KES per GB (approximate Safaricom data pricing)
        private const val KES_PER_GB = 300.0
        private const val USD_PER_GB = 2.0
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── State ──

    private val _dataSaverState = MutableStateFlow(DataSaverState())
    val dataSaverState: StateFlow<DataSaverState> = _dataSaverState

    init {
        refreshState()
    }

    // ── Public API ──

    /**
     * Check if data saver mode is enabled.
     * When enabled, large downloads require WiFi or explicit user confirmation.
     */
    fun isDataSaverEnabled(): Boolean {
        return prefs.getBoolean(KEY_DATA_SAVER_ENABLED, true) // Default ON for African users
    }

    /**
     * Toggle data saver mode.
     */
    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DATA_SAVER_ENABLED, enabled).apply()
        refreshState()
        Timber.i("Data saver mode: %s", if (enabled) "ENABLED" else "DISABLED")
    }

    /**
     * Check if WiFi-only mode is enabled for large models (>200MB).
     */
    fun isWifiOnlyForLargeModels(): Boolean {
        return prefs.getBoolean(KEY_WIFI_ONLY_LARGE, false) // Default OFF — users have no WiFi
    }

    /**
     * Set WiFi-only preference for large models.
     */
    fun setWifiOnlyForLargeModels(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY_LARGE, wifiOnly).apply()
        refreshState()
    }

    /**
     * Check if we should warn the user before downloading on mobile data.
     */
    fun shouldWarnOnMobileData(): Boolean {
        return prefs.getBoolean(KEY_WARN_MOBILE_DATA, true)
    }

    /**
     * Set whether to warn on mobile data downloads.
     */
    fun setWarnOnMobileData(warn: Boolean) {
        prefs.edit().putBoolean(KEY_WARN_MOBILE_DATA, warn).apply()
    }

    /**
     * Get the current network connection type.
     */
    fun getConnectionType(): ConnectionType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return ConnectionType.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return ConnectionType.NONE

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> classifyCellularType()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.WIFI
            else -> ConnectionType.UNKNOWN
        }
    }

    /**
     * Check if we're on an unmetered connection (WiFi, Ethernet).
     */
    fun isOnUnmeteredConnection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Get a download recommendation for a model based on current network conditions.
     *
     * @param modelSizeBytes Size of the model to download
     * @return Recommendation with user-facing message and action
     */
    fun getDownloadRecommendation(modelSizeBytes: Long): DownloadRecommendation {
        val connection = getConnectionType()
        val isUnmetered = isOnUnmeteredConnection()
        val dataSaver = isDataSaverEnabled()

        return when {
            // WiFi — always safe
            isUnmetered -> DownloadRecommendation(
                action = DownloadAction.DOWNLOAD_NOW,
                message = "Unaweza kupakia sasa (WiFi)",
                messageEn = "Ready to download (WiFi)",
                dataCost = null,
                showWarning = false
            )

            // No connection
            connection == ConnectionType.NONE -> DownloadRecommendation(
                action = DownloadAction.WAIT_FOR_NETWORK,
                message = "Hakuna mtandao — subiri WiFi au data",
                messageEn = "No network — wait for WiFi or data",
                dataCost = null,
                showWarning = false
            )

            // 2G/EDGE — block large downloads
            connection == ConnectionType.CELLULAR_2G && modelSizeBytes > SMALL_MODEL_THRESHOLD ->
                DownloadRecommendation(
                    action = DownloadAction.BLOCKED,
                    message = "Mtandao wa 2G hauwezi — tumia WiFi au 4G",
                    messageEn = "2G network too slow — use WiFi or 4G",
                    dataCost = estimateDataCost(modelSizeBytes),
                    showWarning = true
                )

            // 3G — warn strongly for large models
            connection == ConnectionType.CELLULAR_3G && modelSizeBytes > MEDIUM_MODEL_THRESHOLD ->
                DownloadRecommendation(
                    action = DownloadAction.CONFIRM_REQUIRED,
                    message = "Model kubwa (${formatBytes(modelSizeBytes)}) kwenye 3G — itachukua muda mrefu",
                    messageEn = "Large model (${formatBytes(modelSizeBytes)}) on 3G — will take a long time",
                    dataCost = estimateDataCost(modelSizeBytes),
                    showWarning = true,
                    suggestAlternative = true
                )

            // Mobile data + data saver + large model → warn
            !isUnmetered && dataSaver && modelSizeBytes > MEDIUM_MODEL_THRESHOLD ->
                DownloadRecommendation(
                    action = DownloadAction.CONFIRM_REQUIRED,
                    message = "Model hii ni ${formatBytes(modelSizeBytes)} — itakula data yako!",
                    messageEn = "This model is ${formatBytes(modelSizeBytes)} — it will use your data!",
                    dataCost = estimateDataCost(modelSizeBytes),
                    showWarning = true,
                    suggestAlternative = modelSizeBytes > LARGE_MODEL_THRESHOLD
                )

            // Mobile data + medium model → soft warn
            !isUnmetered && modelSizeBytes > SMALL_MODEL_THRESHOLD ->
                DownloadRecommendation(
                    action = DownloadAction.DOWNLOAD_WITH_WARNING,
                    message = "Pakia sasa (${formatBytes(modelSizeBytes)}) — itakula data",
                    messageEn = "Download now (${formatBytes(modelSizeBytes)}) — uses data",
                    dataCost = estimateDataCost(modelSizeBytes),
                    showWarning = true
                )

            // Small model — always OK
            else -> DownloadRecommendation(
                action = DownloadAction.DOWNLOAD_NOW,
                message = "Pakia sasa (${formatBytes(modelSizeBytes)})",
                messageEn = "Download now (${formatBytes(modelSizeBytes)})",
                dataCost = estimateDataCost(modelSizeBytes),
                showWarning = false
            )
        }
    }

    /**
     * Record data usage from a download.
     * Tracks total data used for user awareness.
     */
    fun recordDataUsage(bytes: Long) {
        val current = prefs.getLong(KEY_DATA_USED_BYTES, 0)
        prefs.edit()
            .putLong(KEY_DATA_USED_BYTES, current + bytes)
            .putInt(KEY_DOWNLOAD_COUNT, prefs.getInt(KEY_DOWNLOAD_COUNT, 0) + 1)
            .apply()
        refreshState()
    }

    /**
     * Get total data used by model downloads.
     */
    fun getTotalDataUsedBytes(): Long {
        return prefs.getLong(KEY_DATA_USED_BYTES, 0)
    }

    /**
     * Get a user-friendly data usage summary.
     */
    fun getDataUsageSummary(): DataUsageSummary {
        val totalBytes = getTotalDataUsedBytes()
        val downloadCount = prefs.getInt(KEY_DOWNLOAD_COUNT, 0)
        val estimatedCost = estimateDataCost(totalBytes)

        return DataUsageSummary(
            totalBytes = totalBytes,
            totalFormatted = formatBytes(totalBytes),
            downloadCount = downloadCount,
            estimatedCostKES = estimatedCost.kes,
            estimatedCostUSD = estimatedCost.usd
        )
    }

    /**
     * Get the smallest available model ID for a given model family.
     * Used for progressive download: start with Q2_K, upgrade to Q4_K_M on WiFi.
     *
     * @param modelId The target model ID (e.g., "gemma-4-e2b-q4km")
     * @return The smallest available variant, or the original if no smaller exists
     */
    fun getSmallestVariant(modelId: String): String {
        return when {
            modelId.contains("gemma-4-e2b") && modelId.contains("q4") -> "gemma-4-e2b-q2k"
            modelId.contains("gemma-4-e2b") && modelId.contains("q3") -> "gemma-4-e2b-q2k"
            modelId.contains("qwen") && modelId.contains("0.8b") && modelId.contains("q4") -> "qwen-3.5-0.8b-q2k"
            else -> modelId
        }
    }

    /**
     * Check if a model has a smaller variant available.
     */
    fun hasSmallerVariant(modelId: String): Boolean {
        return getSmallestVariant(modelId) != modelId
    }

    /**
     * Get size comparison between model variants for UI display.
     */
    fun getSizeComparison(modelId: String): ModelSizeComparison? {
        val def = ModelRegistry.MODELS[modelId] ?: return null
        val smallId = getSmallestVariant(modelId)
        if (smallId == modelId) return null
        val smallDef = ModelRegistry.MODELS[smallId] ?: return null

        val savings = def.sizeBytes - smallDef.sizeBytes
        val savingsPct = (savings * 100 / def.sizeBytes).toInt()

        return ModelSizeComparison(
            fullModelId = modelId,
            fullSize = def.sizeBytes,
            fullSizeFormatted = formatBytes(def.sizeBytes),
            liteModelId = smallId,
            liteSize = smallDef.sizeBytes,
            liteSizeFormatted = formatBytes(smallDef.sizeBytes),
            savingsBytes = savings,
            savingsFormatted = formatBytes(savings),
            savingsPercent = savingsPct
        )
    }

    // ── Private Helpers ──

    private fun classifyCellularType(): ConnectionType {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> ConnectionType.CELLULAR_5G
            TelephonyManager.NETWORK_TYPE_LTE -> ConnectionType.CELLULAR_4G
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> ConnectionType.CELLULAR_3G
            else -> ConnectionType.CELLULAR_2G
        }
    }

    private fun estimateDataCost(bytes: Long): DataCost {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return DataCost(
            kes = (gb * KES_PER_GB).let { DecimalFormat("#").format(it) },
            usd = (gb * USD_PER_GB).let { DecimalFormat("#.##").format(it) },
            mbEquivalent = (bytes / (1024 * 1024)).toInt()
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    private fun refreshState() {
        _dataSaverState.value = DataSaverState(
            dataSaverEnabled = isDataSaverEnabled(),
            wifiOnlyLargeModels = isWifiOnlyForLargeModels(),
            connectionType = getConnectionType(),
            isUnmetered = isOnUnmeteredConnection(),
            totalDataUsedBytes = getTotalDataUsedBytes(),
            totalDataUsedFormatted = formatBytes(getTotalDataUsedBytes()),
            warnOnMobileData = shouldWarnOnMobileData()
        )
    }
}

// ── Data Classes & Enums ──

enum class ConnectionType {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_3G,
    CELLULAR_2G,
    NONE,
    UNKNOWN
}

enum class DownloadAction {
    /** Download immediately — no issues */
    DOWNLOAD_NOW,
    /** Download with a soft warning about data usage */
    DOWNLOAD_WITH_WARNING,
    /** Requires explicit user confirmation (large file on mobile data) */
    CONFIRM_REQUIRED,
    /** Blocked — network too slow or no connection */
    BLOCKED,
    /** Wait for better network (e.g., WiFi) */
    WAIT_FOR_NETWORK
}

data class DataSaverState(
    val dataSaverEnabled: Boolean = true,
    val wifiOnlyLargeModels: Boolean = false,
    val connectionType: ConnectionType = ConnectionType.UNKNOWN,
    val isUnmetered: Boolean = false,
    val totalDataUsedBytes: Long = 0,
    val totalDataUsedFormatted: String = "0 B",
    val warnOnMobileData: Boolean = true
)

data class DownloadRecommendation(
    val action: DownloadAction,
    val message: String,
    val messageEn: String,
    val dataCost: DataCost?,
    val showWarning: Boolean,
    val suggestAlternative: Boolean = false
)

data class DataCost(
    val kes: String,
    val usd: String,
    val mbEquivalent: Int
)

data class DataUsageSummary(
    val totalBytes: Long,
    val totalFormatted: String,
    val downloadCount: Int,
    val estimatedCostKES: String,
    val estimatedCostUSD: String
)

data class ModelSizeComparison(
    val fullModelId: String,
    val fullSize: Long,
    val fullSizeFormatted: String,
    val liteModelId: String,
    val liteSize: Long,
    val liteSizeFormatted: String,
    val savingsBytes: Long,
    val savingsFormatted: String,
    val savingsPercent: Int
)

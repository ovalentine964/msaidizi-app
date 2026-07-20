package com.msaidizi.app.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.ai.DataSaverManager
import com.msaidizi.app.core.ai.ConnectionType
import com.msaidizi.app.core.ai.DataUsageSummary
import com.msaidizi.app.core.ai.DownloadRecommendation
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.core.model.ModelVersionTracker
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.voice.ModelDef
import com.msaidizi.app.voice.ModelRegistry
import com.msaidizi.app.voice.ModelState
import com.msaidizi.app.voice.transfer.ModelTransfer
import com.msaidizi.app.voice.transfer.SdCardModelLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for model download management screen.
 * Exposes tiered model status, download progress, and transfer options.
 */
@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    application: Application,
    private val modelRegistry: ModelRegistry,
    private val networkMonitor: NetworkMonitor,
    private val versionTracker: ModelVersionTracker,
    private val modelTransfer: ModelTransfer,
    private val sdCardLoader: SdCardModelLoader,
    private val dataSaverManager: DataSaverManager,
    private val modelDownloader: com.msaidizi.app.core.ai.ModelDownloader
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModelDownloadUiState())
    val uiState: StateFlow<ModelDownloadUiState> = _uiState.asStateFlow()

    init {
        // Observe download state changes
        viewModelScope.launch {
            modelRegistry.downloadState.collect { states ->
                updateUiState()
            }
        }
        viewModelScope.launch {
            modelRegistry.downloadProgress.collect { progress ->
                updateUiState()
            }
        }
        viewModelScope.launch {
            modelTransfer.transferState.collect { state ->
                updateUiState()
            }
        }
        viewModelScope.launch {
            sdCardLoader.state.collect { state ->
                updateUiState()
            }
        }
        // Observe data-saver state
        viewModelScope.launch {
            dataSaverManager.dataSaverState.collect {
                updateUiState()
            }
        }
        // Observe detailed download progress (speed, ETA, data usage)
        viewModelScope.launch {
            modelDownloader.detailedProgress.collect {
                updateUiState()
            }
        }
        updateUiState()
    }

    private fun updateUiState() {
        val models = ModelRegistry.MODELS.values.map { def ->
            val isReady = modelRegistry.isModelReady(def.id)
            val state = modelRegistry.downloadState.value[def.id] ?: if (isReady) ModelState.READY else ModelState.NOT_DOWNLOADED
            val progress = modelRegistry.downloadProgress.value[def.id] ?: if (isReady) 1f else 0f
            val version = versionTracker.readVersion(def.id)
            val detailedProgress = modelDownloader.detailedProgress.value[def.id]

            ModelStatusUi(
                id = def.id,
                name = getModelDisplayName(def.id),
                description = getModelDescription(def.id),
                tier = def.tier,
                sizeBytes = def.sizeBytes,
                state = state,
                progress = progress,
                version = version ?: def.version,
                currentVersion = def.version,
                updateAvailable = version != null && version != def.version,
                speedFormatted = detailedProgress?.speedFormatted,
                etaFormatted = detailedProgress?.etaFormatted,
                downloadedFormatted = detailedProgress?.downloadedFormatted,
                totalFormatted = detailedProgress?.totalFormatted ?: formatBytes(def.sizeBytes),
                dataSaverRecommendation = dataSaverManager.getDownloadRecommendation(def.sizeBytes)
            )
        }

        val tier1Ready = modelRegistry.isTierReady(ModelTier.FIRST_LAUNCH)
        val tier2Ready = modelRegistry.isTierReady(ModelTier.ON_DEMAND)
        val storageUsed = modelRegistry.getStorageUsedFormatted()
        val dataUsageSummary = dataSaverManager.getDataUsageSummary()

        _uiState.value = ModelDownloadUiState(
            models = models,
            tier1Ready = tier1Ready,
            tier2Ready = tier2Ready,
            storageUsed = storageUsed,
            transferState = modelTransfer.transferState.value,
            sdCardState = sdCardLoader.state.value,
            isSdCardAvailable = sdCardLoader.isSdCardAvailable(),
            isWifiDirectAvailable = modelTransfer.isWifiDirectAvailable(),
            isWifi = networkMonitor.isWifi(),
            dataSaverEnabled = dataSaverManager.isDataSaverEnabled(),
            connectionType = dataSaverManager.getConnectionType(),
            dataUsageSummary = dataUsageSummary
        )
    }

    // ────────────── Actions ──────────────

    fun downloadTier1() {
        viewModelScope.launch {
            Timber.i("Starting Tier 1 download")
            modelRegistry.downloadTier(ModelTier.FIRST_LAUNCH) { modelId, progress ->
                Timber.d("Tier 1 download: %s %.0f%%", modelId, progress * 100)
            }
            updateUiState()
        }
    }

    fun downloadTier2() {
        viewModelScope.launch {
            Timber.i("Starting Tier 2 download")
            modelRegistry.downloadTier(ModelTier.ON_DEMAND) { modelId, progress ->
                Timber.d("Tier 2 download: %s %.0f%%", modelId, progress * 100)
            }
            updateUiState()
        }
    }

    fun downloadSingleModel(modelId: String) {
        viewModelScope.launch {
            Timber.i("Starting download for model: %s", modelId)
            modelRegistry.downloadModel(modelId) { progress ->
                Timber.d("Download %s: %.0f%%", modelId, progress * 100)
            }
            updateUiState()
        }
    }

    fun sendModelViaBluetooth(activity: android.app.Activity, modelId: String) {
        modelTransfer.sendModelViaBluetooth(
            activity,
            modelId
        )
    }

    fun scanSdCard() {
        viewModelScope.launch {
            val found = sdCardLoader.scanForModels()
            Timber.i("SD card scan: found %d models", found.size)
        }
    }

    fun copyFromSdCard() {
        viewModelScope.launch {
            val found = sdCardLoader.scanForModels()
            if (found.isNotEmpty()) {
                sdCardLoader.copyModelsFromSdCard(found) { modelId, progress ->
                    Timber.d("SD copy: %s %.0f%%", modelId, progress * 100)
                }
            }
            updateUiState()
        }
    }

    fun deleteModel(modelId: String) {
        modelRegistry.deleteModel(modelId)
        versionTracker.removeVersion(modelId)
        updateUiState()
    }

    fun resetTransferState() {
        modelTransfer.resetState()
        updateUiState()
    }

    /**
     * Toggle data-saver mode.
     * When enabled, large downloads require WiFi or explicit confirmation.
     */
    fun toggleDataSaver() {
        dataSaverManager.setDataSaverEnabled(!dataSaverManager.isDataSaverEnabled())
    }

    /**
     * Download a model with explicit user confirmation (bypasses data-saver warning).
     * Use when user taps "Download anyway" on mobile data.
     */
    fun downloadWithConfirmation(modelId: String) {
        viewModelScope.launch {
            Timber.i("Starting confirmed download for model: %s", modelId)
            modelDownloader.downloadModel(modelId, forceNetwork = true)
            updateUiState()
        }
    }

    /**
     * Download the smallest available variant of a model.
     * For data-limited users who want to start with Q2_K.
     */
    fun downloadLiteVariant(modelId: String) {
        viewModelScope.launch {
            val liteId = dataSaverManager.getSmallestVariant(modelId)
            Timber.i("Downloading lite variant: %s (original: %s)", liteId, modelId)
            modelDownloader.downloadModel(liteId, forceNetwork = true)
            // Queue full model for WiFi upgrade
            modelDownloader.queueUpgradeDownload(modelId)
            updateUiState()
        }
    }

    private fun getModelDisplayName(id: String): String = when (id) {
        "silero-vad" -> "Sauti ya Kugundua (VAD)"
        "whisper-tiny-int4" -> "Sauti ya Maandishi (Whisper)"
        "piper-swahili" -> "Sauti ya Kiswahili (Piper)"
        "gemma-4-e2b-q4km" -> "Msaidizi wa AI (Gemma 4)"
        "gemma-4-e2b-q3km" -> "Msaidizi wa AI (Gemma 4 Lite)"
        "gemma-4-e2b-q2k" -> "Msaidizi wa AI (Gemma 4 Mini)"
        "qwen-3.5-0.8b-q4km" -> "Msaidizi wa Akili (Qwen — Msaada)"
        "qwen-3.5-0.8b-q2k" -> "Msaidizi wa Akili (Qwen Mini)"
        else -> id
    }

    private fun getModelDescription(id: String): String = when (id) {
        "silero-vad" -> "Inagundua sauti — inafanya kazi mara moja"
        "whisper-tiny-int4" -> "Inabadilisha sauti kuwa maandishi"
        "piper-swahili" -> "Inazungumza Kiswahili"
        "gemma-4-e2b-q4km" -> "Msaidizi mkuu — jibu la maswali yoyote"
        "gemma-4-e2b-q3km" -> "Msaidizi wa simu ndogo — jibu la maswali"
        "gemma-4-e2b-q2k" -> "Msaidizi mwepesi — kidogo data, bado inafanya kazi"
        "qwen-3.5-0.8b-q4km" -> "Msaidizi wa dharura — ikiwa Gemma haifanyi kazi"
        "qwen-3.5-0.8b-q2k" -> "Msaidizi mwepesi sana — data ndogo zaidi"
        else -> ""
    }
}

data class ModelDownloadUiState(
    val models: List<ModelStatusUi> = emptyList(),
    val tier1Ready: Boolean = false,
    val tier2Ready: Boolean = false,
    val storageUsed: String = "0 MB",
    val transferState: ModelTransfer.TransferState = ModelTransfer.TransferState.Idle,
    val sdCardState: SdCardModelLoader.SdCardState = SdCardModelLoader.SdCardState.Idle,
    val isSdCardAvailable: Boolean = false,
    val isWifiDirectAvailable: Boolean = false,
    val isWifi: Boolean = false,
    val dataSaverEnabled: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.UNKNOWN,
    val dataUsageSummary: DataUsageSummary? = null
)

data class ModelStatusUi(
    val id: String,
    val name: String,
    val description: String,
    val tier: ModelTier,
    val sizeBytes: Long,
    val state: ModelState,
    val progress: Float,
    val version: String,
    val currentVersion: String,
    val updateAvailable: Boolean,
    /** Current download speed (e.g., "2.5 MB/s") */
    val speedFormatted: String? = null,
    /** Estimated time remaining (e.g., "5m 30s") */
    val etaFormatted: String? = null,
    /** Bytes downloaded so far (e.g., "250 MB") */
    val downloadedFormatted: String? = null,
    /** Total size formatted (e.g., "580 MB") */
    val totalFormatted: String? = null,
    /** Data-saver recommendation for this model */
    val dataSaverRecommendation: DownloadRecommendation? = null
)

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

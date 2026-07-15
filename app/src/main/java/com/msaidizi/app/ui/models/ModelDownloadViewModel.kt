package com.msaidizi.app.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val sdCardLoader: SdCardModelLoader
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
        updateUiState()
    }

    private fun updateUiState() {
        val models = ModelRegistry.MODELS.values.map { def ->
            val isReady = modelRegistry.isModelReady(def.id)
            val state = modelRegistry.downloadState.value[def.id] ?: if (isReady) ModelState.READY else ModelState.NOT_DOWNLOADED
            val progress = modelRegistry.downloadProgress.value[def.id] ?: if (isReady) 1f else 0f
            val version = versionTracker.readVersion(def.id)

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
                updateAvailable = version != null && version != def.version
            )
        }

        val tier1Ready = modelRegistry.isTierReady(ModelTier.FIRST_LAUNCH)
        val tier2Ready = modelRegistry.isTierReady(ModelTier.ON_DEMAND)
        val storageUsed = modelRegistry.getStorageUsedFormatted()

        _uiState.value = ModelDownloadUiState(
            models = models,
            tier1Ready = tier1Ready,
            tier2Ready = tier2Ready,
            storageUsed = storageUsed,
            transferState = modelTransfer.transferState.value,
            sdCardState = sdCardLoader.state.value,
            isSdCardAvailable = sdCardLoader.isSdCardAvailable(),
            isWifiDirectAvailable = modelTransfer.isWifiDirectAvailable(),
            isWifi = networkMonitor.isWifi()
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

    private fun getModelDisplayName(id: String): String = when (id) {
        "silero-vad" -> "Sauti ya Kugundua (VAD)"
        "whisper-tiny-int4" -> "Sauti ya Maandishi (Whisper)"
        "piper-swahili" -> "Sauti ya Kiswahili (Piper)"
        "qwen-3.5-0.8b-q4km" -> "Akili ya AI (Qwen LLM)"
        else -> id
    }

    private fun getModelDescription(id: String): String = when (id) {
        "silero-vad" -> "Inagundua sauti — inafanya kazi mara moja"
        "whisper-tiny-int4" -> "Inabadilisha sauti kuwa maandishi"
        "piper-swahili" -> "Inazungumza Kiswahili"
        "qwen-3.5-0.8b-q4km" -> "Jibu la maswali yoyote"
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
    val isWifi: Boolean = false
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
    val updateAvailable: Boolean
)

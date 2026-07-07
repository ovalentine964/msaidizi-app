package com.msaidizi.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.ai.BundledModelManager
import com.msaidizi.app.core.ai.BundledModelState
import com.msaidizi.app.core.ai.FullModelDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the model setup onboarding screen.
 * Manages bundled model state and full model download progress.
 */
@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    private val bundledModelManager: BundledModelManager
) : ViewModel() {

    val bundledModelState: StateFlow<BundledModelState> = bundledModelManager.bundledModelState
    val fullModelProgress: StateFlow<Float> = bundledModelManager.fullModelProgress
    val downloadState: StateFlow<FullModelDownloadState> = bundledModelManager.downloadState

    private val _wifiOnly = MutableStateFlow(bundledModelManager.isWifiOnlyDownload())
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    init {
        viewModelScope.launch {
            bundledModelManager.initialize()
        }
    }

    fun toggleWifiOnly() {
        val newValue = !_wifiOnly.value
        _wifiOnly.value = newValue
        bundledModelManager.setWifiOnlyDownload(newValue)
    }
}

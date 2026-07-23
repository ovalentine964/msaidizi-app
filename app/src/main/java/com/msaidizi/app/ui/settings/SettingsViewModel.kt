package com.msaidizi.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.metrics.PhaseMetrics
import com.msaidizi.app.data.database.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val metrics: PhaseMetrics
) : ViewModel() {

    private val prefs = context.getSharedPreferences("msaidizi_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.value = SettingsUiState(
            language = prefs.getString("language", "sw") ?: "sw",
            voiceEnabled = prefs.getBoolean("voice_enabled", true),
            businessType = prefs.getString("business_type", "Duka") ?: "Duka"
        )
    }

    fun setLanguage(language: String) {
        prefs.edit().putString("language", language).apply()
        _uiState.value = _uiState.value.copy(language = language)
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("voice_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(voiceEnabled = enabled)
    }

    fun setBusinessType(type: String) {
        prefs.edit().putString("business_type", type).apply()
        _uiState.value = _uiState.value.copy(businessType = type)
    }

    fun showMetrics() {
        viewModelScope.launch {
            val stats = metrics.getAllPhaseStats()
            // TODO: Show metrics dialog
        }
    }

    fun clearData() {
        viewModelScope.launch {
            database.clearAllTables()
        }
    }
}

data class SettingsUiState(
    val language: String = "sw",
    val voiceEnabled: Boolean = true,
    val businessType: String = "Duka"
)

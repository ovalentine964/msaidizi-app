package com.msaidizi.app.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.model.UserPreferences
import com.msaidizi.app.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// DataStore extension
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * ViewModel for Settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager
) : ViewModel() {

    companion object {
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val VOICE_SPEED_KEY = floatPreferencesKey("voice_speed")
        val AUTO_SYNC_KEY = booleanPreferencesKey("auto_sync")
        val WIFI_ONLY_KEY = booleanPreferencesKey("wifi_only")
        val BUSINESS_NAME_KEY = stringPreferencesKey("business_name")
        val BUSINESS_TYPE_KEY = stringPreferencesKey("business_type")
        val ONBOARDING_KEY = booleanPreferencesKey("onboarding_complete")
        val WHATSAPP_CONNECTED_KEY = booleanPreferencesKey("whatsapp_connected")
        val WHATSAPP_PHONE_KEY = stringPreferencesKey("whatsapp_phone")
        val PHONE_VERIFIED_KEY = booleanPreferencesKey("phone_verified")
        val VERIFICATION_CHANNEL_KEY = stringPreferencesKey("verification_channel")
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { prefs ->
                _uiState.value = SettingsUiState(
                    language = prefs[LANGUAGE_KEY] ?: "sw",
                    voiceSpeed = prefs[VOICE_SPEED_KEY] ?: 1.0f,
                    autoSync = prefs[AUTO_SYNC_KEY] ?: true,
                    wifiOnly = prefs[WIFI_ONLY_KEY] ?: true,
                    businessName = prefs[BUSINESS_NAME_KEY] ?: "",
                    businessType = prefs[BUSINESS_TYPE_KEY] ?: "",
                    onboardingComplete = prefs[ONBOARDING_KEY] ?: false,
                    whatsappConnected = prefs[WHATSAPP_CONNECTED_KEY] ?: false,
                    whatsappPhone = prefs[WHATSAPP_PHONE_KEY] ?: "",
                    phoneVerified = prefs[PHONE_VERIFIED_KEY] ?: false,
                    verificationChannel = prefs[VERIFICATION_CHANNEL_KEY] ?: ""
                )
            }
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[LANGUAGE_KEY] = language
            }
        }
    }

    fun setVoiceSpeed(speed: Float) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[VOICE_SPEED_KEY] = speed
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[AUTO_SYNC_KEY] = enabled
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[WIFI_ONLY_KEY] = enabled
            }
        }
    }

    fun setBusinessName(name: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[BUSINESS_NAME_KEY] = name
            }
        }
    }

    fun setBusinessType(type: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[BUSINESS_TYPE_KEY] = type
            }
        }
    }

    fun triggerSync() {
        viewModelScope.launch {
            syncManager.syncNow(viewModelScope)
        }
    }

    fun setWhatsAppConnected(connected: Boolean, phone: String = "") {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[WHATSAPP_CONNECTED_KEY] = connected
                if (phone.isNotBlank()) {
                    prefs[WHATSAPP_PHONE_KEY] = phone
                }
            }
        }
    }

    fun disconnectWhatsApp() {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[WHATSAPP_CONNECTED_KEY] = false
                prefs[WHATSAPP_PHONE_KEY] = ""
            }
        }
    }

    suspend fun getSyncInfo(): Map<String, Any> {
        return syncManager.getSyncInfo()
    }
}

data class SettingsUiState(
    val language: String = "sw",
    val voiceSpeed: Float = 1.0f,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val businessName: String = "",
    val businessType: String = "",
    val onboardingComplete: Boolean = false,
    /** Whether WhatsApp is connected for report delivery */
    val whatsappConnected: Boolean = false,
    /** WhatsApp phone number if connected */
    val whatsappPhone: String = "",
    /** Whether phone was verified via SMS */
    val phoneVerified: Boolean = false,
    /** Verification channel used during onboarding */
    val verificationChannel: String = ""
)

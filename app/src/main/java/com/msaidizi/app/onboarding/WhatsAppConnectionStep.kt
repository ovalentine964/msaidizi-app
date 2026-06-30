package com.msaidizi.app.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.data.model.WhatsAppError
import com.msaidizi.app.utils.PhoneValidator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WhatsAppConnectionStep(
    application: Application,
    private val api: MsaidiziApi,
    private val onboardingData: OnboardingSessionData
) : AndroidViewModel(application) {

    enum class StepPhase {
        PHONE_INPUT, PHONE_CONFIRM, SENDING, WAITING_FOR_RECEIPT, CONNECTED, ERROR
    }

    data class UiState(
        val phase: StepPhase = StepPhase.PHONE_INPUT,
        val phoneInput: String = "",
        val formattedPhone: String = "",
        val normalizedPhone: String = "",
        val validationError: String? = null,
        val isSending: Boolean = false,
        val isWaiting: Boolean = false,
        val errorMessage: String? = null,
        val error: WhatsAppError? = null,
        val canRetry: Boolean = true,
        val elapsedSeconds: Int = 0,
        val assistantName: String = onboardingData.assistantName ?: "Msaidizi",
        val userName: String = onboardingData.userName ?: "mteja"
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val verificationManager = WhatsAppVerificationManager(api)

    init {
        viewModelScope.launch {
            verificationManager.state.collect { state -> handleVerificationState(state) }
        }
    }

    fun onPhoneInputChanged(input: String) {
        val filtered = input.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        _uiState.update { it.copy(phoneInput = filtered, validationError = null) }
    }

    fun onPhoneSubmit() {
        val input = _uiState.value.phoneInput.trim()
        when (val result = PhoneValidator.validate(input)) {
            is PhoneValidator.ValidationResult.Valid -> {
                val formatted = PhoneValidator.formatForDisplay(input)
                _uiState.update { it.copy(phase = StepPhase.PHONE_CONFIRM, formattedPhone = formatted, normalizedPhone = result.normalized, validationError = null) }
            }
            is PhoneValidator.ValidationResult.Invalid -> {
                _uiState.update { it.copy(validationError = result.reason) }
            }
        }
    }

    fun onPhoneConfirmed() {
        val state = _uiState.value
        _uiState.update { it.copy(phase = StepPhase.SENDING, isSending = true, errorMessage = null) }
        verificationManager.connect(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            userName = state.userName,
            assistantName = state.assistantName,
            language = onboardingData.language ?: "sw",
            reportTime = onboardingData.reportTime ?: "evening",
            scope = viewModelScope
        )
    }

    fun onReceiptConfirmed() {
        verificationManager.confirmReceipt(viewModelScope)
    }

    fun onEditPhone() {
        verificationManager.reset()
        _uiState.update { it.copy(phase = StepPhase.PHONE_INPUT, errorMessage = null, validationError = null) }
    }

    fun onRetry() {
        val state = _uiState.value
        _uiState.update { it.copy(phase = StepPhase.SENDING, isSending = true, errorMessage = null, error = null) }
        verificationManager.retry(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            userName = state.userName,
            assistantName = state.assistantName,
            language = onboardingData.language ?: "sw",
            reportTime = onboardingData.reportTime ?: "evening",
            scope = viewModelScope
        )
    }

    fun onSkip() {
        verificationManager.reset()
        onboardingData.whatsappSkipped = true
    }

    fun onContinue(): OnboardingSessionData {
        onboardingData.whatsappPhone = _uiState.value.normalizedPhone
        onboardingData.whatsappConnected = true
        return onboardingData
    }

    private fun handleVerificationState(state: WhatsAppVerificationManager.VerificationState) {
        when (state) {
            is WhatsAppVerificationManager.VerificationState.Idle -> {}
            is WhatsAppVerificationManager.VerificationState.Sending -> _uiState.update { it.copy(phase = StepPhase.SENDING, isSending = true, isWaiting = false) }
            is WhatsAppVerificationManager.VerificationState.WaitingForConfirmation -> _uiState.update { it.copy(phase = StepPhase.WAITING_FOR_RECEIPT, isSending = false, isWaiting = true, elapsedSeconds = (state.elapsedMs / 1000).toInt()) }
            is WhatsAppVerificationManager.VerificationState.Polling -> {}
            is WhatsAppVerificationManager.VerificationState.Connected -> _uiState.update { it.copy(phase = StepPhase.CONNECTED, isSending = false, isWaiting = false, normalizedPhone = state.phone, errorMessage = null) }
            is WhatsAppVerificationManager.VerificationState.Failed -> _uiState.update { it.copy(phase = StepPhase.ERROR, isSending = false, isWaiting = false, error = state.error, errorMessage = state.error.messageSw, canRetry = state.canRetry) }
            is WhatsAppVerificationManager.VerificationState.TimedOut -> _uiState.update { it.copy(phase = StepPhase.ERROR, isSending = false, isWaiting = false, error = WhatsAppError.VERIFICATION_EXPIRED, errorMessage = "Muda umekwisha. Ujumbe wa WhatsApp haujafika bado. Tafadhali hakikisha una WhatsApp imewashwa.", canRetry = true) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        verificationManager.destroy()
    }
}

data class OnboardingSessionData(
    var userId: String? = null,
    var userName: String? = null,
    var assistantName: String? = null,
    var businessDescription: String? = null,
    var businessLocation: String? = null,
    var businessHours: String? = null,
    var whatsappPhone: String? = null,
    var whatsappConnected: Boolean = false,
    var whatsappSkipped: Boolean = false,
    var language: String? = null,
    var reportTime: String? = null,
    var speed: String? = null
)

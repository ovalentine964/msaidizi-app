package com.msaidizi.app.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.data.model.*
import com.msaidizi.app.utils.PhoneValidator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * PhoneVerificationStep — unified phone verification ViewModel.
 *
 * Supports two verification channels:
 * 1. **SMS** — Server sends a 6-digit code via SMS (Africa's Talking API)
 * 2. **WhatsApp** — Server sends verification via WhatsApp Business API
 *
 * WhatsApp is OPTIONAL. Users can skip it entirely and add it later
 * from settings. Core features work without WhatsApp.
 *
 * ## Flow
 * 1. Phone input → validate
 * 2. Choose channel: "SMS" or "WhatsApp" (SMS is default)
 * 3a. SMS: Request code → manual entry (or auto-detect) → verify
 * 3b. WhatsApp: Send message → wait for receipt → confirm
 * 4. Complete → continue onboarding
 *
 * ## Academic Foundation
 * - ECO 204: 30% of Kenyan informal workers lack WhatsApp
 * - HCI: Progressive disclosure — don't overwhelm new users
 * - PSY 101: Choice architecture — SMS as default for accessibility
 */
class PhoneVerificationStep(
    application: Application,
    private val api: MsaidiziApi,
    private val onboardingData: OnboardingSessionData
) : AndroidViewModel(application) {

    enum class StepPhase {
        /** Initial state — phone number input */
        PHONE_INPUT,
        /** Phone confirmed, choose verification channel */
        CHANNEL_SELECT,
        /** SMS: Sending verification code */
        SMS_SENDING,
        /** SMS: Code sent, waiting for user to enter */
        SMS_CODE_INPUT,
        /** SMS: Verifying the entered code */
        SMS_VERIFYING,
        /** WhatsApp: Sending verification message */
        WHATSAPP_SENDING,
        /** WhatsApp: Waiting for user to confirm receipt */
        WHATSAPP_WAITING,
        /** Verification complete */
        VERIFIED,
        /** Error state */
        ERROR
    }

    data class UiState(
        val phase: StepPhase = StepPhase.PHONE_INPUT,
        val phoneInput: String = "",
        val formattedPhone: String = "",
        val normalizedPhone: String = "",
        val validationError: String? = null,
        val selectedChannel: VerificationChannel = VerificationChannel.SMS,
        val codeInput: String = "",
        val codeError: String? = null,
        val isSending: Boolean = false,
        val isWaiting: Boolean = false,
        val isVerifying: Boolean = false,
        val errorMessage: String? = null,
        val error: Any? = null,  // WhatsAppError or SmsVerificationError
        val canRetry: Boolean = true,
        val elapsedSeconds: Int = 0,
        val assistantName: String = "Msaidizi",
        val userName: String = "mteja",
        val smsExpiresInMs: Long = 0,
        val smsExpiresInSeconds: Int = 0
    )

    private val _uiState = MutableStateFlow(
        UiState(
            assistantName = onboardingData.assistantName ?: "Msaidizi",
            userName = onboardingData.userName ?: "mteja"
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val smsManager = SmsVerificationManager(api)
    private val whatsappManager = WhatsAppVerificationManager(api)

    init {
        // Observe SMS verification state
        viewModelScope.launch {
            smsManager.state.collect { state -> handleSmsState(state) }
        }
        // Observe WhatsApp verification state
        viewModelScope.launch {
            whatsappManager.state.collect { state -> handleWhatsAppState(state) }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PHONE INPUT
    // ═══════════════════════════════════════════════════════════

    fun onPhoneInputChanged(input: String) {
        val filtered = input.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        _uiState.update { it.copy(phoneInput = filtered, validationError = null) }
    }

    fun onPhoneSubmit() {
        val input = _uiState.value.phoneInput.trim()
        when (val result = PhoneValidator.validate(input)) {
            is PhoneValidator.ValidationResult.Valid -> {
                val formatted = PhoneValidator.formatForDisplay(input)
                _uiState.update {
                    it.copy(
                        phase = StepPhase.CHANNEL_SELECT,
                        formattedPhone = formatted,
                        normalizedPhone = result.normalized,
                        validationError = null
                    )
                }
            }
            is PhoneValidator.ValidationResult.Invalid -> {
                _uiState.update { it.copy(validationError = result.reason) }
            }
        }
    }

    fun onEditPhone() {
        smsManager.reset()
        whatsappManager.reset()
        _uiState.update {
            it.copy(
                phase = StepPhase.PHONE_INPUT,
                errorMessage = null,
                validationError = null,
                codeInput = "",
                codeError = null
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CHANNEL SELECTION
    // ═══════════════════════════════════════════════════════════

    fun onSelectSms() {
        _uiState.update { it.copy(selectedChannel = VerificationChannel.SMS) }
        startSmsVerification()
    }

    fun onSelectWhatsApp() {
        _uiState.update { it.copy(selectedChannel = VerificationChannel.WHATSAPP) }
        startWhatsAppVerification()
    }

    // ═══════════════════════════════════════════════════════════
    // SMS VERIFICATION
    // ═══════════════════════════════════════════════════════════

    private fun startSmsVerification() {
        val state = _uiState.value
        _uiState.update { it.copy(phase = StepPhase.SMS_SENDING, isSending = true, errorMessage = null) }
        smsManager.requestVerification(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            language = onboardingData.language ?: "sw",
            scope = viewModelScope
        )
    }

    fun onCodeInputChanged(code: String) {
        val filtered = code.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(codeInput = filtered, codeError = null) }
    }

    fun onCodeSubmit() {
        val code = _uiState.value.codeInput.trim()
        if (code.length < 4) {
            _uiState.update { it.copy(codeError = "Weka nambari ya uthibitisho") }
            return
        }
        _uiState.update { it.copy(phase = StepPhase.SMS_VERIFYING, isVerifying = true, codeError = null) }
        smsManager.verifyCode(code, viewModelScope)
    }

    /**
     * Auto-fill code from SMS auto-detection (Google SMS Consent API).
     */
    fun onSmsCodeAutoDetected(code: String) {
        _uiState.update { it.copy(codeInput = code, phase = StepPhase.SMS_VERIFYING, isVerifying = true) }
        smsManager.onSmsCodeAutoDetected(code, viewModelScope)
    }

    fun onResendSms() {
        val state = _uiState.value
        smsManager.retry(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            language = onboardingData.language ?: "sw",
            scope = viewModelScope
        )
    }

    // ═══════════════════════════════════════════════════════════
    // WHATSAPP VERIFICATION
    // ═══════════════════════════════════════════════════════════

    private fun startWhatsAppVerification() {
        val state = _uiState.value
        _uiState.update { it.copy(phase = StepPhase.WHATSAPP_SENDING, isSending = true, errorMessage = null) }
        whatsappManager.connect(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            userName = state.userName,
            assistantName = state.assistantName,
            language = onboardingData.language ?: "sw",
            reportTime = onboardingData.reportTime ?: "evening",
            scope = viewModelScope
        )
    }

    fun onWhatsAppReceiptConfirmed() {
        whatsappManager.confirmReceipt(viewModelScope)
    }

    fun onRetryWhatsApp() {
        val state = _uiState.value
        _uiState.update { it.copy(phase = StepPhase.WHATSAPP_SENDING, isSending = true, errorMessage = null, error = null) }
        whatsappManager.retry(
            rawPhone = state.normalizedPhone,
            userId = onboardingData.userId ?: "",
            userName = state.userName,
            assistantName = state.assistantName,
            language = onboardingData.language ?: "sw",
            reportTime = onboardingData.reportTime ?: "evening",
            scope = viewModelScope
        )
    }

    // ═══════════════════════════════════════════════════════════
    // SKIP / CONTINUE
    // ═══════════════════════════════════════════════════════════

    /**
     * Skip verification entirely. User can add WhatsApp later from settings.
     */
    fun onSkip() {
        smsManager.reset()
        whatsappManager.reset()
        onboardingData.verificationSkipped = true
        onboardingData.whatsappSkipped = true
    }

    /**
     * Switch from WhatsApp to SMS if WhatsApp fails.
     */
    fun onSwitchToSms() {
        whatsappManager.reset()
        _uiState.update { it.copy(selectedChannel = VerificationChannel.SMS) }
        startSmsVerification()
    }

    /**
     * Continue after successful verification.
     */
    fun onContinue(): OnboardingSessionData {
        val state = _uiState.value
        onboardingData.verifiedPhone = state.normalizedPhone
        onboardingData.phoneVerified = true
        onboardingData.verificationChannel = state.selectedChannel

        if (state.selectedChannel == VerificationChannel.WHATSAPP) {
            onboardingData.whatsappPhone = state.normalizedPhone
            onboardingData.whatsappConnected = true
        }

        return onboardingData
    }

    // ═══════════════════════════════════════════════════════════
    // STATE HANDLERS
    // ═══════════════════════════════════════════════════════════

    private fun handleSmsState(state: SmsVerificationManager.SmsState) {
        when (state) {
            is SmsVerificationManager.SmsState.Idle -> {}
            is SmsVerificationManager.SmsState.Sending -> {
                _uiState.update { it.copy(phase = StepPhase.SMS_SENDING, isSending = true, isWaiting = false) }
            }
            is SmsVerificationManager.SmsState.CodeSent -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.SMS_CODE_INPUT,
                        isSending = false,
                        smsExpiresInMs = state.expiresInMs,
                        smsExpiresInSeconds = (state.expiresInMs / 1000).toInt()
                    )
                }
            }
            is SmsVerificationManager.SmsState.Verifying -> {
                _uiState.update { it.copy(isVerifying = true) }
            }
            is SmsVerificationManager.SmsState.Verified -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.VERIFIED,
                        isSending = false,
                        isVerifying = false,
                        errorMessage = null
                    )
                }
            }
            is SmsVerificationManager.SmsState.Failed -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.ERROR,
                        isSending = false,
                        isVerifying = false,
                        error = state.error,
                        errorMessage = state.error.messageSw,
                        canRetry = state.canRetry
                    )
                }
            }
            is SmsVerificationManager.SmsState.TimedOut -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.ERROR,
                        isSending = false,
                        isVerifying = false,
                        error = SmsVerificationError.CODE_EXPIRED,
                        errorMessage = "Muda umekwisha. Omba nambari mpya.",
                        canRetry = true
                    )
                }
            }
        }
    }

    private fun handleWhatsAppState(state: WhatsAppVerificationManager.VerificationState) {
        when (state) {
            is WhatsAppVerificationManager.VerificationState.Idle -> {}
            is WhatsAppVerificationManager.VerificationState.Sending -> {
                _uiState.update { it.copy(phase = StepPhase.WHATSAPP_SENDING, isSending = true, isWaiting = false) }
            }
            is WhatsAppVerificationManager.VerificationState.WaitingForConfirmation -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.WHATSAPP_WAITING,
                        isSending = false,
                        isWaiting = true,
                        elapsedSeconds = (state.elapsedMs / 1000).toInt()
                    )
                }
            }
            is WhatsAppVerificationManager.VerificationState.Polling -> {}
            is WhatsAppVerificationManager.VerificationState.Connected -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.VERIFIED,
                        isSending = false,
                        isWaiting = false,
                        normalizedPhone = state.phone,
                        errorMessage = null
                    )
                }
            }
            is WhatsAppVerificationManager.VerificationState.Failed -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.ERROR,
                        isSending = false,
                        isWaiting = false,
                        error = state.error,
                        errorMessage = state.error.messageSw,
                        canRetry = state.canRetry
                    )
                }
            }
            is WhatsAppVerificationManager.VerificationState.TimedOut -> {
                _uiState.update {
                    it.copy(
                        phase = StepPhase.ERROR,
                        isSending = false,
                        isWaiting = false,
                        error = WhatsAppError.VERIFICATION_EXPIRED,
                        errorMessage = "Muda umekwisha. Ujumbe wa WhatsApp haujafika bado.",
                        canRetry = true
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        smsManager.destroy()
        whatsappManager.destroy()
    }
}

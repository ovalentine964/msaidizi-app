package com.msaidizi.app.onboarding

import android.util.Log
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.data.model.*
import com.msaidizi.app.utils.PhoneValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SmsVerificationManager — handles phone verification via SMS.
 *
 * Provides a fallback for users who don't have WhatsApp (30% of Kenyan
 * informal workers). Uses the server-side OTP API to send verification
 * codes via SMS.
 *
 * Flow:
 * 1. Request SMS verification → server sends code via SMS
 * 2. User enters code manually (or auto-detect via SMS consent API)
 * 3. Server confirms code → verification complete
 *
 * ## Academic Foundation
 * - ECO 204 (Development Economics): Mobile money adoption in Kenya
 *   shows ~70% WhatsApp penetration, meaning 30% need SMS fallback
 * - CS 101 (Security): 6-digit code with 5-minute expiry, rate limiting
 */
class SmsVerificationManager(
    private val api: MsaidiziApi
) {
    companion object {
        private const val TAG = "SmsVerify"
        private const val CODE_TIMEOUT_MS = 300_000L  // 5 minutes
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_SEND_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L
    }

    sealed class SmsState {
        object Idle : SmsState()
        object Sending : SmsState()
        data class CodeSent(
            val verificationId: String,
            val phone: String,
            val expiresInMs: Long = CODE_TIMEOUT_MS
        ) : SmsState()
        object Verifying : SmsState()
        data class Verified(val phone: String) : SmsState()
        data class Failed(val error: SmsVerificationError, val canRetry: Boolean = true) : SmsState()
        data class TimedOut(val verificationId: String, val phone: String) : SmsState()
    }

    private val _state = MutableStateFlow<SmsState>(SmsState.Idle)
    val state: StateFlow<SmsState> = _state.asStateFlow()

    private var verificationJob: Job? = null
    private var currentVerificationId: String? = null
    private val defaultScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Request SMS verification code for the given phone number.
     * The server will send a 6-digit code via SMS.
     */
    fun requestVerification(
        rawPhone: String,
        userId: String,
        language: String = "sw",
        scope: CoroutineScope = defaultScope
    ) {
        verificationJob?.cancel()
        verificationJob = scope.launch {
            try {
                val validation = PhoneValidator.validate(rawPhone)
                if (validation is PhoneValidator.ValidationResult.Invalid) {
                    _state.value = SmsState.Failed(SmsVerificationError.INVALID_PHONE, canRetry = true)
                    return@launch
                }

                val normalizedPhone = (validation as PhoneValidator.ValidationResult.Valid).normalized
                _state.value = SmsState.Sending

                val response = sendSmsRequest(normalizedPhone, userId, language)

                if (response == null) {
                    _state.value = SmsState.Failed(SmsVerificationError.NETWORK_ERROR, canRetry = true)
                    return@launch
                }

                when (response.status) {
                    "error" -> {
                        val error = SmsVerificationError.fromCode(response.errorCode)
                        _state.value = SmsState.Failed(error, canRetry = error != SmsVerificationError.TOO_MANY_ATTEMPTS)
                    }
                    "sent", "ok" -> {
                        val verificationId = response.verificationId
                        if (verificationId == null) {
                            _state.value = SmsState.Failed(SmsVerificationError.UNKNOWN_ERROR, canRetry = true)
                            return@launch
                        }
                        currentVerificationId = verificationId
                        _state.value = SmsState.CodeSent(
                            verificationId = verificationId,
                            phone = normalizedPhone,
                            expiresInMs = (response.expiresIn * 1000).toLong()
                        )
                    }
                    else -> _state.value = SmsState.Failed(SmsVerificationError.UNKNOWN_ERROR, canRetry = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error in SMS verification", e)
                _state.value = SmsState.Failed(SmsVerificationError.UNKNOWN_ERROR, canRetry = true)
            }
        }
    }

    /**
     * Verify the code entered by the user (manual entry).
     */
    fun verifyCode(
        code: String,
        scope: CoroutineScope = defaultScope
    ) {
        val verificationId = currentVerificationId ?: return
        verificationJob?.cancel()
        verificationJob = scope.launch {
            try {
                _state.value = SmsState.Verifying

                var lastResponse: SmsVerifyCodeResponse? = null
                repeat(MAX_SEND_RETRIES) { attempt ->
                    try {
                        val response = api.confirmSmsVerification(
                            SmsVerifyCodeRequest(verificationId = verificationId, code = code)
                        )
                        if (response.isSuccessful && response.body() != null) {
                            lastResponse = response.body()
                            return@repeat
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Verify attempt ${attempt + 1} failed", e)
                    }
                    if (attempt < MAX_SEND_RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
                }

                val body = lastResponse
                if (body == null) {
                    _state.value = SmsState.Failed(SmsVerificationError.NETWORK_ERROR, canRetry = true)
                    return@launch
                }

                when (body.status) {
                    "verified", "success" -> {
                        _state.value = SmsState.Verified(body.userId ?: "")
                    }
                    "invalid_code" -> {
                        _state.value = SmsState.Failed(SmsVerificationError.INVALID_CODE, canRetry = true)
                    }
                    "expired" -> {
                        _state.value = SmsState.Failed(SmsVerificationError.CODE_EXPIRED, canRetry = true)
                    }
                    "too_many_attempts" -> {
                        _state.value = SmsState.Failed(SmsVerificationError.TOO_MANY_ATTEMPTS, canRetry = false)
                    }
                    "error" -> {
                        val error = SmsVerificationError.fromCode(body.errorCode)
                        _state.value = SmsState.Failed(error, canRetry = true)
                    }
                    else -> _state.value = SmsState.Failed(SmsVerificationError.UNKNOWN_ERROR, canRetry = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error verifying code", e)
                _state.value = SmsState.Failed(SmsVerificationError.NETWORK_ERROR, canRetry = true)
            }
        }
    }

    /**
     * Auto-verify using code detected from SMS (Google SMS Consent API).
     */
    fun onSmsCodeAutoDetected(code: String, scope: CoroutineScope = defaultScope) {
        verifyCode(code, scope)
    }

    fun retry(
        rawPhone: String,
        userId: String,
        language: String = "sw",
        scope: CoroutineScope = defaultScope
    ) {
        reset()
        requestVerification(rawPhone, userId, language, scope)
    }

    fun reset() {
        verificationJob?.cancel()
        verificationJob = null
        currentVerificationId = null
        _state.value = SmsState.Idle
    }

    fun destroy() {
        verificationJob?.cancel()
        verificationJob = null
        defaultScope.cancel()
    }

    private suspend fun sendSmsRequest(
        phone: String,
        userId: String,
        language: String
    ): SmsVerificationResponse? {
        repeat(MAX_SEND_RETRIES) { attempt ->
            try {
                val response = api.requestSmsVerification(
                    SmsVerificationRequest(phone = phone, userId = userId, language = language)
                )
                if (response.isSuccessful && response.body() != null) return response.body()
                if (response.code() == 429) {
                    val retryAfter = response.headers()["Retry-After"]?.toLongOrNull() ?: 30
                    delay(retryAfter * 1000)
                    return null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "SMS request attempt ${attempt + 1} exception", e)
            }
            if (attempt < MAX_SEND_RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return null
    }
}

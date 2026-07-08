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
 * WhatsAppVerificationManager — orchestrates the full WhatsApp connection flow.
 */
class WhatsAppVerificationManager(
    private val api: MsaidiziApi
) {
    companion object {
        private const val TAG = "WhatsAppVerify"
        private const val VERIFICATION_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_CONNECT_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L
    }

    sealed class VerificationState {
        object Idle : VerificationState()
        object Sending : VerificationState()
        data class WaitingForConfirmation(val verificationId: String, val phone: String, val elapsedMs: Long = 0) : VerificationState()
        data class Polling(val verificationId: String, val attempt: Int) : VerificationState()
        data class Connected(val phone: String, val whatsappId: String) : VerificationState()
        data class Failed(val error: WhatsAppError, val canRetry: Boolean = true) : VerificationState()
        data class TimedOut(val verificationId: String, val phone: String) : VerificationState()
    }

    private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    private var verificationJob: Job? = null
    private var currentVerificationId: String? = null
    private val defaultScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun connect(
        rawPhone: String,
        userId: String,
        userName: String,
        assistantName: String,
        language: String = "sw",
        reportTime: String = "evening",
        scope: CoroutineScope = defaultScope
    ) {
        verificationJob?.cancel()
        verificationJob = scope.launch {
            try {
                val validation = PhoneValidator.validate(rawPhone)
                if (validation is PhoneValidator.ValidationResult.Invalid) {
                    _state.value = VerificationState.Failed(WhatsAppError.UNKNOWN_ERROR, canRetry = true)
                    return@launch
                }

                val normalizedPhone = (validation as PhoneValidator.ValidationResult.Valid).normalized
                _state.value = VerificationState.Sending

                val connectResponse = sendConnectRequest(normalizedPhone, userId, userName, assistantName, language, reportTime)

                if (connectResponse == null) {
                    _state.value = VerificationState.Failed(WhatsAppError.NETWORK_ERROR, canRetry = true)
                    return@launch
                }

                when (connectResponse.status) {
                    "error" -> {
                        val error = WhatsAppError.fromCode(connectResponse.errorCode)
                        _state.value = VerificationState.Failed(error, canRetry = error != WhatsAppError.NUMBER_NOT_ON_WHATSAPP)
                    }
                    "already_connected" -> {
                        _state.value = VerificationState.Connected(normalizedPhone, connectResponse.verificationId ?: "")
                    }
                    "sent" -> {
                        val verificationId = connectResponse.verificationId
                        if (verificationId == null) {
                            _state.value = VerificationState.Failed(WhatsAppError.UNKNOWN_ERROR, canRetry = true)
                            return@launch
                        }
                        currentVerificationId = verificationId
                        pollForConfirmation(verificationId, normalizedPhone)
                    }
                    else -> _state.value = VerificationState.Failed(WhatsAppError.UNKNOWN_ERROR, canRetry = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in connect flow", e)
                _state.value = VerificationState.Failed(WhatsAppError.UNKNOWN_ERROR, canRetry = true)
            }
        }
    }

    fun confirmReceipt(scope: CoroutineScope = defaultScope) {
        val verificationId = currentVerificationId ?: return
        scope.launch {
            try {
                val response = api.verifyWhatsApp(WhatsAppVerifyRequest(verificationId = verificationId))
                if (response.isSuccessful && response.body()?.status == "connected") {
                    val body = requireNotNull(response.body()) { "WhatsApp verify response body must not be null" }
                    _state.value = VerificationState.Connected(
                        (_state.value as? VerificationState.WaitingForConfirmation)?.phone ?: "",
                        body.whatsappId ?: ""
                    )
                } else {
                    _state.value = VerificationState.Failed(WhatsAppError.UNKNOWN_ERROR, canRetry = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error confirming receipt", e)
                _state.value = VerificationState.Failed(WhatsAppError.NETWORK_ERROR, canRetry = true)
            }
        }
    }

    fun retry(rawPhone: String, userId: String, userName: String, assistantName: String, language: String = "sw", reportTime: String = "evening", scope: CoroutineScope = defaultScope) {
        reset()
        connect(rawPhone, userId, userName, assistantName, language, reportTime, scope)
    }

    fun reset() {
        verificationJob?.cancel()
        verificationJob = null
        currentVerificationId = null
        _state.value = VerificationState.Idle
    }

    fun destroy() {
        verificationJob?.cancel()
        verificationJob = null
        defaultScope.cancel()
    }

    private suspend fun sendConnectRequest(phone: String, userId: String, userName: String, assistantName: String, language: String, reportTime: String): WhatsAppConnectResponse? {
        repeat(MAX_CONNECT_RETRIES) { attempt ->
            try {
                val response = api.connectWhatsApp(WhatsAppConnectRequest(phone = phone, userId = userId, name = userName, assistantName = assistantName, language = language, reportTime = reportTime))
                if (response.isSuccessful && response.body() != null) return response.body()
                if (response.code() == 429) {
                    val retryAfter = response.headers()["Retry-After"]?.toLongOrNull() ?: 30
                    delay(retryAfter * 1000)
                    return null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connect attempt ${attempt + 1} exception", e)
            }
            if (attempt < MAX_CONNECT_RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return null
    }

    private suspend fun pollForConfirmation(verificationId: String, phone: String) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startTime
            _state.value = VerificationState.WaitingForConfirmation(verificationId, phone, elapsed)
            delay(POLL_INTERVAL_MS)
            try {
                _state.value = VerificationState.Polling(verificationId, ((elapsed / POLL_INTERVAL_MS) + 1).toInt())
                val response = api.checkVerificationStatus(verificationId)
                if (response.isSuccessful && response.body() != null) {
                    val pollBody = requireNotNull(response.body()) { "WhatsApp poll response body must not be null" }
                    when (pollBody.status) {
                        "connected" -> {
                            _state.value = VerificationState.Connected(phone, pollBody.whatsappId ?: "")
                            return
                        }
                        "expired" -> {
                            _state.value = VerificationState.Failed(WhatsAppError.VERIFICATION_EXPIRED, canRetry = true)
                            return
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt failed", e)
            }
        }
        _state.value = VerificationState.TimedOut(verificationId, phone)
    }
}

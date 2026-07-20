package com.msaidizi.app.security.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Biometric authentication manager.
 *
 * Per SECURITY_ARCHITECTURE.md Section 3.3:
 * - Face biometric for financial transactions > KES 5,000 / NGN 50,000
 * - Fingerprint/face unlock for session re-authentication
 * - Liveness detection integration point
 * - Anti-spoofing via hardware-backed biometric API
 *
 * Uses AndroidX Biometric library which delegates to the system's
 * Trusted Execution Environment (TEE) / StrongBox for matching.
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BiometricAuth"
        // Threshold for requiring biometric step-up (KES)
        const val BIOMETRIC_THRESHOLD_KES = 5_000.0
        const val BIOMETRIC_THRESHOLD_NGN = 50_000.0
    }

    sealed class BiometricState {
        object Available : BiometricState()
        object NotAvailable : BiometricState()
        object Authenticating : BiometricState()
        data class Authenticated(val cryptoObject: BiometricPrompt.CryptoObject?) : BiometricState()
        data class Failed(val reason: String) : BiometricState()
        data class Error(val errorCode: Int, val message: String) : BiometricState()
    }

    private val _state = MutableStateFlow<BiometricState>(BiometricState.NotAvailable)
    val state: StateFlow<BiometricState> = _state.asStateFlow()

    /**
     * Check if biometric authentication is available on this device.
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Check if device has strong biometric (fingerprint, face).
     */
    fun hasStrongBiometric(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Check if biometric step-up is required for a transaction.
     */
    fun requiresBiometricStepUp(amountKES: Double): Boolean {
        return amountKES >= BIOMETRIC_THRESHOLD_KES
    }

    /**
     * Launch biometric prompt for authentication.
     * Must be called from an Activity context.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Verify your identity",
        subtitle: String = "Use your fingerprint or face to continue",
        negativeButtonText: String = "Cancel",
        allowDeviceCredential: Boolean = false,
        onSuccess: () -> Unit,
        onError: (Int, String) -> Unit,
        onFailed: () -> Unit
    ) {
        if (!isBiometricAvailable()) {
            _state.value = BiometricState.NotAvailable
            onError(-1, "Biometric authentication not available on this device")
            return
        }

        _state.value = BiometricState.Authenticating

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                _state.value = BiometricState.Authenticated(result.cryptoObject)
                Timber.i("Biometric authentication succeeded")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                _state.value = BiometricState.Error(errorCode, errString.toString())
                Timber.w("Biometric authentication error: %d - %s", errorCode, errString)
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                _state.value = BiometricState.Failed("Biometric not recognized")
                Timber.w("Biometric authentication failed — not recognized")
                onFailed()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (allowDeviceCredential) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptInfoBuilder.setNegativeButtonText(negativeButtonText)
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
        }

        try {
            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Throwable) {
            Timber.e(e, "Failed to launch biometric prompt")
            _state.value = BiometricState.Error(-1, "Failed to start biometric authentication")
            onError(-1, "Failed to start biometric authentication")
        }
    }

    /**
     * Authenticate with a crypto object for binding to a cryptographic operation.
     * This ensures the biometric authentication is bound to a specific key.
     */
    fun authenticateWithCrypto(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Timber.i("Biometric crypto-auth succeeded")
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Timber.w("Biometric crypto-auth error: %d - %s", errorCode, errString)
                onError(errorCode, errString.toString())
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify your identity")
            .setSubtitle("Authenticate to access secure data")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }
}

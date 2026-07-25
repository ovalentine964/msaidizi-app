package com.msaidizi.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Biometric authentication manager using AndroidX Biometric library.
 *
 * Supports:
 * - Fingerprint authentication
 * - Face authentication
 * - Device credential fallback (PIN/pattern/password)
 * - Crypto-backed biometric auth (key invalidated on biometric change)
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager
) {
    private val biometricManager = BiometricManager.from(context)

    /**
     * Check if biometric authentication is available on this device.
     */
    fun canAuthenticate(): BiometricStatus {
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricStatus.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricStatus.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricStatus.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricStatus.SecurityUpdateRequired
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                BiometricStatus.Unsupported
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                BiometricStatus.Unknown
            else -> BiometricStatus.Unknown
        }
    }

    /**
     * Check if biometric hardware exists and is configured.
     */
    fun isBiometricAvailable(): Boolean {
        return canAuthenticate() == BiometricStatus.Available
    }

    /**
     * Show biometric prompt with crypto binding.
     * The crypto object ensures the auth token is cryptographically bound.
     */
    fun authenticateWithBiometric(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Verify your identity to continue",
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (Int, String) -> Unit,
        onFailed: () -> Unit
    ) {
        val cryptoObject = createBiometricCryptoObject()

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Timber.d("Biometric authentication succeeded")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Timber.w("Biometric auth error: $errorCode - $errString")
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Timber.w("Biometric authentication failed (unrecognized biometric)")
                onFailed()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (cryptoObject != null) {
            // Crypto-backed: requires biometric (no device credential fallback with crypto)
            promptInfoBuilder.setNegativeButtonText(negativeButtonText)
            biometricPrompt.authenticate(cryptoObject, promptInfoBuilder.build())
        } else {
            // Non-crypto: allow device credential fallback
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            biometricPrompt.authenticate(promptInfoBuilder.build())
        }
    }

    /**
     * Create a crypto-backed biometric prompt.
     * The key is invalidated if biometrics change.
     */
    private fun createBiometricCryptoObject(): BiometricPrompt.CryptoObject? {
        return try {
            val key = getOrCreateBiometricKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            BiometricPrompt.CryptoObject(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.w(e, "Biometric key invalidated (enrollment changed)")
            deleteBiometricKey()
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to create biometric crypto object")
            null
        }
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        keyStore.getEntry(BIOMETRIC_KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deleteBiometricKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete biometric key")
        }
    }

    sealed class BiometricStatus {
        data object Available : BiometricStatus()
        data object NoHardware : BiometricStatus()
        data object HardwareUnavailable : BiometricStatus()
        data object NotEnrolled : BiometricStatus()
        data object SecurityUpdateRequired : BiometricStatus()
        data object Unsupported : BiometricStatus()
        data object Unknown : BiometricStatus()
    }

    companion object {
        private const val BIOMETRIC_KEY_ALIAS = "msaidizi_biometric_key"
    }
}

package com.msaidizi.core.security

import com.msaidizi.core.BuildConfig
import okhttp3.CertificatePinner
import timber.log.Timber

/**
 * Factory for creating OkHttp CertificatePinner with pinned certificates.
 *
 * Certificate pinning prevents MITM attacks even if a CA is compromised.
 * Pins are SHA-256 hashes of the Subject Public Key Info (SPKI).
 *
 * Generate pins with:
 *   openssl sclient -connect api.msaidizi.com:443 -servername api.msaidizi.com \
 *     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER \
 *     | openssl dgst -sha256 -binary | openssl enc -base64
 *
 * IMPORTANT: Always include backup pins for key rotation.
 */
object CertificatePinnerFactory {

    // ╔═══════════════════════════════════════════════════════════════════╗
    // ║  CERTIFICATE PINS — MUST be set before any release build        ║
    // ║                                                                   ║
    // ║  Generate pins with:                                              ║
    // ║    openssl s_client -connect api.msaidizi.com:443 \              ║
    //║      -servername api.msaidizi.com | openssl x509 -pubkey -noout | ║
    //║      openssl pkey -pubin -outform DER | openssl dgst -sha256 \   ║
    //║      -binary | openssl enc -base64                                ║
    //║                                                                   ║
    //║  Set via BuildConfig fields (injected from CI/CD secrets):        ║
    //║    buildConfigField "String", "CERT_PIN_PRIMARY", ...             ║
    //║    buildConfigField "String", "CERT_PIN_BACKUP", ...              ║
    //║                                                                   ║
    //║  NEVER ship placeholder hashes in a release APK.                 ║
    //╚═══════════════════════════════════════════════════════════════════╝
    private const val API_HOSTNAME = "api.msaidizi.com"

    // Pins are sourced from BuildConfig at build time (set in build.gradle):
    //   buildConfigField "String", "CERT_PIN_PRIMARY", '"sha256/REAL_HASH_HERE"'
    //   buildConfigField "String", "CERT_PIN_BACKUP", '"sha256/BACKUP_HASH_HERE"'
    private val PIN_PRIMARY: String
        get() = try {
            val field = BuildConfig::class.java.getField("CERT_PIN_PRIMARY")
            field.get(null) as? String ?: ""
        } catch (_: Exception) { "" }

    private val PIN_BACKUP: String
        get() = try {
            val field = BuildConfig::class.java.getField("CERT_PIN_BACKUP")
            field.get(null) as? String ?: ""
        } catch (_: Exception) { "" }

    /**
     * Create a CertificatePinner for the Msaidizi API backend.
     */
    fun create(): CertificatePinner {
        // Build-time guard: prevent shipping without real certificate pins
        val hasPlaceholderPins = PIN_PRIMARY.isEmpty() || PIN_BACKUP.isEmpty() ||
            PIN_PRIMARY.contains("AAA") || PIN_BACKUP.contains("BBB") ||
            PIN_PRIMARY.contains("placeholder") || PIN_BACKUP.contains("placeholder")
        if (hasPlaceholderPins && !BuildConfig.DEBUG) {
            throw IllegalStateException(
                "SECURITY: Certificate pins are not configured! " +
                    "Set CERT_PIN_PRIMARY and CERT_PIN_BACKUP in BuildConfig " +
                    "via CI/CD secrets before building a release. " +
                    "See CertificatePinnerFactory KDoc for generation instructions."
            )
        }
        if (hasPlaceholderPins) {
            Timber.w("SECURITY: Certificate pins are empty/placeholders — pinning disabled in debug build")
            return CertificatePinner.Builder().build() // no pinning in debug with placeholders
        }

        val pinner = CertificatePinner.Builder()
            .add(API_HOSTNAME, PIN_PRIMARY)
            .add(API_HOSTNAME, PIN_BACKUP)
            .build()

        Timber.d("Certificate pinning configured for %s with %d pins", API_HOSTNAME, 2)
        return pinner
    }

    /**
     * Create a CertificatePinner for a custom hostname with given pins.
     * Use this for staging, dev, or self-hosted instances.
     */
    fun createForHost(hostname: String, vararg pins: String): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for (pin in pins) {
            builder.add(hostname, pin)
        }
        return builder.build()
    }
}

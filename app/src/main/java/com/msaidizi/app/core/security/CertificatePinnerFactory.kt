package com.msaidizi.app.core.security

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

    // Certificate pins MUST be generated from production backend certificates before release.
    // Run the openssl command documented in the class KDoc against your production API domain
    // and replace the placeholder values below with the real SHA-256 SPKI hashes.
    // Always include at least one backup pin from a different key/cert for rotation.
    private const val API_HOSTNAME = "api.msaidizi.com"

    // Primary pin: current certificate's SPKI hash (replace before production deployment)
    private const val PIN_PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    // Backup pin: backup certificate or intermediate CA SPKI hash (replace before production deployment)
    private const val PIN_BACKUP = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    /**
     * Create a CertificatePinner for the Msaidizi API backend.
     */
    fun create(): CertificatePinner {
        val pinner = CertificatePinner.Builder()
            .add(API_HOSTNAME, PIN_PRIMARY)
            .add(API_HOSTNAME, PIN_BACKUP)
            .build()

        Timber.d("Certificate pinning configured for $API_HOSTNAME")
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

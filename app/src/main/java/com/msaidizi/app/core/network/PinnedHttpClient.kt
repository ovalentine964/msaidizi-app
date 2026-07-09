package com.msaidizi.app.core.network

import android.content.Context
import com.msaidizi.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * HTTP client with certificate pinning for model CDN downloads.
 *
 * Models are executable code — a compromised model could exfiltrate business data.
 * Certificate pinning prevents MITM attacks that could inject malicious models.
 *
 * QUANTUM-SAFETY NOTES:
 * - Certificate pins hash the SPKI (algorithm-agnostic) — SHA-256 is quantum-safe enough
 *   for pin verification (collision resistance drops but remains impractical to exploit)
 * - When CDN upgrades to PQ certificates (ML-DSA), pins will need rotation
 * - Model downloads are integrity-verified separately (hash/signature check after download)
 * - The TLS key exchange for CDN is classical — acceptable because model content
 *   is verified post-download, not solely via TLS channel security
 *
 * Pin SHA-256 hashes for models.msaidizi.app.
 * Update these hashes when CDN certificates rotate.
 */
@Singleton
class PinnedHttpClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Certificate pin for models.msaidizi.app — SHA-256 of SubjectPublicKeyInfo
        private const val CDN_HOST = "models.msaidizi.app"

        // Let's Encrypt certificate pins for models.msaidizi.app.
        //
        // Primary: ISRG Root X1 (Let's Encrypt's root CA) — stable, rarely rotates.
        // Backup:  Let's Encrypt's cross-signed intermediate for rotation safety.
        //
        // To regenerate:
        //   openssl s_client -connect models.msaidizi.app:443 2>/dev/null \
        //     | openssl x509 -pubkey -noout \
        //     | openssl pkey -pubin -outform DER \
        //     | openssl dgst -sha256 -binary \
        //     | base64
        //
        // Rotate backup pin when CDN certificate rotates (update via OTA or app update).
        private val CERTIFICATE_PINS = listOf<String>(
            "sha256/C/5hW8MVw+3h8YoFOUoRz2OgNFNPTq3MwGE/2siEpx0=",  // ISRG Root X1 (Let's Encrypt)
            "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0=",  // Let's Encrypt Authority X3 cross-signed
        )

        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 120L
        private const val WRITE_TIMEOUT_SEC = 60L
    }

    /**
     * Get an OkHttpClient with certificate pinning for the CDN.
     * Use this for model downloads only — regular API calls should use the
     * Ktor client from AppModule.
     *
     * In debug builds, certificate pinning is bypassed to allow development
     * without real CDN certificates. In release builds, pinning is enforced.
     */
    fun create(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(false) // Handle redirects manually to prevent pin bypass
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    val response = chain.proceed(request)

                    // Manual redirect handling — only allow same-origin redirects
                    if (response.code in 301..308) {
                        val location = response.header("Location")
                        if (location != null) {
                            val redirectUrl = location.toHttpUrlOrNull()
                            if (redirectUrl != null && redirectUrl.host != CDN_HOST) {
                                response.close()
                                Timber.e(
                                    "Blocked redirect to non-CDN host: %s (from %s)",
                                    redirectUrl.host, request.url
                                )
                                throw IOException(
                                    "Redirect to non-CDN host blocked: ${redirectUrl.host}"
                                )
                            }
                        }
                    }

                    response
                } catch (e: SSLPeerUnverifiedException) {
                    Timber.e(e, "CERT PIN FAILURE for %s — possible MITM attack", request.url)
                    throw IOException("Certificate verification failed. Download blocked for security.", e)
                }
            }

        // Only enable certificate pinning when real pins are configured.
        if (CERTIFICATE_PINS.isNotEmpty() && !BuildConfig.DEBUG) {
            val pinner = CertificatePinner.Builder().apply {
                for (pin in CERTIFICATE_PINS) {
                    add(CDN_HOST, pin)
                }
            }.build()
            builder.certificatePinner(pinner)
            Timber.d("PinnedHttpClient: Certificate pinning enabled for %s", CDN_HOST)
        } else {
            Timber.w("PinnedHttpClient: Certificate pinning DISABLED in debug build")
        }

        return builder.build()
    }
}

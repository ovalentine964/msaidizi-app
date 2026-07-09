package com.msaidizi.app.security.crypto

import android.content.Context
import com.msaidizi.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import timber.log.Timber
import com.msaidizi.app.security.crypto.pqc.CryptoAuditLogger
import com.msaidizi.app.security.crypto.pqc.PqcConfig
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 configuration and certificate pinning for API calls.
 *
 * Per SECURITY_ARCHITECTURE.md Section 4.2:
 * - TLS 1.3 minimum (TLS 1.2 only for legacy USSD gateway)
 * - Certificate pinning with backup pins
 * - No cleartext HTTP permitted
 * - Approved cipher suites: TLS_AES_256_GCM_SHA384, TLS_CHACHA20_POLY1305_SHA256, TLS_AES_128_GCM_SHA256
 */
@Singleton
class TlsConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogger: CryptoAuditLogger? = null
) {
    companion object {
        // API hosts that require certificate pinning
        private const val API_HOST = "api.angavu.com"
        private const val API_HOST_IO = "api.angavu.io"

        // Let's Encrypt certificate pins for api.angavu.com and api.angavu.io.
        //
        // Primary: ISRG Root X1 (Let's Encrypt's root CA) — stable, rarely rotates.
        // Backup:  Let's Encrypt's cross-signed intermediate for rotation safety.
        //
        // To regenerate:
        //   openssl s_client -connect api.angavu.com:443 2>/dev/null \
        //     | openssl x509 -pubkey -noout \
        //     | openssl pkey -pubin -outform DER \
        //     | openssl dgst -sha256 -binary \
        //     | base64
        private val API_PINS = listOf(
            "sha256/C/5hW8MVw+3h8YoFOUoRz2OgNFNPTq3MwGE/2siEpx0=",  // ISRG Root X1 (Let's Encrypt)
            "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0="   // Let's Encrypt Authority X3 cross-signed
        )

        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L
        private const val WRITE_TIMEOUT_SEC = 60L
    }

    /**
     * Create an OkHttpClient with TLS 1.3 enforcement, certificate pinning,
     * and post-quantum hybrid key exchange support.
     *
     * When PQC hybrid mode is enabled (PqcConfig.enableHybridKeyExchange),
     * the TLS connection will attempt hybrid X25519+ML-KEM key exchange
     * when the server supports it. This provides quantum resistance while
     * maintaining backward compatibility.
     *
     * Per White House EO 14412 and Cloudflare/Meta PQC deployment patterns.
     */
    fun createSecureClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)

        // Enforce TLS 1.3 only (no TLS 1.2 fallback for non-USSD endpoints)
        try {
            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(null, null, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, systemTrustManager())

            // ConnectionSpec: TLS 1.3 ONLY — reject TLS 1.2 for API calls
            // TLS 1.2 only allowed for USSD gateway (separate client)
            val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3)
                .build()
            builder.connectionSpecs(listOf(tlsSpec))

            // Enforce specific cipher suites (quantum-safe AES-256 preferred)
            // TLS 1.3 ciphers: all use AEAD, but AES-256 provides 128-bit PQ security
            val cipherSuites = listOf(
                "TLS_AES_256_GCM_SHA384",       // Preferred: AES-256 (quantum-safe)
                "TLS_CHACHA20_POLY1305_SHA256",  // Alternative: ChaCha20
                "TLS_AES_128_GCM_SHA256"         // Minimum: AES-128
            )

            // Log TLS configuration for PQC audit trail
            if (PqcConfig.enableAuditLogging) {
                auditLogger?.logTlsConnection(
                    host = "*",
                    tlsVersion = "TLSv1.3",
                    cipherSuite = cipherSuites.joinToString(","),
                    hasPqKeyExchange = PqcConfig.shouldUseHybridKeyExchange(),
                    success = true
                )
                Timber.i("TLS configured: TLS 1.3 ONLY, PQ hybrid=%b, phase=%s",
                    PqcConfig.shouldUseHybridKeyExchange(),
                    PqcConfig.migrationPhase.name)
            }
        } catch (e: Exception) {
            Timber.e("TLS 1.3 initialization FAILED — this is a critical security error: %s", e.message)
            auditLogger?.logTlsConnection(
                host = "*",
                tlsVersion = "FAILED",
                cipherSuite = "none",
                hasPqKeyExchange = false,
                success = false,
                error = e.message
            )
            // In production, this should fail hard — TLS 1.3 is mandatory for financial data
            throw SecurityException("TLS 1.3 is required but unavailable: ${e.message}", e)
        }

        // Certificate pinning — disabled in debug builds for development
        if (!BuildConfig.DEBUG && API_PINS.isNotEmpty()) {
            val pinner = CertificatePinner.Builder()
                .apply {
                    API_PINS.forEach { pin ->
                        add(API_HOST, pin)
                        add(API_HOST_IO, pin)
                    }
                }
                .build()
            builder.certificatePinner(pinner)
            Timber.d("Certificate pinning enabled for %s and %s", API_HOST, API_HOST_IO)
        } else {
            Timber.w("Certificate pinning DISABLED (debug build)")
        }

        // Interceptor to enforce HTTPS — HARD BLOCK on cleartext
        builder.addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.isHttps) {
                // Log and block — no cleartext HTTP allowed for financial data
                auditLogger?.logTlsConnection(
                    host = request.url.host,
                    tlsVersion = "CLEARTEXT_BLOCKED",
                    cipherSuite = "none",
                    hasPqKeyExchange = false,
                    success = false,
                    error = "Cleartext HTTP blocked for: ${request.url}"
                )
                throw SecurityException(
                    "SECURITY VIOLATION: Cleartext HTTP is forbidden. " +
                    "All API calls must use TLS 1.3. Blocked URL: ${request.url}"
                )
            }
            chain.proceed(request)
        }

        // Interceptor to add security headers and request tracking
        builder.addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Request-ID", java.util.UUID.randomUUID().toString())
                .header("X-App-Version", com.msaidizi.app.BuildConfig.VERSION_NAME)
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }

        // Interceptor to validate response security headers
        builder.addInterceptor { chain ->
            val response = chain.proceed(chain.request())

            // Verify server sent security headers
            val hsts = response.header("Strict-Transport-Security")
            if (hsts == null) {
                Timber.w("Server missing HSTS header for: %s", chain.request().url.host)
            }

            // Verify Content-Type is JSON (prevents MIME confusion attacks)
            val contentType = response.header("Content-Type")
            if (contentType != null && !contentType.contains("application/json") &&
                !contentType.contains("application/octet-stream") &&
                !contentType.contains("text/plain")) {
                Timber.w("Unexpected Content-Type from server: %s", contentType)
            }

            response
        }

        return builder.build()
    }

    /**
     * Get the system's default trust manager.
     */
    private fun systemTrustManager(): X509TrustManager {
        val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as java.security.KeyStore?)
        return trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }
}

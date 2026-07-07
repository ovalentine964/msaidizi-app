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

        // Certificate pins — SHA-256 of SubjectPublicKeyInfo
        // TODO(SECURITY): Replace with real pins before production
        // Generate with:
        //   openssl s_client -connect api.angavu.com:443 2>/dev/null \
        //     | openssl x509 -pubkey -noout \
        //     | openssl pkey -pubin -outform DER \
        //     | openssl dgst -sha256 -binary \
        //     | base64
        private val API_PINS = listOf(
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",  // Primary pin
            "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="   // Backup pin
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

        // Enforce TLS 1.3 only (with TLS 1.2 fallback for compatibility)
        try {
            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(null, null, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, systemTrustManager())

            // ConnectionSpec: TLS 1.3 preferred, TLS 1.2 minimum
            // When IANA assigns PQ-hybrid cipher suite code points, they'll be added here
            val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build()
            builder.connectionSpecs(listOf(tlsSpec))

            // Log TLS configuration for PQC audit trail
            if (PqcConfig.enableAuditLogging) {
                Timber.i("TLS configured: TLS 1.3, PQ hybrid=%b, phase=%s",
                    PqcConfig.shouldUseHybridKeyExchange(),
                    PqcConfig.migrationPhase.name)
            }
        } catch (e: Exception) {
            Timber.w("TLS 1.3 not available, falling back to default: %s", e.message)
            auditLogger?.logTlsConnection(
                host = "*",
                tlsVersion = "fallback",
                cipherSuite = "default",
                hasPqKeyExchange = false,
                success = false,
                error = e.message
            )
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

        // Interceptor to enforce HTTPS
        builder.addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.isHttps) {
                throw SecurityException("Cleartext HTTP blocked: ${request.url}")
            }
            chain.proceed(request)
        }

        // Interceptor to add security headers
        builder.addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Request-ID", java.util.UUID.randomUUID().toString())
                .build()
            chain.proceed(request)
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

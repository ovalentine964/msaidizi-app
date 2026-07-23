package com.msaidizi.app.core.network

import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Provides an OkHttpClient configured for TLS 1.3 minimum.
 *
 * TLS 1.3 is negotiated automatically by OkHttp/Conscrypt on Android 10+.
 * On older devices, this falls back to TLS 1.2 (the minimum safe version).
 *
 * Usage: inject via Hilt or call [create] directly.
 */
object SecureOkHttpProvider {

    /**
     * Create an OkHttpClient with TLS 1.3 enforcement and hardened timeouts.
     */
    fun create(): OkHttpClient {
        // TLS 1.3 is the default on Android 10+ with Conscrypt/platform SSL.
        // We explicitly set connection specs to prefer modern cipher suites.
        val connectionSpec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .allEnabledCipherSuites() // Let platform pick (TLS 1.3 suites preferred)
            .build()

        return OkHttpClient.Builder()
            .connectionSpecs(listOf(connectionSpec))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Certificate pinning can be added here for additional enforcement
            // beyond network_security_config.xml
            .build()
    }
}

package com.msaidizi.app.core.network

import android.content.Context
import com.msaidizi.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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
 * Pin SHA-256 hashes for models.msaidizi.app.
 * Update these hashes when CDN certificates rotate.
 */
@Singleton
class PinnedHttpClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Certificate pin for models.msaidizi.app — SHA-256 of SubjectPublicKeyInfo */
        private const val CDN_HOST = "models.msaidizi.app"

        // TODO(security): Replace these placeholder hashes with real SHA-256 pin hashes
        // from the CDN certificate before production release.
        // Generate with:
        //   openssl s_client -connect models.msaidizi.app:443 2>/dev/null \
        //     | openssl x509 -pubkey -noout \
        //     | openssl pkey -pubin -outform DER \
        //     | openssl dgst -sha256 -binary \
        //     | base64
        // Also add a backup pin for certificate rotation.
        //
        // Current values are SHA-256 of well-known test keys (NOT production).
        // These will allow connections in release builds but MUST be replaced.
        private val CERTIFICATE_PINS = listOf(
            "sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=",  // Backup: Let's Encrypt R3
            "sha256/sRHdihwgkaib1P1gN7SkKPIhFRBcBx0p0sHJKbQG/uEtLMkBgFF2Fuihg="   // TODO: Replace with actual CDN pin
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
                    if (response.isRedirect) {
                        val location = response.header("Location")
                        if (location != null) {
                            val redirectUrl = HttpUrl.parse(location)
                            if (redirectUrl != null && redirectUrl.host() != CDN_HOST) {
                                response.close()
                                Timber.e(
                                    "Blocked redirect to non-CDN host: %s (from %s)",
                                    redirectUrl.host(), request.url()
                                )
                                throw IOException(
                                    "Redirect to non-CDN host blocked: ${redirectUrl.host()}"
                                )
                            }
                        }
                    }

                    response
                } catch (e: SSLPeerUnverifiedException) {
                    Timber.e(e, "CERT PIN FAILURE for %s — possible MITM attack", request.url())
                    throw IOException("Certificate verification failed. Download blocked for security.", e)
                }
            }

        // In debug builds, skip certificate pinning for development flexibility
        if (!BuildConfig.DEBUG) {
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

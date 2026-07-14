package com.msaidizi.app.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * HTTP client for model downloads.
 *
 * Models are executable code — a compromised model could exfiltrate business data.
 * Downloads use HTTPS with integrity verification (SHA-256) after download.
 *
 * CDN HOSTS:
 * - Primary: huggingface.co (models hosted at ovalentine964/msaidizi-models)
 * - LFS redirects: cdn-lfs.huggingface.co (automatic, same-origin)
 * - Fallback: github.com/releases (for sherpa-onnx models)
 *
 * Certificate pinning is NOT used because:
 * - HuggingFace and GitHub manage their own certificate rotation
 * - Model integrity is verified via SHA-256 hash after download
 * - Pinning would break on CDN certificate rotation
 *
 * @see ModelRegistry for SHA-256 verification of downloaded models
 */
@Singleton
class PinnedHttpClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Trusted download hosts
        private val TRUSTED_HOSTS = setOf(
            "huggingface.co",
            "cdn-lfs.huggingface.co",
            "github.com",
            "objects.githubusercontent.com",
            "hf-mirror.com"
        )

        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 120L
        private const val WRITE_TIMEOUT_SEC = 60L
    }

    /**
     * Get an OkHttpClient for model downloads.
     * Use this for model downloads only — regular API calls should use the
     * Ktor client from AppModule.
     *
     * Follows redirects within trusted hosts (HuggingFace LFS, GitHub).
     * Model integrity is verified post-download via SHA-256.
     */
    fun create(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    val response = chain.proceed(request)

                    // Verify redirect targets are trusted hosts
                    if (response.code in 301..308) {
                        val location = response.header("Location")
                        if (location != null) {
                            val redirectUrl = location.toHttpUrlOrNull()
                            if (redirectUrl != null && redirectUrl.host !in TRUSTED_HOSTS) {
                                response.close()
                                Timber.e(
                                    "Blocked redirect to untrusted host: %s (from %s)",
                                    redirectUrl.host, request.url
                                )
                                throw IOException(
                                    "Redirect to untrusted host blocked: ${redirectUrl.host}"
                                )
                            }
                        }
                    }

                    response
                } catch (e: SSLPeerUnverifiedException) {
                    Timber.e(e, "SSL verification failure for %s", request.url)
                    throw IOException("SSL verification failed. Download blocked for security.", e)
                }
            }

        Timber.d("PinnedHttpClient: Created with trusted hosts: %s", TRUSTED_HOSTS)
        return builder.build()
    }
}

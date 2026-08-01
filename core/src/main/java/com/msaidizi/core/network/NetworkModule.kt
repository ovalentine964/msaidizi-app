package com.msaidizi.core.network

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import android.content.Context
import com.msaidizi.core.BuildConfig
import com.msaidizi.core.security.CertificatePinnerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL_KEY = "sync_base_url"
    const val DEFAULT_BASE_URL = "https://sync.msaidizi.app/"

    /** HTTP disk cache size: 10 MB */
    private const val CACHE_SIZE_BYTES = 10L * 1024 * 1024

    /** Max age for cached GET responses (market data, score updates) */
    private const val CACHE_MAX_AGE_MINUTES = 5L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner {
        return CertificatePinnerFactory.create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        certificatePinner: CertificatePinner,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        val loggingLevel = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        val logging = HttpLoggingInterceptor { message ->
            Timber.tag("SyncHTTP").d(message)
        }.apply {
            level = loggingLevel
        }

        // HTTP disk cache: serves stale responses when offline, reduces bandwidth
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, CACHE_SIZE_BYTES)

        // Network interceptor: add cache-control headers for GET responses
        val cacheInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().method == "GET") {
                val cacheControl = CacheControl.Builder()
                    .maxAge(CACHE_MAX_AGE_MINUTES.toInt(), TimeUnit.MINUTES)
                    .build()
                response.newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Cache-Control")
                    .header("Cache-Control", cacheControl.toString())
                    .build()
            } else {
                response
            }
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .certificatePinner(certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addNetworkInterceptor(cacheInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(DEFAULT_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi {
        return retrofit.create(SyncApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGraphSyncApi(retrofit: Retrofit): GraphSyncApi {
        return retrofit.create(GraphSyncApi::class.java)
    }
}

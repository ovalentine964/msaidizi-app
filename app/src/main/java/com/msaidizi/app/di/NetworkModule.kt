package com.msaidizi.app.di

import android.content.Context
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.sync.SyncManager
import com.msaidizi.app.update.AutoUpdater
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing network-related dependencies.
 *
 * Provides:
 * - OkHttpClient (with logging, timeouts for 2G networks)
 * - Retrofit (for REST API)
 * - MsaidiziApi (backend API client)
 * - NetworkMonitor (connectivity tracking)
 * - SyncManager (data synchronization)
 * - AutoUpdater (silent app updates)
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.msaidizi.app/"
    private const val CONNECT_TIMEOUT = 30L  // seconds — generous for 2G
    private const val READ_TIMEOUT = 60L     // seconds — large payloads
    private const val WRITE_TIMEOUT = 30L    // seconds

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(logging)
            // Retry on connection failure (important for flaky 2G/3G)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideMsaidiziApi(retrofit: Retrofit): MsaidiziApi {
        return retrofit.create(MsaidiziApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor {
        return NetworkMonitor(context)
    }

    /**
     * NOTE: SyncManager and AutoUpdater likely need @Inject constructor.
     * If they don't have it, add @Provides methods here.
     *
     * If SyncManager requires AppDatabase, TransactionDao, etc.,
     * add those as parameters to the @Provides method.
     */

    // @Provides
    // @Singleton
    // fun provideSyncManager(
    //     @ApplicationContext context: Context,
    //     api: MsaidiziApi,
    //     networkMonitor: NetworkMonitor
    // ): SyncManager {
    //     return SyncManager(context, api, networkMonitor)
    // }

    // @Provides
    // @Singleton
    // fun provideAutoUpdater(
    //     @ApplicationContext context: Context,
    //     api: MsaidiziApi
    // ): AutoUpdater {
    //     return AutoUpdater(context, api)
    // }
}

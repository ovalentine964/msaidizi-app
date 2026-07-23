package com.msaidizi.app.core.di

import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.sync.SyncManager
import com.msaidizi.app.sync.SyncQueue
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import android.content.Context
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

/**
 * Network-related dependencies: HTTP clients, API services, sync infrastructure.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMsaidiziApi(tlsConfig: com.msaidizi.app.security.crypto.TlsConfig): MsaidiziApi {
        val secureClient = tlsConfig.createSecureClient()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.angavu.io/")
            .callFactory(secureClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(MsaidiziApi::class.java)
    }

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = if (com.msaidizi.app.BuildConfig.DEBUG) LogLevel.BODY else LogLevel.NONE
            logger = object : Logger {
                override fun log(message: String) {
                    timber.log.Timber.d("Ktor: %s", message)
                }
            }
        }
        engine {
            config {
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        NetworkMonitor(context)

    @Provides
    @Singleton
    fun provideSyncQueue(
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): SyncQueue = SyncQueue(transactionDao, patternDao)

    @Provides
    @Singleton
    fun provideSyncManager(
        syncQueue: SyncQueue,
        networkMonitor: NetworkMonitor,
        httpClient: HttpClient,
        cryptoService: com.msaidizi.app.security.crypto.CryptoService,
        transactionDao: TransactionDao
    ): SyncManager = SyncManager(syncQueue, networkMonitor, httpClient, Gson(), cryptoService, transactionDao)
}

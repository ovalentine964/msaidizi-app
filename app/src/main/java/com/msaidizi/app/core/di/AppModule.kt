package com.msaidizi.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msaidizi.app.core.database.AppDatabase
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.agent.Orchestrator
import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.AnalysisAgent
import com.msaidizi.app.agent.AdvisorAgent
import com.msaidizi.app.agent.LearningAgent
import com.msaidizi.app.sync.SyncManager
import com.msaidizi.app.sync.SyncQueue
import com.msaidizi.app.sync.NetworkMonitor
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
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 * Provides all singletons for the Msaidizi app.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // === DATABASE ===

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "msaidizi.db"
        )
            // Enable WAL mode for better concurrent read performance
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGING)
            // Set busy timeout for WAL mode
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Enable WAL mode and set busy timeout
                    db.execSQL("PRAGMA journal_mode=WAL")
                    db.execSQL("PRAGMA busy_timeout=5000")
                    db.execSQL("PRAGMA synchronous=NORMAL")
                    db.execSQL("PRAGMA cache_size=-2000")  // 2MB cache
                }
            })
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideInventoryDao(db: AppDatabase): InventoryDao = db.inventoryDao()

    @Provides
    fun providePatternDao(db: AppDatabase): PatternDao = db.patternDao()

    // === NETWORK ===

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
        install(Loging) {
            level = LogLevel.BODY
            logger = object : Logger {
                override fun log(message: String) {
                    com.timber.log.Timber.d("Ktor: %s", message)
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

    // === AGENTS ===

    @Provides
    @Singleton
    fun provideIntentRouter(): IntentRouter = IntentRouter()

    @Provides
    @Singleton
    fun provideBusinessAgent(
        transactionDao: TransactionDao,
        inventoryDao: InventoryDao
    ): BusinessAgent = BusinessAgent(transactionDao, inventoryDao)

    @Provides
    @Singleton
    fun provideAnalysisAgent(
        transactionDao: TransactionDao
    ): AnalysisAgent = AnalysisAgent(transactionDao)

    @Provides
    @Singleton
    fun provideAdvisorAgent(
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent
    ): AdvisorAgent = AdvisorAgent(businessAgent, analysisAgent)

    @Provides
    @Singleton
    fun provideLearningAgent(
        patternDao: PatternDao,
        inventoryDao: InventoryDao
    ): LearningAgent = LearningAgent(patternDao, inventoryDao)

    @Provides
    @Singleton
    fun provideOrchestrator(
        intentRouter: IntentRouter,
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        learningAgent: LearningAgent
    ): Orchestrator = Orchestrator(
        intentRouter, businessAgent, analysisAgent, advisorAgent, learningAgent
    )

    // === SYNC ===

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
        json: Json
    ): SyncManager = SyncManager(syncQueue, networkMonitor, httpClient, json)
}

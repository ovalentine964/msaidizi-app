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
import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.model.UserVocabularyDao
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import com.msaidizi.app.core.language.ConfidenceCalibrator
import com.msaidizi.app.core.language.PhonemeMapper
import com.msaidizi.app.core.language.LanguageModelRegistry
import com.msaidizi.app.core.language.FederatedLearningClient
import com.msaidizi.app.core.network.PinnedHttpClient
import com.msaidizi.app.sync.SyncManager
import com.msaidizi.app.sync.SyncQueue
import com.msaidizi.app.sync.NetworkMonitor
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
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
        // Initialize SQLCipher for database encryption
        net.zetetic.database.sqlcipher.SQLiteDatabase.loadLibs(context)
        val passphrase = com.msaidizi.app.core.util.CryptoUtils.getOrCreateDatabaseKey(context)
        val factory = SupportOpenHelperFactory(passphrase.toByteArray())

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "msaidizi.db"
        )
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL("PRAGMA journal_mode=WAL")
                    db.execSQL("PRAGMA busy_timeout=5000")
                    db.execSQL("PRAGMA synchronous=NORMAL")
                    db.execSQL("PRAGMA cache_size=-2000")
                }
            })
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_vocabulary` (
                            `spokenForm` TEXT NOT NULL,
                            `canonicalForm` TEXT NOT NULL,
                            `language` TEXT NOT NULL DEFAULT 'sw',
                            `frequency` INTEGER NOT NULL DEFAULT 1,
                            `confidence` REAL NOT NULL DEFAULT 0.1,
                            `minPrice` REAL NOT NULL DEFAULT 0.0,
                            `maxPrice` REAL NOT NULL DEFAULT 0.0,
                            `avgPrice` REAL NOT NULL DEFAULT 0.0,
                            `priceObservations` INTEGER NOT NULL DEFAULT 0,
                            `avgQuantity` REAL NOT NULL DEFAULT 0.0,
                            `category` TEXT NOT NULL DEFAULT '',
                            `isUserDefined` INTEGER NOT NULL DEFAULT 0,
                            `lastUsedAt` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`spokenForm`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_vocabulary_spokenForm` ON `user_vocabulary` (`spokenForm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_vocabulary_canonicalForm` ON `user_vocabulary` (`canonicalForm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_vocabulary_confidence` ON `user_vocabulary` (`confidence`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_vocabulary_lastUsedAt` ON `user_vocabulary` (`lastUsedAt`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_corrections` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `originalTransactionId` INTEGER NOT NULL DEFAULT 0,
                            `correctionType` TEXT NOT NULL,
                            `originalValue` TEXT NOT NULL,
                            `correctedValue` TEXT NOT NULL,
                            `originalInput` TEXT NOT NULL DEFAULT '',
                            `correctionInput` TEXT NOT NULL DEFAULT '',
                            `context` TEXT NOT NULL DEFAULT '{}',
                            `applied` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_corrections_originalTransactionId` ON `user_corrections` (`originalTransactionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_corrections_correctionType` ON `user_corrections` (`correctionType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_corrections_createdAt` ON `user_corrections` (`createdAt`)")
                    db.execSQL("""
                        INSERT OR IGNORE INTO `user_vocabulary`
                            (`spokenForm`, `canonicalForm`, `language`, `frequency`, `confidence`, `lastUsedAt`, `createdAt`)
                        SELECT
                            `spokenForm`, `canonicalForm`, `language`, `frequency`,
                            CASE WHEN `frequency` >= 10 THEN 0.7
                                 WHEN `frequency` >= 5 THEN 0.5
                                 WHEN `frequency` >= 2 THEN 0.3
                                 ELSE 0.1 END,
                            `lastUsedAt`, `lastUsedAt`
                        FROM `vocabulary`
                    """.trimIndent())
                }
            })
            // Migration v2 → v3: Added learned_words table (schema matches LearnedWord entity)
            .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `learned_words` (
                            `word` TEXT NOT NULL,
                            `frequency` INTEGER NOT NULL DEFAULT 1,
                            `dialectRegion` TEXT NOT NULL DEFAULT 'STANDARD',
                            `canonicalForm` TEXT,
                            `categoryHint` TEXT NOT NULL DEFAULT 'unknown',
                            `firstSeenAt` INTEGER NOT NULL DEFAULT 0,
                            `lastSeenAt` INTEGER NOT NULL DEFAULT 0,
                            `mappedAt` INTEGER,
                            PRIMARY KEY(`word`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_words_frequency` ON `learned_words` (`frequency`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_words_canonicalForm` ON `learned_words` (`canonicalForm`)")
                }
            })
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideInventoryDao(db: AppDatabase): InventoryDao = db.inventoryDao()

    @Provides
    fun providePatternDao(db: AppDatabase): PatternDao = db.patternDao()

    @Provides
    fun provideUserVocabularyDao(db: AppDatabase): UserVocabularyDao = db.userVocabularyDao()

    @Provides
    fun provideUserCorrectionDao(db: AppDatabase): UserCorrectionDao = db.userCorrectionDao()

    @Provides
    fun provideVocabularyLearningDao(db: AppDatabase): VocabularyLearningDao = db.vocabularyLearningDao()

    // === DIALECT & ADAPTIVE VOCABULARY ===

    @Provides
    @Singleton
    fun provideAdaptiveVocabulary(
        learningDao: VocabularyLearningDao,
        userVocabDao: UserVocabularyDao
    ): AdaptiveVocabulary = AdaptiveVocabulary(learningDao, userVocabDao)

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
    fun provideBusinessPatternTracker(
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): BusinessPatternTracker = BusinessPatternTracker(transactionDao, patternDao)

    @Provides
    @Singleton
    fun provideAdaptiveLearningEngine(
        userVocabularyDao: UserVocabularyDao,
        userCorrectionDao: UserCorrectionDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao,
        patternTracker: BusinessPatternTracker,
        learningAgent: LearningAgent
    ): AdaptiveLearningEngine = AdaptiveLearningEngine(
        userVocabularyDao, userCorrectionDao, transactionDao, patternDao,
        patternTracker, learningAgent
    )

    @Provides
    @Singleton
    fun provideOrchestrator(
        intentRouter: IntentRouter,
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        learningAgent: LearningAgent,
        adaptiveLearning: AdaptiveLearningEngine
    ): Orchestrator = Orchestrator(
        intentRouter, businessAgent, analysisAgent, advisorAgent, learningAgent, adaptiveLearning
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

    // === ADAPTIVE ASR & LANGUAGE LEARNING ===

    @Provides
    @Singleton
    fun provideConfidenceCalibrator(
        userVocabularyDao: UserVocabularyDao
    ): ConfidenceCalibrator = ConfidenceCalibrator(userVocabularyDao)

    @Provides
    @Singleton
    fun providePhonemeMapper(): PhonemeMapper = PhonemeMapper()

    @Provides
    @Singleton
    fun provideLanguageModelRegistry(
        @ApplicationContext context: Context
    ): LanguageModelRegistry = LanguageModelRegistry(context)

    @Provides
    @Singleton
    fun provideAdaptiveAsrEngine(
        speechRecognizer: com.msaidizi.app.voice.SpeechRecognizer,
        confidenceCalibrator: ConfidenceCalibrator,
        phonemeMapper: PhonemeMapper,
        languageModelRegistry: LanguageModelRegistry,
        userCorrectionDao: UserCorrectionDao,
        userVocabularyDao: UserVocabularyDao
    ): AdaptiveAsrEngine = AdaptiveAsrEngine(
        speechRecognizer, confidenceCalibrator, phonemeMapper,
        languageModelRegistry, userCorrectionDao, userVocabularyDao
    )

    @Provides
    @Singleton
    fun provideFederatedLearningClient(
        @ApplicationContext context: Context,
        pinnedHttpClient: PinnedHttpClient
    ): FederatedLearningClient = FederatedLearningClient(context, pinnedHttpClient)
}

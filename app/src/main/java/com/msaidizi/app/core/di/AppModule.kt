package com.msaidizi.app.core.di

import android.content.Context
import com.msaidizi.app.data.api.MsaidiziApi
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msaidizi.app.core.database.AppDatabase
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.agent.Orchestrator
import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.IntentPatternConfig
import com.msaidizi.app.agent.ContextManager
import com.msaidizi.app.agent.ErrorCompactor
import com.msaidizi.app.agent.UnifiedStateManager
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.AnalysisAgent
import com.msaidizi.app.agent.AdvisorAgent
import com.msaidizi.app.agent.LearningAgent
import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.model.UserVocabularyDao
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.evolution.FeedbackDao
import com.msaidizi.app.evolution.FeatureRequestDao
import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.RichHabitsDao
import com.msaidizi.app.core.database.MindsetLessonDao
import com.msaidizi.app.evolution.FeedbackCollector
import com.msaidizi.app.evolution.FeatureRequestTracker
import com.msaidizi.app.evolution.SelfEvolutionManager
import com.msaidizi.app.agent.PreferenceLearner
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import com.msaidizi.app.core.language.ConfidenceCalibrator
import com.msaidizi.app.core.language.PhonemeMapper
import com.msaidizi.app.core.language.LanguageModelRegistry
import com.msaidizi.app.core.language.FederatedLearningClient
import com.msaidizi.app.core.language.ConversationLearningPipeline
import com.msaidizi.app.security.privacy.ConsentManager
import com.msaidizi.app.core.network.PinnedHttpClient
import com.msaidizi.app.sync.SyncManager
import com.google.gson.Gson
import com.msaidizi.app.sync.SyncQueue
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.agent.ModelRouter
import com.msaidizi.app.voice.LlmEngine
import com.msaidizi.app.voice.LlamaCppEngine
import com.msaidizi.app.finance.TitheTracker
import com.msaidizi.app.finance.GoalPlanner
import com.msaidizi.app.finance.LoanManager
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.gamification.InsightRewards
import com.msaidizi.app.gamification.MicroRewards
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.CFOEngine
import com.msaidizi.app.skills.SkillBridge
import com.msaidizi.app.social.*
import com.msaidizi.app.vision.ProductClassifier
import com.msaidizi.app.vision.ProductRecognitionHandler
import com.msaidizi.app.vision.VisionCorrectionTracker
import com.msaidizi.app.vision.VisionHarness
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.harness.LearningHarness
import com.msaidizi.app.voice.VoicePipelineHarness
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import com.msaidizi.app.loops.VariableRewardsLoop
import com.msaidizi.app.agent.TransactionHandler
import com.msaidizi.app.agent.QueryHandler
import com.msaidizi.app.agent.AdviceHandler
import com.msaidizi.app.agent.GamificationHandler
import com.msaidizi.app.agent.DomainRouter
import com.msaidizi.app.agent.ConversationManager
import com.msaidizi.app.agent.VoicePersonality
import com.msaidizi.app.agent.proactive.ProactiveAnomalyDetector
import com.msaidizi.app.agent.proactive.StockOutPredictor
import com.msaidizi.app.agent.proactive.CashFlowPredictor
import com.msaidizi.app.agent.proactive.ProactiveAlertEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "msaidizi.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
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
            // Migration v3 → v4: Added feedback and feature_requests tables for self-evolution
            .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `feedback` (
                            `id` TEXT NOT NULL,
                            `workerId` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `text` TEXT NOT NULL,
                            `language` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `category` TEXT,
                            `synced` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_feedback_timestamp` ON `feedback` (`timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_feedback_synced` ON `feedback` (`synced`)")

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `feature_requests` (
                            `id` TEXT NOT NULL,
                            `clusterId` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `requestCount` INTEGER NOT NULL DEFAULT 1,
                            `workerTypes` TEXT NOT NULL,
                            `priority` REAL NOT NULL DEFAULT 0.0,
                            `status` TEXT NOT NULL DEFAULT 'NEW',
                            `createdAt` INTEGER NOT NULL,
                            `lastUpdated` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_feature_requests_clusterId` ON `feature_requests` (`clusterId`)")
                }
            })
            // Migration v4 → v5: Added gamification, rich_habits, and mindset_lessons tables
            .addMigrations(object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `gamification` (
                            `id` INTEGER NOT NULL,
                            `totalPoints` INTEGER NOT NULL DEFAULT 0,
                            `level` INTEGER NOT NULL DEFAULT 0,
                            `currentStreak` INTEGER NOT NULL DEFAULT 0,
                            `longestStreak` INTEGER NOT NULL DEFAULT 0,
                            `lastActiveDay` INTEGER NOT NULL DEFAULT 0,
                            `streakProtectionsUsed` INTEGER NOT NULL DEFAULT 0,
                            `protectionWeek` INTEGER NOT NULL DEFAULT 0,
                            `totalSalesRecorded` INTEGER NOT NULL DEFAULT 0,
                            `totalBalanceChecks` INTEGER NOT NULL DEFAULT 0,
                            `earnedBadges` TEXT NOT NULL DEFAULT '',
                            `updatedAt` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_gamification_level` ON `gamification` (`level`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_gamification_currentStreak` ON `gamification` (`currentStreak`)")

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `rich_habits` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `habitId` TEXT NOT NULL,
                            `date` TEXT NOT NULL,
                            `completed` INTEGER NOT NULL DEFAULT 0,
                            `completedAt` INTEGER NOT NULL DEFAULT 0,
                            `notes` TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_rich_habits_date` ON `rich_habits` (`date`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_rich_habits_habitId` ON `rich_habits` (`habitId`)")

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `mindset_lessons` (
                            `lessonId` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `titleSw` TEXT NOT NULL,
                            `titleEn` TEXT NOT NULL,
                            `contentSw` TEXT NOT NULL,
                            `contentEn` TEXT NOT NULL,
                            `sourceBook` TEXT NOT NULL,
                            `durationSeconds` INTEGER NOT NULL DEFAULT 150,
                            `delivered` INTEGER NOT NULL DEFAULT 0,
                            `completed` INTEGER NOT NULL DEFAULT 0,
                            `deliveredAt` INTEGER NOT NULL DEFAULT 0,
                            `completedAt` INTEGER NOT NULL DEFAULT 0,
                            `sortOrder` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`lessonId`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_mindset_lessons_category` ON `mindset_lessons` (`category`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_mindset_lessons_delivered` ON `mindset_lessons` (`delivered`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_mindset_lessons_completed` ON `mindset_lessons` (`completed`)")
                }
            })
            .addMigrations(object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Tithe records
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `tithe_records` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `type` TEXT NOT NULL,
                            `amount` REAL NOT NULL,
                            `recipient` TEXT NOT NULL DEFAULT '',
                            `date` INTEGER NOT NULL,
                            `category` TEXT NOT NULL DEFAULT '',
                            `notes` TEXT NOT NULL DEFAULT '',
                            `incomeAtTime` REAL NOT NULL DEFAULT 0.0,
                            `inputMethod` TEXT NOT NULL DEFAULT 'VOICE',
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tithe_records_date` ON `tithe_records` (`date`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tithe_records_type` ON `tithe_records` (`type`)")

                    // Goal records
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `goal_records` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `targetAmount` REAL NOT NULL,
                            `currentAmount` REAL NOT NULL DEFAULT 0.0,
                            `category` TEXT NOT NULL,
                            `deadline` INTEGER NOT NULL DEFAULT 0,
                            `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                            `weeklyTarget` REAL NOT NULL DEFAULT 0.0,
                            `dailyTarget` REAL NOT NULL DEFAULT 0.0,
                            `streak` INTEGER NOT NULL DEFAULT 0,
                            `bestStreak` INTEGER NOT NULL DEFAULT 0,
                            `deeperPurpose` TEXT NOT NULL DEFAULT '',
                            `createdAt` INTEGER NOT NULL DEFAULT 0,
                            `updatedAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_records_status` ON `goal_records` (`status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_records_category` ON `goal_records` (`category`)")

                    // Goal progress entries
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `goal_progress_entries` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `goalId` INTEGER NOT NULL,
                            `amount` REAL NOT NULL,
                            `note` TEXT NOT NULL DEFAULT '',
                            `timestamp` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`goalId`) REFERENCES `goal_records`(`id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_progress_entries_goalId` ON `goal_progress_entries` (`goalId`)")

                    // Goal milestones
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `goal_milestones` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `goalId` INTEGER NOT NULL,
                            `percentage` REAL NOT NULL,
                            `reachedAt` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`goalId`) REFERENCES `goal_records`(`id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_milestones_goalId` ON `goal_milestones` (`goalId`)")

                    // Loan records
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `loan_records` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `amount` REAL NOT NULL,
                            `purpose` TEXT NOT NULL,
                            `lender` TEXT NOT NULL DEFAULT '',
                            `interestRate` REAL NOT NULL DEFAULT 0.0,
                            `totalDue` REAL NOT NULL DEFAULT 0.0,
                            `startDate` INTEGER NOT NULL,
                            `endDate` INTEGER NOT NULL DEFAULT 0,
                            `repaymentFrequency` TEXT NOT NULL DEFAULT 'MONTHLY',
                            `totalRepaid` REAL NOT NULL DEFAULT 0.0,
                            `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                            `createdAt` INTEGER NOT NULL DEFAULT 0,
                            `updatedAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_records_status` ON `loan_records` (`status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_records_purpose` ON `loan_records` (`purpose`)")

                    // Loan repayments
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `loan_repayments` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `loanId` INTEGER NOT NULL,
                            `amount` REAL NOT NULL,
                            `dueDate` INTEGER NOT NULL,
                            `paidDate` INTEGER,
                            `paidAmount` REAL,
                            `status` TEXT NOT NULL DEFAULT 'PENDING',
                            `penalty` REAL NOT NULL DEFAULT 0.0,
                            FOREIGN KEY(`loanId`) REFERENCES `loan_records`(`id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_repayments_loanId` ON `loan_repayments` (`loanId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_repayments_status` ON `loan_repayments` (`status`)")
                }
            })
            // Migration v6 → v7: Added briefing_deliveries for morning briefing feedback loop
            .addMigrations(object : androidx.room.migration.Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `briefing_deliveries` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `briefingType` TEXT NOT NULL,
                            `briefingText` TEXT NOT NULL,
                            `predictedSales` REAL NOT NULL DEFAULT 0.0,
                            `predictedProfit` REAL NOT NULL DEFAULT 0.0,
                            `keyAdvice` TEXT NOT NULL DEFAULT '',
                            `opened` INTEGER NOT NULL DEFAULT 0,
                            `openedAt` INTEGER NOT NULL DEFAULT 0,
                            `actedOn` INTEGER NOT NULL DEFAULT 0,
                            `actedOnAt` INTEGER NOT NULL DEFAULT 0,
                            `actualSales` REAL NOT NULL DEFAULT 0.0,
                            `actualProfit` REAL NOT NULL DEFAULT 0.0,
                            `outcomeScore` REAL NOT NULL DEFAULT 0.0,
                            `adviceFollowed` INTEGER,
                            `deliveredAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_briefingType` ON `briefing_deliveries` (`briefingType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_deliveredAt` ON `briefing_deliveries` (`deliveredAt`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_actedOn` ON `briefing_deliveries` (`actedOn`)")
                }
            })
            // Migration v7 → v8: Added WorkerProfile table and composite indexes for query optimization
            .addMigrations(object : androidx.room.migration.Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `worker_profile` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL DEFAULT '',
                            `businessType` TEXT NOT NULL DEFAULT '',
                            `language` TEXT NOT NULL DEFAULT 'sw',
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                }
            })
            // Migration v8 → v9: Added composite indexes for tithe, goal, loan, briefing queries
            .addMigrations(object : androidx.room.migration.Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Tithe composite indexes
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tithe_records_type_date` ON `tithe_records` (`type`, `date`)")
                    // Goal composite indexes
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_records_status_category` ON `goal_records` (`status`, `category`)")
                    // Loan composite indexes
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_records_status_startDate` ON `loan_records` (`status`, `startDate`)")
                    // Briefing composite indexes
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_briefingType_deliveredAt` ON `briefing_deliveries` (`briefingType`, `deliveredAt`)")
                }
            })
            // Migration v10 → v11: Added worker_vocabulary table for per-worker personalized vocabulary
            .addMigrations(object : androidx.room.migration.Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `worker_vocabulary` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `workerId` INTEGER NOT NULL DEFAULT 1,
                            `spokenForm` TEXT NOT NULL,
                            `canonicalForm` TEXT NOT NULL,
                            `language` TEXT NOT NULL DEFAULT 'sw',
                            `wordType` TEXT NOT NULL DEFAULT 'PRODUCT',
                            `frequency` INTEGER NOT NULL DEFAULT 1,
                            `confidence` REAL NOT NULL DEFAULT 0.3,
                            `pronunciationVariants` TEXT NOT NULL DEFAULT '[]',
                            `categoryHint` TEXT NOT NULL DEFAULT 'unknown',
                            `dialectRegion` TEXT NOT NULL DEFAULT 'STANDARD',
                            `avgAsrConfidence` REAL NOT NULL DEFAULT 0.0,
                            `lowConfidenceCount` INTEGER NOT NULL DEFAULT 0,
                            `autoPromoted` INTEGER NOT NULL DEFAULT 0,
                            `workerConfirmed` INTEGER NOT NULL DEFAULT 0,
                            `firstSeenAt` INTEGER NOT NULL DEFAULT 0,
                            `lastSeenAt` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`workerId`) REFERENCES `worker_profile`(`id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_worker_vocabulary_workerId` ON `worker_vocabulary` (`workerId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_worker_vocabulary_spokenForm` ON `worker_vocabulary` (`spokenForm`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_worker_vocabulary_wordType` ON `worker_vocabulary` (`wordType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_worker_vocabulary_frequency` ON `worker_vocabulary` (`frequency`)")
                }
            })
            // Migration v11 → v12: Added social layer tables for peer comparison, leaderboard, community tips, WhatsApp groups
            .addMigrations(object : androidx.room.migration.Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Peer metrics — aggregated peer data by location × business type
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `peer_metrics` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `location` TEXT NOT NULL,
                            `businessType` TEXT NOT NULL,
                            `periodStart` INTEGER NOT NULL,
                            `periodType` TEXT NOT NULL DEFAULT 'DAILY',
                            `avgDailySales` REAL NOT NULL DEFAULT 0.0,
                            `medianDailySales` REAL NOT NULL DEFAULT 0.0,
                            `p25DailySales` REAL NOT NULL DEFAULT 0.0,
                            `p75DailySales` REAL NOT NULL DEFAULT 0.0,
                            `p90DailySales` REAL NOT NULL DEFAULT 0.0,
                            `avgDailyProfit` REAL NOT NULL DEFAULT 0.0,
                            `medianDailyProfit` REAL NOT NULL DEFAULT 0.0,
                            `avgTransactionCount` REAL NOT NULL DEFAULT 0.0,
                            `avgStreak` REAL NOT NULL DEFAULT 0.0,
                            `maxStreak` INTEGER NOT NULL DEFAULT 0,
                            `peerCount` INTEGER NOT NULL DEFAULT 0,
                            `computedAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_metrics_location_businessType` ON `peer_metrics` (`location`, `businessType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_metrics_periodStart` ON `peer_metrics` (`periodStart`)")

                    // Peer comparison result — cached comparison for current user
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `peer_comparisons` (
                            `id` INTEGER NOT NULL DEFAULT 1,
                            `location` TEXT NOT NULL DEFAULT '',
                            `businessType` TEXT NOT NULL DEFAULT '',
                            `workerDailySales` REAL NOT NULL DEFAULT 0.0,
                            `salesPercentile` INTEGER NOT NULL DEFAULT 0,
                            `workerDailyProfit` REAL NOT NULL DEFAULT 0.0,
                            `profitPercentile` INTEGER NOT NULL DEFAULT 0,
                            `workerTransactionCount` INTEGER NOT NULL DEFAULT 0,
                            `transactionPercentile` INTEGER NOT NULL DEFAULT 0,
                            `workerStreak` INTEGER NOT NULL DEFAULT 0,
                            `peerAvgDailySales` REAL NOT NULL DEFAULT 0.0,
                            `peerCount` INTEGER NOT NULL DEFAULT 0,
                            `comparedAt` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_comparisons_comparedAt` ON `peer_comparisons` (`comparedAt`)")

                    // Leaderboard entries — weekly rankings
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `leaderboard_entries` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `location` TEXT NOT NULL,
                            `businessType` TEXT NOT NULL,
                            `weekStart` INTEGER NOT NULL,
                            `rank` INTEGER NOT NULL,
                            `weeklySales` REAL NOT NULL DEFAULT 0.0,
                            `weeklyProfit` REAL NOT NULL DEFAULT 0.0,
                            `transactionCount` INTEGER NOT NULL DEFAULT 0,
                            `streak` INTEGER NOT NULL DEFAULT 0,
                            `totalPoints` INTEGER NOT NULL DEFAULT 0,
                            `isCurrentUser` INTEGER NOT NULL DEFAULT 0,
                            `totalParticipants` INTEGER NOT NULL DEFAULT 0,
                            `syncedAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_leaderboard_entries_location_businessType_weekStart` ON `leaderboard_entries` (`location`, `businessType`, `weekStart`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_leaderboard_entries_weekStart` ON `leaderboard_entries` (`weekStart`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_leaderboard_entries_rank` ON `leaderboard_entries` (`rank`)")

                    // Leaderboard summary — cached user position
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `leaderboard_summary` (
                            `id` INTEGER NOT NULL DEFAULT 1,
                            `myRank` INTEGER NOT NULL DEFAULT 0,
                            `totalParticipants` INTEGER NOT NULL DEFAULT 0,
                            `myWeeklySales` REAL NOT NULL DEFAULT 0.0,
                            `myWeeklyProfit` REAL NOT NULL DEFAULT 0.0,
                            `rankChange` INTEGER NOT NULL DEFAULT 0,
                            `weekStart` INTEGER NOT NULL DEFAULT 0,
                            `location` TEXT NOT NULL DEFAULT '',
                            `businessType` TEXT NOT NULL DEFAULT '',
                            `updatedAt` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())

                    // Community tips — anonymous business tips
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `community_tips` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `content` TEXT NOT NULL,
                            `location` TEXT NOT NULL,
                            `businessType` TEXT NOT NULL,
                            `category` TEXT NOT NULL DEFAULT 'general',
                            `upvotes` INTEGER NOT NULL DEFAULT 0,
                            `featured` INTEGER NOT NULL DEFAULT 0,
                            `featuredCount` INTEGER NOT NULL DEFAULT 0,
                            `isOwnTip` INTEGER NOT NULL DEFAULT 0,
                            `hasUpvoted` INTEGER NOT NULL DEFAULT 0,
                            `serverId` TEXT NOT NULL DEFAULT '',
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_community_tips_location_businessType` ON `community_tips` (`location`, `businessType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_community_tips_upvotes` ON `community_tips` (`upvotes`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_community_tips_createdAt` ON `community_tips` (`createdAt`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_community_tips_featured` ON `community_tips` (`featured`)")

                    // Tip delivery log — tracks which tips were shown
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `tip_delivery_log` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `tipId` INTEGER NOT NULL,
                            `deliveredAt` INTEGER NOT NULL DEFAULT 0,
                            `engaged` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tip_delivery_log_tipId` ON `tip_delivery_log` (`tipId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tip_delivery_log_deliveredAt` ON `tip_delivery_log` (`deliveredAt`)")

                    // WhatsApp groups — community group metadata
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `whatsapp_groups` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `groupId` TEXT NOT NULL,
                            `groupName` TEXT NOT NULL,
                            `location` TEXT NOT NULL,
                            `businessType` TEXT NOT NULL,
                            `memberCount` INTEGER NOT NULL DEFAULT 0,
                            `isMember` INTEGER NOT NULL DEFAULT 0,
                            `isMuted` INTEGER NOT NULL DEFAULT 0,
                            `lastBriefSharedAt` INTEGER NOT NULL DEFAULT 0,
                            `lastChallengeAt` INTEGER NOT NULL DEFAULT 0,
                            `inviteLink` TEXT NOT NULL DEFAULT '',
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_whatsapp_groups_location_businessType` ON `whatsapp_groups` (`location`, `businessType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_whatsapp_groups_groupId` ON `whatsapp_groups` (`groupId`)")

                    // Peer challenges — friendly competition via WhatsApp
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `peer_challenges` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `groupId` INTEGER NOT NULL,
                            `challengeType` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `metric` TEXT NOT NULL,
                            `targetValue` REAL NOT NULL DEFAULT 0.0,
                            `currentProgress` REAL NOT NULL DEFAULT 0.0,
                            `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                            `startsAt` INTEGER NOT NULL,
                            `endsAt` INTEGER NOT NULL,
                            `participantCount` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_challenges_groupId` ON `peer_challenges` (`groupId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_challenges_status` ON `peer_challenges` (`status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_challenges_endsAt` ON `peer_challenges` (`endsAt`)")
                }
            })
            // Migration v11 → v12: Added streak recovery tracking columns to gamification table
            .addMigrations(object : androidx.room.migration.Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveriesUsedThisMonth` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveryMonth` INTEGER NOT NULL DEFAULT 0")
                }
            })
            .fallbackToDestructiveMigration()
            .build()
        AppDatabase.setInstance(db)
        return db
    }

    @Provides
    @Singleton
    fun provideMsaidiziApi(): MsaidiziApi {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.angavu.io/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(MsaidiziApi::class.java)
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

    @Provides
    fun provideFeedbackDao(db: AppDatabase): FeedbackDao = db.feedbackDao()

    @Provides
    fun provideFeatureRequestDao(db: AppDatabase): FeatureRequestDao = db.featureRequestDao()

    @Provides
    fun provideGamificationDao(db: AppDatabase): GamificationDao = db.gamificationDao()

    @Provides
    fun provideRichHabitsDao(db: AppDatabase): RichHabitsDao = db.richHabitsDao()

    @Provides
    fun provideTitheDao(db: AppDatabase): TitheDao = db.titheDao()

    @Provides
    fun provideGoalDao(db: AppDatabase): GoalDao = db.goalDao()

    @Provides
    fun provideLoanDao(db: AppDatabase): LoanDao = db.loanDao()

    @Provides
    fun provideMindsetLessonDao(db: AppDatabase): MindsetLessonDao = db.mindsetLessonDao()

    @Provides
    fun provideBriefingDeliveryDao(db: AppDatabase): BriefingDeliveryDao = db.briefingDeliveryDao()

    @Provides
    fun provideWorkerVocabularyDao(db: AppDatabase): com.msaidizi.app.core.model.WorkerVocabularyDao = db.workerVocabularyDao()

    // === DIALECT & ADAPTIVE VOCABULARY ===

    @Provides
    @Singleton
    fun provideAdaptiveVocabulary(
        learningDao: VocabularyLearningDao,
        userVocabDao: UserVocabularyDao
    ): AdaptiveVocabulary = AdaptiveVocabulary(learningDao, userVocabDao)

    // === RECEIPT SCANNING ===

    @Provides
    @Singleton
    fun provideReceiptScanner(): com.msaidizi.app.scanner.ReceiptScanner =
        com.msaidizi.app.scanner.ReceiptScanner()

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

    // === 12-FACTOR AGENT INFRASTRUCTURE ===

    @Provides
    @Singleton
    fun provideContextManager(): ContextManager = ContextManager(agentName = "orchestrator")

    @Provides
    @Singleton
    fun provideErrorCompactor(): ErrorCompactor = ErrorCompactor(agentName = "orchestrator")

    @Provides
    @Singleton
    fun provideUnifiedStateManager(): UnifiedStateManager = UnifiedStateManager(agentName = "orchestrator")

    // === AGENTS ===

    @Provides
    @Singleton
    fun provideAgentEventBus(): AgentEventBus = AgentEventBus.getInstance()

    @Provides
    @Singleton
    fun provideIntentPatternConfig(@ApplicationContext context: Context): IntentPatternConfig = IntentPatternConfig(context)

    @Provides
    @Singleton
    fun provideIntentRouter(config: IntentPatternConfig): IntentRouter = IntentRouter(config)

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
        analysisAgent: AnalysisAgent,
        voicePersonality: VoicePersonality
    ): AdvisorAgent = AdvisorAgent(businessAgent, analysisAgent, voicePersonality)

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
        learningAgent: LearningAgent,
        learningHarness: LearningHarness
    ): AdaptiveLearningEngine = AdaptiveLearningEngine(
        userVocabularyDao, userCorrectionDao, transactionDao, patternDao,
        patternTracker, learningAgent, learningHarness
    )

    @Provides
    @Singleton
    fun providePreferenceLearner(
        patternDao: PatternDao,
        userCorrectionDao: UserCorrectionDao,
        userVocabularyDao: UserVocabularyDao
    ): PreferenceLearner = PreferenceLearner(patternDao, userCorrectionDao, userVocabularyDao)

    // === DECOMPOSED HANDLERS ===

    @Provides
    @Singleton
    fun provideTransactionHandler(
        businessAgent: BusinessAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        gamificationEngine: GamificationEngine,
        ahaMomentFlow: AhaMomentFlow,
        richHabitsScore: RichHabitsScore,
        morningBriefingLoop: MorningBriefingLoop,
        streakProtectionLoop: StreakProtectionLoop,
        variableRewardsLoop: VariableRewardsLoop,
        briefingDelivery: BriefingDelivery,
        selfEvolution: SelfEvolutionManager
    ): TransactionHandler = TransactionHandler(
        businessAgent, adaptiveLearning, learningAgent,
        gamificationEngine, ahaMomentFlow, richHabitsScore,
        morningBriefingLoop, streakProtectionLoop, variableRewardsLoop,
        briefingDelivery, selfEvolution
    )

    @Provides
    @Singleton
    fun provideQueryHandler(
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        gamificationEngine: GamificationEngine,
        richHabitsScore: RichHabitsScore
    ): QueryHandler = QueryHandler(
        businessAgent, analysisAgent, advisorAgent,
        gamificationEngine, richHabitsScore
    )

    @Provides
    @Singleton
    fun provideAdviceHandler(
        advisorAgent: AdvisorAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        selfEvolution: SelfEvolutionManager,
        preferenceLearner: PreferenceLearner
    ): AdviceHandler = AdviceHandler(
        advisorAgent, adaptiveLearning, selfEvolution, preferenceLearner
    )

    @Provides
    @Singleton
    fun provideGamificationHandler(
        titheTracker: TitheTracker,
        goalPlanner: GoalPlanner,
        loanManager: LoanManager,
        gamificationEngine: GamificationEngine,
        richHabitsScore: RichHabitsScore
    ): GamificationHandler = GamificationHandler(
        titheTracker, goalPlanner, loanManager, gamificationEngine, richHabitsScore
    )

    @Provides
    @Singleton
    fun provideDomainRouter(
        businessAgent: BusinessAgent,
        advisorAgent: AdvisorAgent
    ): DomainRouter = DomainRouter(businessAgent, advisorAgent)

    @Provides
    @Singleton
    fun provideConversationManager(
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        selfEvolution: SelfEvolutionManager,
        preferenceLearner: PreferenceLearner,
        llmEngine: LlmEngine
    ): ConversationManager = ConversationManager(
        llmEngine = llmEngine,
        selfEvolution = selfEvolution,
        adaptiveLearning = adaptiveLearning,
        learningAgent = learningAgent,
        preferenceLearner = preferenceLearner
    )

    // === ON-DEVICE AI PREDICTORS ===

    @Provides
    @Singleton
    fun provideProactiveAnomalyDetector(
        transactionDao: TransactionDao
    ): ProactiveAnomalyDetector = ProactiveAnomalyDetector(transactionDao)

    @Provides
    @Singleton
    fun provideStockOutPredictor(
        inventoryDao: InventoryDao,
        transactionDao: TransactionDao
    ): StockOutPredictor = StockOutPredictor(inventoryDao, transactionDao)

    @Provides
    @Singleton
    fun provideCashFlowPredictor(
        transactionDao: TransactionDao
    ): CashFlowPredictor = CashFlowPredictor(transactionDao)

    @Provides
    @Singleton
    fun provideProactiveAlertEngine(
        patternTracker: BusinessPatternTracker,
        anomalyDetector: ProactiveAnomalyDetector,
        stockOutPredictor: StockOutPredictor,
        cashFlowPredictor: CashFlowPredictor,
        transactionDao: TransactionDao,
        inventoryDao: InventoryDao,
        eventBus: AgentEventBus
    ): ProactiveAlertEngine = ProactiveAlertEngine(
        patternTracker, anomalyDetector, stockOutPredictor, cashFlowPredictor,
        transactionDao, inventoryDao, eventBus
    )

    @Provides
    @Singleton
    fun provideOrchestrator(
        intentRouter: IntentRouter,
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        learningAgent: LearningAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        transactionHandler: TransactionHandler,
        queryHandler: QueryHandler,
        adviceHandler: AdviceHandler,
        gamificationHandler: GamificationHandler,
        domainRouter: DomainRouter,
        conversationManager: ConversationManager,
        gamificationEngine: GamificationEngine,
        ahaMomentFlow: AhaMomentFlow,
        richHabitsScore: RichHabitsScore,
        mindsetAcademy: MindsetAcademy,
        titheTracker: TitheTracker,
        goalPlanner: GoalPlanner,
        loanManager: LoanManager,
        titheDao: TitheDao,
        goalDao: GoalDao,
        loanDao: LoanDao,
        briefingDelivery: BriefingDelivery,
        morningBriefingLoop: MorningBriefingLoop,
        streakProtectionLoop: StreakProtectionLoop,
        variableRewardsLoop: VariableRewardsLoop,
        selfEvolution: SelfEvolutionManager,
        preferenceLearner: PreferenceLearner,
        adaptiveVocabulary: AdaptiveVocabulary,
        conversationLearningPipeline: ConversationLearningPipeline,
        llmEngine: LlmEngine,
        voicePersonality: VoicePersonality,
        proactiveAlertEngine: ProactiveAlertEngine,
        socialHandler: SocialHandler
    ): Orchestrator = Orchestrator(
        intentRouter = intentRouter,
        businessAgent = businessAgent,
        analysisAgent = analysisAgent,
        advisorAgent = advisorAgent,
        learningAgent = learningAgent,
        adaptiveLearning = adaptiveLearning,
        transactionHandler = transactionHandler,
        queryHandler = queryHandler,
        adviceHandler = adviceHandler,
        gamificationHandler = gamificationHandler,
        domainRouter = domainRouter,
        conversationManager = conversationManager,
        gamificationEngine = gamificationEngine,
        ahaMomentFlow = ahaMomentFlow,
        richHabitsScore = richHabitsScore,
        mindsetAcademy = mindsetAcademy,
        titheTracker = titheTracker,
        goalPlanner = goalPlanner,
        loanManager = loanManager,
        titheDao = titheDao,
        goalDao = goalDao,
        loanDao = loanDao,
        briefingDelivery = briefingDelivery,
        morningBriefingLoop = morningBriefingLoop,
        streakProtectionLoop = streakProtectionLoop,
        variableRewardsLoop = variableRewardsLoop,
        selfEvolution = selfEvolution,
        preferenceLearner = preferenceLearner,
        adaptiveVocabulary = adaptiveVocabulary,
        conversationLearningPipeline = conversationLearningPipeline,
        llmEngine = llmEngine,
        voicePersonality = voicePersonality,
        proactiveAlertEngine = proactiveAlertEngine,
        socialHandler = socialHandler
    )

    @Provides
    @Singleton
    fun provideLlamaCppEngine(
        @ApplicationContext context: Context
    ): LlamaCppEngine = LlamaCppEngine(context)

    @Provides
    @Singleton
    fun provideLlmEngine(
        @ApplicationContext context: Context,
        llamaCppEngine: LlamaCppEngine,
        languageModelRegistry: LanguageModelRegistry,
        adaptiveAsrEngine: AdaptiveAsrEngine
    ): LlmEngine = LlmEngine(context, llamaCppEngine, languageModelRegistry, adaptiveAsrEngine)

    @Provides
    @Singleton
    fun provideModelRouter(
        @ApplicationContext context: Context,
        llmEngine: LlmEngine,
        api: MsaidiziApi,
        inferenceHarness: InferenceHarness
    ): ModelRouter = ModelRouter(context, llmEngine = llmEngine, apiClient = api, inferenceHarness = inferenceHarness)

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
        httpClient: HttpClient
    ): SyncManager = SyncManager(syncQueue, networkMonitor, httpClient, Gson())

    // === ADAPTIVE ASR & LANGUAGE LEARNING ===

    @Provides
    @Singleton
    fun provideConfidenceCalibrator(): ConfidenceCalibrator = ConfidenceCalibrator()

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
        pinnedHttpClient: PinnedHttpClient,
        consentManager: ConsentManager
    ): FederatedLearningClient = FederatedLearningClient(context, pinnedHttpClient, consentManager)

    // === EVOLUTION / SELF-EVOLUTION ===

    @Provides
    @Singleton
    fun provideFeedbackCollector(
        feedbackDao: FeedbackDao,
        httpClient: HttpClient,
        json: Json,
        @ApplicationContext context: Context
    ): FeedbackCollector = FeedbackCollector(feedbackDao, httpClient, json, context)

    @Provides
    @Singleton
    fun provideFeatureRequestTracker(
        requestDao: FeatureRequestDao,
        feedbackDao: FeedbackDao
    ): FeatureRequestTracker = FeatureRequestTracker(requestDao, feedbackDao)

    // === GAMIFICATION & STICKINESS ===

    @Provides
    @Singleton
    fun provideGamificationEngine(
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): GamificationEngine {
        val engine = GamificationEngine(gamificationDao)
        // Wire up the data-dependent reward subsystems
        engine.microRewards = MicroRewards(gamificationDao, transactionDao, patternDao)
        engine.insightRewards = InsightRewards(gamificationDao, transactionDao, patternDao)
        return engine
    }

    @Provides
    @Singleton
    fun provideMindsetAcademy(
        mindsetLessonDao: MindsetLessonDao
    ): MindsetAcademy = MindsetAcademy(mindsetLessonDao)

    @Provides
    @Singleton
    fun provideRichHabitsScore(
        richHabitsDao: RichHabitsDao
    ): RichHabitsScore = RichHabitsScore(richHabitsDao)

    @Provides
    @Singleton
    fun provideTitheTracker(
        titheDao: TitheDao
    ): TitheTracker = TitheTracker(titheDao)

    @Provides
    @Singleton
    fun provideGoalPlanner(
        goalDao: GoalDao
    ): GoalPlanner = GoalPlanner(goalDao)

    @Provides
    @Singleton
    fun provideLoanManager(
        loanDao: LoanDao
    ): LoanManager = LoanManager(loanDao)

    @Provides
    @Singleton
    fun provideAhaMomentFlow(
        businessAgent: BusinessAgent
    ): AhaMomentFlow = AhaMomentFlow(businessAgent)

    @Provides
    @Singleton
    fun provideCFOEngine(): CFOEngine = CFOEngine()

    @Provides
    @Singleton
    fun provideBriefingDelivery(
        cfoEngine: CFOEngine,
        businessAgent: BusinessAgent,
        loanManager: LoanManager,
        gamificationEngine: GamificationEngine,
        mindsetAcademy: MindsetAcademy,
        richHabitsScore: RichHabitsScore,
        briefingDeliveryDao: BriefingDeliveryDao,
        peerComparison: PeerComparison,
        communityTips: CommunityTips,
        leaderboardService: LeaderboardService
    ): BriefingDelivery = BriefingDelivery(
        cfoEngine, businessAgent, loanManager,
        gamificationEngine, mindsetAcademy, richHabitsScore,
        briefingDeliveryDao, peerComparison, communityTips, leaderboardService
    )

    // === LOOPS — Foundation engagement cycles ===

    @Provides
    @Singleton
    fun provideMorningBriefingLoop(
        cfoEngine: CFOEngine,
        briefingDelivery: BriefingDelivery,
        businessAgent: BusinessAgent,
        transactionDao: TransactionDao,
        briefingDeliveryDao: BriefingDeliveryDao
    ): MorningBriefingLoop = MorningBriefingLoop(
        cfoEngine, briefingDelivery, businessAgent,
        transactionDao, briefingDeliveryDao
    )

    @Provides
    @Singleton
    fun provideStreakProtectionLoop(
        gamificationEngine: GamificationEngine,
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao
    ): StreakProtectionLoop = StreakProtectionLoop(
        gamificationEngine, gamificationDao, transactionDao
    )

    @Provides
    @Singleton
    fun provideVariableRewardsLoop(
        gamificationEngine: GamificationEngine,
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): VariableRewardsLoop = VariableRewardsLoop(
        gamificationEngine, gamificationDao, transactionDao, patternDao
    )

    // === SKILLS — Degree-to-skill bridge ===

    @Provides
    @Singleton
    fun provideSkillBridge(
        httpClient: HttpClient,
        json: Json
    ): SkillBridge = SkillBridge(httpClient, json)

    // === SOCIAL LAYER — Peer comparison, leaderboard, community tips, WhatsApp ===

    @Provides
    fun provideSocialDao(db: AppDatabase): SocialDao = db.socialDao()

    @Provides
    @Singleton
    fun providePeerComparison(
        socialDao: SocialDao,
        transactionDao: TransactionDao
    ): PeerComparison = PeerComparison(socialDao, transactionDao)

    @Provides
    @Singleton
    fun provideLeaderboardService(
        socialDao: SocialDao,
        transactionDao: TransactionDao,
        gamificationEngine: GamificationEngine
    ): LeaderboardService = LeaderboardService(socialDao, transactionDao, gamificationEngine)

    @Provides
    @Singleton
    fun provideCommunityTips(
        socialDao: SocialDao
    ): CommunityTips = CommunityTips(socialDao)

    @Provides
    @Singleton
    fun provideWhatsAppCommunity(
        socialDao: SocialDao
    ): WhatsAppCommunity = WhatsAppCommunity(socialDao)

    @Provides
    @Singleton
    fun provideSocialHandler(
        peerComparison: PeerComparison,
        leaderboardService: LeaderboardService,
        communityTips: CommunityTips,
        whatsappCommunity: WhatsAppCommunity
    ): SocialHandler = SocialHandler(peerComparison, leaderboardService, communityTips, whatsappCommunity)

    // === VISION — Product Recognition ===

    @Provides
    @Singleton
    fun provideProductClassifier(
        @ApplicationContext context: Context
    ): ProductClassifier = ProductClassifier(context)

    @Provides
    @Singleton
    fun provideVisionCorrectionTracker(
        workerVocabularyDao: com.msaidizi.app.core.model.WorkerVocabularyDao,
        federatedLearningClient: FederatedLearningClient
    ): VisionCorrectionTracker = VisionCorrectionTracker(workerVocabularyDao, federatedLearningClient)

    @Provides
    @Singleton
    fun provideProductRecognitionHandler(
        classifier: ProductClassifier,
        inventoryDao: InventoryDao,
        workerVocabularyDao: com.msaidizi.app.core.model.WorkerVocabularyDao,
        correctionTracker: VisionCorrectionTracker,
        tts: com.msaidizi.app.voice.TextToSpeech,
        visionHarness: VisionHarness
    ): ProductRecognitionHandler = ProductRecognitionHandler(
        classifier, inventoryDao, workerVocabularyDao, correctionTracker, tts, visionHarness
    )

    // === HARNESS LAYER ===

    @Provides
    @Singleton
    fun provideInferenceHarness(
        costTracker: com.msaidizi.app.agent.cost.InferenceCostTracker
    ): InferenceHarness = InferenceHarness(costTracker)

    @Provides
    @Singleton
    fun provideVoicePipelineHarness(
        inferenceHarness: InferenceHarness
    ): VoicePipelineHarness = VoicePipelineHarness(inferenceHarness)

    @Provides
    @Singleton
    fun provideLearningHarness(): LearningHarness = LearningHarness()

    @Provides
    @Singleton
    fun provideVisionHarness(
        inferenceHarness: InferenceHarness,
        correctionTracker: VisionCorrectionTracker
    ): VisionHarness = VisionHarness(inferenceHarness, correctionTracker)
}

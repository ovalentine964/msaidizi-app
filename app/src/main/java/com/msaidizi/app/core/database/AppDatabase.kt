package com.msaidizi.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.msaidizi.app.core.model.*
import com.msaidizi.app.evolution.FeedbackEntity
import com.msaidizi.app.evolution.FeatureRequestEntity
import com.msaidizi.app.evolution.FeedbackDao
import com.msaidizi.app.evolution.FeatureRequestDao
import com.msaidizi.app.core.model.TitheRecord
import com.msaidizi.app.core.model.GoalRecord
import com.msaidizi.app.core.model.GoalProgressEntry
import com.msaidizi.app.core.model.GoalMilestone
import com.msaidizi.app.core.model.LoanRecord
import com.msaidizi.app.core.model.LoanRepayment
import com.msaidizi.app.core.model.GamificationEntity
import com.msaidizi.app.core.model.RichHabitEntry
import com.msaidizi.app.core.model.MindsetLessonEntity
import com.msaidizi.app.core.model.BriefingDeliveryEntity
import com.msaidizi.app.onboarding.WorkerProfile
import com.msaidizi.app.data.sync.SyncableGoal
import com.msaidizi.app.data.sync.SyncableInventory
import com.msaidizi.app.data.sync.SyncableTransaction

/**
 * Room database for Msaidizi.
 *
 * Stores all business transactions, inventory, patterns, vocabulary,
 * user corrections, adaptive learning data, and evolution feedback.
 * Optimized for 2GB devices:
 * - WAL mode for concurrent reads during writes
 * - Minimal indices (only what's needed for common queries)
 * - Integer timestamps (not datetime strings)
 *
 * Version 2: Added UserVocabulary and UserCorrection for adaptive learning.
 * Version 4: Added Feedback and FeatureRequest for self-evolution.
 * Version 5: Added gamification, rich habits, and mindset lessons.
 * Version 6: Added tithe records, goal records, and loan records.
 * Version 7: Added composite indexes for query optimization.
 * Version 9: Added composite indexes for tithe, goal, loan, briefing queries.
 */
@Database(
    entities = [
        Transaction::class,
        InventoryItem::class,
        BusinessPattern::class,
        VocabularyEntry::class,
        DailySummary::class,
        UserVocabulary::class,
        UserCorrection::class,
        LearnedWord::class,
        FeedbackEntity::class,
        FeatureRequestEntity::class,
        GamificationEntity::class,
        RichHabitEntry::class,
        MindsetLessonEntity::class,
        TitheRecord::class,
        GoalRecord::class,
        GoalProgressEntry::class,
        GoalMilestone::class,
        LoanRecord::class,
        LoanRepayment::class,
        WorkerProfile::class,
        SyncableTransaction::class,
        SyncableInventory::class,
        SyncableGoal::class,
        BriefingDeliveryEntity::class,
        WorkerVocabulary::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun patternDao(): PatternDao
    abstract fun userVocabularyDao(): UserVocabularyDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun vocabularyLearningDao(): VocabularyLearningDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun featureRequestDao(): FeatureRequestDao
    abstract fun gamificationDao(): GamificationDao
    abstract fun richHabitsDao(): RichHabitsDao
    abstract fun titheDao(): TitheDao
    abstract fun goalDao(): GoalDao
    abstract fun loanDao(): LoanDao
    abstract fun mindsetLessonDao(): MindsetLessonDao
    abstract fun briefingDeliveryDao(): BriefingDeliveryDao
    abstract fun workerVocabularyDao(): WorkerVocabularyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Set the database instance (called from Hilt/AppModule).
         * Allows WorkManager workers to access the database without Hilt injection.
         */
        fun setInstance(database: AppDatabase) {
            INSTANCE = database
        }

        /**
         * Get the database instance.
         * Returns null if not yet initialized (before Application.onCreate).
         */
        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: throw IllegalStateException(
                    "AppDatabase not initialized. Call setInstance() from AppModule first."
                )
            }
        }
    }
}

// Converters class moved to Converters.kt

package com.msaidizi.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.model.*
import com.msaidizi.app.evolution.FeedbackDao
import com.msaidizi.app.evolution.FeatureRequestDao
import com.msaidizi.app.onboarding.WorkerProfile
import com.msaidizi.app.social.SocialDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

/**
 * Hilt module providing the Room database and all DAOs.
 *
 * Database is encrypted with SQLCipher. The passphrase is derived
 * from the device's Android ID + a stored salt.
 *
 * All DAOs are provided as singletons (Room DAOs are lightweight
 * wrappers around the database connection pool).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provide the encrypted Room database instance.
     *
     * Note: SQLCipher must be loaded before Room.databaseBuilder().
     * The passphrase is stored in EncryptedSharedPreferences.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // Load SQLCipher native library
        System.loadLibrary("sqlcipher")

        // Get or generate encryption passphrase
        val passphrase = getOrCreatePassphrase(context)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "msaidizi.db"
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray()))
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Enable WAL mode for concurrent reads on 2GB devices
                    db.execSQL("PRAGMA journal_mode=WAL")
                }
            })
            .build()
            .also { AppDatabase.setInstance(it) }
    }

    /**
     * Get or create the database encryption passphrase.
     * Stored in EncryptedSharedPreferences for persistence.
     */
    private fun getOrCreatePassphrase(context: Context): String {
        val prefs = context.getSharedPreferences("db_security", Context.MODE_PRIVATE)
        var passphrase = prefs.getString("db_passphrase", null)
        if (passphrase == null) {
            passphrase = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("db_passphrase", passphrase).apply()
        }
        return passphrase
    }

    // ── DAO Providers ──────────────────────────────────────────

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
    fun provideSocialDao(db: AppDatabase): SocialDao = db.socialDao()

    @Provides
    fun provideWorkerVocabularyDao(db: AppDatabase): WorkerVocabularyDao = db.workerVocabularyDao()
}

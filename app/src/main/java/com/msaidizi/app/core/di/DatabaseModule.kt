package com.msaidizi.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msaidizi.app.core.database.AppDatabase
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.UserVocabularyDao
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.RichHabitsDao
import com.msaidizi.app.core.database.MindsetLessonDao
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.evolution.FeedbackDao
import com.msaidizi.app.evolution.FeatureRequestDao
import com.msaidizi.app.security.crypto.DatabaseKeyManager
import com.msaidizi.app.social.SocialDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase
import timber.log.Timber
import javax.inject.Singleton

/**
 * Database-related dependencies: Room database, all DAOs, SQLCipher encryption.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        databaseKeyManager: DatabaseKeyManager
    ): AppDatabase {
        val dbName = "msaidizi.db"
        val dbFile = context.getDatabasePath(dbName)
        var oldDbPath: String? = null

        if (dbFile.exists()) {
            try {
                val header = ByteArray(16)
                dbFile.inputStream().use { it.read(header) }
                val isUnencrypted = String(header, 0, 15).startsWith("SQLite format 3")

                if (isUnencrypted) {
                    val backupPath = "${dbFile.absolutePath}.old"
                    val backupFile = java.io.File(backupPath)
                    if (backupFile.exists()) backupFile.delete()
                    if (dbFile.renameTo(backupFile)) {
                        oldDbPath = backupPath
                        Timber.w("Existing unencrypted database renamed for SQLCipher migration: %s", backupPath)
                    } else {
                        Timber.e("Failed to rename unencrypted database — deleting instead")
                        dbFile.delete()
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error checking database encryption state — proceeding with fresh database")
                dbFile.delete()
            }
        }

        // Backup database before attempting migration (safety net for v1→v14 chain)
        if (dbFile.exists()) {
            backupDatabaseBeforeMigration(context, dbName)
        }

        // Log key storage health for diagnostics
        Timber.i("Database key health: %s", databaseKeyManager.getKeyStorageHealth())

        val passphrase = try {
            databaseKeyManager.getPassphrase()
        } catch (e: Throwable) {
            // Ultimate fallback — should rarely happen now with deterministic key recovery
            Timber.e(e, "CRITICAL: getPassphrase() threw — using transient key")
            try { io.sentry.Sentry.captureException(e) } catch (_: Throwable) {}
            ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
        }

        // Build the encrypted database, falling back to unencrypted if anything fails.
        // On budget devices (Tecno, Infinix, Itel), EncryptedSharedPreferences / SQLCipher
        // can fail due to Keystore corruption, partial DB files, or memory pressure.
        // A working unencrypted database is always better than a crash.
        val db: AppDatabase = try {
            val factory = SupportFactory(passphrase)

            // Migrate old unencrypted DB into encrypted format if needed
            if (oldDbPath != null) {
                try {
                    val newEncryptedPath = dbFile.absolutePath
                    val srcDb = SQLiteDatabase.openDatabase(
                        oldDbPath, "", null, SQLiteDatabase.OPEN_READONLY
                    )
                    srcDb.rawExecSQL("ATTACH DATABASE '$newEncryptedPath' AS encrypted KEY " +
                        "x'" + passphrase.joinToString("") { "%02x".format(it) } + "'")
                    srcDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                    srcDb.rawExecSQL("DETACH DATABASE encrypted")
                    srcDb.close()
                    java.io.File(oldDbPath).delete()
                    Timber.i("SQLCipher migration: data exported from unencrypted database into encrypted database")
                } catch (e: Throwable) {
                    Timber.e(e, "SQLCipher migration failed — falling back to destructive migration")
                    try { java.io.File(oldDbPath).delete() } catch (_: Throwable) {}
                }
            }

            Timber.i("Building encrypted database '%s'", dbName)
            commonDbBuilder(context, dbName)
                .openHelperFactory(factory)
                .build()
        } catch (e: Throwable) {
            Timber.e(e, "Encrypted database build failed — falling back to UNENCRYPTED database")
            try { io.sentry.Sentry.captureException(e) } catch (_: Throwable) {}
            try {
                context.deleteDatabase(dbName)
                Timber.i("Deleted corrupt encrypted database '%s'", dbName)
            } catch (delEx: Throwable) {
                Timber.e(delEx, "Failed to delete corrupt database")
            }
            // Clean up old DB migration file if it exists
            if (oldDbPath != null) {
                try { java.io.File(oldDbPath).delete() } catch (_: Throwable) {}
            }
            Timber.w("Building UNENCRYPTED database '%s' as fallback — encryption disabled", dbName)
            commonDbBuilder(context, dbName)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        Timber.i("Database initialized successfully for '%s'", dbName)
        AppDatabase.setInstance(db)
        return db
    }

    /**
     * Create a backup of the database file before attempting migration.
     * Returns the backup file, or null if backup failed.
     */
    private fun backupDatabaseBeforeMigration(context: Context, dbName: String): java.io.File? {
        return try {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return null
            val backupDir = java.io.File(context.filesDir, "db_backups")
            backupDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val backupFile = java.io.File(backupDir, "${dbName}.backup.$timestamp")
            dbFile.copyTo(backupFile, overwrite = true)
            // Also copy WAL and SHM files if they exist
            val walFile = java.io.File(dbFile.absolutePath + "-wal")
            val shmFile = java.io.File(dbFile.absolutePath + "-shm")
            if (walFile.exists()) walFile.copyTo(java.io.File(backupFile.absolutePath + "-wal"), overwrite = true)
            if (shmFile.exists()) shmFile.copyTo(java.io.File(backupFile.absolutePath + "-shm"), overwrite = true)
            Timber.i("Database backup created: %s (%d MB)", backupFile.name, backupFile.length() / (1024 * 1024))
            // Prune old backups — keep at most 2
            val backups = backupDir.listFiles { f -> f.name.startsWith("$dbName.backup.") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            backups.drop(2).forEach { old ->
                try { old.delete() } catch (_: Throwable) {}
                try { java.io.File(old.absolutePath + "-wal").delete() } catch (_: Throwable) {}
                try { java.io.File(old.absolutePath + "-shm").delete() } catch (_: Throwable) {}
            }
            backupFile
        } catch (e: Throwable) {
            Timber.e(e, "Failed to create database backup before migration")
            null
        }
    }

    /**
     * Common Room database builder with all migrations and WAL callback.
     * Encryption (openHelperFactory) is applied by the caller.
     *
     * Migration resilience strategy:
     * 1. All incremental migrations (1→2, 2→3, ..., 13→14) are registered
     * 2. A last-resort Migration(1, 14) recreates the full schema if any step fails
     * 3. Database is backed up before migration begins
     * 4. Migration failures are logged with the specific step that failed
     */
    private fun commonDbBuilder(
        context: Context,
        dbName: String
    ): RoomDatabase.Builder<AppDatabase> {
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL("PRAGMA journal_mode=WAL")
                    db.execSQL("PRAGMA busy_timeout=5000")
                    db.execSQL("PRAGMA synchronous=NORMAL")
                    db.execSQL("PRAGMA cache_size=-2000")
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA busy_timeout=5000")
                }
            })
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    super.onDestructiveMigration(db)
                    Timber.w("Destructive migration triggered — database was recreated from scratch")
                    try { io.sentry.Sentry.captureMessage("Database destructive migration triggered") } catch (_: Throwable) {}
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
            .addMigrations(object : androidx.room.migration.Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tithe_records_type_date` ON `tithe_records` (`type`, `date`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_records_status_category` ON `goal_records` (`status`, `category`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_records_status_startDate` ON `loan_records` (`status`, `startDate`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_briefingType_deliveredAt` ON `briefing_deliveries` (`briefingType`, `deliveredAt`)")
                }
            })
            .addMigrations(object : androidx.room.migration.Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) { }
            })
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
            .addMigrations(object : androidx.room.migration.Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveriesUsedThisMonth` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveryMonth` INTEGER NOT NULL DEFAULT 0")
                }
            })
            .addMigrations(object : androidx.room.migration.Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `knowledge_nodes` (
                            `node_id` TEXT NOT NULL,
                            `node_type` TEXT NOT NULL,
                            `domain` TEXT NOT NULL,
                            `key` TEXT NOT NULL,
                            `value_json` TEXT NOT NULL,
                            `confidence` REAL NOT NULL DEFAULT 0.0,
                            `created_at` INTEGER NOT NULL DEFAULT 0,
                            `updated_at` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`node_id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_domain` ON `knowledge_nodes` (`domain`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_node_type` ON `knowledge_nodes` (`node_type`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_domain_type` ON `knowledge_nodes` (`domain`, `node_type`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_updated_at` ON `knowledge_nodes` (`updated_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_confidence` ON `knowledge_nodes` (`confidence`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `knowledge_edges` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `from_node` TEXT NOT NULL,
                            `to_node` TEXT NOT NULL,
                            `relation_type` TEXT NOT NULL,
                            `strength` REAL NOT NULL DEFAULT 0.0,
                            `shared_keys_json` TEXT NOT NULL DEFAULT '[]',
                            `created_at` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`from_node`) REFERENCES `knowledge_nodes`(`node_id`) ON DELETE CASCADE,
                            FOREIGN KEY(`to_node`) REFERENCES `knowledge_nodes`(`node_id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_from_node` ON `knowledge_edges` (`from_node`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_to_node` ON `knowledge_edges` (`to_node`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_edges_from_to` ON `knowledge_edges` (`from_node`, `to_node`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_relation_type` ON `knowledge_edges` (`relation_type`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `agent_sessions` (
                            `session_id` TEXT NOT NULL,
                            `worker_id` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL DEFAULT 0,
                            `last_active` INTEGER NOT NULL DEFAULT 0,
                            `last_channel` TEXT NOT NULL DEFAULT 'app',
                            `context_window_json` TEXT NOT NULL DEFAULT '[]',
                            `active_trace_id` TEXT,
                            `active_skill_ids_json` TEXT NOT NULL DEFAULT '[]',
                            `last_skill_query` TEXT,
                            PRIMARY KEY(`session_id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_worker_id` ON `agent_sessions` (`worker_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_last_active` ON `agent_sessions` (`last_active`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_worker_active` ON `agent_sessions` (`worker_id`, `last_active`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `agent_traces` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `session_id` TEXT NOT NULL,
                            `trace_id` TEXT NOT NULL,
                            `step_index` INTEGER NOT NULL DEFAULT 0,
                            `action` TEXT NOT NULL,
                            `tool_used` TEXT,
                            `success` INTEGER NOT NULL DEFAULT 1,
                            `error` TEXT,
                            `duration_ms` INTEGER NOT NULL DEFAULT 0,
                            `created_at` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`session_id`) REFERENCES `agent_sessions`(`session_id`) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_session_id` ON `agent_traces` (`session_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_trace_id` ON `agent_traces` (`trace_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_created_at` ON `agent_traces` (`created_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_session_trace` ON `agent_traces` (`session_id`, `trace_id`)")
                }
            })
            .addMigrations(object : androidx.room.migration.Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `agent_task_checkpoints` (
                            `taskId` TEXT NOT NULL,
                            `taskType` TEXT NOT NULL,
                            `state` TEXT NOT NULL,
                            `lastPhase` TEXT NOT NULL,
                            `inputJson` TEXT NOT NULL,
                            `observationsJson` TEXT NOT NULL DEFAULT '{}',
                            `orientationJson` TEXT NOT NULL DEFAULT '{}',
                            `decisionJson` TEXT,
                            `contextJson` TEXT NOT NULL DEFAULT '{}',
                            `currentStepId` TEXT,
                            `completedStepsJson` TEXT NOT NULL DEFAULT '[]',
                            `retryCount` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` INTEGER NOT NULL DEFAULT 0,
                            `updatedAt` INTEGER NOT NULL DEFAULT 0,
                            `lastError` TEXT,
                            `language` TEXT NOT NULL DEFAULT 'sw',
                            PRIMARY KEY(`taskId`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_state` ON `agent_task_checkpoints` (`state`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_createdAt` ON `agent_task_checkpoints` (`createdAt`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_updatedAt` ON `agent_task_checkpoints` (`updatedAt`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `agent_recovery_traces` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `taskId` TEXT NOT NULL,
                            `traceType` TEXT NOT NULL,
                            `traceJson` TEXT NOT NULL,
                            `success` INTEGER NOT NULL DEFAULT 0,
                            `durationMs` INTEGER NOT NULL DEFAULT 0,
                            `timestamp` INTEGER NOT NULL DEFAULT 0,
                            `summary` TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_taskId` ON `agent_recovery_traces` (`taskId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_traceType` ON `agent_recovery_traces` (`traceType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_timestamp` ON `agent_recovery_traces` (`timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_success` ON `agent_recovery_traces` (`success`)")
                }
            })
            // ── Last-resort fallback: Migration(1, 14) ──
            // If any incremental migration fails (syntax error, constraint violation),
            // Room will try this migration which recreates the entire schema.
            // Data from v1 is lost, but the app doesn't crash.
            .addMigrations(object : androidx.room.migration.Migration(1, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Timber.w("LAST-RESORT Migration(1→14): Recreating entire schema — all user data from v1 is lost")
                    try { io.sentry.Sentry.captureMessage("Database migration fallback: recreating schema from v1 to v14") } catch (_: Throwable) {}
                    // Drop all tables (ignore errors for non-existent tables)
                    val tablesToDrop = listOf(
                        "user_vocabulary", "user_corrections", "learned_words",
                        "feedback", "feature_requests",
                        "gamification", "rich_habits", "mindset_lessons",
                        "tithe_records", "goal_records", "goal_progress_entries", "goal_milestones",
                        "loan_records", "loan_repayments",
                        "briefing_deliveries",
                        "worker_profile", "worker_vocabulary",
                        "peer_metrics", "peer_comparisons", "leaderboard_entries", "leaderboard_summary",
                        "community_tips", "tip_delivery_log", "whatsapp_groups", "peer_challenges",
                        "knowledge_nodes", "knowledge_edges",
                        "agent_sessions", "agent_traces",
                        "agent_task_checkpoints", "agent_recovery_traces",
                        // v1 tables
                        "transactions", "inventory", "patterns", "vocabulary", "daily_summaries"
                    )
                    for (table in tablesToDrop) {
                        try { db.execSQL("DROP TABLE IF EXISTS `$table`") } catch (_: Throwable) {}
                    }
                    // Recreate all tables matching version 14 schema
                    createFullSchema(db)
                    Timber.i("Last-resort migration completed — fresh v14 schema created")
                }
            })
    }

    /**
     * Recreate the complete v14 database schema.
     * Called by Migration(1, 14) as a last-resort fallback.
     * This matches all tables and indices defined in the incremental migrations.
     */
    private fun createFullSchema(db: SupportSQLiteDatabase) {
        // ── v1 base tables (Transaction, Inventory, Pattern, Vocabulary, DailySummary) ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `transactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `itemName` TEXT NOT NULL,
                `quantity` REAL NOT NULL,
                `unitPrice` REAL NOT NULL,
                `totalAmount` REAL NOT NULL,
                `isCredit` INTEGER NOT NULL DEFAULT 0,
                `isExpense` INTEGER NOT NULL DEFAULT 0,
                `customerName` TEXT NOT NULL DEFAULT '',
                `timestamp` INTEGER NOT NULL DEFAULT 0,
                `notes` TEXT NOT NULL DEFAULT '',
                `synced` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `inventory` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `itemName` TEXT NOT NULL,
                `quantity` REAL NOT NULL DEFAULT 0,
                `unitPrice` REAL NOT NULL DEFAULT 0,
                `minStockLevel` REAL NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT '',
                `lastRestockedAt` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `patterns` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `patternType` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `confidence` REAL NOT NULL DEFAULT 0.0,
                `createdAt` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `vocabulary` (
                `spokenForm` TEXT NOT NULL,
                `canonicalForm` TEXT NOT NULL,
                `language` TEXT NOT NULL DEFAULT 'sw',
                `frequency` INTEGER NOT NULL DEFAULT 1,
                `lastUsedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`spokenForm`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `daily_summaries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `date` TEXT NOT NULL,
                `totalSales` REAL NOT NULL DEFAULT 0,
                `totalExpenses` REAL NOT NULL DEFAULT 0,
                `transactionCount` INTEGER NOT NULL DEFAULT 0,
                `topItem` TEXT NOT NULL DEFAULT '',
                `createdAt` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // ── v2: UserVocabulary + UserCorrection ──
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

        // ── v3: LearnedWord ──
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

        // ── v4: Feedback + FeatureRequest ──
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

        // ── v5: Gamification + RichHabits + MindsetLessons ──
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

        // ── v6: Tithe + Goals + Loans ──
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

        // ── v7: BriefingDeliveries ──
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

        // ── v8: WorkerProfile ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `worker_profile` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL DEFAULT '',
                `businessType` TEXT NOT NULL DEFAULT '',
                `language` TEXT NOT NULL DEFAULT 'sw',
                `createdAt` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // ── v9: Composite indexes ──
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tithe_records_type_date` ON `tithe_records` (`type`, `date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_records_status_category` ON `goal_records` (`status`, `category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_records_status_startDate` ON `loan_records` (`status`, `startDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_briefing_deliveries_briefingType_deliveredAt` ON `briefing_deliveries` (`briefingType`, `deliveredAt`)")

        // ── v11: WorkerVocabulary ──
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

        // ── v12: Social tables (peer metrics, leaderboard, community, etc.) ──
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

        // v12 also adds streak recovery columns to gamification
        // (already included in gamification table creation above — these are additive)
        db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveriesUsedThisMonth` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `gamification` ADD COLUMN `streakRecoveryMonth` INTEGER NOT NULL DEFAULT 0")

        // ── v13: Knowledge graph + Agent sessions ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `knowledge_nodes` (
                `node_id` TEXT NOT NULL,
                `node_type` TEXT NOT NULL,
                `domain` TEXT NOT NULL,
                `key` TEXT NOT NULL,
                `value_json` TEXT NOT NULL,
                `confidence` REAL NOT NULL DEFAULT 0.0,
                `created_at` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`node_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_domain` ON `knowledge_nodes` (`domain`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_node_type` ON `knowledge_nodes` (`node_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_domain_type` ON `knowledge_nodes` (`domain`, `node_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_updated_at` ON `knowledge_nodes` (`updated_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_confidence` ON `knowledge_nodes` (`confidence`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `knowledge_edges` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `from_node` TEXT NOT NULL,
                `to_node` TEXT NOT NULL,
                `relation_type` TEXT NOT NULL,
                `strength` REAL NOT NULL DEFAULT 0.0,
                `shared_keys_json` TEXT NOT NULL DEFAULT '[]',
                `created_at` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`from_node`) REFERENCES `knowledge_nodes`(`node_id`) ON DELETE CASCADE,
                FOREIGN KEY(`to_node`) REFERENCES `knowledge_nodes`(`node_id`) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_from_node` ON `knowledge_edges` (`from_node`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_to_node` ON `knowledge_edges` (`to_node`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_edges_from_to` ON `knowledge_edges` (`from_node`, `to_node`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_relation_type` ON `knowledge_edges` (`relation_type`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_sessions` (
                `session_id` TEXT NOT NULL,
                `worker_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL DEFAULT 0,
                `last_active` INTEGER NOT NULL DEFAULT 0,
                `last_channel` TEXT NOT NULL DEFAULT 'app',
                `context_window_json` TEXT NOT NULL DEFAULT '[]',
                `active_trace_id` TEXT,
                `active_skill_ids_json` TEXT NOT NULL DEFAULT '[]',
                `last_skill_query` TEXT,
                PRIMARY KEY(`session_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_worker_id` ON `agent_sessions` (`worker_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_last_active` ON `agent_sessions` (`last_active`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sessions_worker_active` ON `agent_sessions` (`worker_id`, `last_active`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_traces` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `session_id` TEXT NOT NULL,
                `trace_id` TEXT NOT NULL,
                `step_index` INTEGER NOT NULL DEFAULT 0,
                `action` TEXT NOT NULL,
                `tool_used` TEXT,
                `success` INTEGER NOT NULL DEFAULT 1,
                `error` TEXT,
                `duration_ms` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`session_id`) REFERENCES `agent_sessions`(`session_id`) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_session_id` ON `agent_traces` (`session_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_trace_id` ON `agent_traces` (`trace_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_created_at` ON `agent_traces` (`created_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_session_trace` ON `agent_traces` (`session_id`, `trace_id`)")

        // ── v14: Agent task checkpoints + recovery traces ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_task_checkpoints` (
                `taskId` TEXT NOT NULL,
                `taskType` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `lastPhase` TEXT NOT NULL,
                `inputJson` TEXT NOT NULL,
                `observationsJson` TEXT NOT NULL DEFAULT '{}',
                `orientationJson` TEXT NOT NULL DEFAULT '{}',
                `decisionJson` TEXT,
                `contextJson` TEXT NOT NULL DEFAULT '{}',
                `currentStepId` TEXT,
                `completedStepsJson` TEXT NOT NULL DEFAULT '[]',
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                `lastError` TEXT,
                `language` TEXT NOT NULL DEFAULT 'sw',
                PRIMARY KEY(`taskId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_state` ON `agent_task_checkpoints` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_createdAt` ON `agent_task_checkpoints` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_checkpoints_updatedAt` ON `agent_task_checkpoints` (`updatedAt`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_recovery_traces` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `taskId` TEXT NOT NULL,
                `traceType` TEXT NOT NULL,
                `traceJson` TEXT NOT NULL,
                `success` INTEGER NOT NULL DEFAULT 0,
                `durationMs` INTEGER NOT NULL DEFAULT 0,
                `timestamp` INTEGER NOT NULL DEFAULT 0,
                `summary` TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_taskId` ON `agent_recovery_traces` (`taskId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_traceType` ON `agent_recovery_traces` (`traceType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_timestamp` ON `agent_recovery_traces` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_recovery_traces_success` ON `agent_recovery_traces` (`success`)")

        Timber.i("Full v14 schema created successfully (38 tables)")
    }

    // ── DAOs ──

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

    @Provides
    fun provideKnowledgeDao(db: AppDatabase): com.msaidizi.app.core.database.KnowledgeDao = db.knowledgeDao()

    @Provides
    fun provideSessionDao(db: AppDatabase): com.msaidizi.app.core.database.SessionDao = db.sessionDao()

    @Provides
    fun provideTaskCheckpointDao(db: AppDatabase): com.msaidizi.app.agent.recovery.TaskCheckpointDao = db.taskCheckpointDao()

    @Provides
    fun provideAgentRecoveryTraceDao(db: AppDatabase): com.msaidizi.app.agent.recovery.AgentTraceDao = db.agentRecoveryTraceDao()

    @Provides
    fun provideWorkerProfileDao(db: AppDatabase): com.msaidizi.app.onboarding.WorkerProfileDao = db.workerProfileDao()

    @Provides
    fun provideSocialDao(db: AppDatabase): com.msaidizi.app.social.SocialDao = db.socialDao()
}

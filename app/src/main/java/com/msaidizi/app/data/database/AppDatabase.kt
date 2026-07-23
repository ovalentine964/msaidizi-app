package com.msaidizi.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.msaidizi.app.data.dao.*
import com.msaidizi.app.data.entity.*
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        TransactionEntity::class,
        InventoryEntity::class,
        GoalEntity::class,
        LoanEntity::class,
        GivingEntity::class,
        EpisodeEntity::class,
        FtsEpisodeEntity::class,
        PhaseMetricEntity::class,
        PhaseMetricAlertEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun goalDao(): GoalDao
    abstract fun loanDao(): LoanDao
    abstract fun givingDao(): GivingDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun phaseMetricsDao(): PhaseMetricsDao

    companion object {
        const val DATABASE_NAME = "msaidizi.db"

        /**
         * Create encrypted database using SQLCipher.
         * The passphrase is derived from the worker's device-bound key.
         * Falls back to unencrypted Room if SQLCipher fails to initialize.
         */
        fun create(context: Context): AppDatabase {
            return try {
                // Load SQLCipher native libs
                System.loadLibrary("sqlcipher")

                // Derive passphrase from device-unique material
                val prefs = context.getSharedPreferences("msaidizi_prefs", Context.MODE_PRIVATE)
                val dbPassphrase = prefs.getString("db_encryption_key", null)
                    ?: generateAndStoreKey(prefs)

                val passphrase = dbPassphrase.toByteArray(Charsets.UTF_8)
                val factory = SupportFactory(passphrase)

                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
            } catch (e: Exception) {
                // Fallback: unencrypted (for devices where SQLCipher fails)
                android.util.Log.w("AppDatabase", "SQLCipher unavailable, using unencrypted Room", e)
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
            }
        }

        /**
         * Migration v1 → v2: Add FTS5 virtual table for episode full-text search.
         * Enables BM25-ranked search replacing slow LIKE '%term%' queries.
         *
         * The FTS5 table uses unicode61 tokenizer for proper handling of
         * Swahili text (diacritics, mixed Latin/Bantu vocabulary).
         *
         * content=episodes links the FTS index to the episodes table,
         * so Room INSERT/UPDATE/DELETE on episodes automatically syncs the FTS index.
         */
        private val MIGRATION_1_2 = object : androidx.room.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create FTS5 virtual table backed by episodes content
                db.execSQL(
                    """CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(
                        workerId,
                        query,
                        response,
                        outcome,
                        intent,
                        sessionId,
                        content='episodes',
                        content_rowid='id',
                        tokenize='unicode61'
                    )"""
                )

                // Populate FTS index from existing episodes
                db.execSQL(
                    """INSERT INTO episodes_fts(rowid, workerId, query, response, outcome, intent, sessionId)
                       SELECT id, workerId, query, response, outcome, intent, sessionId FROM episodes"""
                )

                // Create triggers to keep FTS index in sync with episodes table
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS episodes_ai AFTER INSERT ON episodes BEGIN
                        INSERT INTO episodes_fts(rowid, workerId, query, response, outcome, intent, sessionId)
                        VALUES (new.id, new.workerId, new.query, new.response, new.outcome, new.intent, new.sessionId);
                    END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS episodes_ad AFTER DELETE ON episodes BEGIN
                        INSERT INTO episodes_fts(episodes_fts, rowid, workerId, query, response, outcome, intent, sessionId)
                        VALUES ('delete', old.id, old.workerId, old.query, old.response, old.outcome, old.intent, old.sessionId);
                    END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS episodes_au AFTER UPDATE ON episodes BEGIN
                        INSERT INTO episodes_fts(episodes_fts, rowid, workerId, query, response, outcome, intent, sessionId)
                        VALUES ('delete', old.id, old.workerId, old.query, old.response, old.outcome, old.intent, old.sessionId);
                        INSERT INTO episodes_fts(rowid, workerId, query, response, outcome, intent, sessionId)
                        VALUES (new.id, new.workerId, new.query, new.response, new.outcome, new.intent, new.sessionId);
                    END"""
                )
            }
        }

        private fun generateAndStoreKey(prefs: android.content.SharedPreferences): String {
            val key = java.util.UUID.randomUUID().toString() + "-" + System.currentTimeMillis()
            prefs.edit().putString("db_encryption_key", key).apply()
            return key
        }
    }
}

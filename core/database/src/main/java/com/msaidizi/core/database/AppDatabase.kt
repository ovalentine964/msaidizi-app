package com.msaidizi.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msaidizi.core.database.converter.Converters
import com.msaidizi.core.database.dao.AgentTraceDao
import com.msaidizi.core.database.dao.ClientProfileDao
import com.msaidizi.core.database.dao.GamificationDao
import com.msaidizi.core.database.dao.InventoryDao
import com.msaidizi.core.database.dao.MaterialInventoryDao
import com.msaidizi.core.database.dao.MiscDao
import com.msaidizi.core.database.dao.ProofPointDao
import com.msaidizi.core.database.dao.ProjectDao
import com.msaidizi.core.database.dao.SessionDao
import com.msaidizi.core.database.dao.SpoilageDao
import com.msaidizi.core.database.dao.ToolDao
import com.msaidizi.core.database.dao.TransactionDao
import com.msaidizi.core.database.dao.WorkerProfileDao
import com.msaidizi.core.database.entity.AgentTraceEntity
import com.msaidizi.core.database.entity.ClientProfileEntity
import com.msaidizi.core.database.entity.GamificationEntity
import com.msaidizi.core.database.entity.InventoryItemEntity
import com.msaidizi.core.database.entity.MaterialInventoryEntity
import com.msaidizi.core.database.entity.ProofPointEntity
import com.msaidizi.core.database.entity.ProjectEntity
import com.msaidizi.core.database.entity.SessionEntity
import com.msaidizi.core.database.entity.SpoilageRecordEntity
import com.msaidizi.core.database.entity.ToolEntity
import com.msaidizi.core.database.entity.TransactionEntity
import com.msaidizi.core.database.entity.WorkerProfileEntity

/**
 * Msaidizi Room Database — single source of truth for all persistent data.
 *
 * Version 14+ with SQLCipher encryption support. All entities are
 * designed for the M-KOPA proof accumulation model: every transaction
 * is a proof point, every proof point builds toward Alama Score.
 *
 * ## Migration Strategy
 * - Version 1-13: Legacy (pre-superagent architecture)
 * - Version 14: Superagent architecture — new entities for proof points,
 *   tool tracking, material inventory, client profiles, projects
 *
 * ## Encryption
 * SQLCipher encryption is applied via DatabaseKeyManager in :core:security.
 * The database key is derived from biometric auth + device binding.
 */
@Database(
    entities = [
        TransactionEntity::class,
        WorkerProfileEntity::class,
        ClientProfileEntity::class,
        ProjectEntity::class,
        InventoryItemEntity::class,
        ToolEntity::class,
        MaterialInventoryEntity::class,
        SpoilageRecordEntity::class,
        ProofPointEntity::class,
        AgentTraceEntity::class,
        GamificationEntity::class,
        SessionEntity::class
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    // ═══ DAOs ═══
    abstract fun transactionDao(): TransactionDao
    abstract fun workerProfileDao(): WorkerProfileDao
    abstract fun clientProfileDao(): ClientProfileDao
    abstract fun projectDao(): ProjectDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun toolDao(): ToolDao
    abstract fun materialInventoryDao(): MaterialInventoryDao
    abstract fun spoilageDao(): SpoilageDao
    abstract fun proofPointDao(): ProofPointDao
    abstract fun agentTraceDao(): AgentTraceDao
    abstract fun gamificationDao(): GamificationDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val DATABASE_NAME = "msaidizi.db"

        /**
         * Room migration from v13 to v14 (superagent architecture).
         * Adds new entities: ProofPoint, Tool, MaterialInventory,
         * SpoilageRecord, ClientProfile, Project.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Proof points table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `proof_points` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `weight` REAL NOT NULL DEFAULT 1.0,
                        `day_number` INTEGER NOT NULL DEFAULT 0,
                        `timestamp` INTEGER NOT NULL,
                        `data_json` TEXT NOT NULL DEFAULT '{}',
                        `is_synced` INTEGER NOT NULL DEFAULT 0,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proof_points_type` ON `proof_points` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proof_points_timestamp` ON `proof_points` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proof_points_is_synced` ON `proof_points` (`is_synced`)")

                // Tools table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tools` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tool_name` TEXT NOT NULL,
                        `category` TEXT NOT NULL DEFAULT '',
                        `brand` TEXT NOT NULL DEFAULT '',
                        `serial_number` TEXT NOT NULL DEFAULT '',
                        `purchase_price` REAL NOT NULL DEFAULT 0.0,
                        `current_value` REAL NOT NULL DEFAULT 0.0,
                        `depreciation_method` TEXT NOT NULL DEFAULT 'straight_line',
                        `useful_life_months` INTEGER NOT NULL DEFAULT 24,
                        `salvage_value` REAL NOT NULL DEFAULT 0.0,
                        `monthly_depreciation` REAL NOT NULL DEFAULT 0.0,
                        `condition` TEXT NOT NULL DEFAULT 'good',
                        `last_maintenance_date` INTEGER,
                        `next_maintenance_date` INTEGER,
                        `total_maintenance_cost` REAL NOT NULL DEFAULT 0.0,
                        `is_in_use` INTEGER NOT NULL DEFAULT 1,
                        `location` TEXT NOT NULL DEFAULT '',
                        `used_by` TEXT NOT NULL DEFAULT '',
                        `purchase_date` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tools_tool_name` ON `tools` (`tool_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tools_category` ON `tools` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tools_is_in_use` ON `tools` (`is_in_use`)")

                // Material inventory table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `material_inventory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `material_name` TEXT NOT NULL,
                        `category` TEXT NOT NULL DEFAULT '',
                        `supplier` TEXT NOT NULL DEFAULT '',
                        `supplier_phone` TEXT NOT NULL DEFAULT '',
                        `current_stock` REAL NOT NULL DEFAULT 0.0,
                        `unit` TEXT NOT NULL DEFAULT 'kg',
                        `reorder_level` REAL NOT NULL DEFAULT 0.0,
                        `reorder_quantity` REAL NOT NULL DEFAULT 0.0,
                        `safety_stock` REAL NOT NULL DEFAULT 0.0,
                        `current_price` REAL NOT NULL DEFAULT 0.0,
                        `previous_price` REAL NOT NULL DEFAULT 0.0,
                        `avg_price_30_days` REAL NOT NULL DEFAULT 0.0,
                        `price_trend` TEXT NOT NULL DEFAULT 'stable',
                        `avg_daily_usage` REAL NOT NULL DEFAULT 0.0,
                        `days_of_stock_remaining` INTEGER NOT NULL DEFAULT 0,
                        `last_restock_date` INTEGER NOT NULL,
                        `lead_time_days` INTEGER NOT NULL DEFAULT 1,
                        `is_below_reorder_level` INTEGER NOT NULL DEFAULT 0,
                        `will_stock_out` INTEGER NOT NULL DEFAULT 0,
                        `price_increased` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_material_inventory_material_name` ON `material_inventory` (`material_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_material_inventory_is_below_reorder_level` ON `material_inventory` (`is_below_reorder_level`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_material_inventory_supplier` ON `material_inventory` (`supplier`)")

                // Spoilage records table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `spoilage_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `inventory_item_id` INTEGER NOT NULL DEFAULT 0,
                        `item_name` TEXT NOT NULL,
                        `quantity_spoiled` REAL NOT NULL DEFAULT 0.0,
                        `unit` TEXT NOT NULL DEFAULT 'pieces',
                        `unit_cost` REAL NOT NULL DEFAULT 0.0,
                        `estimated_cost` REAL NOT NULL DEFAULT 0.0,
                        `reason` TEXT NOT NULL DEFAULT 'EXPIRED',
                        `reason_detail` TEXT NOT NULL DEFAULT '',
                        `recorded_at` INTEGER NOT NULL,
                        `location_name` TEXT NOT NULL DEFAULT '',
                        `preventable` INTEGER NOT NULL DEFAULT 1,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spoilage_records_inventory_item_id` ON `spoilage_records` (`inventory_item_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_spoilage_records_recorded_at` ON `spoilage_records` (`recorded_at`)")

                // Client profiles table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `client_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `phone_number` TEXT NOT NULL DEFAULT '',
                        `relationship` TEXT NOT NULL DEFAULT '',
                        `total_transactions` INTEGER NOT NULL DEFAULT 0,
                        `total_spent` REAL NOT NULL DEFAULT 0.0,
                        `avg_transaction_amount` REAL NOT NULL DEFAULT 0.0,
                        `frequent_items` TEXT NOT NULL DEFAULT '',
                        `preferred_payment` TEXT NOT NULL DEFAULT 'cash',
                        `has_credit` INTEGER NOT NULL DEFAULT 0,
                        `credit_balance` REAL NOT NULL DEFAULT 0.0,
                        `total_credit_given` REAL NOT NULL DEFAULT 0.0,
                        `total_credit_repaid` REAL NOT NULL DEFAULT 0.0,
                        `credit_reliability` REAL NOT NULL DEFAULT 0.0,
                        `visit_frequency` TEXT NOT NULL DEFAULT 'occasional',
                        `typical_visit_day` INTEGER NOT NULL DEFAULT 0,
                        `days_since_last_visit` INTEGER NOT NULL DEFAULT 0,
                        `is_recurring` INTEGER NOT NULL DEFAULT 0,
                        `first_transaction_at` INTEGER NOT NULL,
                        `last_transaction_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_client_profiles_name` ON `client_profiles` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_client_profiles_is_recurring` ON `client_profiles` (`is_recurring`)")

                // Projects table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `client_name` TEXT NOT NULL DEFAULT '',
                        `client_phone` TEXT NOT NULL DEFAULT '',
                        `description` TEXT NOT NULL DEFAULT '',
                        `category` TEXT NOT NULL DEFAULT '',
                        `quoted_price` REAL NOT NULL DEFAULT 0.0,
                        `total_paid` REAL NOT NULL DEFAULT 0.0,
                        `total_expenses` REAL NOT NULL DEFAULT 0.0,
                        `estimated_profit` REAL NOT NULL DEFAULT 0.0,
                        `payment_method` TEXT NOT NULL DEFAULT 'cash',
                        `start_date` INTEGER NOT NULL,
                        `expected_end_date` INTEGER NOT NULL DEFAULT 0,
                        `actual_end_date` INTEGER,
                        `phase` TEXT NOT NULL DEFAULT 'planning',
                        `milestones_completed` TEXT NOT NULL DEFAULT '',
                        `total_milestones` INTEGER NOT NULL DEFAULT 0,
                        `progress_percent` INTEGER NOT NULL DEFAULT 0,
                        `total_visits` INTEGER NOT NULL DEFAULT 0,
                        `total_hours` REAL NOT NULL DEFAULT 0.0,
                        `materials_used` TEXT NOT NULL DEFAULT '',
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `has_outstanding_balance` INTEGER NOT NULL DEFAULT 0,
                        `outstanding_amount` REAL NOT NULL DEFAULT 0.0,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `synced_at` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_is_active` ON `projects` (`is_active`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_client_name` ON `projects` (`client_name`)")

                // Add new columns to existing tables
                // Transaction: verification_source, data_completeness
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `verification_source` TEXT NOT NULL DEFAULT 'voice'")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `subcategory` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `product_code` TEXT NOT NULL DEFAULT ''")

                // WorkerProfile: alama_score, alama_tier, total_proof_points
                db.execSQL("ALTER TABLE `worker_profiles` ADD COLUMN `alama_score` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `worker_profiles` ADD COLUMN `alama_tier` TEXT NOT NULL DEFAULT 'MTOTO'")
                db.execSQL("ALTER TABLE `worker_profiles` ADD COLUMN `total_proof_points` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * All migrations in order.
         */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_13_14)

        /**
         * Create database instance with optional SQLCipher encryption.
         *
         * @param context Application context
         * @param passphrase Optional SQLCipher passphrase (null = no encryption)
         */
        fun create(context: Context, passphrase: ByteArray? = null): AppDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigration()

            // Apply SQLCipher encryption if passphrase provided
            if (passphrase != null) {
                val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)
                builder.openHelperFactory(factory)
            }

            return builder.build()
        }
    }
}

/**
 * Migration base class (Room's Migration is abstract).
 */
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(db: SupportSQLiteDatabase)
}

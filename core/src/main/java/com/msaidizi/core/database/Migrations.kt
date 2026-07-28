package com.msaidizi.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database migrations for Msaidizi.
 * Add new migrations here as the schema evolves.
 */
object Migrations {

    /**
     * Migration 8 → 9: Knowledge Graph tables
     * Adds kg_nodes, kg_edges, kg_facts tables for graph engineering.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Knowledge Graph tables
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_nodes (
                    id TEXT NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    label TEXT NOT NULL,
                    propertiesJson TEXT NOT NULL DEFAULT '{}',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fromId TEXT NOT NULL,
                    toId TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    propertiesJson TEXT NOT NULL DEFAULT '{}',
                    weight REAL NOT NULL DEFAULT 1.0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subject TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    obj TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    source TEXT NOT NULL DEFAULT 'system',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            // Indices for graph traversal performance
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_fromId ON kg_edges(fromId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_toId ON kg_edges(toId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_relation ON kg_edges(relation)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_type ON kg_nodes(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_label ON kg_nodes(label)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_facts_subject ON kg_facts(subject)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_facts_predicate ON kg_facts(predicate)")
        }
    }

    /**
     * Migration 9 → 10: Council event tracking
     * Adds council_events table for inter-council communication logging.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS council_events (
                    id TEXT NOT NULL PRIMARY KEY,
                    event_type TEXT NOT NULL,
                    source_council TEXT NOT NULL,
                    payload TEXT NOT NULL DEFAULT '{}',
                    timestamp INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_council_events_type ON council_events(event_type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_council_events_timestamp ON council_events(timestamp)")
        }
    }

    /**
     * Migration 10 → 11: Service, Bulk Order, and Knowledge Graph entities
     * Adds service_transactions, service_menu, bulk_orders, bulk_commitments,
     * bulk_escrow tables and registers kg_nodes, kg_edges, kg_facts as Room entities.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Service transaction tables
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS service_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    serviceName TEXT NOT NULL,
                    serviceCategory TEXT NOT NULL,
                    labourCost REAL NOT NULL,
                    materialsCost REAL NOT NULL,
                    totalCharged REAL NOT NULL,
                    customerName TEXT,
                    paymentMethod TEXT NOT NULL DEFAULT 'cash',
                    timestamp INTEGER NOT NULL,
                    notes TEXT
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS service_menu (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    basePrice REAL NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    usageCount INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL
                )
            """)

            // Bulk order tables
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bulk_orders (
                    orderId TEXT NOT NULL PRIMARY KEY,
                    product TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    targetPricePerUnit REAL NOT NULL,
                    totalQuantityNeeded REAL NOT NULL,
                    totalQuantityCommitted REAL NOT NULL DEFAULT 0.0,
                    minimumQuantity REAL NOT NULL,
                    area TEXT NOT NULL,
                    creatorWorkerId TEXT NOT NULL,
                    creatorName TEXT NOT NULL,
                    creatorPhone TEXT NOT NULL,
                    status TEXT NOT NULL,
                    deadline INTEGER NOT NULL,
                    supplierName TEXT,
                    agreedPricePerUnit REAL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bulk_commitments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    orderId TEXT NOT NULL,
                    workerId TEXT NOT NULL,
                    workerName TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    amountPaid REAL NOT NULL DEFAULT 0.0,
                    paymentStatus TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (orderId) REFERENCES bulk_orders(orderId) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bulk_commitments_orderId ON bulk_commitments(orderId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bulk_commitments_workerId ON bulk_commitments(workerId)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bulk_escrow (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    orderId TEXT NOT NULL,
                    workerId TEXT NOT NULL,
                    amount REAL NOT NULL,
                    type TEXT NOT NULL,
                    mpesaReference TEXT,
                    createdAt INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (orderId) REFERENCES bulk_orders(orderId) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bulk_escrow_orderId ON bulk_escrow(orderId)")

            // Knowledge Graph tables — recreate with correct column names
            // Drop old tables if they exist (from migration 8→9 with wrong schema)
            db.execSQL("DROP TABLE IF EXISTS kg_facts")
            db.execSQL("DROP TABLE IF EXISTS kg_edges")
            db.execSQL("DROP TABLE IF EXISTS kg_nodes")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_nodes (
                    id TEXT NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    label TEXT NOT NULL,
                    propertiesJson TEXT NOT NULL DEFAULT '{}',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fromId TEXT NOT NULL,
                    toId TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    propertiesJson TEXT NOT NULL DEFAULT '{}',
                    weight REAL NOT NULL DEFAULT 1.0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subject TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    obj TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    source TEXT NOT NULL DEFAULT 'system',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_type ON kg_nodes(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_label ON kg_nodes(label)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_fromId ON kg_edges(fromId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_toId ON kg_edges(toId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_relation ON kg_edges(relation)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_kg_facts_subject_predicate ON kg_facts(subject, predicate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_facts_subject ON kg_facts(subject)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_facts_predicate ON kg_facts(predicate)")
        }
    }

    /**
     * All migrations in order.
     * Add to MsaidiziDatabase.builder():
     *   Room.databaseBuilder(..., MsaidiziDatabase::class.java, "msaidizi-db")
     *       .addMigrations(*ALL_MIGRATIONS)
     *       .build()
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11
    )
}

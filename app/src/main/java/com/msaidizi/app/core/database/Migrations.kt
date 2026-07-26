package com.msaidizi.app.core.database

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
                    properties TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_edges (
                    id TEXT NOT NULL PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    weight REAL NOT NULL DEFAULT 1.0,
                    properties TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (source_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
                    FOREIGN KEY (target_id) REFERENCES kg_nodes(id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_facts (
                    id TEXT NOT NULL PRIMARY KEY,
                    subject_id TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    object_value TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    source TEXT NOT NULL DEFAULT 'user',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (subject_id) REFERENCES kg_nodes(id) ON DELETE CASCADE
                )
            """)

            // Indices for graph traversal performance
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_edges_source ON kg_edges(source_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_edges_target ON kg_edges(target_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_edges_type ON kg_edges(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_nodes_type ON kg_nodes(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_facts_subject ON kg_facts(subject_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kg_facts_predicate ON kg_facts(predicate)")
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
     * All migrations in order.
     * Add to MsaidiziDatabase.builder():
     *   Room.databaseBuilder(..., MsaidiziDatabase::class.java, "msaidizi-db")
     *       .addMigrations(*ALL_MIGRATIONS)
     *       .build()
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_8_9,
        MIGRATION_9_10
    )
}

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
     * Migration 11 → 12: Agent trace collection table
     * Adds agent_traces table for structured trace logging (Loop 4: harness improvement).
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS agent_traces (
                    traceId TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    rawInputHash TEXT NOT NULL,
                    intentType TEXT NOT NULL,
                    intentConfidence REAL NOT NULL,
                    intentTier TEXT NOT NULL,
                    toolsSelected TEXT NOT NULL DEFAULT '[]',
                    toolsSucceeded INTEGER NOT NULL DEFAULT 0,
                    toolsFailed INTEGER NOT NULL DEFAULT 0,
                    toolResultsJson TEXT NOT NULL DEFAULT '[]',
                    promptTokenCount INTEGER NOT NULL DEFAULT 0,
                    outputTokenCount INTEGER NOT NULL DEFAULT 0,
                    llmResponseSummary TEXT NOT NULL DEFAULT '',
                    totalLatencyMs INTEGER NOT NULL DEFAULT 0,
                    intentRoutingMs INTEGER NOT NULL DEFAULT 0,
                    toolExecutionMs INTEGER NOT NULL DEFAULT 0,
                    llmInferenceMs INTEGER NOT NULL DEFAULT 0,
                    finalConfidence REAL NOT NULL DEFAULT 0.0,
                    oodaIterations INTEGER NOT NULL DEFAULT 1,
                    guardrailBlocked INTEGER NOT NULL DEFAULT 0,
                    userFeedback INTEGER,
                    userCorrection TEXT,
                    correctionLatencyMs INTEGER,
                    oodaPhase TEXT NOT NULL DEFAULT 'OBSERVE',
                    isVoice INTEGER NOT NULL DEFAULT 0,
                    businessCategory TEXT,
                    region TEXT,
                    needsSync INTEGER NOT NULL DEFAULT 1,
                    syncedAt INTEGER
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_traces_timestamp ON agent_traces(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_traces_intentType ON agent_traces(intentType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_traces_needsSync ON agent_traces(needsSync)")
        }
    }

    /**
     * Migration 12 → 13: Boda boda, safety, hire-purchase, and M-Pesa tables.
     * Adds mpesa_transactions, emergency_contacts, sos_events, boda_income,
     * boda_expense, fuel_purchase, trip_kilometers, fare_record,
     * hire_purchase_agreement, hire_payment tables.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // M-Pesa transactions — columns match MpesaTransactionEntity in core.database
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS mpesa_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    receipt TEXT NOT NULL,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    counterparty TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    transactionDate TEXT NOT NULL,
                    balance REAL,
                    category TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    rawSms TEXT NOT NULL,
                    isReconciled INTEGER NOT NULL DEFAULT 0,
                    reconciledRecordId INTEGER,
                    createdAt INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mpesa_transactions_receipt ON mpesa_transactions(receipt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mpesa_transactions_phone ON mpesa_transactions(phone)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mpesa_transactions_transactionDate ON mpesa_transactions(transactionDate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mpesa_transactions_isReconciled ON mpesa_transactions(isReconciled)")

            // Emergency contacts — matches EmergencyContactEntity (tableName = emergency_contacts)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS emergency_contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    relationship TEXT NOT NULL DEFAULT '',
                    isActive INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL
                )
            """)

            // SOS events — matches SOSEventEntity (tableName = sos_events)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sos_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    triggeredAt INTEGER NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    locationAccuracy REAL,
                    audioFilePath TEXT,
                    contactsNotified INTEGER NOT NULL DEFAULT 0,
                    smsMessage TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'triggered',
                    resolvedAt INTEGER,
                    notes TEXT NOT NULL DEFAULT ''
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sos_events_triggeredAt ON sos_events(triggeredAt)")

            // Boda income — matches BodaIncomeEntity (tableName = boda_income)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS boda_income (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amount REAL NOT NULL,
                    route TEXT NOT NULL DEFAULT '',
                    tripType TEXT NOT NULL DEFAULT 'fare',
                    paymentMethod TEXT NOT NULL DEFAULT 'cash',
                    passengerCount INTEGER NOT NULL DEFAULT 1,
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_boda_income_date ON boda_income(date)")

            // Boda expense — matches BodaExpenseEntity (tableName = boda_expenses)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS boda_expenses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amount REAL NOT NULL,
                    category TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_boda_expenses_date ON boda_expenses(date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_boda_expenses_category ON boda_expenses(category)")

            // Fuel purchases — matches FuelPurchaseEntity (tableName = fuel_purchases)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS fuel_purchases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    liters REAL NOT NULL,
                    costPerLiter REAL NOT NULL,
                    totalCost REAL NOT NULL,
                    stationName TEXT NOT NULL DEFAULT '',
                    odometer REAL,
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_fuel_purchases_date ON fuel_purchases(date)")

            // Trip kilometers — matches TripKilometersEntity (tableName = trip_kilometers)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS trip_kilometers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    kilometers REAL NOT NULL,
                    route TEXT NOT NULL DEFAULT '',
                    tripType TEXT NOT NULL DEFAULT 'regular',
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_kilometers_date ON trip_kilometers(date)")

            // Fare records — matches FareRecordEntity (tableName = fare_records)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS fare_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fare REAL NOT NULL,
                    route TEXT NOT NULL,
                    fromLocation TEXT NOT NULL,
                    toLocation TEXT NOT NULL,
                    distanceKm REAL,
                    hourOfDay INTEGER NOT NULL DEFAULT 0,
                    dayOfWeek INTEGER NOT NULL DEFAULT 0,
                    weather TEXT NOT NULL DEFAULT 'clear',
                    passengerCount INTEGER NOT NULL DEFAULT 1,
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_fare_records_route ON fare_records(route)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_fare_records_date ON fare_records(date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_fare_records_hourOfDay ON fare_records(hourOfDay)")

            // Hire purchase agreements — matches HirePurchaseAgreementEntity (tableName = hire_purchase_agreements)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS hire_purchase_agreements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ownerName TEXT NOT NULL,
                    ownerPhone TEXT NOT NULL DEFAULT '',
                    motorcycleDescription TEXT NOT NULL DEFAULT '',
                    dailyFee REAL NOT NULL,
                    depositPaid REAL NOT NULL DEFAULT 0.0,
                    startDate TEXT NOT NULL DEFAULT '',
                    endDate TEXT,
                    totalPurchasePrice REAL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    notes TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_hire_purchase_agreements_isActive ON hire_purchase_agreements(isActive)")

            // Hire payments — matches HirePaymentEntity (tableName = hire_payments)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS hire_payments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agreementId INTEGER NOT NULL,
                    amount REAL NOT NULL,
                    paymentType TEXT NOT NULL DEFAULT 'daily_fee',
                    date TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL,
                    needsSync INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (agreementId) REFERENCES hire_purchase_agreements(id) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_hire_payments_agreementId ON hire_payments(agreementId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_hire_payments_agreementId_date ON hire_payments(agreementId, date)")
        }
    }

    /**
     * Migration 13 → 14: Performance indices on frequently queried columns.
     * Adds composite and single-column indices for sales, products, expenses,
     * conversations, knowledge_entries, debts, and customer_visits.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Sales performance indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_timestamp ON sales(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_productName ON sales(productName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_paymentMethod ON sales(paymentMethod)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_customerId ON sales(customerId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_timestamp_paymentMethod ON sales(timestamp, paymentMethod)")

            // Products indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_category ON products(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_isActive ON products(isActive)")

            // Expenses indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_timestamp ON expenses(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_category ON expenses(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_timestamp_category ON expenses(timestamp, category)")

            // Conversations indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_sessionId ON conversations(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_timestamp ON conversations(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_role_timestamp ON conversations(role, timestamp)")

            // Knowledge entries indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_entries_category ON knowledge_entries(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_entries_category_key ON knowledge_entries(category, key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_entries_updatedAt ON knowledge_entries(updatedAt)")

            // Debts indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_status ON debts(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_customerName ON debts(customerName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_dueDate ON debts(dueDate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_status_outstandingBalance ON debts(status, outstandingBalance)")

            // Customer visits indices
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_visits_workerId ON customer_visits(workerId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_visits_customerKey ON customer_visits(customerKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_visits_visitDate ON customer_visits(visitDate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_visits_workerId_customerKey ON customer_visits(workerId, customerKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_visits_workerId_visitDate ON customer_visits(workerId, visitDate)")
        }
    }

    /**
     * All migrations in order.
     * Add to MsaidiziDatabase.builder():
     *   Room.databaseBuilder(..., MsaidiziDatabase::class.java, "msaidizi-db")
     *       .addMigrations(*ALL_MIGRATIONS)
     *       .build()
     */
    /**
     * Migration 14 → 15: Goal tracker persistence
     * Adds goals and goal_contributions tables for persistent savings goals.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS goals (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    targetAmount REAL NOT NULL,
                    currentAmount REAL NOT NULL DEFAULT 0.0,
                    deadline INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    goalType TEXT NOT NULL DEFAULT 'savings',
                    notes TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_status ON goals(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_deadline ON goals(deadline)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_status_deadline ON goals(status, deadline)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS goal_contributions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    goalId TEXT NOT NULL,
                    amount REAL NOT NULL,
                    source TEXT NOT NULL DEFAULT 'manual',
                    notes TEXT,
                    timestamp INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS index_goal_contributions_goalId ON goal_contributions(goalId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_goal_contributions_timestamp ON goal_contributions(timestamp)")
        }
    }

    /**
     * Migration 15 → 16: Offline sync queue table
     * Adds sync_queue table for persisting offline operations across app restarts.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    operationId TEXT NOT NULL,
                    operationType TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    maxRetries INTEGER NOT NULL DEFAULT 3,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_operationType ON sync_queue(operationType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_createdAt ON sync_queue(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status ON sync_queue(status)")
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16
    )
}

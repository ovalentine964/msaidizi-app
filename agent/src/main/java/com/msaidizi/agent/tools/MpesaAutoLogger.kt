package com.msaidizi.agent.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import com.google.gson.Gson
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ──────────────────────────────────────────────
// Data Models
// ──────────────────────────────────────────────

/**
 * A parsed M-Pesa transaction extracted from SMS.
 */
data class MpesaAutoTransaction(
    val id: Long = 0,
    val transactionCode: String,         // e.g. "RKL45GHTSL"
    val direction: String,               // "in" | "out"
    val amount: Double,
    val counterparty: String,            // person or business name
    val counterpartyPhone: String? = null,
    val transactionType: String,         // "paybill", "till", "send", "receive", "withdraw", "deposit", "buy_goods"
    val paybillNumber: String? = null,   // 5-7 digit business number
    val tillNumber: String? = null,      // 5-7 digit till number
    val accountNumber: String? = null,   // paybill account ref
    val fee: Double = 0.0,
    val balance: Double? = null,
    val category: String = "uncategorized",  // inferred business category
    val rawSms: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isAutoDetected: Boolean = true   // true = SMS listener, false = manual parse
)

/**
 * Known business entities (paybill/till → category mapping).
 * Built up over time from user transactions.
 */
data class MpesaBusiness(
    val id: Long = 0,
    val paybillNumber: String? = null,
    val tillNumber: String? = null,
    val businessName: String,
    val category: String,           // "transport", "rent", "utilities", "stock", "food", etc.
    val transactionCount: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * A detected recurring payment pattern.
 */
data class RecurringPattern(
    val id: Long = 0,
    val counterparty: String,
    val category: String,
    val averageAmount: Double,
    val frequencyDays: Double,       // e.g. 1.0 = daily, 7.0 = weekly, 30.0 = monthly
    val frequencyLabel: String,      // "daily", "weekly", "biweekly", "monthly", "irregular"
    val occurrences: Int = 0,
    val totalSpent: Double = 0.0,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val isActive: Boolean = true
)

/**
 * Aggregated financial profile built from M-Pesa history.
 */
data class FinancialProfile(
    val totalInflow: Double = 0.0,
    val totalOutflow: Double = 0.0,
    val netCashFlow: Double = 0.0,
    val averageDailySpend: Double = 0.0,
    val averageDailyIncome: Double = 0.0,
    val topExpenseCategories: List<CategorySpend> = emptyList(),
    val topIncomeSources: List<IncomeSource> = emptyList(),
    val recurringObligations: List<RecurringPattern> = emptyList(),
    val transactionCount: Int = 0,
    val dateRangeDays: Int = 0
)

data class CategorySpend(val category: String, val total: Double, val percentage: Double, val count: Int)
data class IncomeSource(val source: String, val total: Double, val count: Int)

// ──────────────────────────────────────────────
// SQLite / Statement extensions
// ──────────────────────────────────────────────

private fun android.database.sqlite.SQLiteStatement.bindStringOrNull(index: Int, value: String?) {
    if (value != null) bindString(index, value) else bindNull(index)
}

private fun android.database.sqlite.SQLiteStatement.bindDoubleOrNull(index: Int, value: Double?) {
    if (value != null) bindDouble(index, value) else bindNull(index)
}

private fun android.database.Cursor.getDoubleOrNull(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}

// ──────────────────────────────────────────────
// In-memory SQLite Store (no Room dependency)
// ──────────────────────────────────────────────

/**
 * Lightweight SQLite store for M-Pesa auto-logging.
 * Uses android.database.sqlite directly — no Room annotations needed.
 * This keeps the tool self-contained and avoids schema migration complexity.
 */
class MpesaDatabase(private val context: Context) {

    private val db by lazy {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("mpesa_auto_log.db").also {
                it.parentFile?.mkdirs()
            },
            null
        ).also { initTables(it) }
    }

    private fun initTables(database: android.database.sqlite.SQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS mpesa_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_code TEXT UNIQUE,
                direction TEXT NOT NULL,
                amount REAL NOT NULL,
                counterparty TEXT NOT NULL,
                counterparty_phone TEXT,
                transaction_type TEXT NOT NULL,
                paybill_number TEXT,
                till_number TEXT,
                account_number TEXT,
                fee REAL DEFAULT 0,
                balance REAL,
                category TEXT DEFAULT 'uncategorized',
                raw_sms TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_auto_detected INTEGER DEFAULT 1
            )
        """)
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS mpesa_businesses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                paybill_number TEXT,
                till_number TEXT,
                business_name TEXT NOT NULL,
                category TEXT NOT NULL,
                transaction_count INTEGER DEFAULT 0,
                last_seen INTEGER NOT NULL,
                UNIQUE(paybill_number, till_number)
            )
        """)
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS recurring_patterns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                counterparty TEXT NOT NULL,
                category TEXT NOT NULL,
                average_amount REAL NOT NULL,
                frequency_days REAL NOT NULL,
                frequency_label TEXT NOT NULL,
                occurrences INTEGER DEFAULT 0,
                total_spent REAL DEFAULT 0,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                is_active INTEGER DEFAULT 1,
                UNIQUE(counterparty, category)
            )
        """)
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_timestamp ON mpesa_transactions(timestamp)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_counterparty ON mpesa_transactions(counterparty)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_category ON mpesa_transactions(category)")
    }

    // ── Transaction CRUD ──

    fun insertTransaction(txn: MpesaAutoTransaction): Long {
        // Skip duplicates by transaction code
        if (txn.transactionCode.isNotBlank()) {
            val cursor = db.rawQuery(
                "SELECT id FROM mpesa_transactions WHERE transaction_code = ?",
                arrayOf(txn.transactionCode)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            if (exists) return -1L
        }

        val stmt = db.compileStatement("""
            INSERT INTO mpesa_transactions
            (transaction_code, direction, amount, counterparty, counterparty_phone,
             transaction_type, paybill_number, till_number, account_number,
             fee, balance, category, raw_sms, timestamp, is_auto_detected)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.bindString(1, txn.transactionCode)
        stmt.bindString(2, txn.direction)
        stmt.bindDouble(3, txn.amount)
        stmt.bindString(4, txn.counterparty)
        stmt.bindStringOrNull(5, txn.counterpartyPhone)
        stmt.bindString(6, txn.transactionType)
        stmt.bindStringOrNull(7, txn.paybillNumber)
        stmt.bindStringOrNull(8, txn.tillNumber)
        stmt.bindStringOrNull(9, txn.accountNumber)
        stmt.bindDouble(10, txn.fee)
        stmt.bindDoubleOrNull(11, txn.balance)
        stmt.bindString(12, txn.category)
        stmt.bindString(13, txn.rawSms)
        stmt.bindLong(14, txn.timestamp)
        stmt.bindLong(15, if (txn.isAutoDetected) 1L else 0L)
        return stmt.executeInsert()
    }

    fun getTransactions(limit: Int = 50, offset: Int = 0, category: String? = null): List<MpesaAutoTransaction> {
        val query = if (category != null) {
            "SELECT * FROM mpesa_transactions WHERE category = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM mpesa_transactions ORDER BY timestamp DESC LIMIT ? OFFSET ?"
        }
        val args = if (category != null) arrayOf(category, limit.toString(), offset.toString())
                   else arrayOf(limit.toString(), offset.toString())
        return queryTransactions(query, args)
    }

    fun getTransactionsByDateRange(fromTs: Long, toTs: Long): List<MpesaAutoTransaction> {
        return queryTransactions(
            "SELECT * FROM mpesa_transactions WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        )
    }

    private fun queryTransactions(query: String, args: Array<String>): List<MpesaAutoTransaction> {
        val cursor = db.rawQuery(query, args)
        val list = mutableListOf<MpesaAutoTransaction>()
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor))
        }
        cursor.close()
        return list
    }

    private fun cursorToTransaction(cursor: android.database.Cursor): MpesaAutoTransaction {
        return MpesaAutoTransaction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            transactionCode = cursor.getString(cursor.getColumnIndexOrThrow("transaction_code")),
            direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
            counterparty = cursor.getString(cursor.getColumnIndexOrThrow("counterparty")),
            counterpartyPhone = cursor.getString(cursor.getColumnIndexOrThrow("counterparty_phone")),
            transactionType = cursor.getString(cursor.getColumnIndexOrThrow("transaction_type")),
            paybillNumber = cursor.getString(cursor.getColumnIndexOrThrow("paybill_number")),
            tillNumber = cursor.getString(cursor.getColumnIndexOrThrow("till_number")),
            accountNumber = cursor.getString(cursor.getColumnIndexOrThrow("account_number")),
            fee = cursor.getDouble(cursor.getColumnIndexOrThrow("fee")),
            balance = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("balance")),
            category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
            rawSms = cursor.getString(cursor.getColumnIndexOrThrow("raw_sms")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            isAutoDetected = cursor.getInt(cursor.getColumnIndexOrThrow("is_auto_detected")) == 1
        )
    }

    // ── Business CRUD ──

    fun upsertBusiness(biz: MpesaBusiness) {
        db.execSQL("""
            INSERT INTO mpesa_businesses (paybill_number, till_number, business_name, category, transaction_count, last_seen)
            VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT(paybill_number, till_number) DO UPDATE SET
                transaction_count = transaction_count + 1,
                last_seen = excluded.last_seen,
                business_name = CASE
                    WHEN excluded.business_name != '' THEN excluded.business_name
                    ELSE mpesa_businesses.business_name
                END
        """, arrayOf(biz.paybillNumber, biz.tillNumber, biz.businessName, biz.category, biz.lastSeen.toString()))
    }

    fun lookupBusiness(paybill: String? = null, till: String? = null): MpesaBusiness? {
        val cursor = db.rawQuery(
            "SELECT * FROM mpesa_businesses WHERE paybill_number = ? OR till_number = ? LIMIT 1",
            arrayOf(paybill ?: "", till ?: "")
        )
        val result = if (cursor.moveToFirst()) {
            MpesaBusiness(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                paybillNumber = cursor.getString(cursor.getColumnIndexOrThrow("paybill_number")),
                tillNumber = cursor.getString(cursor.getColumnIndexOrThrow("till_number")),
                businessName = cursor.getString(cursor.getColumnIndexOrThrow("business_name")),
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                transactionCount = cursor.getInt(cursor.getColumnIndexOrThrow("transaction_count")),
                lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
            )
        } else null
        cursor.close()
        return result
    }

    // ── Recurring Patterns ──

    fun upsertRecurringPattern(pattern: RecurringPattern) {
        db.execSQL("""
            INSERT INTO recurring_patterns
            (counterparty, category, average_amount, frequency_days, frequency_label,
             occurrences, total_spent, first_seen, last_seen, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(counterparty, category) DO UPDATE SET
                average_amount = (recurring_patterns.average_amount * recurring_patterns.occurrences + excluded.average_amount) / (recurring_patterns.occurrences + 1),
                occurrences = recurring_patterns.occurrences + 1,
                total_spent = recurring_patterns.total_spent + excluded.total_spent,
                last_seen = excluded.last_seen,
                frequency_days = (recurring_patterns.frequency_days * recurring_patterns.occurrences + excluded.frequency_days) / (recurring_patterns.occurrences + 1),
                is_active = 1
        """, arrayOf(
            pattern.counterparty, pattern.category, pattern.averageAmount.toString(),
            pattern.frequencyDays.toString(), pattern.frequencyLabel, pattern.occurrences.toString(),
            pattern.totalSpent.toString(), pattern.firstSeen.toString(), pattern.lastSeen.toString()
        ))
    }

    fun getActiveRecurringPatterns(): List<RecurringPattern> {
        val cursor = db.rawQuery(
            "SELECT * FROM recurring_patterns WHERE is_active = 1 ORDER BY total_spent DESC", null
        )
        val list = mutableListOf<RecurringPattern>()
        while (cursor.moveToNext()) {
            list.add(RecurringPattern(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                counterparty = cursor.getString(cursor.getColumnIndexOrThrow("counterparty")),
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                averageAmount = cursor.getDouble(cursor.getColumnIndexOrThrow("average_amount")),
                frequencyDays = cursor.getDouble(cursor.getColumnIndexOrThrow("frequency_days")),
                frequencyLabel = cursor.getString(cursor.getColumnIndexOrThrow("frequency_label")),
                occurrences = cursor.getInt(cursor.getColumnIndexOrThrow("occurrences")),
                totalSpent = cursor.getDouble(cursor.getColumnIndexOrThrow("total_spent")),
                firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen")),
                isActive = cursor.getInt(cursor.getColumnIndexOrThrow("is_active")) == 1
            ))
        }
        cursor.close()
        return list
    }

    // ── Aggregations ──

    fun getCategorySpend(fromTs: Long, toTs: Long): List<CategorySpend> {
        val cursor = db.rawQuery("""
            SELECT category, SUM(amount) as total, COUNT(*) as cnt
            FROM mpesa_transactions
            WHERE direction = 'out' AND timestamp BETWEEN ? AND ?
            GROUP BY category ORDER BY total DESC
        """, arrayOf(fromTs.toString(), toTs.toString()))
        val grandTotal = getTotalOutflow(fromTs, toTs)
        val list = mutableListOf<CategorySpend>()
        while (cursor.moveToNext()) {
            val total = cursor.getDouble(cursor.getColumnIndexOrThrow("total"))
            list.add(CategorySpend(
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                total = total,
                percentage = if (grandTotal > 0) (total / grandTotal * 100) else 0.0,
                count = cursor.getInt(cursor.getColumnIndexOrThrow("cnt"))
            ))
        }
        cursor.close()
        return list
    }

    fun getIncomeSources(fromTs: Long, toTs: Long): List<IncomeSource> {
        val cursor = db.rawQuery("""
            SELECT counterparty, SUM(amount) as total, COUNT(*) as cnt
            FROM mpesa_transactions
            WHERE direction = 'in' AND timestamp BETWEEN ? AND ?
            GROUP BY counterparty ORDER BY total DESC LIMIT 10
        """, arrayOf(fromTs.toString(), toTs.toString()))
        val list = mutableListOf<IncomeSource>()
        while (cursor.moveToNext()) {
            list.add(IncomeSource(
                source = cursor.getString(cursor.getColumnIndexOrThrow("counterparty")),
                total = cursor.getDouble(cursor.getColumnIndexOrThrow("total")),
                count = cursor.getInt(cursor.getColumnIndexOrThrow("cnt"))
            ))
        }
        cursor.close()
        return list
    }

    fun getTotalInflow(fromTs: Long, toTs: Long): Double {
        val cursor = db.rawQuery("""
            SELECT COALESCE(SUM(amount), 0) FROM mpesa_transactions
            WHERE direction = 'in' AND timestamp BETWEEN ? AND ?
        """, arrayOf(fromTs.toString(), toTs.toString()))
        val result = if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        cursor.close()
        return result
    }

    fun getTotalOutflow(fromTs: Long, toTs: Long): Double {
        val cursor = db.rawQuery("""
            SELECT COALESCE(SUM(amount), 0) FROM mpesa_transactions
            WHERE direction = 'out' AND timestamp BETWEEN ? AND ?
        """, arrayOf(fromTs.toString(), toTs.toString()))
        val result = if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        cursor.close()
        return result
    }

    fun getTransactionCount(fromTs: Long, toTs: Long): Int {
        val cursor = db.rawQuery("""
            SELECT COUNT(*) FROM mpesa_transactions WHERE timestamp BETWEEN ? AND ?
        """, arrayOf(fromTs.toString(), toTs.toString()))
        val result = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return result
    }

    /**
     * Update a single transaction's category by ID.
     */
    fun updateTransactionCategory(id: Long, category: String) {
        db.execSQL("UPDATE mpesa_transactions SET category = ? WHERE id = ?", arrayOf(category, id.toString()))
    }

    /**
     * Get a single transaction by ID.
     */
    fun getTransactionById(id: Long): MpesaAutoTransaction? {
        val cursor = db.rawQuery("SELECT * FROM mpesa_transactions WHERE id = ?", arrayOf(id.toString()))
        val result = if (cursor.moveToFirst()) cursorToTransaction(cursor) else null
        cursor.close()
        return result
    }

    fun close() { db.close() }
}

// ──────────────────────────────────────────────
// M-Pesa SMS Parser — the core engine
// ──────────────────────────────────────────────

/**
 * MpesaSmsParser — Extracts structured transaction data from Safaricom M-Pesa SMS.
 *
 * Handles all major M-Pesa transaction types:
 * - Send money (person-to-person)
 * - Receive money
 * - Pay Bill (paybill with account number)
 * - Buy Goods (till number)
 * - Withdraw (agent)
 * - Deposit
 * - Fuliza (overdraft)
 * - MShwari / KCB M-Pesa transfers
 * - Airtime purchase
 */
object MpesaSmsParser {

    // Safaricom M-Pesa sender identifiers
    private val MPESA_SENDERS = setOf("MPESA", "M-PESA", "SAFARICOM", "SAFARICOMM")

    // Transaction code: 10 alphanumeric chars at start of SMS
    private val CODE_REGEX = Regex("^([A-Z0-9]{6,12})\\s")

    // Core amount pattern: "Ksh1,234.56" or "Ksh 1,234.56"
    private val AMOUNT_REGEX = Regex("Ksh\\s?([\\d,]+(?:\\.\\d{1,2})?)")

    // Fee pattern: "Transaction cost, Ksh12.00" or "Fee Ksh 10"
    private val FEE_REGEX = Regex("(?:transaction\\s*cost|fee|charges?)\\s*,?\\s*Ksh\\s?([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)

    // Balance pattern: "New M-PESA balance is Ksh1,234.56"
    private val BALANCE_REGEX = Regex("balance\\s*(?:is)?\\s*Ksh\\s?([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)

    // Phone number pattern: "0712345678" or "+254712345678"
    private val PHONE_REGEX = Regex("(?:\\+254|0)(7\\d{8})")

    /**
     * Parse an M-Pesa SMS into a structured transaction.
     * Returns null if the SMS is not a recognized M-Pesa message.
     */
    fun parse(sms: String): MpesaAutoTransaction? {
        val text = sms.trim()
        if (!isMpesaSms(text)) return null

        val code = extractCode(text)
        val amount = extractAmount(text) ?: return null
        val fee = extractFee(text)
        val balance = extractBalance(text)
        val phone = extractPhone(text)

        return when {
            // Sent to Pay Bill XXX
            text.contains(Regex("sent to\\s+Pay\\s*Bill", RegexOption.IGNORE_CASE)) ->
                parsePaybill(text, code, amount, fee, balance, phone)

            // Sent to till/shop/till number
            text.contains(Regex("sent to\\s+(?:till|shop)", RegexOption.IGNORE_CASE)) ->
                parseTill(text, code, amount, fee, balance, phone)

            // Sent to [person name]
            text.contains(Regex("^${Regex.escape(code)}\\s+Ksh.*sent to\\s+", RegexOption.IGNORE_CASE)) ->
                parseSendMoney(text, code, amount, fee, balance, phone)

            // Received from
            text.contains(Regex("received from", RegexOption.IGNORE_CASE)) ->
                parseReceived(text, code, amount, fee, balance, phone)

            // Withdrawn from agent
            text.contains(Regex("withdraw(?:n)?\\s*(?:from)?", RegexOption.IGNORE_CASE)) ->
                parseWithdraw(text, code, amount, fee, balance, phone)

            // Deposited / given cash
            text.contains(Regex("(?:deposited|given\\s*cash)", RegexOption.IGNORE_CASE)) ->
                parseDeposit(text, code, amount, fee, balance, phone)

            // Fuliza
            text.contains(Regex("fuliza", RegexOption.IGNORE_CASE)) ->
                parseFuliza(text, code, amount, fee, balance, phone)

            // MShwari / KCB
            text.contains(Regex("(?:mshwari|kcb\\s*m-pesa)", RegexOption.IGNORE_CASE)) ->
                parseMshwari(text, code, amount, fee, balance, phone)

            // Airtime purchase
            text.contains(Regex("(?:airtime|top\\s*up)", RegexOption.IGNORE_CASE)) ->
                parseAirtime(text, code, amount, fee, balance, phone)

            // Generic fallback — try to detect direction
            else -> parseGeneric(text, code, amount, fee, balance, phone)
        }
    }

    fun isMpesaSms(sms: String): Boolean {
        val upper = sms.uppercase()
        return MPESA_SENDERS.any { upper.contains(it) } ||
               (CODE_REGEX.containsMatchIn(sms) && AMOUNT_REGEX.containsMatchIn(sms) &&
                sms.contains(Regex("Ksh\\s?[\\d,]", RegexOption.IGNORE_CASE)))
    }

    private fun extractCode(text: String): String =
        CODE_REGEX.find(text)?.groupValues?.get(1) ?: generateCode(text)

    private fun extractAmount(text: String): Double? =
        AMOUNT_REGEX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    private fun extractFee(text: String): Double =
        FEE_REGEX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0

    private fun extractBalance(text: String): Double? =
        BALANCE_REGEX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    private fun extractPhone(text: String): String? =
        PHONE_REGEX.find(text)?.value

    private fun generateCode(text: String): String =
        "AUTO${Math.abs(text.hashCode() % 100000).toString().padStart(5, '0')}"

    // ── Type-specific parsers ──

    private fun parsePaybill(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val paybillRegex = Regex("Pay\\s*Bill\\s*(\\d{4,7})", RegexOption.IGNORE_CASE)
        val accountRegex = Regex("(?:account|acc|acct|A/C)\\s*(?:no\\.?\\s*)?([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)

        val paybill = paybillRegex.find(text)?.groupValues?.get(1)
        val account = accountRegex.find(text)?.groupValues?.get(1)

        val nameRegex = Regex("sent to\\s+(.+?)(?:\\s+on|\\s+Account|\\s+A/C|\\s+Ref|\\.$)", RegexOption.IGNORE_CASE)
        val name = nameRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Pay Bill $paybill"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "out",
            amount = amount,
            counterparty = name,
            transactionType = "paybill",
            paybillNumber = paybill,
            accountNumber = account,
            fee = fee,
            balance = bal,
            category = categorizePaybill(paybill, name, account),
            rawSms = text
        )
    }

    private fun parseTill(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val tillRegex = Regex("till\\s*(?:number\\s*)?(\\d{5,7})", RegexOption.IGNORE_CASE)
        val till = tillRegex.find(text)?.groupValues?.get(1)

        val nameRegex = Regex("(?:till\\s*\\d+\\s*-?\\s*|to\\s+till\\s+\\d+\\s*)(.+?)(?:\\s+on|\\s+Ref|\\.$)", RegexOption.IGNORE_CASE)
        val name = nameRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Till $till"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "out",
            amount = amount,
            counterparty = name,
            transactionType = "till",
            tillNumber = till,
            fee = fee,
            balance = bal,
            category = categorizeTill(till, name),
            rawSms = text
        )
    }

    private fun parseSendMoney(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val nameRegex = Regex("sent to\\s+(.+?)(?:\\s+on|\\s+Ref|\\s+new|\\.$)", RegexOption.IGNORE_CASE)
        val name = nameRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Unknown"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "out",
            amount = amount,
            counterparty = name,
            counterpartyPhone = phone,
            transactionType = "send",
            fee = fee,
            balance = bal,
            category = "person_to_person",
            rawSms = text
        )
    }

    private fun parseReceived(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val nameRegex = Regex("received from\\s+(.+?)(?:\\s+on|\\s+Ref|\\s+new|\\.$)", RegexOption.IGNORE_CASE)
        val name = nameRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Unknown"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "in",
            amount = amount,
            counterparty = name,
            counterpartyPhone = phone,
            transactionType = "receive",
            fee = fee,
            balance = bal,
            category = "income",
            rawSms = text
        )
    }

    private fun parseWithdraw(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val agentRegex = Regex("agent\\s+(.+?)(?:\\s+on|\\s+new|\\.$)", RegexOption.IGNORE_CASE)
        val agent = agentRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Agent"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "out",
            amount = amount,
            counterparty = agent,
            transactionType = "withdraw",
            fee = fee,
            balance = bal,
            category = "cash_withdrawal",
            rawSms = text
        )
    }

    private fun parseDeposit(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val agentRegex = Regex("agent\\s+(.+?)(?:\\s+on|\\s+new|\\.$)", RegexOption.IGNORE_CASE)
        val agent = agentRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Agent"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "in",
            amount = amount,
            counterparty = agent,
            transactionType = "deposit",
            fee = fee,
            balance = bal,
            category = "cash_deposit",
            rawSms = text
        )
    }

    private fun parseFuliza(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        return MpesaAutoTransaction(
            transactionCode = code,
            direction = if (text.contains("repay", ignoreCase = true)) "out" else "in",
            amount = amount,
            counterparty = "Fuliza M-PESA",
            transactionType = "fuliza",
            fee = fee,
            balance = bal,
            category = "fuliza",
            rawSms = text
        )
    }

    private fun parseMshwari(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        val direction = if (text.contains(Regex("from\\s+mshwari|from\\s+kcb", RegexOption.IGNORE_CASE))) "in" else "out"
        return MpesaAutoTransaction(
            transactionCode = code,
            direction = direction,
            amount = amount,
            counterparty = if (text.contains("kcb", ignoreCase = true)) "KCB M-Pesa" else "MShwari",
            transactionType = "mshwari",
            fee = fee,
            balance = bal,
            category = "savings",
            rawSms = text
        )
    }

    private fun parseAirtime(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction {
        return MpesaAutoTransaction(
            transactionCode = code,
            direction = "out",
            amount = amount,
            counterparty = "Airtime",
            transactionType = "airtime",
            fee = fee,
            balance = bal,
            category = "airtime",
            rawSms = text
        )
    }

    private fun parseGeneric(text: String, code: String, amount: Double, fee: Double, bal: Double?, phone: String?): MpesaAutoTransaction? {
        val direction = when {
            text.contains(Regex("sent|paid|bought|withdrawn", RegexOption.IGNORE_CASE)) -> "out"
            text.contains(Regex("received|deposited|refund", RegexOption.IGNORE_CASE)) -> "in"
            else -> return null // Cannot determine direction
        }
        val counterparty = Regex("(?:to|from)\\s+(.+?)(?:\\s+on|\\s+new|\\.$)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim() ?: "Unknown"

        return MpesaAutoTransaction(
            transactionCode = code,
            direction = direction,
            amount = amount,
            counterparty = counterparty,
            transactionType = "other",
            fee = fee,
            balance = bal,
            category = "uncategorized",
            rawSms = text
        )
    }

    // ── Category inference ──

    internal fun categorizePaybill(paybill: String?, name: String?, account: String?): String {
        val combined = "${name ?: ""} ${account ?: ""} ${paybill ?: ""}".lowercase()
        return when {
            // KNOWN PAYBILL NUMBERS — Kenya's most common
            paybill == "247247" || combined.contains("safaricom") -> "utilities"
            paybill == "220220" || combined.contains("kplc") || combined.contains("kenya power") -> "utilities"
            paybill == "333222" || combined.contains("nairobi water") -> "utilities"
            paybill == "100100" || combined.contains("ntv") || combined.contains("dstv") || combined.contains("gotv") -> "entertainment"
            paybill == "400200" || combined.contains("kra") || combined.contains("tax") -> "tax"
            paybill == "200200" || combined.contains("nhif") || combined.contains("insurance") -> "insurance"
            paybill == "600100" || combined.contains("nssf") || combined.contains("pension") -> "pension"
            // Transport
            paybill == "175175" || combined.contains("sgr") || combined.contains("madaraka") -> "transport"
            paybill == "808080" || combined.contains("matatu") || combined.contains("bus") -> "transport"
            // Rent & Housing
            combined.contains("rent") || combined.contains("housing") || combined.contains("landlord") -> "rent"
            combined.contains("deposit") && combined.contains("house") -> "rent"
            // Utilities
            combined.contains("electric") || combined.contains("power") || combined.contains("kplc") -> "utilities"
            combined.contains("water") || combined.contains("nwsc") -> "utilities"
            combined.contains("internet") || combined.contains("wifi") || combined.contains("fiber") -> "utilities"
            combined.contains("gas") || combined.contains("meko") -> "utilities"
            // Education
            combined.contains("school") || combined.contains("fees") || combined.contains("university") || combined.contains("college") -> "education"
            // Health
            combined.contains("hospital") || combined.contains("clinic") || combined.contains("pharmacy") || combined.contains("daktari") -> "health"
            combined.contains("nhif") -> "insurance"
            // Government
            combined.contains("kra") || combined.contains("county") || combined.contains("government") || combined.contains("ntsa") -> "government"
            else -> "paybill"
        }
    }

    internal fun categorizeTill(till: String?, name: String?): String {
        val combined = "${name ?: ""} ${till ?: ""}".lowercase()
        return when {
            // Food & Groceries
            combined.contains(Regex("supermarket|naivas|carrefour|quickmart|tuskys|jumia|glovo|uber\\s*eats|bolt\\s*food|food|grocery|mboga|butchery|nyama")) -> "food"
            combined.contains(Regex("restaurant|cafe|hotel|kitchen|chicken|pizza|burger|kfc|subway|java|artcaffe")) -> "food"
            combined.contains(Regex("duka|kiosk|minishop|general\\s*store")) -> "general_store"
            // Transport
            combined.contains(Regex("petrol|fuel|oil|shell|total|kenol|oilcom|station|matatu|uber|bolt|taxi|boda|ntv\\s*express")) -> "transport"
            // Health
            combined.contains(Regex("pharmacy|chemist|hospital|clinic|daktari|optical|dental|medical")) -> "health"
            // Beauty
            combined.contains(Regex("salon|barber|beauty|spa|nails|hair")) -> "beauty"
            // Clothing
            combined.contains(Regex("clothes|fashion|boutique|nguo|shoes|vitu|textile")) -> "clothing"
            // Electronics
            combined.contains(Regex("phone|simu|electronics|computer|laptop|samsung|iphone|techno")) -> "electronics"
            // Hardware
            combined.contains(Regex("hardware|mbau|iron|steel|cement|paint|tools|mabati")) -> "hardware"
            // Entertainment
            combined.contains(Regex("bar|pub|club|wine|beer|alcohol|spirits|entertainment|cinema")) -> "entertainment"
            else -> "till_payment"
        }
    }
}

// ──────────────────────────────────────────────
// Recurring Pattern Detector
// ──────────────────────────────────────────────

/**
 * Analyzes transaction history to detect recurring payment patterns.
 * Uses frequency analysis: if the same counterparty appears at regular intervals,
 * it's likely a recurring obligation (matatu fare, supplier payment, rent, etc.).
 */
object RecurringDetector {

    /**
     * Analyze transactions and detect recurring patterns.
     * Returns new or updated patterns.
     */
    fun detect(transactions: List<MpesaAutoTransaction>): List<RecurringPattern> {
        if (transactions.size < 3) return emptyList()

        // Group by counterparty + category
        val groups = transactions.groupBy { "${it.counterparty.lowercase()}|${it.category}" }

        val patterns = mutableListOf<RecurringPattern>()

        for ((_, txns) in groups) {
            if (txns.size < 3) continue // Need at least 3 occurrences

            val sorted = txns.sortedBy { it.timestamp }
            val intervals = mutableListOf<Long>()

            for (i in 1 until sorted.size) {
                intervals.add(sorted[i].timestamp - sorted[i - 1].timestamp)
            }

            val avgIntervalMs = intervals.average()
            val avgIntervalDays = avgIntervalMs / (1000.0 * 60 * 60 * 24)

            // Classify frequency
            val label = when {
                avgIntervalDays < 1.5 -> "daily"
                avgIntervalDays < 3.5 -> "every_2_days"
                avgIntervalDays < 9.0 -> "weekly"
                avgIntervalDays < 18.0 -> "biweekly"
                avgIntervalDays < 40.0 -> "monthly"
                avgIntervalDays < 95.0 -> "quarterly"
                else -> "irregular"
            }

            val avgAmount = sorted.map { it.amount }.average()
            val totalSpent = sorted.sumOf { it.amount }

            // Check if still active (last transaction within 2.5x the average interval)
            val lastTxnAge = (System.currentTimeMillis() - sorted.last().timestamp) / (1000.0 * 60 * 60 * 24)
            val stillActive = lastTxnAge < avgIntervalDays * 2.5

            patterns.add(RecurringPattern(
                counterparty = sorted.first().counterparty,
                category = sorted.first().category,
                averageAmount = avgAmount,
                frequencyDays = avgIntervalDays,
                frequencyLabel = label,
                occurrences = txns.size,
                totalSpent = totalSpent,
                firstSeen = sorted.first().timestamp,
                lastSeen = sorted.last().timestamp,
                isActive = stillActive
            ))
        }

        return patterns.sortedByDescending { it.totalSpent }
    }
}

// ──────────────────────────────────────────────
// Financial Profile Builder
// ──────────────────────────────────────────────

object ProfileBuilder {

    /**
     * Build a financial profile from stored transaction data.
     */
    fun build(db: MpesaDatabase, periodDays: Int = 30): FinancialProfile {
        val now = System.currentTimeMillis()
        val fromTs = now - (periodDays * 24 * 60 * 60 * 1000L)

        val totalIn = db.getTotalInflow(fromTs, now)
        val totalOut = db.getTotalOutflow(fromTs, now)
        val txnCount = db.getTransactionCount(fromTs, now)
        val categories = db.getCategorySpend(fromTs, now)
        val income = db.getIncomeSources(fromTs, now)
        val recurring = db.getActiveRecurringPatterns()

        return FinancialProfile(
            totalInflow = totalIn,
            totalOutflow = totalOut,
            netCashFlow = totalIn - totalOut,
            averageDailySpend = if (periodDays > 0) totalOut / periodDays else 0.0,
            averageDailyIncome = if (periodDays > 0) totalIn / periodDays else 0.0,
            topExpenseCategories = categories,
            topIncomeSources = income,
            recurringObligations = recurring,
            transactionCount = txnCount,
            dateRangeDays = periodDays
        )
    }

    /**
     * Format a financial profile as a human-readable summary.
     * Uses Swahili/English mix matching Msaidizi's voice.
     */
    fun formatSummary(profile: FinancialProfile): String {
        val sb = StringBuilder()
        sb.appendLine("📊 *M-Pesa Financial Summary* (${profile.dateRangeDays} days)")
        sb.appendLine()
        sb.appendLine("💰 Income: Ksh ${"%,.0f".format(profile.totalInflow)}")
        sb.appendLine("💸 Expenses: Ksh ${"%,.0f".format(profile.totalOutflow)}")
        sb.appendLine("📈 Net: Ksh ${"%,.0f".format(profile.netCashFlow)}")
        sb.appendLine("📊 Transactions: ${profile.transactionCount}")
        sb.appendLine("📅 Daily avg spend: Ksh ${"%,.0f".format(profile.averageDailySpend)}")
        sb.appendLine()

        if (profile.topExpenseCategories.isNotEmpty()) {
            sb.appendLine("*Top Expense Categories:*")
            for (cat in profile.topExpenseCategories.take(5)) {
                val bar = "█".repeat((cat.percentage / 5).toInt().coerceIn(1, 20))
                sb.appendLine("  ${cat.category}: Ksh ${"%,.0f".format(cat.total)} (${cat.percentage.toInt()}%) $bar")
            }
            sb.appendLine()
        }

        if (profile.topIncomeSources.isNotEmpty()) {
            sb.appendLine("*Top Income Sources:*")
            for (src in profile.topIncomeSources.take(3)) {
                sb.appendLine("  ${src.source}: Ksh ${"%,.0f".format(src.total)} (${src.count}x)")
            }
            sb.appendLine()
        }

        if (profile.recurringObligations.isNotEmpty()) {
            sb.appendLine("*Recurring Payments:*")
            for (r in profile.recurringObligations.filter { it.isActive }.take(5)) {
                sb.appendLine("  ${r.counterparty}: Ksh ${"%,.0f".format(r.averageAmount)} ${r.frequencyLabel} (total: Ksh ${"%,.0f".format(r.totalSpent)})")
            }
        }

        return sb.toString()
    }
}

// ──────────────────────────────────────────────
// M-Pesa SMS BroadcastReceiver
// ──────────────────────────────────────────────

/**
 * Listens for incoming SMS and auto-detects M-Pesa notifications.
 * Registers as a BroadcastReceiver for SMS_RECEIVED.
 *
 * Usage:
 * ```
 * val receiver = MpesaSmsReceiver(context) { txn -> process(txn) }
 * receiver.register()
 * // ...
 * receiver.unregister()
 * ```
 */
class MpesaSmsReceiver(
    private val context: Context,
    private val onTransaction: (MpesaAutoTransaction) -> Unit
) : BroadcastReceiver() {

    private var registered = false

    fun register() {
        if (!registered) {
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            context.registerReceiver(this, filter)
            registered = true
            Timber.d("MpesaSmsReceiver registered")
        }
    }

    fun unregister() {
        if (registered) {
            try {
                context.unregisterReceiver(this)
            } catch (_: Exception) {}
            registered = false
            Timber.d("MpesaSmsReceiver unregistered")
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (sms in messages) {
            val body = sms.messageBody ?: continue
            val sender = sms.displayOriginatingAddress ?: ""

            // Quick filter: only process likely M-Pesa SMS
            if (!isLikelyMpesa(sender, body)) continue

            val txn = MpesaSmsParser.parse(body)
            if (txn != null) {
                Timber.d("Auto-detected M-Pesa: ${txn.transactionType} Ksh ${txn.amount} → ${txn.counterparty}")
                onTransaction(txn)
            }
        }
    }

    private fun isLikelyMpesa(sender: String, body: String): Boolean {
        val senderUpper = sender.uppercase()
        val bodyUpper = body.uppercase()
        return senderUpper.contains("MPESA") || senderUpper.contains("SAFARICOM") ||
               (bodyUpper.contains("KSH") && bodyUpper.contains("BALANCE"))
    }
}

// ──────────────────────────────────────────────
// Main Tool: MpesaAutoLogger
// ──────────────────────────────────────────────

/**
 * MpesaAutoLogger — Auto-detect, parse, categorize, and record M-Pesa transactions.
 *
 * This tool solves INFORMATION ASYMMETRY for informal workers:
 * most M-Pesa transactions are never tracked because manual entry is too tedious.
 * By auto-logging from SMS notifications + manual SMS paste, it builds a complete
 * financial picture with zero effort.
 *
 * Actions:
 * - parse_sms:    Parse a single M-Pesa SMS text
 * - batch_parse:  Parse multiple M-Pesa SMS messages
 * - start_listen: Start auto-detecting M-Pesa SMS (register BroadcastReceiver)
 * - stop_listen:  Stop auto-detecting
 * - history:      View transaction history
 * - profile:      Get financial profile summary
 * - recurring:    View detected recurring payments
 * - categorize:   Manually set category for a transaction
 * - business:     Look up or add a known business (paybill/till → category)
 */
@Singleton
class MpesaAutoLogger @Inject constructor(
    private val context: Context,
    private val gson: Gson
) : Tool {

    override val name = "mpesa_auto_logger"
    override val description = "Auto-detect, parse, categorize, and analyze M-Pesa transactions from SMS. " +
        "Solves information asymmetry — workers don't track where money goes. " +
        "Supports: paybill, till, send/receive, withdraw, deposit, fuliza, MShwari."

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("parse_sms", "batch_parse", "start_listen", "stop_listen",
                   "history", "profile", "recurring", "categorize", "business"))
        string("sms", "M-Pesa SMS text to parse (for parse_sms)", required = false)
        string("messages", "JSON array of SMS texts to parse (for batch_parse)", required = false)
        integer("days", "Number of days for history/profile/recurring analysis", required = false)
        string("category", "Category to set (for categorize action)", required = false)
        string("transaction_id", "Transaction ID (for categorize action)", required = false)
        string("paybill", "Paybill number (for business action)", required = false)
        string("till", "Till number (for business action)", required = false)
        string("business_name", "Business name (for business action)", required = false)
        enum("business_category", "Business category (for business action)",
            listOf("transport", "rent", "utilities", "stock", "food", "health",
                   "education", "entertainment", "airtime", "clothing", "electronics",
                   "hardware", "beauty", "insurance", "tax", "government", "general"), required = false)
        integer("limit", "Max results for history", required = false)
        string("filter_category", "Filter history by category", required = false)
    }

    private val db by lazy { MpesaDatabase(context) }
    private var smsReceiver: MpesaSmsReceiver? = null

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "parse_sms"
        return when (action.lowercase()) {
            "parse_sms" -> handleParseSms(params)
            "batch_parse" -> handleBatchParse(params)
            "start_listen" -> handleStartListen()
            "stop_listen" -> handleStopListen()
            "history" -> handleHistory(params)
            "profile" -> handleProfile(params)
            "recurring" -> handleRecurring(params)
            "categorize" -> handleCategorize(params)
            "business" -> handleBusiness(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ── Actions ──

    private fun handleParseSms(params: Map<String, String>): ToolResult {
        val sms = params["sms"]
            ?: return ToolResult.error(name, "SMS text required. Paste your M-Pesa SMS.", "MISSING_SMS")

        val txn = MpesaSmsParser.parse(sms)
            ?: return ToolResult.error(name, "Not a recognized M-Pesa SMS. Check the text and try again.", "PARSE_ERROR")

        // Store and link
        val stored = storeTransaction(txn)
        if (stored != null) {
            registerBusinessIfNeeded(stored)
            updateRecurringPatterns(stored)
        }

        return buildParseResult(stored ?: txn)
    }

    private fun handleBatchParse(params: Map<String, String>): ToolResult {
        val messagesJson = params["messages"]
            ?: return ToolResult.error(name, "Messages JSON array required", "MISSING_MESSAGES")

        return try {
            val messages = gson.fromJson(messagesJson, Array<String>::class.java)
            var parsed = 0
            var failed = 0
            val transactions = mutableListOf<Map<String, Any?>>()

            for (msg in messages) {
                val txn = MpesaSmsParser.parse(msg)
                if (txn != null) {
                    val stored = storeTransaction(txn)
                    if (stored != null) {
                        registerBusinessIfNeeded(stored)
                        updateRecurringPatterns(stored)
                    }
                    transactions.add(txnToMap(stored ?: txn))
                    parsed++
                } else {
                    failed++
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "parsed" to parsed,
                    "failed" to failed,
                    "transactions" to transactions
                ),
                message = "Batch parse: $parsed transactions recorded, $failed failed"
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed to parse batch: ${e.message}", "PARSE_ERROR")
        }
    }

    private fun handleStartListen(): ToolResult {
        if (smsReceiver != null) {
            return ToolResult.success(name, null, "Already listening for M-Pesa SMS")
        }

        smsReceiver = MpesaSmsReceiver(context) { txn ->
            // Auto-record on background thread
            Thread {
                val stored = storeTransaction(txn)
                if (stored != null) {
                    registerBusinessIfNeeded(stored)
                    updateRecurringPatterns(stored)
                    Timber.d("Auto-recorded: ${txn.transactionType} Ksh ${txn.amount} → ${txn.counterparty}")
                }
            }.start()
        }
        smsReceiver?.register()

        return ToolResult.success(
            toolName = name,
            data = mapOf("listening" to true),
            message = "✅ Now auto-detecting M-Pesa SMS notifications. Transactions will be logged automatically."
        )
    }

    private fun handleStopListen(): ToolResult {
        smsReceiver?.unregister()
        smsReceiver = null
        return ToolResult.success(name, mapOf("listening" to false), "Stopped auto-detecting M-Pesa SMS")
    }

    private fun handleHistory(params: Map<String, String>): ToolResult {
        val days = params["days"]?.toIntOrNull() ?: 30
        val limit = params["limit"]?.toIntOrNull() ?: 50
        val category = params["filter_category"]

        val fromTs = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        val txns = if (category != null) {
            db.getTransactions(limit, 0, category)
        } else {
            db.getTransactionsByDateRange(fromTs, System.currentTimeMillis())
        }

        val totalIn = txns.filter { it.direction == "in" }.sumOf { it.amount }
        val totalOut = txns.filter { it.direction == "out" }.sumOf { it.amount }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "transactions" to txns.take(limit).map { txnToMap(it) },
                "count" to txns.size,
                "total_inflow" to totalIn,
                "total_outflow" to totalOut,
                "period_days" to days
            ),
            message = "Found ${txns.size} transactions in the last $days days. " +
                "In: Ksh ${"%,.0f".format(totalIn)}, Out: Ksh ${"%,.0f".format(totalOut)}"
        )
    }

    private fun handleProfile(params: Map<String, String>): ToolResult {
        val days = params["days"]?.toIntOrNull() ?: 30
        val profile = ProfileBuilder.build(db, days)
        val summary = ProfileBuilder.formatSummary(profile)

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "total_inflow" to profile.totalInflow,
                "total_outflow" to profile.totalOutflow,
                "net_cash_flow" to profile.netCashFlow,
                "average_daily_spend" to profile.averageDailySpend,
                "average_daily_income" to profile.averageDailyIncome,
                "transaction_count" to profile.transactionCount,
                "categories" to profile.topExpenseCategories.map { mapOf("category" to it.category, "total" to it.total, "percentage" to it.percentage) },
                "income_sources" to profile.topIncomeSources.map { mapOf("source" to it.source, "total" to it.total, "count" to it.count) },
                "summary" to summary
            ),
            message = summary
        )
    }

    private fun handleRecurring(params: Map<String, String>): ToolResult {
        val days = params["days"]?.toIntOrNull() ?: 90
        val fromTs = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        val txns = db.getTransactionsByDateRange(fromTs, System.currentTimeMillis())
        val patterns = RecurringDetector.detect(txns)

        val sb = StringBuilder()
        sb.appendLine("🔄 *Recurring Payment Patterns* (last $days days)")
        sb.appendLine()
        if (patterns.isEmpty()) {
            sb.appendLine("No recurring patterns detected yet. Need more transaction history.")
        } else {
            for (p in patterns.filter { it.isActive }.take(10)) {
                sb.appendLine("• ${p.counterparty}")
                sb.appendLine("  Ksh ${"%,.0f".format(p.averageAmount)} ${p.frequencyLabel} (${p.occurrences}x)")
                sb.appendLine("  Total: Ksh ${"%,.0f".format(p.totalSpent)} | Category: ${p.category}")
                sb.appendLine()
            }
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "patterns" to patterns.map { mapOf(
                    "counterparty" to it.counterparty,
                    "category" to it.category,
                    "average_amount" to it.averageAmount,
                    "frequency" to it.frequencyLabel,
                    "occurrences" to it.occurrences,
                    "total_spent" to it.totalSpent,
                    "is_active" to it.isActive
                )},
                "count" to patterns.size
            ),
            message = sb.toString().trim()
        )
    }

    private fun handleCategorize(params: Map<String, String>): ToolResult {
        val txnId = params["transaction_id"]
            ?: return ToolResult.error(name, "Transaction ID required", "MISSING_ID")
        val category = params["category"]
            ?: return ToolResult.error(name, "Category required", "MISSING_CATEGORY")

        val id = txnId.toLongOrNull()
            ?: return ToolResult.error(name, "Invalid transaction ID", "INVALID_ID")

        val existing = db.getTransactionById(id)
            ?: return ToolResult.error(name, "Transaction not found", "NOT_FOUND")

        db.updateTransactionCategory(id, category)

        // Also update the business category if this txn has a paybill/till
        if (existing.paybillNumber != null || existing.tillNumber != null) {
            db.upsertBusiness(MpesaBusiness(
                paybillNumber = existing.paybillNumber,
                tillNumber = existing.tillNumber,
                businessName = existing.counterparty,
                category = category
            ))
        }

        return ToolResult.success(name, mapOf("id" to id, "category" to category), "Category updated to '$category'")
    }

    private fun handleBusiness(params: Map<String, String>): ToolResult {
        val paybill = params["paybill"]
        val till = params["till"]
        val bizName = params["business_name"]
        val category = params["business_category"]

        // Lookup mode
        if (bizName == null && category == null) {
            if (paybill == null && till == null) {
                return ToolResult.error(name, "Provide paybill or till number", "MISSING_PARAMS")
            }
            val biz = db.lookupBusiness(paybill, till)
            return if (biz != null) {
                ToolResult.success(name, mapOf(
                    "name" to biz.businessName,
                    "category" to biz.category,
                    "transaction_count" to biz.transactionCount
                ), "Known business: ${biz.businessName} (${biz.category})")
            } else {
                ToolResult.error(name, "Unknown business. Provide business_name and category to register.", "NOT_FOUND")
            }
        }

        // Register mode
        if ((paybill == null && till == null) || bizName == null || category == null) {
            return ToolResult.error(name, "Need paybill/till + business_name + business_category", "MISSING_PARAMS")
        }

        db.upsertBusiness(MpesaBusiness(
            paybillNumber = paybill,
            tillNumber = till,
            businessName = bizName,
            category = category
        ))

        return ToolResult.success(
            name,
            mapOf("name" to bizName, "paybill" to paybill, "till" to till, "category" to category),
            "Registered: $bizName → $category"
        )
    }

    // ── Helpers ──

    private fun storeTransaction(txn: MpesaAutoTransaction): MpesaAutoTransaction? {
        val id = db.insertTransaction(txn)
        return if (id > 0) txn.copy(id = id) else null // null = duplicate
    }

    private fun registerBusinessIfNeeded(txn: MpesaAutoTransaction) {
        if (txn.paybillNumber != null || txn.tillNumber != null) {
            // Don't overwrite if we already know this business with a better category
            val existing = db.lookupBusiness(txn.paybillNumber, txn.tillNumber)
            if (existing == null || existing.category == "uncategorized") {
                db.upsertBusiness(MpesaBusiness(
                    paybillNumber = txn.paybillNumber,
                    tillNumber = txn.tillNumber,
                    businessName = txn.counterparty,
                    category = txn.category,
                    lastSeen = txn.timestamp
                ))
            }
        }
    }

    private fun updateRecurringPatterns(txn: MpesaAutoTransaction) {
        // Only analyze outflow (recurring expenses)
        if (txn.direction != "out") return

        // Get all transactions with this counterparty
        val related = db.getTransactions(200).filter {
            it.counterparty.equals(txn.counterparty, ignoreCase = true) && it.direction == "out"
        }
        if (related.size < 3) return

        val patterns = RecurringDetector.detect(related)
        for (p in patterns) {
            db.upsertRecurringPattern(p)
        }
    }

    private fun txnToMap(txn: MpesaAutoTransaction): Map<String, Any?> = mapOf(
        "id" to txn.id,
        "code" to txn.transactionCode,
        "direction" to txn.direction,
        "amount" to txn.amount,
        "counterparty" to txn.counterparty,
        "type" to txn.transactionType,
        "paybill" to txn.paybillNumber,
        "till" to txn.tillNumber,
        "account" to txn.accountNumber,
        "fee" to txn.fee,
        "balance" to txn.balance,
        "category" to txn.category,
        "timestamp" to txn.timestamp,
        "auto_detected" to txn.isAutoDetected
    )

    private fun buildParseResult(txn: MpesaAutoTransaction): ToolResult {
        val directionEmoji = if (txn.direction == "in") "📥" else "📤"
        val typeLabel = when (txn.transactionType) {
            "paybill" -> "Pay Bill${txn.paybillNumber?.let { " ($it)" } ?: ""}"
            "till" -> "Till${txn.tillNumber?.let { " ($it)" } ?: ""}"
            "send" -> "Sent to"
            "receive" -> "Received from"
            "withdraw" -> "Withdrawn at"
            "deposit" -> "Deposited at"
            "fuliza" -> "Fuliza"
            "mshwari" -> "MShwari/KCB"
            "airtime" -> "Airtime"
            else -> txn.transactionType.replaceFirstChar { it.uppercase() }
        }

        val msg = buildString {
            appendLine("$directionEmoji *M-Pesa Transaction Recorded*")
            appendLine("Type: $typeLabel")
            appendLine("Amount: Ksh ${"%,.2f".format(txn.amount)}")
            appendLine("${if (txn.direction == "in") "From" else "To"}: ${txn.counterparty}")
            txn.accountNumber?.let { appendLine("Account: $it") }
            if (txn.fee > 0) appendLine("Fee: Ksh ${"%,.2f".format(txn.fee)}")
            txn.balance?.let { appendLine("Balance: Ksh ${"%,.2f".format(it)}") }
            appendLine("Category: ${txn.category}")
            appendLine("Code: ${txn.transactionCode}")
        }

        return ToolResult.success(
            toolName = name,
            data = txnToMap(txn),
            message = msg.trim()
        )
    }

    /**
     * Shutdown — unregister receiver, close database.
     */
    fun shutdown() {
        smsReceiver?.unregister()
        smsReceiver = null
        db.close()
    }
}

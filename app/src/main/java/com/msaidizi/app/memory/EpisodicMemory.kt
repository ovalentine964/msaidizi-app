package com.msaidizi.app.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID

/**
 * Episodic Memory — SQLite FTS5 full-text search for on-device memory.
 *
 * Implements the Hermes L2 memory layer:
 * - Stores every worker interaction with full-text indexing
 * - Sub-10ms retrieval via FTS5 BM25 ranking
 * - Skills from the closed learning loop
 * - Relevance decay and eviction for bounded memory
 * - Privacy-first: worker IDs are hashed
 *
 * Why SQLite FTS5 over vector embeddings:
 * - Zero dependencies (runs natively on Android)
 * - Sub-10ms latency on $50 phones (Snapdragon 450 class)
 * - No embedding model needed (saves 50-200MB storage)
 * - Works fully offline (critical for Africa's connectivity gaps)
 * - FTS5 BM25 is excellent for Swahili/multilingual text
 *
 * Capacity: ~10K episodes before eviction kicks in.
 */
class EpisodicMemory(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "angavu_episodic.db"
        private const val DATABASE_VERSION = 1

        // Episodes table
        private const val TABLE_EPISODES = "episodes"
        private const val COL_ID = "id"
        private const val COL_WORKER_HASH = "worker_hash"
        private const val COL_QUERY = "query"
        private const val COL_RESPONSE = "response"
        private const val COL_OUTCOME = "outcome"
        private const val COL_LESSONS = "lessons"
        private const val COL_DIALECT = "dialect"
        private const val COL_CONTEXT = "context_json"
        private const val COL_RELEVANCE = "relevance"
        private const val COL_ACCESS_COUNT = "access_count"
        private const val COL_TIMESTAMP = "timestamp"

        // FTS5 virtual table for episodes
        private const val TABLE_EPISODES_FTS = "episodes_fts"

        // Skills table
        private const val TABLE_SKILLS = "skills"
        private const val COL_SKILL_ID = "skill_id"
        private const val COL_TITLE = "title"
        private const val COL_CATEGORY = "category"
        private const val COL_CONTENT = "content"
        private const val COL_KEYWORDS = "keywords"
        private const val COL_CONFIDENCE = "confidence"
        private const val COL_USAGE_COUNT = "usage_count"
        private const val COL_SUCCESS_COUNT = "success_count"

        // FTS5 virtual table for skills
        private const val TABLE_SKILLS_FTS = "skills_fts"

        // Eviction thresholds
        private const val MAX_EPISODES = 10_000
        private const val EVICTION_PERCENT = 0.10  // Remove oldest 10%

        // Relevance decay
        private const val DECAY_HALF_LIFE_DAYS = 30.0
        private const val SKILL_DECAY_HALF_LIFE_DAYS = 60.0
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Episodes table
        db.execSQL("""
            CREATE TABLE $TABLE_EPISODES (
                $COL_ID TEXT PRIMARY KEY,
                $COL_WORKER_HASH TEXT NOT NULL,
                $COL_QUERY TEXT NOT NULL,
                $COL_RESPONSE TEXT NOT NULL,
                $COL_OUTCOME TEXT DEFAULT 'success',
                $COL_LESSONS TEXT DEFAULT '',
                $COL_DIALECT TEXT DEFAULT 'sw',
                $COL_CONTEXT TEXT DEFAULT '{}',
                $COL_RELEVANCE REAL DEFAULT 1.0,
                $COL_ACCESS_COUNT INTEGER DEFAULT 0,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL(
            "CREATE INDEX idx_episodes_worker ON $TABLE_EPISODES($COL_WORKER_HASH)"
        )
        db.execSQL(
            "CREATE INDEX idx_episodes_timestamp ON $TABLE_EPISODES($COL_TIMESTAMP)"
        )

        // FTS5 virtual table for episodes
        // unicode61 remove_diacritics 2 handles Swahili diacritics,
        // Sheng, and mixed scripts
        db.execSQL("""
            CREATE VIRTUAL TABLE $TABLE_EPISODES_FTS USING fts5(
                $COL_QUERY,
                $COL_RESPONSE,
                $COL_LESSONS,
                content='$TABLE_EPISODES',
                content_rowid='$COL_ID',
                tokenize='unicode61 remove_diacritics 2'
            )
        """.trimIndent())

        // Triggers to keep FTS index in sync
        db.execSQL("""
            CREATE TRIGGER episodes_ai AFTER INSERT ON $TABLE_EPISODES BEGIN
                INSERT INTO $TABLE_EPISODES_FTS(rowid, $COL_QUERY, $COL_RESPONSE, $COL_LESSONS)
                VALUES (new.$COL_ID, new.$COL_QUERY, new.$COL_RESPONSE, new.$COL_LESSONS);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER episodes_ad AFTER DELETE ON $TABLE_EPISODES BEGIN
                INSERT INTO $TABLE_EPISODES_FTS($TABLE_EPISODES_FTS, rowid, $COL_QUERY, $COL_RESPONSE, $COL_LESSONS)
                VALUES ('delete', old.$COL_ID, old.$COL_QUERY, old.$COL_RESPONSE, old.$COL_LESSONS);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER episodes_au AFTER UPDATE ON $TABLE_EPISODES BEGIN
                INSERT INTO $TABLE_EPISODES_FTS($TABLE_EPISODES_FTS, rowid, $COL_QUERY, $COL_RESPONSE, $COL_LESSONS)
                VALUES ('delete', old.$COL_ID, old.$COL_QUERY, old.$COL_RESPONSE, old.$COL_LESSONS);
                INSERT INTO $TABLE_EPISODES_FTS(rowid, $COL_QUERY, $COL_RESPONSE, $COL_LESSONS)
                VALUES (new.$COL_ID, new.$COL_QUERY, new.$COL_RESPONSE, new.$COL_LESSONS);
            END
        """.trimIndent())

        // Skills table
        db.execSQL("""
            CREATE TABLE $TABLE_SKILLS (
                $COL_SKILL_ID TEXT PRIMARY KEY,
                $COL_WORKER_HASH TEXT DEFAULT '',
                $COL_TITLE TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_KEYWORDS TEXT DEFAULT '',
                $COL_CONFIDENCE REAL DEFAULT 0.5,
                $COL_USAGE_COUNT INTEGER DEFAULT 0,
                $COL_SUCCESS_COUNT INTEGER DEFAULT 0,
                $COL_RELEVANCE REAL DEFAULT 1.0,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())

        // FTS5 virtual table for skills
        db.execSQL("""
            CREATE VIRTUAL TABLE $TABLE_SKILLS_FTS USING fts5(
                $COL_TITLE,
                $COL_CONTENT,
                $COL_KEYWORDS,
                content='$TABLE_SKILLS',
                content_rowid='$COL_SKILL_ID',
                tokenize='unicode61 remove_diacritics 2'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER skills_ai AFTER INSERT ON $TABLE_SKILLS BEGIN
                INSERT INTO $TABLE_SKILLS_FTS(rowid, $COL_TITLE, $COL_CONTENT, $COL_KEYWORDS)
                VALUES (new.$COL_SKILL_ID, new.$COL_TITLE, new.$COL_CONTENT, new.$COL_KEYWORDS);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER skills_ad AFTER DELETE ON $TABLE_SKILLS BEGIN
                INSERT INTO $TABLE_SKILLS_FTS($TABLE_SKILLS_FTS, rowid, $COL_TITLE, $COL_CONTENT, $COL_KEYWORDS)
                VALUES ('delete', old.$COL_SKILL_ID, old.$COL_TITLE, old.$COL_CONTENT, old.$COL_KEYWORDS);
            END
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    // ════════════════════════════════════════════════════════════════
    // Episode Storage
    // ════════════════════════════════════════════════════════════════

    /**
     * Store an interaction episode.
     *
     * @param workerId Worker UUID (will be hashed for privacy)
     * @param query The worker's question/request
     * @param response The agent's response
     * @param outcome "success", "partial", or "failure"
     * @param lessons Lessons learned from this interaction
     * @param dialect Language/dialect used (e.g., "sw", "sw-sheng")
     * @param context Additional context as key-value pairs
     * @return The episode ID
     */
    fun storeEpisode(
        workerId: String,
        query: String,
        response: String,
        outcome: String = "success",
        lessons: String = "",
        dialect: String = "sw",
        context: Map<String, String> = emptyMap()
    ): String {
        val episodeId = UUID.randomUUID().toString()
        val workerHash = hashWorkerId(workerId)
        val contextJson = context.entries.joinToString(",") {
            "\"${it.key}\":\"${it.value}\""
        }

        val values = ContentValues().apply {
            put(COL_ID, episodeId)
            put(COL_WORKER_HASH, workerHash)
            put(COL_QUERY, query)
            put(COL_RESPONSE, response)
            put(COL_OUTCOME, outcome)
            put(COL_LESSONS, lessons)
            put(COL_DIALECT, dialect)
            put(COL_CONTEXT, "{$contextJson}")
            put(COL_RELEVANCE, 1.0)
            put(COL_ACCESS_COUNT, 0)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }

        writableDatabase.insert(TABLE_EPISODES, null, values)

        // Check if eviction is needed
        maybeEvict()

        return episodeId
    }

    // ════════════════════════════════════════════════════════════════
    // Full-Text Search
    // ════════════════════════════════════════════════════════════════

    /**
     * Search episodes using FTS5 full-text search with BM25 ranking.
     *
     * Target: sub-10ms on Snapdragon 450 class devices.
     *
     * @param query Search query (supports Swahili, Sheng, mixed scripts)
     * @param workerId Optional worker filter (searches all if null)
     * @param limit Maximum results to return
     * @return List of matching episodes, ranked by relevance
     */
    fun search(
        query: String,
        workerId: String? = null,
        limit: Int = 10
    ): List<Episode> {
        val ftsQuery = buildFtsQuery(query)
        if (ftsQuery.isBlank()) return emptyList()

        val sql = if (workerId != null) {
            """
            SELECT e.$COL_ID, e.$COL_WORKER_HASH, e.$COL_QUERY,
                   e.$COL_RESPONSE, e.$COL_OUTCOME, e.$COL_LESSONS,
                   e.$COL_DIALECT, e.$COL_CONTEXT, e.$COL_RELEVANCE,
                   e.$COL_ACCESS_COUNT, e.$COL_TIMESTAMP,
                   bm25($TABLE_EPISODES_FTS) AS rank
            FROM $TABLE_EPISODES_FTS fts
            JOIN $TABLE_EPISODES e ON fts.rowid = e.$COL_ID
            WHERE $TABLE_EPISODES_FTS MATCH ?
              AND e.$COL_WORKER_HASH = ?
            ORDER BY (rank * e.$COL_RELEVANCE) ASC
            LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT e.$COL_ID, e.$COL_WORKER_HASH, e.$COL_QUERY,
                   e.$COL_RESPONSE, e.$COL_OUTCOME, e.$COL_LESSONS,
                   e.$COL_DIALECT, e.$COL_CONTEXT, e.$COL_RELEVANCE,
                   e.$COL_ACCESS_COUNT, e.$COL_TIMESTAMP,
                   bm25($TABLE_EPISODES_FTS) AS rank
            FROM $TABLE_EPISODES_FTS fts
            JOIN $TABLE_EPISODES e ON fts.rowid = e.$COL_ID
            WHERE $TABLE_EPISODES_FTS MATCH ?
            ORDER BY (rank * e.$COL_RELEVANCE) ASC
            LIMIT ?
            """.trimIndent()
        }

        val cursor = if (workerId != null) {
            readableDatabase.rawQuery(sql, arrayOf(ftsQuery, hashWorkerId(workerId), limit.toString()))
        } else {
            readableDatabase.rawQuery(sql, arrayOf(ftsQuery, limit.toString()))
        }

        return cursorToEpisodes(cursor)
    }

    /**
     * Search for skills relevant to a query.
     *
     * @param query Search query
     * @param workerId Optional worker filter
     * @param limit Maximum results
     * @return List of matching skills
     */
    fun searchSkills(
        query: String,
        workerId: String? = null,
        limit: Int = 5
    ): List<Skill> {
        val ftsQuery = buildFtsQuery(query)
        if (ftsQuery.isBlank()) return emptyList()

        val sql = """
            SELECT s.$COL_SKILL_ID, s.$COL_WORKER_HASH, s.$COL_TITLE,
                   s.$COL_CATEGORY, s.$COL_CONTENT, s.$COL_KEYWORDS,
                   s.$COL_CONFIDENCE, s.$COL_USAGE_COUNT, s.$COL_SUCCESS_COUNT,
                   s.$COL_RELEVANCE, s.$COL_TIMESTAMP,
                   bm25($TABLE_SKILLS_FTS) AS rank
            FROM $TABLE_SKILLS_FTS fts
            JOIN $TABLE_SKILLS s ON fts.rowid = s.$COL_SKILL_ID
            WHERE $TABLE_SKILLS_FTS MATCH ?
            ORDER BY (rank * s.$COL_RELEVANCE * s.$COL_CONFIDENCE) ASC
            LIMIT ?
        """.trimIndent()

        val cursor = readableDatabase.rawQuery(sql, arrayOf(ftsQuery, limit.toString()))
        return cursorToSkills(cursor)
    }

    // ════════════════════════════════════════════════════════════════
    // Skill Storage (Closed Learning Loop)
    // ════════════════════════════════════════════════════════════════

    /**
     * Store a generated skill from the closed learning loop.
     */
    fun storeSkill(
        skillId: String,
        title: String,
        category: String,
        content: String,
        keywords: List<String>,
        confidence: Double,
        workerId: String = ""
    ) {
        val values = ContentValues().apply {
            put(COL_SKILL_ID, skillId)
            put(COL_WORKER_HASH, if (workerId.isNotEmpty()) hashWorkerId(workerId) else "")
            put(COL_TITLE, title)
            put(COL_CATEGORY, category)
            put(COL_CONTENT, content)
            put(COL_KEYWORDS, keywords.joinToString(" "))
            put(COL_CONFIDENCE, confidence)
            put(COL_USAGE_COUNT, 0)
            put(COL_SUCCESS_COUNT, 0)
            put(COL_RELEVANCE, 1.0)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }

        writableDatabase.insertWithOnConflict(
            TABLE_SKILLS, null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Record skill usage — updates confidence based on success rate.
     */
    fun recordSkillUsage(skillId: String, success: Boolean) {
        val incrementUsage = if (success) {
            "UPDATE $TABLE_SKILLS SET $COL_USAGE_COUNT = $COL_USAGE_COUNT + 1, " +
                "$COL_SUCCESS_COUNT = $COL_SUCCESS_COUNT + 1, " +
                "$COL_CONFIDENCE = MIN(1.0, 0.5 + " +
                "(CAST($COL_SUCCESS_COUNT + 1 AS REAL) / ($COL_USAGE_COUNT + 1)) * 0.5) " +
                "WHERE $COL_SKILL_ID = ?"
        } else {
            "UPDATE $TABLE_SKILLS SET $COL_USAGE_COUNT = $COL_USAGE_COUNT + 1, " +
                "$COL_CONFIDENCE = MAX(0.1, 0.5 + " +
                "(CAST($COL_SUCCESS_COUNT AS REAL) / ($COL_USAGE_COUNT + 1)) * 0.5) " +
                "WHERE $COL_SKILL_ID = ?"
        }

        writableDatabase.execSQL(incrementUsage, arrayOf(skillId))
    }

    // ════════════════════════════════════════════════════════════════
    // Relevance Management
    // ════════════════════════════════════════════════════════════════

    /**
     * Boost relevance of a specific episode.
     */
    fun boostRelevance(episodeId: String, boost: Double) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_EPISODES SET $COL_RELEVANCE = " +
                "MIN(2.0, $COL_RELEVANCE + ?), " +
                "$COL_ACCESS_COUNT = $COL_ACCESS_COUNT + 1 " +
                "WHERE $COL_ID = ?",
            arrayOf(boost, episodeId)
        )
    }

    /**
     * Run relevance decay on all episodes and skills.
     * Call this periodically (e.g., once per day).
     *
     * Uses exponential decay: relevance *= 2^(-days / half_life)
     */
    fun runDecay() {
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L

        // Decay episodes (30-day half-life)
        writableDatabase.execSQL(
            """
            UPDATE $TABLE_EPISODES
            SET $COL_RELEVANCE = $COL_RELEVANCE *
                POWER(2.0, -(($now - $COL_TIMESTAMP) / $dayMs) / $DECAY_HALF_LIFE_DAYS)
            WHERE $COL_RELEVANCE > 0.01
            """.trimIndent()
        )

        // Decay skills (60-day half-life)
        writableDatabase.execSQL(
            """
            UPDATE $TABLE_SKILLS
            SET $COL_RELEVANCE = $COL_RELEVANCE *
                POWER(2.0, -(($now - $COL_TIMESTAMP) / $dayMs) / $SKILL_DECAY_HALF_LIFE_DAYS)
            WHERE $COL_RELEVANCE > 0.01
            """.trimIndent()
        )

        // Remove stale episodes (relevance < 0.01)
        writableDatabase.delete(TABLE_EPISODES, "$COL_RELEVANCE < 0.01", null)
    }

    // ════════════════════════════════════════════════════════════════
    // Eviction
    // ════════════════════════════════════════════════════════════════

    /**
     * Evict old episodes when capacity is reached.
     * Removes oldest 10% by access count + relevance.
     */
    private fun maybeEvict() {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_EPISODES", null
        )
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()

        if (count <= MAX_EPISODES) return

        val evictCount = (MAX_EPISODES * EVICTION_PERCENT).toInt()

        // Delete lowest-value episodes (oldest + least accessed + lowest relevance)
        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE_EPISODES
            WHERE $COL_ID IN (
                SELECT $COL_ID FROM $TABLE_EPISODES
                ORDER BY ($COL_ACCESS_COUNT * $COL_RELEVANCE) ASC, $COL_TIMESTAMP ASC
                LIMIT $evictCount
            )
            """.trimIndent()
        )
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Build FTS5 query from user input.
     * Handles Swahili, Sheng, and mixed scripts.
     */
    private fun buildFtsQuery(query: String): String {
        // Split into words, escape special FTS5 characters
        val words = query.trim().split("\\s+".toRegex())
            .filter { it.length > 1 }
            .map { it.replace(Regex("[\"'*(){}\\[\\]^~]"), "") }
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return ""

        // Use OR matching for broader results
        return words.joinToString(" OR ")
    }

    /**
     * Hash worker ID for privacy. Consistent hashing ensures
     * the same worker always gets the same hash.
     */
    private fun hashWorkerId(workerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(workerId.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun cursorToEpisodes(cursor: Cursor): List<Episode> {
        val episodes = mutableListOf<Episode>()
        cursor.use {
            while (it.moveToNext()) {
                episodes.add(
                    Episode(
                        id = it.getString(it.getColumnIndexOrThrow(COL_ID)),
                        workerHash = it.getString(it.getColumnIndexOrThrow(COL_WORKER_HASH)),
                        query = it.getString(it.getColumnIndexOrThrow(COL_QUERY)),
                        response = it.getString(it.getColumnIndexOrThrow(COL_RESPONSE)),
                        outcome = it.getString(it.getColumnIndexOrThrow(COL_OUTCOME)),
                        lessons = it.getString(it.getColumnIndexOrThrow(COL_LESSONS)),
                        dialect = it.getString(it.getColumnIndexOrThrow(COL_DIALECT)),
                        contextJson = it.getString(it.getColumnIndexOrThrow(COL_CONTEXT)),
                        relevance = it.getDouble(it.getColumnIndexOrThrow(COL_RELEVANCE)),
                        accessCount = it.getInt(it.getColumnIndexOrThrow(COL_ACCESS_COUNT)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            }
        }
        return episodes
    }

    private fun cursorToSkills(cursor: Cursor): List<Skill> {
        val skills = mutableListOf<Skill>()
        cursor.use {
            while (it.moveToNext()) {
                skills.add(
                    Skill(
                        skillId = it.getString(it.getColumnIndexOrThrow(COL_SKILL_ID)),
                        workerHash = it.getString(it.getColumnIndexOrThrow(COL_WORKER_HASH)),
                        title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)),
                        category = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY)),
                        content = it.getString(it.getColumnIndexOrThrow(COL_CONTENT)),
                        keywords = it.getString(it.getColumnIndexOrThrow(COL_KEYWORDS)).split(" "),
                        confidence = it.getDouble(it.getColumnIndexOrThrow(COL_CONFIDENCE)),
                        usageCount = it.getInt(it.getColumnIndexOrThrow(COL_USAGE_COUNT)),
                        successCount = it.getInt(it.getColumnIndexOrThrow(COL_SUCCESS_COUNT)),
                        relevance = it.getDouble(it.getColumnIndexOrThrow(COL_RELEVANCE)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            }
        }
        return skills
    }

    /**
     * Get statistics about the episodic memory.
     */
    fun getStats(): MemoryStats {
        val epCursor = readableDatabase.rawQuery(
            "SELECT COUNT(*), AVG($COL_RELEVANCE) FROM $TABLE_EPISODES", null
        )
        epCursor.moveToFirst()
        val episodeCount = epCursor.getInt(0)
        val avgRelevance = epCursor.getDouble(1)
        epCursor.close()

        val skillCursor = readableDatabase.rawQuery(
            "SELECT COUNT(*), AVG($COL_CONFIDENCE) FROM $TABLE_SKILLS", null
        )
        skillCursor.moveToFirst()
        val skillCount = skillCursor.getInt(0)
        val avgConfidence = skillCursor.getDouble(1)
        skillCursor.close()

        return MemoryStats(
            episodeCount = episodeCount,
            skillCount = skillCount,
            avgEpisodeRelevance = avgRelevance,
            avgSkillConfidence = avgConfidence,
            capacity = MAX_EPISODES,
            utilizationPercent = (episodeCount.toDouble() / MAX_EPISODES * 100).toInt()
        )
    }
}

// ════════════════════════════════════════════════════════════════
// Data Classes
// ════════════════════════════════════════════════════════════════

data class Episode(
    val id: String,
    val workerHash: String,
    val query: String,
    val response: String,
    val outcome: String,
    val lessons: String,
    val dialect: String,
    val contextJson: String,
    val relevance: Double,
    val accessCount: Int,
    val timestamp: Long
)

data class Skill(
    val skillId: String,
    val workerHash: String,
    val title: String,
    val category: String,
    val content: String,
    val keywords: List<String>,
    val confidence: Double,
    val usageCount: Int,
    val successCount: Int,
    val relevance: Double,
    val timestamp: Long
) {
    val successRate: Double
        get() = if (usageCount == 0) 0.0 else successCount.toDouble() / usageCount
}

data class MemoryStats(
    val episodeCount: Int,
    val skillCount: Int,
    val avgEpisodeRelevance: Double,
    val avgSkillConfidence: Double,
    val capacity: Int,
    val utilizationPercent: Int
)

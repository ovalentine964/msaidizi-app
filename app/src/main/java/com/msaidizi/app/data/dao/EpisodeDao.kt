package com.msaidizi.app.data.dao

import androidx.room.*
import com.msaidizi.app.data.entity.EpisodeEntity

@Dao
interface EpisodeDao {
    @Insert
    suspend fun insert(episode: EpisodeEntity)

    @Insert
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("""
        SELECT * FROM episodes
        WHERE workerId = :workerId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecent(workerId: String, limit: Int = 10): List<EpisodeEntity>

    // ═══ FTS5 MATCH SEARCH (replaces LIKE '%term%') ═══
    // Uses BM25 ranking for relevance-ordered results.
    // Sub-10ms retrieval on 10K episodes as specified in arch_memory.md.

    /**
     * Full-text search using FTS5 MATCH with BM25 ranking.
     * Searches across query, response, intent, and sessionId columns.
     * Results are ranked by BM25 relevance score.
     *
     * The workerId filter is applied via the FTS5 table's workerId column.
     * Tokenizer: unicode61 (handles Swahili diacritics).
     */
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN episodes_fts fts ON e.id = fts.rowid
        WHERE episodes_fts MATCH :ftsQuery
        ORDER BY rank
        LIMIT :limit
    """)
    suspend fun searchFts(ftsQuery: String, limit: Int = 5): List<EpisodeEntity>

    /**
     * Full-text search scoped to a specific worker.
     * Combines FTS5 MATCH with workerId filter for privacy isolation.
     */
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN episodes_fts fts ON e.id = fts.rowid
        WHERE episodes_fts MATCH :ftsQuery AND fts.workerId = :workerId
        ORDER BY rank
        LIMIT :limit
    """)
    suspend fun searchFtsForWorker(workerId: String, ftsQuery: String, limit: Int = 5): List<EpisodeEntity>

    /**
     * Legacy LIKE search — kept as fallback if FTS5 fails.
     * Prefer searchFts() / searchFtsForWorker() for production use.
     */
    @Query("""
        SELECT * FROM episodes
        WHERE workerId = :workerId AND query LIKE '%' || :term || '%'
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun search(workerId: String, term: String, limit: Int = 5): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE workerId = :workerId AND intent = :intent
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getByIntent(workerId: String, intent: String, limit: Int = 5): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM episodes WHERE workerId = :workerId")
    suspend fun getCount(workerId: String): Int

    @Query("""
        UPDATE episodes SET relevanceScore = relevanceScore + :boost
        WHERE id = :id
    """)
    suspend fun boostRelevance(id: Long, boost: Double)

    @Query("""
        DELETE FROM episodes WHERE id IN (
            SELECT id FROM episodes WHERE workerId = :workerId
            ORDER BY timestamp ASC LIMIT :count
        )
    """)
    suspend fun evictOldest(workerId: String, count: Int)

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM episodes WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}

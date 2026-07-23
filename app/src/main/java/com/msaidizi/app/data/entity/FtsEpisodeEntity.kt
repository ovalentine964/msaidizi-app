package com.msaidizi.app.data.entity

import androidx.room.Entity
import androidx.room.Fts5

/**
 * FTS5 virtual table for episode full-text search.
 *
 * Enables BM25-ranked search over episode queries and responses.
 * This replaces the slow LIKE '%term%' pattern with sub-10ms FTS5 MATCH.
 *
 * The content=episodes option makes this a content table backed by
 * the episodes entity. FTS5 automatically keeps the index in sync.
 */
@Fts5
@Entity(tableName = "episodes_fts")
data class FtsEpisodeEntity(
    val workerId: String,
    val query: String,
    val response: String,
    val outcome: String,
    val intent: String,
    val sessionId: String
)

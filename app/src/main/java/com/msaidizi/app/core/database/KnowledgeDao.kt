package com.msaidizi.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the knowledge graph persistence layer.
 *
 * Provides CRUD operations and graph queries. The in-memory
 * ConcurrentHashMap remains the hot cache; Room is the durable
 * fallback for app-kill recovery.
 */
@Dao
interface KnowledgeDao {

    // ═══════════════ NODES ═══════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: KnowledgeNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<KnowledgeNodeEntity>)

    @Query("SELECT * FROM knowledge_nodes WHERE node_id = :nodeId")
    suspend fun getNode(nodeId: String): KnowledgeNodeEntity?

    @Query("SELECT * FROM knowledge_nodes WHERE domain = :domain")
    suspend fun getNodesByDomain(domain: String): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE domain = :domain AND node_type = :type")
    suspend fun getNodesByDomainAndType(domain: String, type: String): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE key LIKE '%' || :keyPattern || '%'")
    suspend fun getNodesByKeyPattern(keyPattern: String): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes ORDER BY confidence DESC LIMIT :limit")
    suspend fun getTopNodes(limit: Int): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE node_type = 'FACT' ORDER BY confidence ASC LIMIT :count")
    suspend fun getLowestConfidenceFacts(count: Int): List<KnowledgeNodeEntity>

    @Query("SELECT COUNT(*) FROM knowledge_nodes")
    suspend fun getNodeCount(): Int

    @Query("DELETE FROM knowledge_nodes WHERE node_id = :nodeId")
    suspend fun deleteNode(nodeId: String)

    @Query("DELETE FROM knowledge_nodes WHERE node_id IN (:nodeIds)")
    suspend fun deleteNodes(nodeIds: List<String>)

    @Query("DELETE FROM knowledge_nodes")
    suspend fun deleteAllNodes()

    // ═══════════════ EDGES ═══════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdge(edge: KnowledgeEdgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdges(edges: List<KnowledgeEdgeEntity>)

    @Query("SELECT * FROM knowledge_edges WHERE from_node = :nodeId")
    suspend fun getEdgesFrom(nodeId: String): List<KnowledgeEdgeEntity>

    @Query("SELECT * FROM knowledge_edges WHERE to_node = :nodeId")
    suspend fun getEdgesTo(nodeId: String): List<KnowledgeEdgeEntity>

    @Query("SELECT * FROM knowledge_edges WHERE from_node = :nodeId OR to_node = :nodeId")
    suspend fun getEdgesForNode(nodeId: String): List<KnowledgeEdgeEntity>

    @Query("SELECT COUNT(*) FROM knowledge_edges")
    suspend fun getEdgeCount(): Int

    @Query("DELETE FROM knowledge_edges WHERE from_node = :fromNode AND to_node = :toNode")
    suspend fun deleteEdge(fromNode: String, toNode: String)

    @Query("DELETE FROM knowledge_edges WHERE from_node IN (:nodeIds) OR to_node IN (:nodeIds)")
    suspend fun deleteEdgesForNodes(nodeIds: List<String>)

    @Query("DELETE FROM knowledge_edges")
    suspend fun deleteAllEdges()

    // ═══════════════ BATCH OPERATIONS ═══════════════

    /**
     * Load the entire graph from disk. Called once at startup
     * to hydrate the in-memory cache.
     */
    @Transaction
    suspend fun loadFullGraph(): Pair<List<KnowledgeNodeEntity>, List<KnowledgeEdgeEntity>> {
        return Pair(getAllNodes(), getAllEdges())
    }

    @Query("SELECT * FROM knowledge_nodes")
    suspend fun getAllNodes(): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_edges")
    suspend fun getAllEdges(): List<KnowledgeEdgeEntity>

    /**
     * Prune old low-confidence FACT nodes and their edges.
     * Returns number of nodes deleted.
     */
    @Transaction
    suspend fun pruneOldFacts(maxAge: Long, maxNodes: Int): Int {
        val cutoff = System.currentTimeMillis() - maxAge
        // Delete old low-confidence facts beyond the cap
        val facts = getLowestConfidenceFacts(maxNodes + 100)
        val toDelete = facts
            .filter { it.updatedAt < cutoff }
            .take(maxNodes)
            .map { it.nodeId }
        if (toDelete.isNotEmpty()) {
            deleteEdgesForNodes(toDelete)
            deleteNodes(toDelete)
        }
        return toDelete.size
    }
}

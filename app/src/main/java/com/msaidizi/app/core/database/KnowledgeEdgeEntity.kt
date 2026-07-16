package com.msaidizi.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for knowledge graph edges (relations between nodes).
 *
 * Stores cross-domain relationships discovered by the knowledge graph.
 * Enables graph traversal queries on restart without rebuilding the
 * entire relation set from scratch.
 *
 * @see com.msaidizi.app.agent.knowledge.KnowledgeRelation
 */
@Entity(
    tableName = "knowledge_edges",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["node_id"],
            childColumns = ["from_node"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["node_id"],
            childColumns = ["to_node"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["from_node"], name = "index_knowledge_edges_from_node"),
        Index(value = ["to_node"], name = "index_knowledge_edges_to_node"),
        Index(value = ["from_node", "to_node"], unique = true, name = "index_knowledge_edges_from_to"),
        Index(value = ["relation_type"], name = "index_knowledge_edges_relation_type")
    ]
)
data class KnowledgeEdgeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Source node ID */
    @ColumnInfo(name = "from_node")
    val fromNode: String,

    /** Target node ID */
    @ColumnInfo(name = "to_node")
    val toNode: String,

    /** Relation type: KEY_OVERLAP, TEMPORAL, CAUSAL, CORRELATION */
    @ColumnInfo(name = "relation_type")
    val relationType: String,

    /** Strength 0.0–1.0 */
    @ColumnInfo(name = "strength")
    val strength: Double,

    /** Shared key terms (JSON array) */
    @ColumnInfo(name = "shared_keys_json")
    val sharedKeysJson: String = "[]",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

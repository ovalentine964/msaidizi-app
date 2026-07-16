package com.msaidizi.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for knowledge graph nodes.
 *
 * Stores facts, patterns, and insights discovered by agents.
 * The in-memory ConcurrentHashMap is the hot cache; this is the
 * durable layer that survives app kills on 2GB devices.
 *
 * @see com.msaidizi.app.agent.knowledge.KnowledgeNode
 */
@Entity(
    tableName = "knowledge_nodes",
    indices = [
        Index(value = ["domain"], name = "index_knowledge_nodes_domain"),
        Index(value = ["node_type"], name = "index_knowledge_nodes_node_type"),
        Index(value = ["domain", "node_type"], name = "index_knowledge_nodes_domain_type"),
        Index(value = ["updated_at"], name = "index_knowledge_nodes_updated_at"),
        Index(value = ["confidence"], name = "index_knowledge_nodes_confidence")
    ]
)
data class KnowledgeNodeEntity(
    /** Composite key: "domain:key" or "domain:type:key" */
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    /** Node type: FACT, PATTERN, INSIGHT */
    @ColumnInfo(name = "node_type")
    val nodeType: String,

    /** Domain: sales, finance, inventory, learning, analysis */
    @ColumnInfo(name = "domain")
    val domain: String,

    /** Key within the domain */
    @ColumnInfo(name = "key")
    val key: String,

    /** Serialized value map (JSON) */
    @ColumnInfo(name = "value_json")
    val valueJson: String,

    /** Confidence score 0.0–1.0 */
    @ColumnInfo(name = "confidence")
    val confidence: Double,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

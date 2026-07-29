package com.msaidizi.core.database

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ═══════════════════════════════════════════════════════════════════
//  ROOM ENTITIES — Knowledge Graph
// ═══════════════════════════════════════════════════════════════════

@Entity(
    tableName = "kg_nodes",
    indices = [
        Index(value = ["type"]),
        Index(value = ["label"])
    ]
)
data class KgNodeEntity(
    @PrimaryKey val id: String,
    val type: String,
    val label: String,
    val propertiesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "kg_edges",
    indices = [
        Index(value = ["fromId"]),
        Index(value = ["toId"]),
        Index(value = ["relation"]),
        Index(value = ["fromId", "relation"])
    ]
)
data class KgEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromId: String,
    val toId: String,
    val relation: String,
    val propertiesJson: String = "{}",
    val weight: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "kg_facts",
    indices = [
        Index(value = ["subject"]),
        Index(value = ["predicate"]),
        Index(value = ["subject", "predicate"], unique = true)
    ]
)
data class KgFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val predicate: String,
    val obj: String,
    val confidence: Float = 1.0f,
    val source: String = "system",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════
//  ROOM DAOs — Knowledge Graph
// ═══════════════════════════════════════════════════════════════════

@Dao
interface KgNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: KgNodeEntity)

    @Query("SELECT * FROM kg_nodes WHERE id = :id")
    suspend fun getById(id: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE type = :type ORDER BY label ASC")
    suspend fun getByType(type: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE label LIKE :query ORDER BY label ASC LIMIT 20")
    suspend fun search(query: String): List<KgNodeEntity>

    @Query("SELECT COUNT(*) FROM kg_nodes")
    suspend fun count(): Int

    @Query("DELETE FROM kg_nodes WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface KgEdgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edge: KgEdgeEntity)

    @Query("SELECT * FROM kg_edges WHERE fromId = :nodeId")
    suspend fun getOutgoing(nodeId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE toId = :nodeId")
    suspend fun getIncoming(nodeId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE fromId = :nodeId AND relation = :relation")
    suspend fun getOutgoingByRelation(nodeId: String, relation: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE fromId = :nodeId AND relation IN (:relations)")
    suspend fun getOutgoingByRelation(nodeId: String, relations: List<String>): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE toId = :nodeId AND relation IN (:relations)")
    suspend fun getIncomingByRelation(nodeId: String, relations: List<String>): List<KgEdgeEntity>

    @Query("SELECT COUNT(*) FROM kg_edges")
    suspend fun count(): Int

    @Query("DELETE FROM kg_edges WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface KgFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: KgFactEntity)

    @Query("SELECT * FROM kg_facts WHERE subject = :subject")
    suspend fun getBySubject(subject: String): List<KgFactEntity>

    @Query("SELECT * FROM kg_facts WHERE predicate = :predicate")
    suspend fun getByPredicate(predicate: String): List<KgFactEntity>

    @Query("SELECT * FROM kg_facts WHERE subject = :subject AND predicate = :predicate LIMIT 1")
    suspend fun get(subject: String, predicate: String): KgFactEntity?

    @Query("SELECT COUNT(*) FROM kg_facts")
    suspend fun count(): Int

    @Query("DELETE FROM kg_facts WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

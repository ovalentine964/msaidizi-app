package com.msaidizi.app.superagent.graph

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KnowledgeGraph — Lightweight on-device knowledge graph for worker context.
 *
 * Connects: Worker → Products → Suppliers → Customers
 *           Products → Categories → MarketPrices
 *           Transactions → Patterns → Insights
 *
 * Uses SQLite adjacency list pattern (NOT a graph DB — too heavy for 2GB RAM).
 * All queries are O(V+E) via indexed lookups.
 *
 * Storage: ~50KB for 500 entities + 2000 edges on a typical worker's device.
 *
 * Design follows the research report's recommendation:
 *   - Property graph model (nodes + edges both carry properties)
 *   - Triple store: (subject, predicate, object) for knowledge facts
 *   - Adjacency list for graph traversal
 *   - All in SQLite, no external dependencies
 */
@Singleton
class KnowledgeGraph @Inject constructor(
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao,
    private val kgFactDao: KgFactDao,
    private val gson: Gson
) {
    // ═══════════════════════════════════════════════════════════════
    //  NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Upsert a node in the knowledge graph.
     * Nodes are entities: products, customers, suppliers, categories, etc.
     */
    suspend fun upsertNode(
        id: String,
        type: NodeType,
        label: String,
        properties: Map<String, String> = emptyMap()
    ) {
        kgNodeDao.upsert(
            KgNodeEntity(
                id = id,
                type = type.name,
                label = label,
                propertiesJson = gson.toJson(properties),
                updatedAt = System.currentTimeMillis()
            )
        )
        Timber.d("KG: upserted node [%s] %s: %s", type, id, label)
    }

    /** Get a node by ID. */
    suspend fun getNode(id: String): KgNode? {
        val entity = kgNodeDao.getById(id) ?: return null
        return entity.toKgNode(gson)
    }

    /** Get all nodes of a given type. */
    suspend fun getNodesByType(type: NodeType): List<KgNode> {
        return kgNodeDao.getByType(type.name).map { it.toKgNode(gson) }
    }

    /** Search nodes by label (fuzzy). */
    suspend fun searchNodes(query: String): List<KgNode> {
        return kgNodeDao.search("%$query%").map { it.toKgNode(gson) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add a directed edge between two nodes.
     * Edges are relationships: "sells", "supplies", "belongs_to", etc.
     */
    suspend fun addEdge(
        fromId: String,
        toId: String,
        relation: RelationType,
        properties: Map<String, String> = emptyMap(),
        weight: Float = 1.0f
    ) {
        kgEdgeDao.upsert(
            KgEdgeEntity(
                fromId = fromId,
                toId = toId,
                relation = relation.name,
                propertiesJson = gson.toJson(properties),
                weight = weight,
                updatedAt = System.currentTimeMillis()
            )
        )
        Timber.d("KG: edge %s -[%s]-> %s", fromId, relation, toId)
    }

    /** Get all outgoing edges from a node. */
    suspend fun getOutgoing(nodeId: String): List<KgEdge> {
        return kgEdgeDao.getOutgoing(nodeId).map { it.toKgEdge(gson) }
    }

    /** Get all incoming edges to a node. */
    suspend fun getIncoming(nodeId: String): List<KgEdge> {
        return kgEdgeDao.getIncoming(nodeId).map { it.toKgEdge(gson) }
    }

    /** Get edges of a specific relation type from a node. */
    suspend fun getOutgoingByRelation(nodeId: String, relation: RelationType): List<KgEdge> {
        return kgEdgeDao.getOutgoingByRelation(nodeId, relation.name).map { it.toKgEdge(gson) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRIPLE FACTS (Knowledge Triples)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Store a knowledge triple: (subject, predicate, object).
     * Used for domain knowledge: "tomatoes" → "is_perishable" → "true"
     */
    suspend fun addFact(
        subject: String,
        predicate: String,
        obj: String,
        confidence: Float = 1.0f,
        source: String = "system"
    ) {
        kgFactDao.upsert(
            KgFactEntity(
                subject = subject,
                predicate = predicate,
                obj = obj,
                confidence = confidence,
                source = source,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Query facts by subject. */
    suspend fun getFactsBySubject(subject: String): List<KgFact> {
        return kgFactDao.getBySubject(subject).map { it.toKgFact() }
    }

    /** Query facts by predicate. */
    suspend fun getFactsByPredicate(predicate: String): List<KgFact> {
        return kgFactDao.getByPredicate(predicate).map { it.toKgFact() }
    }

    /** Query facts by (subject, predicate) pair. */
    suspend fun getFact(subject: String, predicate: String): KgFact? {
        return kgFactDao.get(subject, predicate)?.toKgFact()
    }

    // ═══════════════════════════════════════════════════════════════
    //  GRAPH TRAVERSAL — BFS/DFS Neighborhood Queries
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the N-hop neighborhood of a node.
     * BFS traversal, returns all reachable nodes within [maxHops].
     * O(V + E) for the traversed subgraph.
     *
     * Example: "tomatoes" → 1-hop → [supplier: "Wakulima Market", category: "Vegetables"]
     *          "tomatoes" → 2-hop → [market_price: "KES 80/kg", restock_pattern: "Mondays"]
     */
    suspend fun getNeighborhood(
        nodeId: String,
        maxHops: Int = 2,
        relationFilter: Set<RelationType>? = null
    ): NeighborhoodResult {
        val visited = mutableMapOf<String, Int>() // nodeId → hop distance
        val edges = mutableListOf<KgEdge>()
        val queue = ArrayDeque<Pair<String, Int>>() // (nodeId, hop)

        queue.add(nodeId to 0)
        visited[nodeId] = 0

        while (queue.isNotEmpty()) {
            val (current, hop) = queue.removeFirst()
            if (hop >= maxHops) continue

            // Outgoing edges
            val outgoing = if (relationFilter != null) {
                kgEdgeDao.getOutgoingByRelation(current, relationFilter.map { it.name })
            } else {
                kgEdgeDao.getOutgoing(current)
            }

            for (edge in outgoing.map { it.toKgEdge(gson) }) {
                edges.add(edge)
                if (edge.toId !in visited) {
                    visited[edge.toId] = hop + 1
                    queue.add(edge.toId to (hop + 1))
                }
            }

            // Incoming edges (reverse traversal)
            val incoming = if (relationFilter != null) {
                kgEdgeDao.getIncomingByRelation(current, relationFilter.map { it.name })
            } else {
                kgEdgeDao.getIncoming(current)
            }

            for (edge in incoming.map { it.toKgEdge(gson) }) {
                edges.add(edge)
                if (edge.fromId !in visited) {
                    visited[edge.fromId] = hop + 1
                    queue.add(edge.fromId to (hop + 1))
                }
            }
        }

        // Fetch all visited nodes
        val nodes = visited.keys.mapNotNull { kgNodeDao.getById(it)?.toKgNode(gson) }

        return NeighborhoodResult(
            centerId = nodeId,
            nodes = nodes,
            edges = edges,
            hopDistances = visited
        )
    }

    /**
     * Find all paths between two nodes (up to maxDepth).
     * Useful for "how is X related to Y?" queries.
     */
    suspend fun findPaths(
        fromId: String,
        toId: String,
        maxDepth: Int = 3
    ): List<List<String>> {
        val paths = mutableListOf<List<String>>()
        val stack = ArrayDeque<List<String>>()
        stack.add(listOf(fromId))

        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val current = path.last()

            if (current == toId) {
                paths.add(path)
                if (paths.size >= 10) break // cap to prevent explosion
                continue
            }

            if (path.size > maxDepth) continue

            val outgoing = kgEdgeDao.getOutgoing(current)
            for (edge in outgoing) {
                if (edge.toId !in path) { // avoid cycles
                    stack.add(path + edge.toId)
                }
            }
        }

        return paths
    }

    /**
     * Get related entities for context assembly.
     * Returns a flat list of "entity → relation → entity" strings.
     * Used by ContextAssembler to enrich LLM context.
     */
    suspend fun getRelatedContext(nodeId: String, maxHops: Int = 2): List<String> {
        val neighborhood = getNeighborhood(nodeId, maxHops)
        val contextLines = mutableListOf<String>()

        // Build human-readable relationship descriptions
        for (edge in neighborhood.edges) {
            val fromNode = neighborhood.nodes.find { it.id == edge.fromId }
            val toNode = neighborhood.nodes.find { it.id == edge.toId }

            if (fromNode != null && toNode != null) {
                val fromLabel = fromNode.label
                val toLabel = toNode.label
                val relation = edge.relation.replace("_", " ")
                contextLines.add("$fromLabel $relation $toLabel")

                // Add properties as context
                val props = edge.properties
                if (props.isNotEmpty()) {
                    val propsStr = props.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    contextLines.add("  ($propsStr)")
                }
            }
        }

        // Add relevant facts
        val facts = kgFactDao.getBySubject(nodeId)
        for (fact in facts) {
            contextLines.add("${fact.subject} ${fact.predicate} ${fact.obj}")
        }

        return contextLines.distinct()
    }

    // ═══════════════════════════════════════════════════════════════
    //  BULK OPERATIONS — Seed and Sync
    // ═══════════════════════════════════════════════════════════════

    /**
     * Seed the knowledge graph from existing Room data.
     * Call once during migration or on first run.
     */
    suspend fun seedFromExistingData(
        products: List<Pair<Long, String>>,     // (id, name)
        customers: List<Pair<Long, String>>,    // (id, name)
        categories: List<String>
    ) {
        Timber.d("KG: seeding from existing data (%d products, %d customers, %d categories)",
            products.size, customers.size, categories.size)

        // Create category nodes
        for (category in categories) {
            upsertNode("cat:$category", NodeType.CATEGORY, category)
        }

        // Create product nodes + category edges
        for ((id, name) in products) {
            val nodeId = "product:$id"
            upsertNode(nodeId, NodeType.PRODUCT, name)
            // Auto-categorize based on common Kenyan products
            val category = guessCategory(name)
            if (category != null) {
                addEdge(nodeId, "cat:$category", RelationType.BELONGS_TO)
            }
        }

        // Create customer nodes
        for ((id, name) in customers) {
            upsertNode("customer:$id", NodeType.CUSTOMER, name)
        }
    }

    /**
     * Infer a product category from its name (Kenyan market heuristics).
     */
    private fun guessCategory(productName: String): String? {
        val name = productName.lowercase()
        return when {
            name in listOf("nyanya", "tomato", "tomatoes") -> "Vegetables"
            name in listOf("sukuma", "kale", "spinach", "mboga") -> "Vegetables"
            name in listOf("viazi", "potato", "potatoes") -> "Vegetables"
            name in listOf("vitunguu", "onion", "onions") -> "Vegetables"
            name in listOf("maziwa", "milk") -> "Dairy"
            name in listOf("maembe", "mango", "mangoes") -> "Fruits"
            name in listOf("ndizi", "banana", "bananas") -> "Fruits"
            name in listOf("unga", "flour") -> "Staples"
            name in listOf("mchele", "rice") -> "Staples"
            name in listOf("sukari", "sugar") -> "Staples"
            name in listOf("mafuta", "oil", "cooking oil") -> "Staples"
            name in listOf("chumvi", "salt") -> "Staples"
            name in listOf("sabuni", "soap") -> "Household"
            name in listOf("detergent", "bleach") -> "Household"
            name.contains("nyama") || name.contains("meat") -> "Meat"
            name.contains("samaki") || name.contains("fish") -> "Fish"
            else -> null
        }
    }

    /**
     * Get graph statistics for diagnostics.
     */
    suspend fun getStats(): KgStats {
        return KgStats(
            nodeCount = kgNodeDao.count(),
            edgeCount = kgEdgeDao.count(),
            factCount = kgFactDao.count()
        )
    }

    /**
     * Prune old/unused entries to keep storage under control.
     */
    suspend fun prune(maxAgeDays: Int = 90) {
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        val prunedNodes = kgNodeDao.deleteOlderThan(cutoff)
        val prunedEdges = kgEdgeDao.deleteOlderThan(cutoff)
        val prunedFacts = kgFactDao.deleteOlderThan(cutoff)
        Timber.d("KG: pruned %d nodes, %d edges, %d facts older than %d days",
            prunedNodes, prunedEdges, prunedFacts, maxAgeDays)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  NODE TYPES
// ═══════════════════════════════════════════════════════════════════

enum class NodeType {
    WORKER,         // The business owner
    PRODUCT,        // Goods sold
    SUPPLIER,       // Where products come from
    CUSTOMER,       // People who buy
    CATEGORY,       // Product categories
    MARKET,         // Physical markets
    TRANSACTION,    // A recorded sale/purchase
    INSIGHT,        // Derived business insight
    PATTERN,        // Detected behavioral pattern
    PRICE_POINT     // Market price data
}

enum class RelationType {
    SELLS,          // Worker → Product
    BUYS_FROM,      // Worker → Supplier
    SUPPLIES,       // Supplier → Product
    BELONGS_TO,     // Product → Category
    PURCHASED_BY,   // Transaction → Customer
    BOUGHT,         // Customer → Product (via transaction)
    HAS_PATTERN,    // Worker → Pattern
    HAS_INSIGHT,    // Worker → Insight
    PRICED_AT,      // Product → PricePoint
    LOCATED_AT,     // Supplier → Market
    SUBCATEGORY_OF, // Category → Category
    ALTERNATIVE_TO, // Product → Product (substitutes)
    COMPLEMENTS     // Product → Product (bought together)
}

// ═══════════════════════════════════════════════════════════════════
//  DATA CLASSES (Domain Models)
// ═══════════════════════════════════════════════════════════════════

data class KgNode(
    val id: String,
    val type: NodeType,
    val label: String,
    val properties: Map<String, String> = emptyMap()
)

data class KgEdge(
    val fromId: String,
    val toId: String,
    val relation: RelationType,
    val properties: Map<String, String> = emptyMap(),
    val weight: Float = 1.0f
)

data class KgFact(
    val subject: String,
    val predicate: String,
    val obj: String,
    val confidence: Float = 1.0f,
    val source: String = "system"
)

data class NeighborhoodResult(
    val centerId: String,
    val nodes: List<KgNode>,
    val edges: List<KgEdge>,
    val hopDistances: Map<String, Int>
)

data class KgStats(
    val nodeCount: Int,
    val edgeCount: Int,
    val factCount: Int
)

// ═══════════════════════════════════════════════════════════════════
//  ROOM ENTITIES
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
) {
    fun toKgNode(gson: Gson): KgNode {
        val props: Map<String, String> = try {
            gson.fromJson(propertiesJson, object : TypeToken<Map<String, String>>() {}.type)
        } catch (_: Exception) { emptyMap() }
        return KgNode(id = id, type = NodeType.valueOf(type), label = label, properties = props)
    }
}

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
) {
    fun toKgEdge(gson: Gson): KgEdge {
        val props: Map<String, String> = try {
            gson.fromJson(propertiesJson, object : TypeToken<Map<String, String>>() {}.type)
        } catch (_: Exception) { emptyMap() }
        return KgEdge(
            fromId = fromId, toId = toId,
            relation = RelationType.valueOf(relation),
            properties = props, weight = weight
        )
    }
}

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
) {
    fun toKgFact() = KgFact(subject, predicate, obj, confidence, source)
}

// ═══════════════════════════════════════════════════════════════════
//  ROOM DAOs
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

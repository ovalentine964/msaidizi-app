package com.msaidizi.app.agent.knowledge

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.model.Trend
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.KnowledgeEdgeEntity
import com.msaidizi.app.core.database.KnowledgeNodeEntity
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.model.PatternType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-Domain Knowledge Graph — shared knowledge between agents.
 *
 * Creates a unified knowledge layer that enables pattern propagation
 * and cross-domain insight generation. When the sales agent discovers
 * that mandazi sells best on Fridays, the inventory agent can use
 * that insight to optimize restocking.
 *
 * ## Architecture
 *
 *   ┌──────────┐    ┌──────────┐    ┌──────────┐
 *   │ Sales    │    │ Finance  │    │Inventory │
 *   │ Agent    │    │ Agent    │    │ Agent    │
 *   └────┬─────┘    └────┬─────┘    └────┬─────┘
 *        │               │               │
 *        └───────────────┼───────────────┘
 *                        │
 *                ┌───────▼───────┐
 *                │  Knowledge    │
 *                │  Graph        │
 *                │  (in-memory)  │
 *                └───────┬───────┘
 *                        │
 *        ┌───────────────┼───────────────┐
 *        ▼               ▼               ▼
 *   ┌──────────┐   ┌──────────┐   ┌──────────┐
 *   │ Insights │   │ Patterns │   │ Relations│
 *   │ Store    │   │ Store    │   │ Store    │
 *   └──────────┘   └──────────┘   └──────────┘
 *
 * ## Knowledge Types
 * 1. **Facts** — Direct observations (e.g., "mandazi margin = 40%")
 * 2. **Patterns** — Learned regularities (e.g., "Friday is peak sales day")
 * 3. **Relations** — Cross-domain links (e.g., "rain → umbrella sales ↑")
 * 4. **Insights** — Derived conclusions (e.g., "restock umbrellas before rain")
 *
 * @param patternDao Legacy persistence (business patterns)
 * @param patternTracker Business pattern analysis
 * @param knowledgeDao Room DAO for graph node/edge persistence
 * @param eventBus Event bus for knowledge propagation
 */
class CrossDomainKnowledgeGraph(
    private val patternDao: PatternDao,
    private val patternTracker: BusinessPatternTracker,
    private val knowledgeDao: KnowledgeDao,
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "KnowledgeGraph"
        private const val MAX_KNOWLEDGE_NODES = 500
        private const val INSIGHT_CONFIDENCE_THRESHOLD = 0.5
    }

    // ── In-memory graph stores ────────────────────────────────────

    /** Knowledge nodes: facts, patterns, insights */
    private val nodes = ConcurrentHashMap<String, KnowledgeNode>()

    /** Relations between nodes */
    private val relations = ConcurrentHashMap<String, MutableList<KnowledgeRelation>>()

    /** Domain-specific caches for fast lookup */
    private val domainIndex = ConcurrentHashMap<DomainKey, MutableList<String>>()

    /** Generated insights */
    private val insights = ArrayDeque<CrossDomainInsight>(100)

    /** Whether the graph has been hydrated from Room */
    @Volatile
    private var hydrated = false

    init {
        // Lazy-hydrate from Room in background
        scope.launch { hydrateFromDisk() }

        // Subscribe to events to capture knowledge
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    ingestEvent(event)
                } catch (e: Throwable) {
                    Timber.w(e, "Failed to ingest event into knowledge graph")
                }
            }
        }
    }

    // ═══════════════ LAZY HYDRATION ═══════════════

    /**
     * Load the knowledge graph from Room into the in-memory cache.
     * Runs once at startup; subsequent reads hit the ConcurrentHashMap.
     */
    private suspend fun hydrateFromDisk() = withContext(Dispatchers.IO) {
        if (hydrated) return@withContext
        try {
            val (nodeEntities, edgeEntities) = knowledgeDao.loadFullGraph()

            for (entity in nodeEntities) {
                val valueMap: Map<String, String> = try {
                    json.decodeFromString(entity.valueJson)
                } catch (_: Throwable) { emptyMap() }

                val node = KnowledgeNode(
                    nodeId = entity.nodeId,
                    type = KnowledgeNodeType.valueOf(entity.nodeType),
                    domain = entity.domain,
                    key = entity.key,
                    value = valueMap,
                    confidence = entity.confidence,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
                nodes[entity.nodeId] = node
                domainIndex.getOrPut(DomainKey(entity.domain)) { mutableListOf() }.let { list ->
                    synchronized(list) { if (entity.nodeId !in list) list.add(entity.nodeId) }
                }
            }

            for (edge in edgeEntities) {
                val sharedKeys: List<String> = try {
                    json.decodeFromString(edge.sharedKeysJson)
                } catch (_: Throwable) { emptyList() }

                val relation = KnowledgeRelation(
                    relationId = "${edge.fromNode}→${edge.toNode}",
                    fromNode = edge.fromNode,
                    toNode = edge.toNode,
                    relationType = RelationType.valueOf(edge.relationType),
                    strength = edge.strength,
                    sharedKeys = sharedKeys
                )
                relations.getOrPut(edge.fromNode) { mutableListOf() }.let { list ->
                    synchronized(list) { list.add(relation) }
                }
            }

            hydrated = true
            Timber.i("Hydrated knowledge graph from Room: %d nodes, %d edges",
                nodeEntities.size, edgeEntities.size)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to hydrate knowledge graph from Room")
        }
    }

    // ═══════════════ KNOWLEDGE INGESTION ═══════════════

    /**
     * Ingest an agent event into the knowledge graph.
     */
    private fun ingestEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TransactionRecorded -> {
                addFact(
                    domain = "sales",
                    key = "last_sale_${event.item}",
                    value = mapOf(
                        "item" to event.item,
                        "amount" to event.amount.toString(),
                        "quantity" to event.quantity.toString(),
                        "unitPrice" to (event.amount / event.quantity.coerceAtLeast(1.0)).toString()
                    ),
                    confidence = 1.0
                )
            }
            is AgentEvent.PatternLearned -> {
                addPattern(
                    domain = "learning",
                    key = event.patternType,
                    pattern = event.pattern,
                    confidence = event.confidence
                )
            }
            is AgentEvent.IntelligenceGenerated -> {
                addInsight(
                    domain = "analysis",
                    key = event.analysisType,
                    insight = event.summary,
                    confidence = event.confidence
                )
            }
            else -> { /* Not a knowledge-bearing event */ }
        }
    }

    /**
     * Add a fact node to the knowledge graph.
     */
    fun addFact(
        domain: String,
        key: String,
        value: Map<String, String>,
        confidence: Double = 1.0
    ) {
        val nodeId = "$domain:$key"
        val node = KnowledgeNode(
            nodeId = nodeId,
            type = KnowledgeNodeType.FACT,
            domain = domain,
            key = key,
            value = value,
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        nodes[nodeId] = node
        domainIndex.getOrPut(DomainKey(domain)) { mutableListOf() }.let { list ->
            synchronized(list) {
                if (nodeId !in list) list.add(nodeId)
            }
        }

        // Persist to Room
        scope.launch { persistNodeToDisk(node) }

        // Check for cross-domain relations
        discoverRelations(node)

        // Prune if too many nodes
        pruneIfNeeded()
    }

    /**
     * Add a pattern node.
     */
    fun addPattern(
        domain: String,
        key: String,
        pattern: String,
        confidence: Double
    ) {
        val nodeId = "$domain:pattern:$key"
        val node = KnowledgeNode(
            nodeId = nodeId,
            type = KnowledgeNodeType.PATTERN,
            domain = domain,
            key = key,
            value = mapOf("pattern" to pattern),
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        nodes[nodeId] = node
        domainIndex.getOrPut(DomainKey(domain)) { mutableListOf() }.let { list ->
            synchronized(list) {
                if (nodeId !in list) list.add(nodeId)
            }
        }

        // Persist to Room
        scope.launch { persistNodeToDisk(node) }

        discoverRelations(node)
    }

    /**
     * Add an insight node.
     */
    fun addInsight(
        domain: String,
        key: String,
        insight: String,
        confidence: Double
    ) {
        val nodeId = "$domain:insight:$key"
        val node = KnowledgeNode(
            nodeId = nodeId,
            type = KnowledgeNodeType.INSIGHT,
            domain = domain,
            key = key,
            value = mapOf("insight" to insight),
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        nodes[nodeId] = node

        // Persist to Room
        scope.launch { persistNodeToDisk(node) }
    }

    // ═══════════════ CROSS-DOMAIN INSIGHT GENERATION ═══════════════

    /**
     * Generate cross-domain insights by analyzing relationships.
     *
     * This is the key value of the knowledge graph: connecting
     * dots across domains that individual agents can't see.
     */
    suspend fun generateInsights(): List<CrossDomainInsight> = withContext(Dispatchers.IO) {
        val newInsights = mutableListOf<CrossDomainInsight>()

        // 1. Sales + Inventory: Best sellers need restocking
        newInsights.addAll(analyzeSalesInventoryInsight())

        // 2. Sales + Finance: Profitable items to prioritize
        newInsights.addAll(analyzeSalesFinanceInsight())

        // 3. Patterns + Sales: Time-based recommendations
        newInsights.addAll(analyzeTemporalInsight())

        // Store and publish new insights
        for (insight in newInsights) {
            if (insight.confidence >= INSIGHT_CONFIDENCE_THRESHOLD) {
                synchronized(insights) {
                    if (insights.size >= 100) insights.removeFirst()
                    insights.addLast(insight)
                }

                eventBus.publish(AgentEvent.CrossDomainInsight(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "KnowledgeGraph",
                    insightType = insight.insightType,
                    domains = insight.domains,
                    summary = insight.summary,
                    confidence = insight.confidence
                ))

                Timber.d("Cross-domain insight: [%s] %s", insight.insightType, insight.summary.take(80))
            }
        }

        newInsights
    }

    /**
     * Analyze sales-inventory relationship.
     * Find items selling fast but not recently restocked.
     */
    private suspend fun analyzeSalesInventoryInsight(): List<CrossDomainInsight> {
        val insights = mutableListOf<CrossDomainInsight>()

        val products = patternTracker.analyzeProductPerformance(14)
        val topSellers = products.filter { it.isTopSeller && it.salesVelocity > 3.0 }

        for (product in topSellers) {
            // Check if there's a corresponding inventory fact
            val inventoryNode = nodes["inventory:stock_${product.item}"]
            val hasRecentRestock = nodes["sales:last_sale_${product.item}"] != null

            if (inventoryNode == null && hasRecentRestock) {
                insights.add(CrossDomainInsight(
                    insightId = UUID.randomUUID().toString(),
                    insightType = "SALES_INVENTORY_GAP",
                    domains = listOf("sales", "inventory"),
                    summary = "${product.item} is a top seller (${product.salesVelocity.toInt()}/day) " +
                            "but no stock tracking found. Consider monitoring inventory.",
                    confidence = 0.7,
                    relatedNodes = listOf("sales:last_sale_${product.item}")
                ))
            }
        }

        return insights
    }

    /**
     * Analyze sales-finance relationship.
     * Find items with high revenue but low margins.
     */
    private suspend fun analyzeSalesFinanceInsight(): List<CrossDomainInsight> {
        val insights = mutableListOf<CrossDomainInsight>()

        val margins = patternTracker.analyzeProfitMargins(30)

        // High revenue, low margin — suggest price adjustment
        val lowMarginHighRevenue = margins.filter { it.marginPercent < 15 && it.revenue > 1000 }
        for (item in lowMarginHighRevenue) {
            insights.add(CrossDomainInsight(
                insightId = UUID.randomUUID().toString(),
                insightType = "LOW_MARGIN_HIGH_VOLUME",
                domains = listOf("sales", "finance"),
                summary = "${item.item} has high revenue (KSh ${"%.0f".format(item.revenue)}) " +
                        "but low margin (${item.marginPercent.toInt()}%). Consider raising price or finding cheaper supplier.",
                confidence = 0.8,
                relatedNodes = emptyList()
            ))
        }

        // High margin items — suggest promotion
        val highMargin = margins.filter { it.marginPercent > 50 && it.transactionCount >= 3 }
        for (item in highMargin) {
            insights.add(CrossDomainInsight(
                insightId = UUID.randomUUID().toString(),
                insightType = "HIGH_MARGIN_OPPORTUNITY",
                domains = listOf("sales", "finance"),
                summary = "${item.item} has excellent margin (${item.marginPercent.toInt()}%). " +
                        "Consider promoting it more to increase profit.",
                confidence = 0.7,
                relatedNodes = emptyList()
            ))
        }

        return insights
    }

    /**
     * Analyze temporal patterns for recommendations.
     */
    private suspend fun analyzeTemporalInsight(): List<CrossDomainInsight> {
        val insights = mutableListOf<CrossDomainInsight>()

        val dayPatterns = patternTracker.analyzeDayOfWeekPatterns(4)
        val peakDays = dayPatterns.filter { it.value.isPeakDay }

        if (peakDays.isNotEmpty()) {
            val peakDayNames = peakDays.keys.joinToString(", ")
            insights.add(CrossDomainInsight(
                insightId = UUID.randomUUID().toString(),
                insightType = "TEMPORAL_PEAK",
                domains = listOf("sales", "inventory"),
                summary = "Your best sales days are $peakDayNames. " +
                        "Ensure you're fully stocked before these days.",
                confidence = peakDays.values.map { it.confidence }.average(),
                relatedNodes = emptyList()
            ))
        }

        val peakHours = patternTracker.analyzePeakHours(14)
        val topPeakHours = peakHours.filter { it.isPeakHour }.take(3)
        if (topPeakHours.isNotEmpty()) {
            val hourStr = topPeakHours.joinToString(", ") { "${it.hour}:00" }
            insights.add(CrossDomainInsight(
                insightId = UUID.randomUUID().toString(),
                insightType = "TEMPORAL_PEAK_HOURS",
                domains = listOf("sales"),
                summary = "Your busiest hours are $hourStr. " +
                        "Make sure you're available and stocked during these times.",
                confidence = topPeakHours.map { it.confidence }.average(),
                relatedNodes = emptyList()
            ))
        }

        return insights
    }

    // ═══════════════ RELATION DISCOVERY ═══════════════

    /**
     * Discover relations between a new node and existing nodes.
     */
    private fun discoverRelations(node: KnowledgeNode) {
        // Find nodes in other domains with similar keys
        for ((otherId, otherNode) in nodes) {
            if (otherNode.domain == node.domain) continue

            // Check for key overlap (e.g., same item name across domains)
            val keyWords = node.key.split("_", " ").toSet()
            val otherWords = otherNode.key.split("_", " ").toSet()
            val overlap = keyWords.intersect(otherWords)

            if (overlap.isNotEmpty()) {
                val relationId = "${node.nodeId}→${otherNode.nodeId}"
                val relation = KnowledgeRelation(
                    relationId = relationId,
                    fromNode = node.nodeId,
                    toNode = otherNode.nodeId,
                    relationType = RelationType.KEY_OVERLAP,
                    strength = overlap.size.toDouble() / maxOf(keyWords.size, otherWords.size),
                    sharedKeys = overlap.toList()
                )

                relations.getOrPut(node.nodeId) { mutableListOf() }.let { list ->
                    synchronized(list) {
                        if (list.none { it.toNode == otherNode.nodeId }) {
                            list.add(relation)
                            // Persist new edge to Room
                            scope.launch { persistEdgeToDisk(relation) }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════ QUERY API ═══════════════

    /**
     * Query knowledge by domain.
     */
    fun queryByDomain(domain: String): List<KnowledgeNode> {
        val nodeIds = domainIndex[DomainKey(domain)] ?: return emptyList()
        synchronized(nodeIds) {
            return nodeIds.mapNotNull { nodes[it] }
        }
    }

    /**
     * Query knowledge by key pattern.
     */
    fun queryByKey(keyPattern: String): List<KnowledgeNode> {
        val regex = Regex(keyPattern, RegexOption.IGNORE_CASE)
        return nodes.values.filter { regex.containsMatchIn(it.key) }
    }

    /**
     * Get related nodes for a given node.
     */
    fun getRelatedNodes(nodeId: String): List<KnowledgeRelation> {
        return relations[nodeId] ?: emptyList()
    }

    /**
     * Get all cross-domain insights.
     */
    fun getInsights(limit: Int = 20): List<CrossDomainInsight> {
        synchronized(insights) {
            return insights.toList().reversed().take(limit)
        }
    }

    /**
     * Get knowledge graph statistics.
     */
    fun getStats(): KnowledgeGraphStats {
        return KnowledgeGraphStats(
            totalNodes = nodes.size,
            factCount = nodes.values.count { it.type == KnowledgeNodeType.FACT },
            patternCount = nodes.values.count { it.type == KnowledgeNodeType.PATTERN },
            insightCount = nodes.values.count { it.type == KnowledgeNodeType.INSIGHT },
            relationCount = relations.values.sumOf { it.size },
            domainCount = domainIndex.size,
            crossDomainInsights = insights.size
        )
    }

    /**
     * Get knowledge graph context for LLM prompt injection.
     * Summarizes relevant knowledge for a given topic.
     */
    fun getContextForTopic(topic: String, maxTokens: Int = 150): String {
        val maxChars = maxTokens * 3
        val relevantNodes = queryByKey(topic).sortedByDescending { it.confidence }.take(10)

        if (relevantNodes.isEmpty()) return ""

        val context = StringBuilder()
        for (node in relevantNodes) {
            val summary = when (node.type) {
                KnowledgeNodeType.FACT -> "Fact: ${node.key} = ${node.value.values.joinToString(", ")}"
                KnowledgeNodeType.PATTERN -> "Pattern: ${node.value["pattern"] ?: node.key}"
                KnowledgeNodeType.INSIGHT -> "Insight: ${node.value["insight"] ?: node.key}"
            }
            context.appendLine(summary)
            if (context.length > maxChars) break
        }

        return context.toString().take(maxChars)
    }

    // ═══════════════ DISK PERSISTENCE ═══════════════

    /** Persist a single node to Room */
    private suspend fun persistNodeToDisk(node: KnowledgeNode) = withContext(Dispatchers.IO) {
        try {
            knowledgeDao.upsertNode(KnowledgeNodeEntity(
                nodeId = node.nodeId,
                nodeType = node.type.name,
                domain = node.domain,
                key = node.key,
                valueJson = json.encodeToString(node.value),
                confidence = node.confidence,
                createdAt = node.createdAt,
                updatedAt = node.updatedAt
            ))
        } catch (e: Throwable) {
            Timber.w(e, "Failed to persist node %s", node.nodeId)
        }
    }

    /** Persist a relation edge to Room */
    private suspend fun persistEdgeToDisk(relation: KnowledgeRelation) = withContext(Dispatchers.IO) {
        try {
            knowledgeDao.upsertEdge(KnowledgeEdgeEntity(
                fromNode = relation.fromNode,
                toNode = relation.toNode,
                relationType = relation.relationType.name,
                strength = relation.strength,
                sharedKeysJson = json.encodeToString(relation.sharedKeys)
            ))
        } catch (e: Throwable) {
            Timber.w(e, "Failed to persist edge %s→%s", relation.fromNode, relation.toNode)
        }
    }

    // ═══════════════ MAINTENANCE ═══════════════

    /**
     * Prune old/low-confidence nodes if graph is too large.
     */
    private fun pruneIfNeeded() {
        if (nodes.size <= MAX_KNOWLEDGE_NODES) return

        // Remove lowest confidence FACT nodes (keep patterns and insights)
        val factNodes = nodes.values
            .filter { it.type == KnowledgeNodeType.FACT }
            .sortedBy { it.confidence }

        val toRemove = factNodes.take(nodes.size - MAX_KNOWLEDGE_NODES)
        for (node in toRemove) {
            nodes.remove(node.nodeId)
            domainIndex[DomainKey(node.domain)]?.let { list ->
                synchronized(list) { list.remove(node.nodeId) }
            }
            relations.remove(node.nodeId)
        }

        // Also prune from Room
        scope.launch {
            try {
                knowledgeDao.deleteNodes(toRemove.map { it.nodeId })
                knowledgeDao.deleteEdgesForNodes(toRemove.map { it.nodeId })
            } catch (e: Throwable) {
                Timber.w(e, "Failed to prune nodes from Room")
            }
        }

        Timber.d("Pruned %d knowledge nodes (graph size: %d)", toRemove.size, nodes.size)
    }

    /**
     * Persist knowledge graph state to PatternDao.
     */
    suspend fun persistState() = withContext(Dispatchers.IO) {
        try {
            val state = KnowledgeGraphSerializable(
                nodeCount = nodes.size,
                relationCount = relations.values.sumOf { it.size },
                insightCount = insights.size,
                domains = domainIndex.keys.map { it.name }
            )
            val dataJson = json.encodeToString(state)
            val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, "knowledge_graph_state")
            if (existing != null) {
                patternDao.updatePattern(existing.copy(
                    data = dataJson,
                    confidence = 0.9,
                    updatedAt = System.currentTimeMillis() / 1000
                ))
            } else {
                patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                    patternType = PatternType.VOCABULARY,
                    data = dataJson,
                    confidence = 0.9
                ))
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to persist knowledge graph state")
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * A node in the knowledge graph.
 */
data class KnowledgeNode(
    val nodeId: String,
    val type: KnowledgeNodeType,
    val domain: String,
    val key: String,
    val value: Map<String, String>,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long
)

enum class KnowledgeNodeType {
    FACT,
    PATTERN,
    INSIGHT
}

/**
 * A relation between two knowledge nodes.
 */
data class KnowledgeRelation(
    val relationId: String,
    val fromNode: String,
    val toNode: String,
    val relationType: RelationType,
    val strength: Double,
    val sharedKeys: List<String> = emptyList()
)

enum class RelationType {
    KEY_OVERLAP,       // Nodes share common key terms
    TEMPORAL,          // Time-based relationship
    CAUSAL,            // One causes/affects the other
    CORRELATION        // Statistically correlated
}

/**
 * A cross-domain insight generated from the knowledge graph.
 */
data class CrossDomainInsight(
    val insightId: String,
    val insightType: String,
    val domains: List<String>,
    val summary: String,
    val confidence: Double,
    val relatedNodes: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Knowledge graph statistics.
 */
data class KnowledgeGraphStats(
    val totalNodes: Int,
    val factCount: Int,
    val patternCount: Int,
    val insightCount: Int,
    val relationCount: Int,
    val domainCount: Int,
    val crossDomainInsights: Int
)

/**
 * Domain key for indexing.
 */
private data class DomainKey(val name: String)

/**
 * Serializable state for persistence.
 */
@kotlinx.serialization.Serializable
private data class KnowledgeGraphSerializable(
    val nodeCount: Int,
    val relationCount: Int,
    val insightCount: Int,
    val domains: List<String>
)

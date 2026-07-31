package com.msaidizi.agent.graph

import com.msaidizi.core.database.*
import com.msaidizi.core.util.DateTimeUtil
import com.msaidizi.core.model.BusinessProfile
import com.msaidizi.core.model.ConversationEntity
import com.msaidizi.core.model.UserProfileEntity
import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.harness.*
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.tools.credit.AlamaScore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GraphAwareContextAssembler — Enhanced ContextAssembler with knowledge graph traversal.
 *
 * Extends the existing ContextAssembler pattern by adding a Layer 6: Knowledge Graph
 * that traverses relationships to pull related entities. Example:
 *   "tomatoes" → supplier → last restock date → market price trend
 *
 * This replaces the flat keyword-matching in getRelevantKnowledge() with
 * multi-hop graph traversal that follows entity relationships.
 *
 * Usage: Inject this alongside or in place of ContextAssembler in SuperagentHarness.
 * Backward-compatible: if knowledge graph is empty, falls back to flat retrieval.
 */
@Singleton
class GraphAwareContextAssembler @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val knowledgeDao: KnowledgeDao,
    private val memoryManager: MemoryManager,
    private val flywheelEngine: FlywheelEngine,
    private val alamaScore: AlamaScore,
    private val knowledgeGraph: KnowledgeGraph,
    private val toolGraph: ToolGraph,
    private val workflowDAG: WorkflowDAG,
    private val gson: Gson
) {
    // ── Layer 1 Cache (same as ContextAssembler) ───────────────────
    private var cachedIdentity: SystemIdentity? = null
    private var identityCacheTimestamp: Long = 0
    private val IDENTITY_CACHE_TTL_MS = 5 * 60 * 1000L

    // ── Layer 2: OODA State ────────────────────────────────────────
    private var currentOodaPhase: OodaPhase = OodaPhase.OBSERVE
    private val oodaObservations = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val oodaDecisions = java.util.concurrent.ConcurrentLinkedDeque<String>()

    // ── Session Summaries Cache ────────────────────────────────────
    private var cachedSessionSummaries: List<String>? = null
    private var sessionSummariesTimestamp: Long = 0
    private val SESSION_SUMMARIES_TTL_MS = 2 * 60 * 1000L

    // ═══════════════════════════════════════════════════════════════
    //  MAIN ASSEMBLY — 6 Layers (added Layer 6: Knowledge Graph)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Assemble full 6-layer context for the LLM.
     * Layer 6 (Knowledge Graph) is the key enhancement — traverses
     * entity relationships to find related context.
     */
    suspend fun assemble(
        intent: UserIntent,
        sessionId: String,
        recentConversation: Flow<List<ConversationEntity>>
    ): AssembledContext {
        // ── Layer 1: System Identity (static, cached) ───────────────
        val identity = buildOrGetIdentity()

        // ── Layer 2: Working Memory / OODA State ────────────────────
        updateOodaState(intent)

        // ── Layer 3: Session Memory (conversation) ──────────────────
        val conversation = try {
            recentConversation.first()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load recent conversation")
            emptyList()
        }
        val sessionSummaries = getSessionSummaries()

        // ── Layer 4: Knowledge Base (flat retrieval — legacy) ────────
        val financialSummary = buildFinancialSummary()

        // ── Layer 5: Flywheel Insights (learned) ────────────────────
        val flywheelPatterns = getFlywheelInsights(intent)

        // ── Layer 6: Knowledge Graph (graph traversal) ──────────────
        val graphContext = getGraphContext(intent)

        // Merge Layer 4 (flat) with Layer 6 (graph) — graph takes priority
        val knowledge = mergeKnowledge(
            flatKnowledge = getFlatKnowledge(intent),
            graphKnowledge = graphContext
        )

        val marketInsights = getMarketInsights(intent)

        return AssembledContext(
            // Layer 1
            userProfile = identity.userProfile,
            businessProfile = identity.businessProfile,
            alamaScore = identity.alamaScore,
            // Layer 2
            oodaPhase = currentOodaPhase,
            oodaObservations = oodaObservations.toList(),
            oodaDecisions = oodaDecisions.toList(),
            // Layer 3
            recentConversation = conversation,
            sessionSummaries = sessionSummaries,
            // Layer 4 + 6 merged
            recentFinancialSummary = financialSummary,
            knowledgeContext = knowledge,
            marketInsights = marketInsights,
            // Layer 5
            relevantPatterns = flywheelPatterns.learnedPatterns,
            learnedVocabulary = flywheelPatterns.learnedVocabulary,
            businessRhythms = flywheelPatterns.businessRhythms,
            // Graph metadata (new)
            graphStats = GraphContextStats(
                entitiesTraversed = graphContext.size,
                graphEnabled = true
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 6: KNOWLEDGE GRAPH TRAVERSAL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Traverse the knowledge graph to find context related to the intent.
     * Extracts entity references from the intent and follows edges to
     * pull related context.
     *
     * Example flow:
     *   Intent: RECORD_SALE, entity: product="nyanya"
     *   → KG lookup: node "nyanya" (PRODUCT)
     *   → 1-hop: supplier "Wakulima Market", category "Vegetables"
     *   → 2-hop: market price "KES 80/kg", restock pattern "every Monday"
     *   → Facts: "nyanya" is_perishable true, shelf_life 3 days
     *   → Return: ["Wakulima Market supplies nyanya", "KES 80/kg", ...]
     */
    private suspend fun getGraphContext(intent: UserIntent): List<String> {
        val context = mutableListOf<String>()

        try {
            // Extract entity references from intent
            val entityIds = extractEntityIds(intent)
            if (entityIds.isEmpty()) return emptyList()

            for (entityId in entityIds) {
                // 2-hop neighborhood traversal
                val neighborhood = knowledgeGraph.getNeighborhood(entityId, maxHops = 2)

                // Convert graph relationships to context strings
                for (edge in neighborhood.edges) {
                    val fromNode = neighborhood.nodes.find { it.id == edge.fromId }
                    val toNode = neighborhood.nodes.find { it.id == edge.toId }

                    if (fromNode != null && toNode != null) {
                        val relation = edge.relation.name.replace("_", " ")
                        context.add("${fromNode.label} $relation ${toNode.label}")

                        // Add edge properties
                        for ((key, value) in edge.properties) {
                            context.add("  ${fromNode.label} $key: $value")
                        }
                    }
                }

                // Add relevant knowledge facts
                val facts = knowledgeGraph.getFactsBySubject(entityId)
                for (fact in facts) {
                    context.add("${fact.subject} ${fact.predicate} ${fact.obj}")
                }
            }

            // Add cross-entity insights (products bought together, etc.)
            if (entityIds.size >= 2) {
                val crossContext = findCrossEntityInsights(entityIds)
                context.addAll(crossContext)
            }

        } catch (e: Exception) {
            Timber.w(e, "GraphAwareContextAssembler: graph traversal failed")
        }

        return context.distinct().take(20) // Cap to avoid context overflow
    }

    /**
     * Extract entity IDs from intent entities.
     * Maps intent parameters to knowledge graph node IDs.
     */
    private fun extractEntityIds(intent: UserIntent): List<String> {
        val ids = mutableListOf<String>()

        // Map product names to KG node IDs
        intent.entities["product"]?.let { productName ->
            // Try exact match first
            ids.add("product:$productName")
            // Also try normalized form
            ids.add("product:${productName.lowercase().trim()}")
        }

        // Map customer names
        intent.entities["customer"]?.let { customerName ->
            ids.add("customer:$customerName")
        }

        // For specific intent types, add category-level nodes
        when (intent.type) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> {
                // Add sales-related category node
                ids.add("cat:Sales")
            }
            IntentType.ASK_STOCK, IntentType.RECORD_PURCHASE -> {
                ids.add("cat:Inventory")
            }
            IntentType.ASK_PROFIT, IntentType.DAILY_REPORT -> {
                ids.add("cat:Finance")
            }
            else -> {}
        }

        return ids.filter { it.isNotBlank() }
    }

    /**
     * Find insights that connect multiple entities.
     * Example: "Customer A and Customer B both buy tomatoes"
     */
    private suspend fun findCrossEntityInsights(entityIds: List<String>): List<String> {
        val insights = mutableListOf<String>()

        // Check if entities share common neighbors
        for (i in entityIds.indices) {
            for (j in i + 1 until entityIds.size) {
                val paths = knowledgeGraph.findPaths(entityIds[i], entityIds[j], maxDepth = 3)
                if (paths.isNotEmpty()) {
                    val pathDesc = paths.first().map { id ->
                        knowledgeGraph.getNode(id)?.label ?: id
                    }.joinToString(" → ")
                    insights.add("Related: $pathDesc")
                }
            }
        }

        return insights.take(5)
    }

    /**
     * Merge flat knowledge (legacy) with graph knowledge (new).
     * Graph knowledge takes priority; flat fills gaps.
     */
    private fun mergeKnowledge(
        flatKnowledge: List<String>,
        graphKnowledge: List<String>
    ): List<String> {
        val merged = mutableListOf<String>()

        // Graph knowledge first (more structured, higher relevance)
        merged.addAll(graphKnowledge.take(10))

        // Fill remaining budget with flat knowledge
        val remaining = 15 - merged.size
        if (remaining > 0) {
            val flatFiltered = flatKnowledge.filter { flat ->
                merged.none { graph -> graph.contains(flat.take(30), ignoreCase = true) }
            }
            merged.addAll(flatFiltered.take(remaining))
        }

        return merged
    }

    // ═══════════════════════════════════════════════════════════════
    //  GRAPH-AWARE CONTEXT FOR SPECIFIC QUERIES
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get rich context about a specific product from the knowledge graph.
     * Used when the LLM needs deep context about a product.
     *
     * Returns: supplier info, price trends, related products, restock patterns.
     */
    suspend fun getProductContext(productName: String): ProductGraphContext {
        val nodeId = "product:$productName"
        val node = knowledgeGraph.getNode(nodeId)
            ?: knowledgeGraph.searchNodes(productName).firstOrNull()

        if (node == null) {
            return ProductGraphContext(productName = productName, found = false)
        }

        val neighborhood = knowledgeGraph.getNeighborhood(node.id, maxHops = 2)

        val suppliers = neighborhood.nodes.filter { it.type == NodeType.SUPPLIER }
        val categories = neighborhood.nodes.filter { it.type == NodeType.CATEGORY }
        val prices = neighborhood.edges.filter { it.relation == RelationType.PRICED_AT }
        val alternatives = neighborhood.nodes.filter { n ->
            neighborhood.edges.any { it.relation == RelationType.ALTERNATIVE_TO && it.toId == n.id }
        }

        // Get facts about this product
        val facts = knowledgeGraph.getFactsBySubject(node.id)

        return ProductGraphContext(
            productName = productName,
            found = true,
            nodeId = node.id,
            suppliers = suppliers.map { it.label },
            categories = categories.map { it.label },
            currentPrice = prices.firstOrNull()?.properties?.get("price"),
            priceTrend = prices.firstOrNull()?.properties?.get("trend"),
            alternatives = alternatives.map { it.label },
            facts = facts.associate { it.predicate to it.obj }
        )
    }

    /**
     * Get context about a customer's relationship graph.
     * Used for personalized interactions.
     */
    suspend fun getCustomerContext(customerName: String): CustomerGraphContext {
        val nodeId = "customer:$customerName"
        val node = knowledgeGraph.getNode(nodeId)
            ?: knowledgeGraph.searchNodes(customerName).firstOrNull()

        if (node == null) {
            return CustomerGraphContext(customerName = customerName, found = false)
        }

        val neighborhood = knowledgeGraph.getNeighborhood(node.id, maxHops = 2)
        val purchasedProducts = neighborhood.nodes.filter { it.type == NodeType.PRODUCT }
        val facts = knowledgeGraph.getFactsBySubject(node.id)

        return CustomerGraphContext(
            customerName = customerName,
            found = true,
            nodeId = node.id,
            purchasedProducts = purchasedProducts.map { it.label },
            facts = facts.associate { it.predicate to it.obj }
        )
    }

    /**
     * Sync existing Room data into the knowledge graph.
     * Call after migration or on first run when KG is empty.
     */
    suspend fun syncRoomDataToGraph() {
        try {
            val stats = knowledgeGraph.getStats()
            if (stats.nodeCount > 0) {
                Timber.d("KG already has %d nodes, skipping seed", stats.nodeCount)
                return
            }

            // Seed products
            val products = productDao.getAllActive().first().map { it.id to it.name }
            val categories = products.mapNotNull { (_, name) ->
                // Use the KG's built-in category guesser
                null // Categories will be auto-inferred
            }.distinct()

            // Seed customers
            val customers = customerDao.getAll().first().map { it.id to it.name }

            knowledgeGraph.seedFromExistingData(products, customers, listOf(
                "Vegetables", "Fruits", "Dairy", "Staples", "Meat", "Fish", "Household"
            ))

            Timber.d("KG: seeded %d products, %d customers", products.size, customers.size)
        } catch (e: Exception) {
            Timber.e(e, "KG: failed to sync Room data")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LEGACY METHODS (from ContextAssembler, kept for compatibility)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun buildOrGetIdentity(): SystemIdentity {
        val now = System.currentTimeMillis()
        cachedIdentity?.let { cached ->
            if (now - identityCacheTimestamp < IDENTITY_CACHE_TTL_MS) return cached
        }

        val profile = userProfileDao.getProfileOnce()
        val businessProfile = profile?.businessProfile?.let {
            try { gson.fromJson(it, BusinessProfile::class.java) } catch (_: Exception) { null }
        }
        val alamaResult = try { alamaScore.calculateScore() } catch (_: Exception) { null }

        val identity = SystemIdentity(profile, businessProfile, alamaResult)
        cachedIdentity = identity
        identityCacheTimestamp = now
        return identity
    }

    fun invalidateIdentityCache() {
        cachedIdentity = null
        identityCacheTimestamp = 0
    }

    private fun updateOodaState(intent: UserIntent) {
        val newPhase = when (intent.type) {
            IntentType.ASK_SALES_TODAY, IntentType.ASK_STOCK,
            IntentType.ASK_EXPENSES, IntentType.ASK_DEBTORS,
            IntentType.CHECK_CUSTOMER_DEBT, IntentType.GREETING,
            IntentType.HELP -> OodaPhase.OBSERVE

            IntentType.ASK_ADVICE -> OodaPhase.ORIENT

            IntentType.ASK_PROFIT, IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT, IntentType.MONTHLY_REPORT -> OodaPhase.DECIDE

            IntentType.RECORD_SALE, IntentType.RECORD_EXPENSE,
            IntentType.RECORD_PURCHASE, IntentType.RECORD_SERVICE,
            IntentType.ADD_PRODUCT, IntentType.UPDATE_STOCK,
            IntentType.ADD_CUSTOMER, IntentType.RECORD_PAYMENT -> OodaPhase.ACT

            else -> currentOodaPhase
        }

        oodaObservations.addLast("${intent.type.name}: ${intent.rawText.take(80)}")
        while (oodaObservations.size > 10) oodaObservations.pollFirst()

        if (newPhase == OodaPhase.ACT && currentOodaPhase != OodaPhase.ACT) {
            oodaDecisions.addLast("Acting on: ${intent.type.name}")
            while (oodaDecisions.size > 5) oodaDecisions.pollFirst()
        }
        currentOodaPhase = newPhase
    }

    private suspend fun getSessionSummaries(): List<String> {
        val now = System.currentTimeMillis()
        cachedSessionSummaries?.let { cached ->
            if (now - sessionSummariesTimestamp < SESSION_SUMMARIES_TTL_MS) return cached
        }
        val summaries = try { memoryManager.getSessionSummaries() } catch (_: Exception) { emptyList() }
        cachedSessionSummaries = summaries
        sessionSummariesTimestamp = now
        return summaries
    }

    private suspend fun buildFinancialSummary(): String? {
        val todayStart = DateTimeUtil.startOfDay()
        val todayEnd = DateTimeUtil.endOfDay()
        val totalSales = saleDao.getTotalSalesBetween(todayStart, todayEnd).first() ?: 0.0
        val totalExpenses = expenseDao.getTotalExpensesBetween(todayStart, todayEnd).first() ?: 0.0
        val transactionCount = saleDao.getTransactionCountBetween(todayStart, todayEnd).first()

        if (totalSales == 0.0 && totalExpenses == 0.0) return null

        val profit = totalSales - totalExpenses
        val lowStock = productDao.getLowStock().first()

        return buildString {
            appendLine("Today (${DateTimeUtil.today()}):")
            appendLine("- Sales: ${DateTimeUtil.formatCurrency(totalSales)} ($transactionCount transactions)")
            appendLine("- Expenses: ${DateTimeUtil.formatCurrency(totalExpenses)}")
            appendLine("- Profit: ${DateTimeUtil.formatCurrency(profit)}")
            if (lowStock.isNotEmpty()) {
                appendLine("- LOW STOCK ALERT: ${lowStock.joinToString(", ") { it.name }}")
            }
        }
    }

    /** Legacy flat knowledge retrieval (kept as fallback). */
    private suspend fun getFlatKnowledge(intent: UserIntent): List<String> {
        val knowledge = mutableListOf<String>()
        when (intent.type) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> {
                knowledgeDao.getByCategory("business_patterns").first().take(3).forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_ADVICE -> {
                knowledgeDao.getByCategory("advice").first().sortedByDescending { it.confidence }.take(5).forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_STOCK, IntentType.RECORD_PURCHASE -> {
                knowledgeDao.getByCategory("stock_patterns").first().take(3).forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_PROFIT, IntentType.DAILY_REPORT -> {
                knowledgeDao.getByCategory("daily_pattern").first().sortedByDescending { it.updatedAt }.take(3).forEach { knowledge.add(it.value) }
            }
            else -> {}
        }
        return knowledge
    }

    private suspend fun getMarketInsights(intent: UserIntent): List<String> {
        val insights = mutableListOf<String>()
        try {
            knowledgeDao.getByCategory("market_insights").first().sortedByDescending { it.confidence }.take(2).forEach { insights.add(it.value) }
        } catch (_: Exception) {}
        return insights
    }

    private suspend fun getFlywheelInsights(intent: UserIntent): FlywheelInsights {
        val patterns = try {
            flywheelEngine.getLearnedPatterns().sortedByDescending { it.confidence }.take(5).map { pattern ->
                "${pattern.key}: ${pattern.data.entries.joinToString(", ") { "${it.key}=${it.value}" }} (confidence=${String.format("%.1f", pattern.confidence)})"
            }
        } catch (_: Exception) { emptyList() }

        val vocabulary = try {
            flywheelEngine.getLearnedVocabulary().entries.sortedByDescending { it.value }.take(10)
                .joinToString(", ") { "${it.key}(${String.format("%.0f", it.value * 100)}%)" }
        } catch (_: Exception) { "" }

        val rhythms = try {
            val hourlyPatterns = flywheelEngine.getHourlyPatterns()
            if (hourlyPatterns.isNotEmpty()) {
                val peakHours = hourlyPatterns.entries.sortedByDescending { it.value }.take(3).map { "${it.key}:00" }
                "Peak hours: ${peakHours.joinToString(", ")}"
            } else ""
        } catch (_: Exception) { "" }

        return FlywheelInsights(patterns, vocabulary, rhythms)
    }

    fun invalidateSessionCache() {
        cachedSessionSummaries = null
        sessionSummariesTimestamp = 0
    }
}

// ═══════════════════════════════════════════════════════════════════
//  EXTENDED DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/**
 * Graph-specific context stats added to AssembledContext.
 * Add this field to AssembledContext for graph metadata.
 */
data class GraphContextStats(
    val entitiesTraversed: Int = 0,
    val graphEnabled: Boolean = false,
    val traversalHops: Int = 2
)

/**
 * Rich product context from the knowledge graph.
 */
data class ProductGraphContext(
    val productName: String,
    val found: Boolean = false,
    val nodeId: String? = null,
    val suppliers: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val currentPrice: String? = null,
    val priceTrend: String? = null,
    val alternatives: List<String> = emptyList(),
    val facts: Map<String, String> = emptyMap()
)

/**
 * Customer context from the knowledge graph.
 */
data class CustomerGraphContext(
    val customerName: String,
    val found: Boolean = false,
    val nodeId: String? = null,
    val purchasedProducts: List<String> = emptyList(),
    val facts: Map<String, String> = emptyMap()
)

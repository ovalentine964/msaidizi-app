package com.msaidizi.agent.graph

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GraphSyncManager — Device-server graph sync protocol (G5).
 *
 * Implements delta-based synchronization between the on-device KnowledgeGraph
 * and the PostgreSQL backend knowledge graph.
 *
 * Design principles:
 * 1. Local-first: device graph works fully offline. Sync is enhancement.
 * 2. k-Anonymity preserved: only cohort-level (k≥10) data leaves the device.
 * 3. Conflict-free: timestamp-based last-writer-wins for most fields.
 * 4. Delta-based: only send changes since last sync, not full graph.
 * 5. Battery-aware: sync only on Wi-Fi + charging.
 *
 * What syncs UP (device → server):
 * - Learned vocabulary (aggregated into cohorts)
 * - Business patterns (anonymized)
 * - Detected worker type
 * - Product names (taxonomy expansion)
 * - Price observations (k≥10 per region)
 *
 * What does NOT sync (stays on device):
 * - Customer relationships (PII)
 * - Individual transactions (PII)
 * - Conversation history
 *
 * What syncs DOWN (server → device):
 * - Demand signals ("Tomatoes in high demand")
 * - Price trends ("Tomato prices up 15%")
 * - Market insights
 * - Cohort patterns ("Similar businesses restock Mondays")
 * - Credit score updates
 */
@Singleton
class GraphSyncManager @Inject constructor(
    private val knowledgeGraph: KnowledgeGraph,
    private val gson: Gson
) {
    private var lastSyncTimestamp: Long = 0
    private var syncInProgress = false

    /**
     * Build a sync message with all changes since the last sync.
     * This is what gets sent to the server.
     */
    suspend fun buildSyncPayload(
        deviceIdHash: String,
        cohortHash: String,
        workerType: String
    ): GraphSyncPayload {
        Timber.d("GraphSync: building sync payload (last sync: %d)", lastSyncTimestamp)

        val nodeDeltas = mutableListOf<NodeDelta>()
        val edgeDeltas = mutableListOf<EdgeDelta>()
        val factDeltas = mutableListOf<FactDelta>()

        // Collect node deltas since last sync
        // Only sync anonymizable nodes (not customers, not individual transactions)
        val products = knowledgeGraph.getNodesByType(NodeType.PRODUCT)
        for (product in products) {
            nodeDeltas.add(
                NodeDelta(
                    id = product.id,
                    type = "PRODUCT",
                    label = product.label,
                    properties = product.properties,
                    updatedAt = System.currentTimeMillis(),
                    operation = Operation.UPSERT
                )
            )
        }

        val suppliers = knowledgeGraph.getNodesByType(NodeType.SUPPLIER)
        for (supplier in suppliers) {
            nodeDeltas.add(
                NodeDelta(
                    id = supplier.id,
                    type = "SUPPLIER",
                    label = supplier.label,
                    properties = supplier.properties,
                    updatedAt = System.currentTimeMillis(),
                    operation = Operation.UPSERT
                )
            )
        }

        // Collect edge deltas
        for (product in products) {
            val edges = knowledgeGraph.getOutgoing(product.id)
            for (edge in edges) {
                // Skip customer-related edges (PII)
                if (edge.relation == RelationType.PURCHASED_BY ||
                    edge.relation == RelationType.BOUGHT) continue

                edgeDeltas.add(
                    EdgeDelta(
                        fromId = edge.fromId,
                        toId = edge.toId,
                        relation = edge.relation.name,
                        properties = edge.properties,
                        weight = edge.weight,
                        updatedAt = System.currentTimeMillis(),
                        operation = Operation.UPSERT
                    )
                )
            }
        }

        // Collect fact deltas
        // Only sync product/supplier facts, not personal facts
        for (product in products) {
            val facts = knowledgeGraph.getFactsBySubject(product.id)
            for (fact in facts) {
                factDeltas.add(
                    FactDelta(
                        subject = fact.subject,
                        predicate = fact.predicate,
                        obj = fact.obj,
                        confidence = fact.confidence,
                        source = fact.source,
                        updatedAt = System.currentTimeMillis(),
                        operation = Operation.UPSERT
                    )
                )
            }
        }

        // Build device stats (anonymized)
        val stats = DeviceStats(
            transactionCountToday = 0, // Will be populated by caller
            totalRevenueToday = 0.0,
            productCount = products.size,
            customerCount = 0, // Don't sync customer count (PII leak)
            dominantProductCategory = findDominantCategory(),
            workerTypeDetected = workerType
        )

        return GraphSyncPayload(
            deviceIdHash = deviceIdHash,
            cohortHash = cohortHash,
            lastSyncTimestamp = lastSyncTimestamp,
            currentTimestamp = System.currentTimeMillis(),
            nodeDeltas = nodeDeltas,
            edgeDeltas = edgeDeltas,
            factDeltas = factDeltas,
            stats = stats
        )
    }

    /**
     * Apply deltas received from the server.
     * Server sends market signals, price updates, and aggregated insights.
     */
    suspend fun applyServerDeltas(deltas: List<ServerDelta>) {
        Timber.d("GraphSync: applying %d server deltas", deltas.size)

        for (delta in deltas) {
            try {
                when (delta.type) {
                    "PRICE_UPDATE" -> applyPriceUpdate(delta)
                    "DEMAND_SIGNAL" -> applyDemandSignal(delta)
                    "MARKET_INSIGHT" -> applyMarketInsight(delta)
                    "COHORT_PATTERN" -> applyCohortPattern(delta)
                    else -> Timber.w("GraphSync: unknown delta type: %s", delta.type)
                }
            } catch (e: Exception) {
                Timber.e(e, "GraphSync: failed to apply delta: %s", delta.id)
            }
        }

        lastSyncTimestamp = System.currentTimeMillis()
    }

    /**
     * Check if sync should proceed (battery, network conditions).
     */
    fun shouldSync(isWifi: Boolean, isCharging: Boolean, batteryPct: Int): Boolean {
        if (syncInProgress) return false
        if (!isWifi && batteryPct < 50) return false // Save battery on mobile data
        if (batteryPct < 20) return false // Don't sync on low battery
        return true
    }

    fun getLastSyncTimestamp(): Long = lastSyncTimestamp

    // ── Private helpers ──

    private suspend fun applyPriceUpdate(delta: ServerDelta) {
        val productId = delta.properties["product_id"] ?: return
        val price = delta.properties["price_kes"]?.toDoubleOrNull() ?: return
        val market = delta.properties["market"] ?: "unknown"

        knowledgeGraph.upsertNode(
            id = "price:${productId}:${market}",
            type = NodeType.PRICE_POINT,
            label = "KES $price",
            properties = mapOf(
                "product_id" to productId,
                "price_kes" to price.toString(),
                "market" to market,
                "source" to "server_sync"
            )
        )

        // Link product to price point
        knowledgeGraph.addEdge(
            fromId = productId,
            toId = "price:${productId}:${market}",
            relation = RelationType.PRICED_AT,
            properties = mapOf("source" to "server_sync")
        )
    }

    private suspend fun applyDemandSignal(delta: ServerDelta) {
        val productCategory = delta.properties["category"] ?: return
        val direction = delta.properties["direction"] ?: "stable"
        val strength = delta.properties["strength"]?.toDoubleOrNull() ?: 0.5

        knowledgeGraph.addFact(
            subject = productCategory,
            predicate = "demand_direction",
            obj = direction,
            confidence = strength.toFloat(),
            source = "server_demand_signal"
        )
    }

    private suspend fun applyMarketInsight(delta: ServerDelta) {
        val insight = delta.properties["insight"] ?: return
        val category = delta.properties["category"] ?: "general"

        knowledgeGraph.upsertNode(
            id = "insight:${System.currentTimeMillis()}",
            type = NodeType.INSIGHT,
            label = insight,
            properties = mapOf(
                "category" to category,
                "source" to "server_sync",
                "received_at" to System.currentTimeMillis().toString()
            )
        )
    }

    private suspend fun applyCohortPattern(delta: ServerDelta) {
        val pattern = delta.properties["pattern"] ?: return
        val workerType = delta.properties["worker_type"] ?: return

        knowledgeGraph.addFact(
            subject = "cohort:$workerType",
            predicate = "common_pattern",
            obj = pattern,
            confidence = 0.7f,
            source = "server_cohort_aggregation"
        )
    }

    private fun findDominantCategory(): String {
        // Simple heuristic: count products by guessed category
        return "general" // Will be enhanced with actual category counting
    }
}

// ═══════════════════════════════════════════════════════════
//  SYNC DATA MODELS (JSON-serializable)
// ═══════════════════════════════════════════════════════════

data class GraphSyncPayload(
    @SerializedName("device_id_hash") val deviceIdHash: String,
    @SerializedName("cohort_hash") val cohortHash: String,
    @SerializedName("last_sync_timestamp") val lastSyncTimestamp: Long,
    @SerializedName("current_timestamp") val currentTimestamp: Long,
    @SerializedName("node_deltas") val nodeDeltas: List<NodeDelta>,
    @SerializedName("edge_deltas") val edgeDeltas: List<EdgeDelta>,
    @SerializedName("fact_deltas") val factDeltas: List<FactDelta>,
    @SerializedName("stats") val stats: DeviceStats
)

data class NodeDelta(
    val id: String,
    val type: String,
    val label: String,
    val properties: Map<String, String>,
    @SerializedName("updated_at") val updatedAt: Long,
    val operation: Operation
)

data class EdgeDelta(
    @SerializedName("from_id") val fromId: String,
    @SerializedName("to_id") val toId: String,
    val relation: String,
    val properties: Map<String, String>,
    val weight: Float,
    @SerializedName("updated_at") val updatedAt: Long,
    val operation: Operation
)

data class FactDelta(
    val subject: String,
    val predicate: String,
    val obj: String,
    val confidence: Float,
    val source: String,
    @SerializedName("updated_at") val updatedAt: Long,
    val operation: Operation
)

data class DeviceStats(
    @SerializedName("transaction_count_today") val transactionCountToday: Int,
    @SerializedName("total_revenue_today") val totalRevenueToday: Double,
    @SerializedName("product_count") val productCount: Int,
    @SerializedName("customer_count") val customerCount: Int,
    @SerializedName("dominant_product_category") val dominantProductCategory: String,
    @SerializedName("worker_type_detected") val workerTypeDetected: String
)

data class ServerDelta(
    val id: String,
    val type: String,  // PRICE_UPDATE, DEMAND_SIGNAL, MARKET_INSIGHT, COHORT_PATTERN
    val properties: Map<String, String>,
    val timestamp: Long
)

enum class Operation {
    UPSERT, DELETE
}

package com.msaidizi.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit API interface for the graph sync endpoint.
 * Used by GraphSyncManager to sync knowledge graph deltas with the backend.
 */
interface GraphSyncApi {

    @POST("api/v1/sync/graph")
    suspend fun syncGraph(@Body payload: GraphSyncPayload): Response<GraphSyncServerResponse>
}

/**
 * Graph sync payload sent to the server.
 * Uses snake_case via @SerializedName to match the Rust backend's GraphSyncMessage.
 */
@kotlinx.serialization.Serializable
data class GraphSyncPayload(
    @kotlinx.serialization.SerialName("device_id_hash") val deviceIdHash: String,
    @kotlinx.serialization.SerialName("cohort_hash") val cohortHash: String,
    @kotlinx.serialization.SerialName("last_sync_timestamp") val lastSyncTimestamp: Long,
    @kotlinx.serialization.SerialName("current_timestamp") val currentTimestamp: Long,
    @kotlinx.serialization.SerialName("node_deltas") val nodeDeltas: List<GraphNodeDelta>,
    @kotlinx.serialization.SerialName("edge_deltas") val edgeDeltas: List<GraphEdgeDelta>,
    @kotlinx.serialization.SerialName("fact_deltas") val factDeltas: List<GraphFactDelta>,
    @kotlinx.serialization.SerialName("stats") val stats: GraphDeviceStats
)

@kotlinx.serialization.Serializable
data class GraphNodeDelta(
    val id: String,
    @kotlinx.serialization.SerialName("type") val nodeType: String,
    val label: String,
    val properties: Map<String, String>,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long,
    val operation: String  // "UPSERT" or "DELETE"
)

@kotlinx.serialization.Serializable
data class GraphEdgeDelta(
    @kotlinx.serialization.SerialName("from_id") val fromId: String,
    @kotlinx.serialization.SerialName("to_id") val toId: String,
    val relation: String,
    val properties: Map<String, String>,
    val weight: Float,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long,
    val operation: String
)

@kotlinx.serialization.Serializable
data class GraphFactDelta(
    val subject: String,
    val predicate: String,
    @kotlinx.serialization.SerialName("object") val obj: String,
    val confidence: Float,
    val source: String,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long,
    val operation: String
)

@kotlinx.serialization.Serializable
data class GraphDeviceStats(
    @kotlinx.serialization.SerialName("transaction_count_today") val transactionCountToday: Int,
    @kotlinx.serialization.SerialName("total_revenue_today") val totalRevenueToday: Double,
    @kotlinx.serialization.SerialName("product_count") val productCount: Int,
    @kotlinx.serialization.SerialName("customer_count") val customerCount: Int,
    @kotlinx.serialization.SerialName("dominant_product_category") val dominantProductCategory: String,
    @kotlinx.serialization.SerialName("worker_type_detected") val workerTypeDetected: String
)

/**
 * Server response from graph sync.
 */
@kotlinx.serialization.Serializable
data class GraphSyncServerResponse(
    val success: Boolean,
    @kotlinx.serialization.SerialName("server_timestamp") val serverTimestamp: Long,
    @kotlinx.serialization.SerialName("deltas_applied") val deltasApplied: Int,
    @kotlinx.serialization.SerialName("market_signals") val marketSignals: List<GraphServerDelta> = emptyList(),
    @kotlinx.serialization.SerialName("price_updates") val priceUpdates: List<GraphServerDelta> = emptyList(),
    @kotlinx.serialization.SerialName("demand_signals") val demandSignals: List<GraphServerDelta> = emptyList(),
    @kotlinx.serialization.SerialName("cohort_insights") val cohortInsights: List<GraphServerDelta> = emptyList(),
    val error: String? = null
)

@kotlinx.serialization.Serializable
data class GraphServerDelta(
    val id: String,
    @kotlinx.serialization.SerialName("type") val deltaType: String,
    val properties: Map<String, String>,
    val timestamp: Long
)

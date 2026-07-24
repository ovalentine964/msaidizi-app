package com.msaidizi.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Msaidizi backend API interface.
 *
 * Defines all endpoints for data sync, Alama Score, market intelligence,
 * and worker profile management. Uses Retrofit for HTTP communication.
 *
 * ## Authentication
 * All endpoints require a Bearer token (JWT) in the Authorization header.
 * Tokens are managed by JwtManager in :core:security.
 *
 * ## Offline-First
 * The app works fully offline. API calls are made only when syncing
 * data to the backend for Alama Score computation and intelligence
 * products (Soko Pulse, Distribution Intel).
 */
interface MsaidiziApi {

    // ═══ SYNC ═══

    /**
     * Upload a batch of transactions and proof points.
     * Called when internet is available and sync queue has items.
     */
    @POST("api/v1/sync/batch")
    suspend fun syncBatch(
        @Header("Authorization") token: String,
        @Body batch: BackendSyncBatch
    ): Response<SyncResponse>

    /**
     * Get sync status — what's been processed, what's pending.
     */
    @GET("api/v1/sync/status")
    suspend fun getSyncStatus(
        @Header("Authorization") token: String
    ): Response<SyncStatusResponse>

    // ═══ ALAMA SCORE ═══

    /**
     * Get the backend-computed Alama Score.
     * The backend score is authoritative for credit decisions.
     */
    @GET("api/v1/alama/score")
    suspend fun getAlamaScore(
        @Header("Authorization") token: String
    ): Response<AlamaScoreResponse>

    /**
     * Get Alama Score tier details and unlocked capabilities.
     */
    @GET("api/v1/alama/tier")
    suspend fun getAlamaTier(
        @Header("Authorization") token: String
    ): Response<AlamaTierResponse>

    // ═══ WORKER PROFILE ═══

    /**
     * Upload/update worker profile to backend.
     */
    @PUT("api/v1/worker/profile")
    suspend fun updateWorkerProfile(
        @Header("Authorization") token: String,
        @Body profile: BackendWorkerProfile
    ): Response<WorkerProfileResponse>

    /**
     * Get worker profile from backend (for sync recovery).
     */
    @GET("api/v1/worker/profile")
    suspend fun getWorkerProfile(
        @Header("Authorization") token: String
    ): Response<BackendWorkerProfile>

    // ═══ MARKET INTELLIGENCE (SOKO PULSE) ═══

    /**
     * Get market prices for a specific item and location.
     */
    @GET("api/v1/market/prices/{item}")
    suspend fun getMarketPrices(
        @Header("Authorization") token: String,
        @Path("item") item: String
    ): Response<MarketPriceResponse>

    /**
     * Get price trends for the worker's area.
     */
    @GET("api/v1/market/trends")
    suspend fun getMarketTrends(
        @Header("Authorization") token: String
    ): Response<MarketTrendsResponse>

    // ═══ AUTH ═══

    /**
     * Refresh authentication token.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<TokenResponse>
}

// ═══ REQUEST/RESPONSE MODELS ═══

data class BackendSyncBatch(
    val batchId: String,
    val workerId: String,
    val transactions: List<BackendTransactionPayload>,
    val proofPoints: List<BackendProofPoint>,
    val workerProfile: BackendWorkerProfile?,
    val deviceInfo: DeviceInfo,
    val syncTimestamp: Long
)

data class BackendTransactionPayload(
    val workerId: String,
    val transactionId: String,
    val type: String,
    val item: String,
    val category: String,
    val subcategory: String,
    val quantity: Double,
    val unit: String,
    val unitPrice: Double,
    val totalAmount: Double,
    val costBasis: Double,
    val margin: Double,
    val marginPercent: Double,
    val paymentMethod: String,
    val mpesaCode: String?,
    val locationLat: Double?,
    val locationLng: Double?,
    val locationName: String,
    val timestamp: Long,
    val timeOfDay: String,
    val dayOfWeek: Int,
    val confidence: Float,
    val verificationSource: String,
    val dataCompleteness: Float
)

data class BackendProofPoint(
    val proofType: String,
    val weight: Double,
    val timestamp: Long,
    val data: Map<String, String>
)

data class BackendWorkerProfile(
    val workerId: String,
    val name: String,
    val businessType: String,
    val businessCategory: String,
    val locationName: String,
    val region: String,
    val language: String,
    val dialect: String,
    val avgDailyRevenue: Double,
    val daysActive: Int,
    val alamaScore: Double,
    val alamaTier: String
)

data class DeviceInfo(
    val deviceId: String,
    val osVersion: String,
    val appVersion: String,
    val deviceModel: String
)

data class SyncResponse(
    val batchId: String,
    val processedCount: Int,
    val rejectedCount: Int,
    val rejectedIds: List<String>,
    val alamaScore: AlamaScoreResponse?,
    val timestamp: Long
)

data class SyncStatusResponse(
    val lastSyncAt: Long,
    val pendingCount: Int,
    val processedToday: Int
)

data class AlamaScoreResponse(
    val score: Double,
    val tier: String,
    val totalProofPoints: Int,
    val daysActive: Int,
    val consistencyStreak: Int,
    val tierProgress: Double,
    val calculatedAt: Long
)

data class AlamaTierResponse(
    val tier: String,
    val displayName: String,
    val capabilities: List<String>,
    val unlockMessage: String
)

data class WorkerProfileResponse(
    val workerId: String,
    val updatedAt: Long
)

data class MarketPriceResponse(
    val item: String,
    val location: String,
    val avgPrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val sampleSize: Int,
    val updatedAt: Long
)

data class MarketTrendsResponse(
    val location: String,
    val trends: List<PriceTrend>,
    val updatedAt: Long
)

data class PriceTrend(
    val item: String,
    val currentPrice: Double,
    val previousPrice: Double,
    val changePercent: Double,
    val direction: String // "rising", "falling", "stable"
)

data class RefreshTokenRequest(
    val refreshToken: String,
    val deviceId: String
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

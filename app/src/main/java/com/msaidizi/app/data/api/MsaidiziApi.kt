package com.msaidizi.app.data.api

import com.msaidizi.app.data.model.*
import com.msaidizi.app.ui.infrastructure.DataCenterRoadmap
import com.msaidizi.app.ui.infrastructure.WorkerContribution
import retrofit2.Response
import retrofit2.http.*

/**
 * Msaidizi API client — Retrofit interface for all backend endpoints.
 *
 * Endpoint groups:
 * - Auth: OTP-based phone authentication
 * - WhatsApp: WhatsApp Business connection
 * - AI/Agents: Chat, voice, insights
 * - Transactions: CRUD and sync
 * - Goals: Financial goal management
 * - Sync: Offline data synchronization
 * - Infrastructure: Data center roadmap
 */
interface MsaidiziApi {

    // ═══════════════════════════════════════════════════════════════
    // AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/auth/otp/request")
    suspend fun requestOtp(@Body request: OtpRequest): Response<OtpResponse>

    @POST("api/v1/auth/otp/verify")
    suspend fun verifyOtp(@Body request: OtpVerifyRequest): Response<AuthTokensResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthTokensResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Header("Authorization") token: String): Response<Unit>

    // ═══════════════════════════════════════════════════════════════
    // USER
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/users/me")
    suspend fun getCurrentUser(@Header("Authorization") token: String): Response<UserProfile>

    @PATCH("api/v1/users/me")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body request: UpdateProfileRequest
    ): Response<UserProfile>

    // ═══════════════════════════════════════════════════════════════
    // AI / AGENTS
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/ai/chat")
    suspend fun aiChat(@Body request: AiChatRequest): Response<AiChatResponse>

    @GET("api/v1/ai/chat/{conversationId}")
    suspend fun getConversation(
        @Path("conversationId") conversationId: String
    ): Response<ConversationHistoryResponse>

    @POST("api/v1/ai/voice")
    suspend fun aiVoice(@Body request: AiVoiceRequest): Response<AiChatResponse>

    @GET("api/v1/ai/insights")
    suspend fun getInsights(@Header("Authorization") token: String): Response<InsightsResponse>

    @POST("api/v1/ai/feedback")
    suspend fun submitFeedback(
        @Header("Authorization") token: String,
        @Body request: AiFeedbackRequest
    ): Response<Unit>

    // ═══════════════════════════════════════════════════════════════
    // TRANSACTIONS
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/transactions")
    suspend fun getTransactions(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("type") type: String? = null,
        @Query("from") fromDate: Long? = null,
        @Query("to") toDate: Long? = null
    ): Response<TransactionListResponse>

    @POST("api/v1/transactions")
    suspend fun createTransaction(
        @Header("Authorization") token: String,
        @Body request: CreateTransactionRequest
    ): Response<TransactionResponse>

    @GET("api/v1/transactions/summary")
    suspend fun getTransactionSummary(
        @Header("Authorization") token: String,
        @Query("period") period: String = "today"
    ): Response<TransactionSummaryResponse>

    @POST("api/v1/transactions/bulk")
    suspend fun bulkImportTransactions(
        @Header("Authorization") token: String,
        @Body request: BulkTransactionRequest
    ): Response<BulkTransactionResponse>

    // ═══════════════════════════════════════════════════════════════
    // GOALS
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/goals")
    suspend fun getGoals(@Header("Authorization") token: String): Response<GoalListResponse>

    @POST("api/v1/goals")
    suspend fun createGoal(
        @Header("Authorization") token: String,
        @Body request: CreateGoalRequest
    ): Response<GoalResponse>

    @PATCH("api/v1/goals/{id}")
    suspend fun updateGoal(
        @Header("Authorization") token: String,
        @Path("id") goalId: String,
        @Body request: UpdateGoalRequest
    ): Response<GoalResponse>

    @POST("api/v1/goals/{id}/milestone")
    suspend fun addMilestone(
        @Header("Authorization") token: String,
        @Path("id") goalId: String,
        @Body request: AddMilestoneRequest
    ): Response<GoalResponse>

    // ═══════════════════════════════════════════════════════════════
    // MARKET DATA
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/market/prices")
    suspend fun getMarketPrices(
        @Query("area") area: String? = null,
        @Query("category") category: String? = null
    ): Response<MarketPricesResponse>

    @GET("api/v1/market/prices/{commodity}")
    suspend fun getCommodityPriceHistory(
        @Path("commodity") commodity: String,
        @Query("days") days: Int = 30
    ): Response<PriceHistoryResponse>

    // ═══════════════════════════════════════════════════════════════
    // SYNC
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/sync/push")
    suspend fun syncPush(
        @Header("Authorization") token: String,
        @Body request: SyncPushRequest
    ): Response<SyncPushResponse>

    @GET("api/v1/sync/pull")
    suspend fun syncPull(
        @Header("Authorization") token: String,
        @Query("since") sinceTimestamp: Long? = null
    ): Response<SyncPullResponse>

    @GET("api/v1/sync/status")
    suspend fun getSyncStatus(
        @Header("Authorization") token: String
    ): Response<SyncStatusResponse>

    // ═══════════════════════════════════════════════════════════════
    // SMS VERIFICATION
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/sms/verify/request")
    suspend fun requestSmsVerification(@Body request: SmsVerificationRequest): Response<SmsVerificationResponse>

    @POST("api/v1/sms/verify/confirm")
    suspend fun confirmSmsVerification(@Body request: SmsVerifyCodeRequest): Response<SmsVerifyCodeResponse>

    @GET("api/v1/sms/verify/{verificationId}/status")
    suspend fun checkSmsVerificationStatus(@Path("verificationId") verificationId: String): Response<SmsVerifyCodeResponse>

    // ═══════════════════════════════════════════════════════════════
    // WHATSAPP
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/whatsapp/connect")
    suspend fun connectWhatsApp(@Body request: WhatsAppConnectRequest): Response<WhatsAppConnectResponse>

    @POST("api/v1/whatsapp/verify")
    suspend fun verifyWhatsApp(@Body request: WhatsAppVerifyRequest): Response<WhatsAppVerifyResponse>

    @GET("api/v1/whatsapp/verify/{verificationId}/status")
    suspend fun checkVerificationStatus(@Path("verificationId") verificationId: String): Response<WhatsAppVerifyResponse>

    @GET("api/v1/whatsapp/connection/{userId}")
    suspend fun getConnection(@Path("userId") userId: String): Response<WhatsAppConnection>

    @POST("api/v1/whatsapp/disconnect/{userId}")
    suspend fun disconnectWhatsApp(@Path("userId") userId: String): Response<WhatsAppConnectResponse>

    @POST("api/v1/whatsapp/send-report")
    suspend fun sendReport(@Body request: SendReportRequest): Response<SendReportResponse>

    // ═══════════════════════════════════════════════════════════════
    // INFRASTRUCTURE
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/infrastructure/roadmap")
    suspend fun getInfrastructureRoadmap(): Response<DataCenterRoadmap>

    @GET("api/v1/infrastructure/worker-value/{workerId}")
    suspend fun getWorkerValue(@Path("workerId") workerId: String): Response<WorkerContribution>

    @GET("api/v1/infrastructure/fund")
    suspend fun getInfrastructureFund(): Response<Any>

    @GET("api/v1/infrastructure/worker-value/me")
    suspend fun getWorkerContribution(): Response<WorkerContribution>
}

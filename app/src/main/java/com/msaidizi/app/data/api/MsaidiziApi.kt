package com.msaidizi.app.data.api

import com.msaidizi.app.data.model.*
import com.msaidizi.app.ui.infrastructure.DataCenterRoadmap
import com.msaidizi.app.ui.infrastructure.WorkerContribution
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface MsaidiziApi {
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

    // ---------------------------------------------------------------------------
    // Infrastructure / Data Center Roadmap
    // ---------------------------------------------------------------------------

    @GET("api/v1/infrastructure/roadmap")
    suspend fun getInfrastructureRoadmap(): Response<DataCenterRoadmap>

    @GET("api/v1/infrastructure/worker-value/{workerId}")
    suspend fun getWorkerValue(@Path("workerId") workerId: String): Response<WorkerContribution>

    @GET("api/v1/infrastructure/fund")
    suspend fun getInfrastructureFund(): Response<Any>

    // Convenience: get current worker's contribution (uses stored worker ID)
    @GET("api/v1/infrastructure/worker-value/me")
    suspend fun getWorkerContribution(): Response<WorkerContribution>
}

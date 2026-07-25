package com.msaidizi.app.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit API interface for the Msaidizi sync backend.
 */
interface SyncApi {

    @POST("api/v1/sync/anonymized")
    suspend fun syncAnonymized(@Body payload: SyncPayload): Response<SyncResponse>
}

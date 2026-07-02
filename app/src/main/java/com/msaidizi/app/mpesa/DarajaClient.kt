package com.msaidizi.app.mpesa

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import com.msaidizi.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safaricom Daraja API client for M-Pesa integration.
 *
 * Provides:
 * - OAuth token management (auto-refresh before expiry)
 * - STK Push initiation (trigger M-Pesa PIN prompt on customer phone)
 * - STK Push status query
 * - Transaction status query
 *
 * Endpoints:
 * - OAuth: GET /oauth/v1/generate?grant_type=client_credentials
 * - STK Push: POST /mpesa/stkpush/v1/processrequest
 * - STK Query: POST /mpesa/stkpushquery/v1/query
 * - Transaction Status: POST /mpesa/transactionstatus/v1/query
 *
 * Sandbox: https://sandbox.safaricom.co.ke
 * Production: https://api.safaricom.co.ke
 *
 * Free tier: Sandbox for testing, go-live requires Safaricom approval.
 * Rate limits: 10 requests/second (sandbox), varies in production.
 */
@Singleton
class DarajaClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val SANDBOX_BASE = "https://sandbox.safaricom.co.ke"
        private const val PRODUCTION_BASE = "https://api.safaricom.co.ke"

        // Passkey loaded from BuildConfig (set via env var MPESA_PASSKEY at build time).
        // NEVER commit passkeys to source control.

        // Sandbox shortcode
        private const val SHORTCODE_SANDBOX = "174379"

        // Callback URL for STK Push results
        private const val CALLBACK_URL = "https://api.msaidizi.app/v1/mpesa/callback"

        // Token refresh buffer — refresh 60s before expiry
        private const val TOKEN_REFRESH_BUFFER_MS = 60_000L

        // Date format for Daraja API timestamps
        private const val TIMESTAMP_FORMAT = "yyyyMMddHHmmss"

        // Phone number regex — must be 254XXXXXXXXX
        private val PHONE_REGEX = Regex("^254[17]\\d{8}$")
    }

    private val baseUrl: String
        get() = if (isSandbox()) SANDBOX_BASE else PRODUCTION_BASE

    // Cached OAuth token
    private var accessToken: String? = null
    private var tokenExpiry: Long = 0
    private val tokenLock = Any()

    private val dateFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US)

    // ────────────────────── OAuth ──────────────────────

    /**
     * Get OAuth access token.
     * Tokens expire after 1 hour — cached and auto-refreshed.
     *
     * @return Valid access token
     * @throws DarajaException if authentication fails
     */
    suspend fun getAccessToken(): String {
        synchronized(tokenLock) {
            val cached = accessToken
            if (cached != null && System.currentTimeMillis() < tokenExpiry) {
                return cached
            }
        }

        val credentials = getCredentials()
        val encoded = Base64.getEncoder().encodeToString(
            "${credentials.consumerKey}:${credentials.consumerSecret}".toByteArray()
        )

        try {
            val response = httpClient.get("$baseUrl/oauth/v1/generate?grant_type=client_credentials") {
                header("Authorization", "Basic $encoded")
            }

            if (response.status != HttpStatusCode.OK) {
                throw DarajaException("OAuth failed: HTTP ${response.status}")
            }

            val body = json.decodeFromString<OAuthResponse>(response.bodyAsText())

            synchronized(tokenLock) {
                accessToken = body.accessToken
                tokenExpiry = System.currentTimeMillis() +
                    (body.expiresIn.toLong() - TOKEN_REFRESH_BUFFER_MS / 1000) * 1000
            }

            Timber.d("Daraja OAuth token obtained (expires in %ss)", body.expiresIn)
            return body.accessToken

        } catch (e: DarajaException) {
            throw e
        } catch (e: Exception) {
            throw DarajaException("OAuth request failed: ${e.message}", e)
        }
    }

    // ────────────────────── STK Push ──────────────────────

    /**
     * Initiate STK Push to receive payment from customer.
     *
     * This triggers the M-Pesa PIN prompt on the customer's phone.
     * The callback URL receives the result asynchronously.
     *
     * Flow:
     * 1. App calls this method with phone + amount
     * 2. Safaricom sends PIN prompt to customer's phone
     * 3. Customer enters PIN
     * 4. Safaricom sends callback to our backend
     * 5. Backend records transaction and syncs to device
     *
     * @param phoneNumber Customer phone in 254XXXXXXXXX format
     * @param amount Amount in KES (minimum 1)
     * @param accountRef Reference (e.g., order number, "Msaidizi")
     * @param description Transaction description
     * @return StkPushResponse with checkoutRequestId for tracking
     * @throws DarajaException if the request fails
     * @throws IllegalArgumentException if phone number is invalid
     */
    suspend fun initiateStkPush(
        phoneNumber: String,
        amount: Int,
        accountRef: String,
        description: String = "Msaidizi Payment"
    ): StkPushResponse {
        require(amount >= 1) { "Amount must be at least 1 KES" }
        require(isValidPhoneNumber(phoneNumber)) {
            "Invalid phone number: $phoneNumber (expected 254XXXXXXXXX)"
        }

        val token = getAccessToken()
        val timestamp = dateFormat.format(Date())
        val password = Base64.getEncoder().encodeToString(
            "${getShortCode()}${BuildConfig.MPESA_PASSKEY}$timestamp".toByteArray()
        )

        val request = StkPushRequest(
            businessShortCode = getShortCode(),
            password = password,
            timestamp = timestamp,
            transactionType = "CustomerPayBillOnline",
            amount = amount.toString(),
            partyA = phoneNumber,
            partyB = getShortCode(),
            phoneNumber = phoneNumber,
            callBackUrl = CALLBACK_URL,
            accountReference = accountRef.take(12),  // Max 12 chars
            transactionDesc = description.take(13)     // Max 13 chars
        )

        try {
            val response = httpClient.post("$baseUrl/mpesa/stkpush/v1/processrequest") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(StkPushRequest.serializer(), request))
            }

            val body = response.bodyAsText()
            val stkResponse = json.decodeFromString<StkPushResponse>(body)

            if (stkResponse.responseCode == "0") {
                Timber.i(
                    "STK Push initiated: phone=%s, amount=%d, checkoutId=%s",
                    phoneNumber, amount, stkResponse.checkoutRequestId
                )
            } else {
                Timber.w(
                    "STK Push failed: code=%s, desc=%s",
                    stkResponse.responseCode, stkResponse.responseDescription
                )
            }

            return stkResponse

        } catch (e: Exception) {
            throw DarajaException("STK Push request failed: ${e.message}", e)
        }
    }

    /**
     * Query STK Push status.
     * Call this after timeout if callback doesn't arrive (30s+).
     *
     * @param checkoutRequestId The checkout request ID from initiateStkPush
     * @return StkQueryResponse with result status
     */
    suspend fun queryStkStatus(checkoutRequestId: String): StkQueryResponse {
        val token = getAccessToken()
        val timestamp = dateFormat.format(Date())
        val password = Base64.getEncoder().encodeToString(
            "${getShortCode()}${BuildConfig.MPESA_PASSKEY}$timestamp".toByteArray()
        )

        val request = StkQueryRequest(
            businessShortCode = getShortCode(),
            password = password,
            timestamp = timestamp,
            checkoutRequestID = checkoutRequestId
        )

        try {
            val response = httpClient.post("$baseUrl/mpesa/stkpushquery/v1/query") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(StkQueryRequest.serializer(), request))
            }

            return json.decodeFromString<StkQueryResponse>(response.bodyAsText())

        } catch (e: Exception) {
            throw DarajaException("STK Query failed: ${e.message}", e)
        }
    }

    /**
     * Poll STK Push status until completion or timeout.
     *
     * @param checkoutRequestId Checkout request ID
     * @param maxAttempts Maximum polling attempts
     * @param delayMs Delay between attempts
     * @return Final StkQueryResponse
     */
    suspend fun pollStkStatus(
        checkoutRequestId: String,
        maxAttempts: Int = 10,
        delayMs: Long = 3000
    ): StkQueryResponse {
        for (attempt in 1..maxAttempts) {
            val result = queryStkStatus(checkoutRequestId)

            when (result.resultCode) {
                "0" -> return result  // Success
                "1032" -> return result  // User cancelled
                "1037" -> {
                    // Still processing, wait and retry
                    kotlinx.coroutines.delay(delayMs)
                }
                else -> return result  // Other terminal state
            }
        }

        throw DarajaException("STK Push poll timeout after $maxAttempts attempts")
    }

    // ────────────────────── Configuration ──────────────────────

    /**
     * Get business shortcode based on environment.
     */
    private fun getShortCode(): String {
        return if (isSandbox()) {
            SHORTCODE_SANDBOX
        } else {
            // Production shortcode from config
            getProductionShortCode()
        }
    }

    private fun getProductionShortCode(): String {
        // Read from encrypted shared preferences or BuildConfig
        return try {
            val prefs = context.getSharedPreferences("mpesa_config", Context.MODE_PRIVATE)
            prefs.getString("production_shortcode", SHORTCODE_SANDBOX) ?: SHORTCODE_SANDBOX
        } catch (e: Exception) {
            SHORTCODE_SANDBOX
        }
    }

    private fun isSandbox(): Boolean {
        return try {
            val prefs = context.getSharedPreferences("mpesa_config", Context.MODE_PRIVATE)
            prefs.getBoolean("use_sandbox", true)
        } catch (e: Exception) {
            true  // Default to sandbox
        }
    }

    /**
     * Get Daraja API credentials.
     * In production: read from encrypted SharedPreferences.
     */
    private fun getCredentials(): DarajaCredentials {
        return try {
            val prefs = context.getSharedPreferences("mpesa_config", Context.MODE_PRIVATE)
            DarajaCredentials(
                consumerKey = prefs.getString("consumer_key", "") ?: "",
                consumerSecret = prefs.getString("consumer_secret", "") ?: ""
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to read Daraja credentials")
            DarajaCredentials("", "")
        }
    }

    /**
     * Validate phone number format (254XXXXXXXXX).
     */
    private fun isValidPhoneNumber(phone: String): Boolean {
        return PHONE_REGEX.matches(phone)
    }

    /**
     * Normalize phone number to 254XXXXXXXXX format.
     * Handles: 0712345678, +254712345678, 712345678
     */
    fun normalizePhoneNumber(phone: String): String {
        val digits = phone.replace(Regex("[^\\d]"), "")
        return when {
            digits.startsWith("254") && digits.length == 12 -> digits
            digits.startsWith("0") && digits.length == 10 -> "254${digits.substring(1)}"
            digits.length == 9 -> "254$digits"
            else -> digits  // Return as-is, will fail validation later
        }
    }
}

// ────────────────────── Exception ──────────────────────

/**
 * Daraja API exception with optional error code.
 */
class DarajaException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String? = null
) : Exception(message, cause)

// ────────────────────── Request/Response Models ──────────────────────

@Serializable
data class OAuthResponse(
    val accessToken: String,
    val expiresIn: String,
    val tokenType: String? = null
)

@Serializable
data class DarajaCredentials(
    val consumerKey: String,
    val consumerSecret: String
)

@Serializable
data class StkPushRequest(
    val businessShortCode: String,
    val password: String,
    val timestamp: String,
    val transactionType: String,
    val amount: String,
    val partyA: String,
    val partyB: String,
    val phoneNumber: String,
    val callBackUrl: String,
    val accountReference: String,
    val transactionDesc: String
)

@Serializable
data class StkPushResponse(
    val merchantRequestId: String? = null,
    val checkoutRequestId: String? = null,
    val responseCode: String? = null,
    val responseDescription: String? = null,
    val customerMessage: String? = null
) {
    // Whether the STK Push was successfully initiated
    val isSuccess: Boolean get() = responseCode == "0"
}

@Serializable
data class StkQueryRequest(
    val businessShortCode: String,
    val password: String,
    val timestamp: String,
    val checkoutRequestID: String
)

@Serializable
data class StkQueryResponse(
    val responseCode: String? = null,
    val responseDescription: String? = null,
    val merchantRequestId: String? = null,
    val checkoutRequestId: String? = null,
    val resultCode: String? = null,
    val resultDesc: String? = null
) {
    // Whether the payment completed successfully
    val isPaid: Boolean get() = resultCode == "0"
    // Whether the user cancelled the payment
    val isCancelled: Boolean get() = resultCode == "1032"
    // Whether the request is still being processed
    val isPending: Boolean get() = resultCode == "1037"
}

// ────────────────────── Callback Models ──────────────────────

@Serializable
data class MpesaCallbackPayload(
    val body: MpesaCallbackBody
)

@Serializable
data class MpesaCallbackBody(
    val stkCallback: StkCallback
)

@Serializable
data class StkCallback(
    val merchantRequestId: String,
    val checkoutRequestId: String,
    val resultCode: Int,
    val resultDesc: String,
    val callbackMetadata: CallbackMetadata? = null
)

@Serializable
data class CallbackMetadata(
    val item: List<CallbackItem>
)

@Serializable
data class CallbackItem(
    val name: String,
    val value: String? = null
)

/**
 * Parsed callback data with extracted fields.
 */
data class ParsedCallback(
    val merchantRequestId: String,
    val checkoutRequestId: String,
    val isSuccessful: Boolean,
    val resultDescription: String,
    val amount: Double?,
    val mpesaReceiptNumber: String?,
    val phoneNumber: String?,
    val transactionDate: String?
) {
    companion object {
        /**
         * Parse a raw Safaricom callback into structured data.
         */
        fun fromCallback(callback: StkCallback): ParsedCallback {
            val metadata = mutableMapOf<String, String>()
            callback.callbackMetadata?.item?.forEach { item ->
                item.value?.let { metadata[item.name] = it }
            }

            return ParsedCallback(
                merchantRequestId = callback.merchantRequestId,
                checkoutRequestId = callback.checkoutRequestId,
                isSuccessful = callback.resultCode == 0,
                resultDescription = callback.resultDesc,
                amount = metadata["Amount"]?.toDoubleOrNull(),
                mpesaReceiptNumber = metadata["MpesaReceiptNumber"],
                phoneNumber = metadata["PhoneNumber"],
                transactionDate = metadata["TransactionDate"]
            )
        }
    }
}

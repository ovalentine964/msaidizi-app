package com.msaidizi.app.data.model

import com.google.gson.annotations.SerializedName

// ═══════════════════════════════════════════════════════════════
// AUTH MODELS
// ═══════════════════════════════════════════════════════════════

data class OtpRequest(
    @SerializedName("phone") val phone: String
)

data class OtpResponse(
    @SerializedName("status") val status: String,
    @SerializedName("ttl") val ttl: Int = 300,
    @SerializedName("message") val message: String = ""
)

data class OtpVerifyRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("otp") val otp: String
)

data class AuthTokensResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("user") val user: UserProfile? = null,
    @SerializedName("expires_in") val expiresIn: Int = 900
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

// ═══════════════════════════════════════════════════════════════
// SMS VERIFICATION MODELS
// ═══════════════════════════════════════════════════════════════

data class SmsVerificationRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("language") val language: String = "sw"
)

data class SmsVerificationResponse(
    @SerializedName("status") val status: String,
    @SerializedName("verification_id") val verificationId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("expires_in") val expiresIn: Int = 300,
    @SerializedName("error_code") val errorCode: String?
)

data class SmsVerifyCodeRequest(
    @SerializedName("verification_id") val verificationId: String,
    @SerializedName("code") val code: String
)

data class SmsVerifyCodeResponse(
    @SerializedName("status") val status: String,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("error_code") val errorCode: String?
)

enum class SmsVerificationError(val messageSw: String) {
    INVALID_PHONE("Namba ya simu si sahihi. Tafadhali jaribu tena."),
    NETWORK_ERROR("Mtandao haupatikani. Tafadhali jaribu tena."),
    RATE_LIMIT("Umeomba mara nyingi sana. Tafadhali subiri dakika chache."),
    CODE_EXPIRED("Muda wa nambari umekwisha. Tafadhali omba nambari mpya."),
    INVALID_CODE("Nambari si sahihi. Tafadhali jaribu tena."),
    TOO_MANY_ATTEMPTS("Umekosea mara nyingi. Subiri kabla ya kujaribu tena."),
    SEND_FAILED("Imeshindikana kutuma SMS. Tafadhali jaribu tena."),
    UNKNOWN_ERROR("Kuna tatizo. Tafadhali jaribu tena baadaye.");

    companion object {
        fun fromCode(code: String?): SmsVerificationError {
            return when (code) {
                "INVALID_PHONE" -> INVALID_PHONE
                "NETWORK_ERROR" -> NETWORK_ERROR
                "RATE_LIMIT" -> RATE_LIMIT
                "CODE_EXPIRED" -> CODE_EXPIRED
                "INVALID_CODE" -> INVALID_CODE
                "TOO_MANY_ATTEMPTS" -> TOO_MANY_ATTEMPTS
                "SEND_FAILED" -> SEND_FAILED
                else -> UNKNOWN_ERROR
            }
        }
    }
}

/**
 * Delivery channel for phone verification.
 */
enum class VerificationChannel {
    SMS,
    WHATSAPP
}

// ═══════════════════════════════════════════════════════════════
// USER MODELS
// ═══════════════════════════════════════════════════════════════

data class UserProfile(
    @SerializedName("id") val id: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("name") val name: String = "",
    @SerializedName("language") val language: String = "sw",
    @SerializedName("dialect") val dialect: String = "",
    @SerializedName("business_name") val businessName: String = "",
    @SerializedName("business_type") val businessType: String = "",
    @SerializedName("onboarding_complete") val onboardingComplete: Boolean = false,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("last_active") val lastActive: Long = 0
)

data class UpdateProfileRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("language") val language: String? = null,
    @SerializedName("dialect") val dialect: String? = null,
    @SerializedName("business_name") val businessName: String? = null,
    @SerializedName("business_type") val businessType: String? = null
)

// ═══════════════════════════════════════════════════════════════
// AI / AGENT MODELS
// ═══════════════════════════════════════════════════════════════

data class AiChatRequest(
    @SerializedName("message") val message: String,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("max_tokens") val maxTokens: Int = 512,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("language") val language: String = "sw",
    @SerializedName("context") val context: Map<String, Any>? = null
)

data class AiChatResponse(
    @SerializedName("reply") val reply: String,
    @SerializedName("conversation_id") val conversationId: String = "",
    @SerializedName("agent") val agent: String = "",
    @SerializedName("confidence") val confidence: Double = 0.0,
    @SerializedName("model_used") val modelUsed: String = "",
    @SerializedName("tokens_used") val tokensUsed: Int = 0,
    @SerializedName("suggestions") val suggestions: List<String> = emptyList()
)

data class ConversationHistoryResponse(
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("messages") val messages: List<ConversationMessage>,
    @SerializedName("created_at") val createdAt: Long = 0
)

data class ConversationMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: Long = 0
)

data class AiVoiceRequest(
    @SerializedName("audio_base64") val audioBase64: String,
    @SerializedName("language") val language: String = "sw",
    @SerializedName("format") val format: String = "wav"
)

data class InsightsResponse(
    @SerializedName("insights") val insights: List<Insight>,
    @SerializedName("generated_at") val generatedAt: Long = 0
)

data class Insight(
    @SerializedName("type") val type: String,
    @SerializedName("title") val title: String,
    @SerializedName("body") val body: String,
    @SerializedName("priority") val priority: String = "normal",
    @SerializedName("action_url") val actionUrl: String? = null
)

data class AiFeedbackRequest(
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("message_id") val messageId: String,
    @SerializedName("rating") val rating: Int,
    @SerializedName("comment") val comment: String = ""
)

// ═══════════════════════════════════════════════════════════════
// TRANSACTION MODELS
// ═══════════════════════════════════════════════════════════════

data class TransactionListResponse(
    @SerializedName("transactions") val transactions: List<ApiTransaction>,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("limit") val limit: Int = 50
)

data class ApiTransaction(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("item") val item: String,
    @SerializedName("category") val category: String = "",
    @SerializedName("quantity") val quantity: Double = 1.0,
    @SerializedName("unit_price") val unitPrice: Double = 0.0,
    @SerializedName("total_amount") val totalAmount: Double,
    @SerializedName("cost_basis") val costBasis: Double = 0.0,
    @SerializedName("payment_method") val paymentMethod: String = "cash",
    @SerializedName("occurred_at") val occurredAt: Long = 0,
    @SerializedName("synced_at") val syncedAt: Long? = null
)

data class CreateTransactionRequest(
    @SerializedName("type") val type: String,
    @SerializedName("item") val item: String,
    @SerializedName("category") val category: String = "",
    @SerializedName("quantity") val quantity: Double = 1.0,
    @SerializedName("unit_price") val unitPrice: Double = 0.0,
    @SerializedName("total_amount") val totalAmount: Double,
    @SerializedName("cost_basis") val costBasis: Double = 0.0,
    @SerializedName("payment_method") val paymentMethod: String = "cash",
    @SerializedName("occurred_at") val occurredAt: Long = System.currentTimeMillis() / 1000
)

data class TransactionResponse(
    @SerializedName("transaction") val transaction: ApiTransaction,
    @SerializedName("message") val message: String = ""
)

data class TransactionSummaryResponse(
    @SerializedName("period") val period: String,
    @SerializedName("total_sales") val totalSales: Double = 0.0,
    @SerializedName("total_purchases") val totalPurchases: Double = 0.0,
    @SerializedName("total_expenses") val totalExpenses: Double = 0.0,
    @SerializedName("profit") val profit: Double = 0.0,
    @SerializedName("transaction_count") val transactionCount: Int = 0,
    @SerializedName("top_items") val topItems: List<TopItem> = emptyList()
)

data class TopItem(
    @SerializedName("item") val item: String,
    @SerializedName("total_amount") val totalAmount: Double,
    @SerializedName("quantity") val quantity: Double
)

data class BulkTransactionRequest(
    @SerializedName("transactions") val transactions: List<CreateTransactionRequest>
)

data class BulkTransactionResponse(
    @SerializedName("imported") val imported: Int = 0,
    @SerializedName("failed") val failed: Int = 0,
    @SerializedName("errors") val errors: List<String> = emptyList()
)

// ═══════════════════════════════════════════════════════════════
// GOAL MODELS
// ═══════════════════════════════════════════════════════════════

data class GoalListResponse(
    @SerializedName("goals") val goals: List<ApiGoal>
)

data class ApiGoal(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("type") val type: String,
    @SerializedName("target_amount") val targetAmount: Double,
    @SerializedName("current_amount") val currentAmount: Double = 0.0,
    @SerializedName("deadline") val deadline: Long? = null,
    @SerializedName("milestones") val milestones: List<ApiMilestone> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0
)

data class ApiMilestone(
    @SerializedName("percentage") val percentage: Double,
    @SerializedName("reached_at") val reachedAt: Long? = null
)

data class CreateGoalRequest(
    @SerializedName("title") val title: String,
    @SerializedName("type") val type: String = "savings",
    @SerializedName("target_amount") val targetAmount: Double,
    @SerializedName("deadline") val deadline: Long? = null
)

data class UpdateGoalRequest(
    @SerializedName("title") val title: String? = null,
    @SerializedName("target_amount") val targetAmount: Double? = null,
    @SerializedName("current_amount") val currentAmount: Double? = null,
    @SerializedName("deadline") val deadline: Long? = null
)

data class AddMilestoneRequest(
    @SerializedName("percentage") val percentage: Double,
    @SerializedName("reached_at") val reachedAt: Long = System.currentTimeMillis() / 1000
)

data class GoalResponse(
    @SerializedName("goal") val goal: ApiGoal,
    @SerializedName("message") val message: String = ""
)

// ═══════════════════════════════════════════════════════════════
// MARKET DATA MODELS
// ═══════════════════════════════════════════════════════════════

data class MarketPricesResponse(
    @SerializedName("prices") val prices: List<MarketPrice>,
    @SerializedName("area") val area: String = "",
    @SerializedName("updated_at") val updatedAt: Long = 0
)

data class MarketPrice(
    @SerializedName("commodity") val commodity: String,
    @SerializedName("market") val market: String,
    @SerializedName("price") val price: Double,
    @SerializedName("currency") val currency: String = "KES",
    @SerializedName("unit") val unit: String = "kg",
    @SerializedName("change_percent") val changePercent: Double = 0.0,
    @SerializedName("recorded_at") val recordedAt: Long = 0
)

data class PriceHistoryResponse(
    @SerializedName("commodity") val commodity: String,
    @SerializedName("history") val history: List<PricePoint>,
    @SerializedName("average") val average: Double = 0.0,
    @SerializedName("trend") val trend: String = "stable"
)

data class PricePoint(
    @SerializedName("price") val price: Double,
    @SerializedName("recorded_at") val recordedAt: Long
)

// ═══════════════════════════════════════════════════════════════
// SYNC MODELS
// ═══════════════════════════════════════════════════════════════

data class SyncPushRequest(
    @SerializedName("transactions") val transactions: List<CreateTransactionRequest>,
    @SerializedName("device_timestamp") val deviceTimestamp: Long = System.currentTimeMillis() / 1000,
    @SerializedName("vector_clock") val vectorClock: Map<String, Long> = emptyMap()
)

data class SyncPushResponse(
    @SerializedName("synced") val synced: Int = 0,
    @SerializedName("conflicts") val conflicts: List<SyncConflict> = emptyList(),
    @SerializedName("server_timestamp") val serverTimestamp: Long = 0
)

data class SyncConflict(
    @SerializedName("local_id") val localId: String,
    @SerializedName("server_version") val serverVersion: ApiTransaction,
    @SerializedName("resolution") val resolution: String = "server_wins"
)

data class SyncPullResponse(
    @SerializedName("changes") val changes: List<ApiTransaction>,
    @SerializedName("server_timestamp") val serverTimestamp: Long = 0,
    @SerializedName("has_more") val hasMore: Boolean = false
)

data class SyncStatusResponse(
    @SerializedName("last_sync") val lastSync: Long = 0,
    @SerializedName("pending_count") val pendingCount: Int = 0,
    @SerializedName("status") val status: String = "ok"
)

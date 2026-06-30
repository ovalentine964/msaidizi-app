package com.msaidizi.app.data.model

import com.google.gson.annotations.SerializedName

data class WhatsAppConnectRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("name") val name: String,
    @SerializedName("assistant_name") val assistantName: String,
    @SerializedName("language") val language: String = "sw",
    @SerializedName("report_time") val reportTime: String = "evening"
)

data class WhatsAppConnectResponse(
    @SerializedName("status") val status: String,
    @SerializedName("verification_id") val verificationId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("error_code") val errorCode: String?
)

data class WhatsAppVerifyRequest(
    @SerializedName("verification_id") val verificationId: String,
    @SerializedName("code") val code: String? = null
)

data class WhatsAppVerifyResponse(
    @SerializedName("status") val status: String,
    @SerializedName("whatsapp_id") val whatsappId: String?,
    @SerializedName("message") val message: String?
)

data class SendReportRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("report_type") val reportType: String,
    @SerializedName("date") val date: String? = null
)

data class SendReportResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message_id") val messageId: String?,
    @SerializedName("message") val message: String?
)

data class WhatsAppConnection(
    @SerializedName("user_id") val userId: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("connected") val connected: Boolean,
    @SerializedName("connected_at") val connectedAt: String?,
    @SerializedName("assistant_name") val assistantName: String?,
    @SerializedName("language") val language: String,
    @SerializedName("report_time") val reportTime: String,
    @SerializedName("last_report_sent") val lastReportSent: String?
)

enum class WhatsAppError(val messageSw: String) {
    NUMBER_NOT_ON_WHATSAPP("Namba hii haiko kwenye WhatsApp. Tafadhali hakikisha una WhatsApp imewashwa."),
    NETWORK_ERROR("Mtandao haupatikani. Tafadhali jaribu tena."),
    RATE_LIMIT("Umeomba mara nyingi sana. Tafadhali subiri dakika chache."),
    VERIFICATION_EXPIRED("Muda wa uthibitisho umekwisha. Tafadhali jaribu tena."),
    UNKNOWN_ERROR("Kuna tatizo. Tafadhali jaribu tena baadaye.");

    companion object {
        fun fromCode(code: String?): WhatsAppError {
            return when (code) {
                "NUMBER_NOT_ON_WHATSAPP" -> NUMBER_NOT_ON_WHATSAPP
                "NETWORK_ERROR" -> NETWORK_ERROR
                "RATE_LIMIT" -> RATE_LIMIT
                "VERIFICATION_EXPIRED" -> VERIFICATION_EXPIRED
                else -> UNKNOWN_ERROR
            }
        }
    }
}

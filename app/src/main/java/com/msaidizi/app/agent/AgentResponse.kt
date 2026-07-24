package com.msaidizi.app.agent

/**
 * Stub: Agent response data class.
 */
data class AgentResponse(
    val text: String = "",
    val type: ResponseType = ResponseType.INFORMATION,
    val shouldSpeak: Boolean = true,
    val data: Map<String, String> = emptyMap(),
    val confidence: Double = 0.0
)

enum class ResponseType {
    CONFIRMATION,
    TRANSACTION_CONFIRMATION,
    QUERY_RESULT,
    INFORMATION,
    CLARIFICATION,
    ERROR,
    GREETING,
    ADVICE
}

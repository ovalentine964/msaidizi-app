package com.msaidizi.app.agent

/**
 * Stub: Error compactor for reducing error messages.
 */
class ErrorCompactor(private val agentName: String = "superagent") {
    fun compact(error: Throwable): String = error.message ?: "Unknown error"
    fun compact(message: String): String = message.take(200)
}

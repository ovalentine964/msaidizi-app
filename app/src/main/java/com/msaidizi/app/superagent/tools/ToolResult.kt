package com.msaidizi.app.superagent.tools

import com.google.gson.Gson

/**
 * Standard result from any tool execution.
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val data: Any? = null,
    val message: String = "",
    val errorCode: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(gson: Gson): String = gson.toJson(this)

    fun toDisplayString(): String = if (success) {
        message.ifEmpty { "$toolName: OK" }
    } else {
        "Error ($toolName): ${errorCode ?: "UNKNOWN"} - $message"
    }

    companion object {
        fun success(toolName: String, data: Any? = null, message: String = "") =
            ToolResult(toolName = toolName, success = true, data = data, message = message)

        fun error(toolName: String, message: String, errorCode: String = "ERROR") =
            ToolResult(toolName = toolName, success = false, message = message, errorCode = errorCode)
    }
}

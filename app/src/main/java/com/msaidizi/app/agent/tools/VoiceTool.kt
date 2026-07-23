package com.msaidizi.app.agent.tools

/**
 * VoiceTool — Voice input/output status and control.
 * Actual voice processing is handled by VoicePipeline.
 */
class VoiceTool : Tool {
    override val name = "voice"
    override val description = "Sauti — Voice input/output"
    override val supportedIntents = listOf("voice_input", "voice_output", "speak", "listen")
    override val memoryRequiredMB = 0

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = if (language == "sw") {
                "🎤 Sauti iko tayari. Ongea tu!"
            } else {
                "🎤 Voice is ready. Just speak!"
            },
            data = mapOf("status" to "ready"),
            success = true
        )
    }

    override fun onLowMemory() {}
}

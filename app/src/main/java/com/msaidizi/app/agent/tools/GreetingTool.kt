package com.msaidizi.app.agent.tools

/**
 * GreetingTool — Handle greetings with cultural warmth.
 */
class GreetingTool : Tool {
    override val name = "greeting"
    override val description = "Salamu — Greeting with cultural warmth"
    override val supportedIntents = listOf("greeting")
    override val memoryRequiredMB = 0

    private val swahiliGreetings = listOf(
        "Habari! Mimi ni Msaidizi wako wa biashara. Niko hapa kukusaidia. 🤝",
        "Habari yako! Karibu. Leo ni siku nzuri ya biashara! ☀️",
        "Jambo! Niko hapa kukusaidia na biashara yako. Sema chochote! 💪",
        "Salama! Msaidizi wako hapa. Ungehitaji msaada gani leo? 🌟",
        "Habari za asubuhi! Leo tufanye biashara kwa bidii! 🌅",
        "Habari za jioni! Jinsi leo ilivyokuwa? Rekodi mauzo yako. 🌆"
    )

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val greeting = if (language == "sw") {
            swahiliGreetings.random()
        } else {
            "Hello! I'm Msaidizi, your business assistant. How can I help you today? 🤝"
        }

        return ToolResult(
            text = greeting,
            data = mapOf("type" to "greeting"),
            success = true
        )
    }

    override fun onLowMemory() {}
}

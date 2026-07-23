package com.msaidizi.app.agent.tools

import com.msaidizi.app.agent.LlmEngine

/**
 * AdviceTool — Generate business advice using LLM.
 * Falls back to rule-based advice when LLM is unavailable.
 */
class AdviceTool(
    private val llmEngine: LlmEngine
) : Tool {
    override val name = "advice"
    override val description = "Ushauri wa biashara — Business advice"
    override val supportedIntents = listOf("advice", "recommendation", "ask_advice")
    override val memoryRequiredMB = 15

    private val swahiliProverbs = listOf(
        "Mbio za sakafuni, huishia ukingoni. — Endelea na bidii, mafanikio yajayo.",
        "Haraka haraka haina baraka. — Subira ni muhimu katika biashara.",
        "Akili ni mali. — Tumia akili yako katika biashara yako.",
        "Penye nia pana njia. — Ukijitahidi, utafanikiwa.",
        "Maji yakimwagika hayazoleki. — Rekodi kila miamala kabla haijapita."
    )

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val topic = args["topic"]?.toString() ?: args["input"]?.toString() ?: ""
        val workerId = args["workerId"]?.toString()
            ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        // Try LLM first for personalized advice
        val context = args["_prior_results"]?.toString() ?: ""
        val llmAdvice = try {
            if (llmEngine.isModelLoaded()) {
                val prompt = buildString {
                    append("You are Msaidizi, a business advisor for informal workers in Kenya. ")
                    append("Give brief, practical advice in ${if (language == "sw") "Swahili" else "English"}. ")
                    append("Keep it to 2-3 sentences. Be encouraging and specific.\n")
                    if (context.isNotEmpty()) append("Context: $context\n")
                    append("Worker asks: $topic")
                }
                llmEngine.generate(prompt, maxTokens = 128)
            } else null
        } catch (e: Exception) {
            null
        }

        if (!llmAdvice.isNullOrBlank()) {
            return ToolResult(
                text = "💡 $llmAdvice",
                data = mapOf("source" to "llm", "topic" to topic),
                success = true
            )
        }

        // Rule-based fallback advice
        val proverb = swahiliProverbs.random()
        val advice = when {
            topic.contains(Regex("(?i)(price|bei|gharama)")) -> {
                if (language == "sw") "💡 Kuhusu bei: Linganisha bei za wachuuzi wengine. Weka bei ya ushindani lakini yenye faida."
                else "💡 About pricing: Compare with competitors. Set competitive prices but maintain profit margin."
            }
            topic.contains(Regex("(?i)(stock|inventory|bidhaa)")) -> {
                if (language == "sw") "💡 Kuhusu stock: Nunua kwa wingi bei nafuu. Angalia stock kila siku. Weka kiwango cha chini cha re-order."
                else "💡 About stock: Buy in bulk for discounts. Check stock daily. Set reorder levels."
            }
            topic.contains(Regex("(i)(customer|mteja|wateja)")) -> {
                if (language == "sw") "💡 Kuhusu wateja: Heshimu wateja wako. Toa huduma nzuri. Omba maoni yao."
                else "💡 About customers: Respect your customers. Provide good service. Ask for feedback."
            }
            topic.contains(Regex("(?i)(save|akiba|wekeza)")) -> {
                if (language == "sw") "💡 Kuhusu akiba: Weka akiba angalau 10% ya mauzo yako kila siku. Anza kidogo, ongeza taratibu."
                else "💡 About savings: Save at least 10% of daily sales. Start small, increase gradually."
            }
            else -> {
                if (language == "sw") {
                    "💡 Ushauri: Rekodi kila miamala kila siku. Angalia faida yako mara kwa mara. Nunua kwa wingi kupunguza gharama.\n\n🧠 \"$proverb\""
                } else {
                    "💡 Advice: Record every transaction daily. Check your profit regularly. Buy in bulk to reduce costs.\n\n🧠 \"$proverb\""
                }
            }
        }

        return ToolResult(
            text = advice,
            data = mapOf("source" to "rule_based", "topic" to topic),
            success = true
        )
    }

    override fun onLowMemory() {}
}

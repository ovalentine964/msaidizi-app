package com.msaidizi.agent.council

import com.msaidizi.agent.harness.LlmEngine
import com.msaidizi.agent.harness.AssembledContext
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.tools.core.ToolRegistry
import com.msaidizi.agent.tools.core.ToolResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InterCouncilDebate — Multi-council collaborative reasoning for complex advice.
 *
 * P1: When ASK_ADVICE intents span multiple councils, implements a debate pattern:
 *   1. Finance council proposes financial advice
 *   2. Market council critiques with market data
 *   3. Growth council adds growth perspective
 *   4. Final synthesis by the supervisor LLM
 *
 * This produces higher-quality advice than any single council alone.
 *
 * Example flow:
 *   User: "Should I expand my vegetable business?"
 *   Finance: "Your margins are healthy at 35%. Cash flow supports expansion."
 *   Market: "Tomato demand is rising 15% in your area. Competition is moderate."
 *   Growth: "Consider a loan — your Alama Score qualifies you for KES 50K."
 *   Synthesis: "Expand cautiously. Your finances support it, market conditions
 *               are favorable, and you qualify for a growth loan."
 */
@Singleton
class InterCouncilDebate @Inject constructor(
    private val councilManager: CouncilManager,
    private val eventBus: CouncilEventBus
) {
    companion object {
        /** Maximum number of councils that can participate in a debate. */
        const val MAX_DEBATE_PARTICIPANTS = 3

        /** Minimum confidence for a council's perspective to be included. */
        const val MIN_PERSPECTIVE_CONFIDENCE = 0.5f
    }

    /**
     * A perspective from a single council during a debate.
     */
    data class CouncilPerspective(
        val council: CouncilType,
        val analysis: String,
        val confidence: Float,
        val dataPoints: List<String>,
        val recommendation: String
    )

    /**
     * Result of an inter-council debate.
     */
    data class DebateResult(
        val perspectives: List<CouncilPerspective>,
        val synthesis: String,
        val overallConfidence: Float,
        val participatingCouncils: Set<CouncilType>
    )

    /**
     * Conduct a debate across multiple councils for a complex advice request.
     *
     * @param intent The original user intent
     * @param context The assembled context
     * @param llmEngine LLM for generating council perspectives
     * @return DebateResult with perspectives and synthesis
     */
    suspend fun conductDebate(
        intent: UserIntent,
        context: AssembledContext,
        llmEngine: LlmEngine
    ): DebateResult {
        Timber.d("InterCouncilDebate: Starting debate for %s", intent.type)

        // Determine which councils should participate
        val participatingCouncils = selectParticipatingCouncils(intent)
        val perspectives = mutableListOf<CouncilPerspective>()

        // Gather perspectives from each council
        for (council in participatingCouncils) {
            val perspective = gatherCouncilPerspective(council, intent, context, llmEngine)
            if (perspective != null && perspective.confidence >= MIN_PERSPECTIVE_CONFIDENCE) {
                perspectives.add(perspective)
            }
        }

        // Synthesize all perspectives into final advice
        val synthesis = synthesizePerspectives(perspectives, intent, context, llmEngine)

        // Publish debate event for audit
        eventBus.publish(CouncilEvent(
            type = CouncilEventType.CONTEXT_REQUEST,
            sourceCouncil = CouncilType.FINANCE,
            payload = mapOf(
                "debate_type" to intent.type.name,
                "participants" to perspectives.map { it.council.name }.joinToString(","),
                "synthesis_confidence" to synthesis.second.toString()
            )
        ))

        Timber.i("InterCouncilDebate: Completed with %d perspectives, confidence=%.2f",
            perspectives.size, synthesis.second)

        return DebateResult(
            perspectives = perspectives,
            synthesis = synthesis.first,
            overallConfidence = synthesis.second,
            participatingCouncils = participatingCouncils
        )
    }

    /**
     * Select which councils should participate in the debate.
     */
    private fun selectParticipatingCouncils(intent: UserIntent): Set<CouncilType> {
        return when (intent.type) {
            IntentType.ASK_ADVICE -> setOf(
                CouncilType.FINANCE,
                CouncilType.MARKET,
                CouncilType.GROWTH
            )
            IntentType.LOAN_COMPARE -> setOf(
                CouncilType.FINANCE,
                CouncilType.GROWTH
            )
            IntentType.INSURANCE_MATCH -> setOf(
                CouncilType.GROWTH,
                CouncilType.FINANCE
            )
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT -> setOf(
                CouncilType.FINANCE,
                CouncilType.INVENTORY,
                CouncilType.MARKET
            )
            else -> setOf(CouncilType.FINANCE)
        }
    }

    /**
     * Gather a perspective from a specific council.
     */
    private suspend fun gatherCouncilPerspective(
        council: CouncilType,
        intent: UserIntent,
        context: AssembledContext,
        llmEngine: LlmEngine
    ): CouncilPerspective? {
        return try {
            val councilPrompt = buildCouncilPrompt(council, intent, context)
            val response = llmEngine.generate(
                systemPrompt = councilPrompt,
                userMessage = intent.rawText,
                context = context,
                toolResults = emptyList(),
                intent = intent
            )

            // Parse the council's perspective
            val confidence = extractConfidence(response)
            val recommendation = extractRecommendation(response)

            CouncilPerspective(
                council = council,
                analysis = response,
                confidence = confidence,
                dataPoints = extractDataPoints(response),
                recommendation = recommendation
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to get perspective from %s council", council)
            null
        }
    }

    /**
     * Build a council-specific prompt for generating perspectives.
     */
    private fun buildCouncilPrompt(
        council: CouncilType,
        intent: UserIntent,
        context: AssembledContext
    ): String {
        return when (council) {
            CouncilType.FINANCE -> """You are the Finance Council advisor for a Kenyan MSME.
Analyze the financial aspects of this request. Focus on:
- Cash flow implications
- Profit margins and break-even
- Financial risk assessment
- Specific numbers and KES amounts
Provide a brief analysis and clear recommendation."""

            CouncilType.MARKET -> """You are the Market Intelligence Council advisor.
Analyze the market conditions for this request. Focus on:
- Current demand trends for relevant products/services
- Competitive landscape
- Pricing opportunities
- Market timing
Provide a brief analysis and clear recommendation."""

            CouncilType.GROWTH -> """You are the Growth Council advisor for a Kenyan MSME.
Analyze growth opportunities for this request. Focus on:
- Credit and financing options
- Customer acquisition strategies
- Business expansion paths
- Risk mitigation
Provide a brief analysis and clear recommendation."""

            CouncilType.INVENTORY -> """You are the Inventory Council advisor.
Analyze inventory and supply chain aspects. Focus on:
- Stock levels and restocking needs
- Spoilage and waste risks
- Supplier relationships
- Storage optimization
Provide a brief analysis and clear recommendation."""

            else -> """You are a business advisor. Analyze this request and provide
a brief analysis with specific, actionable recommendation."""
        }
    }

    /**
     * Synthesize multiple council perspectives into a final answer.
     */
    private suspend fun synthesizePerspectives(
        perspectives: List<CouncilPerspective>,
        intent: UserIntent,
        context: AssembledContext,
        llmEngine: LlmEngine
    ): Pair<String, Float> {
        if (perspectives.isEmpty()) {
            return "Samahani, sikuweza kupata ushauri wa kutosha." to 0.3f
        }

        if (perspectives.size == 1) {
            return perspectives.first().analysis to perspectives.first().confidence
        }

        // Use LLM to synthesize multiple perspectives
        val synthesisPrompt = buildString {
            appendLine("You are Msaidizi, synthesizing advice from multiple expert councils.")
            appendLine("Combine these perspectives into ONE clear, actionable response.")
            appendLine("Respond in the user's language (Swahili or English). Be concise.")
            appendLine()
            for (perspective in perspectives) {
                appendLine("=== ${perspective.council.name} Council (confidence: ${"%.1f".format(perspective.confidence)}) ===")
                appendLine(perspective.analysis.take(300))
                appendLine("Recommendation: ${perspective.recommendation}")
                appendLine()
            }
            appendLine("=== SYNTHESIS ===")
            appendLine("Provide a unified, actionable recommendation (2-3 sentences):")
        }

        return try {
            val synthesis = llmEngine.generate(
                systemPrompt = synthesisPrompt,
                userMessage = intent.rawText,
                context = context,
                toolResults = emptyList(),
                intent = intent
            )

            val avgConfidence = perspectives.map { it.confidence }.average().toFloat()
            synthesis to avgConfidence
        } catch (e: Exception) {
            Timber.w(e, "Synthesis failed, using highest-confidence perspective")
            val best = perspectives.maxByOrNull { it.confidence }
            (best?.analysis ?: "Ushauri haupatikani.") to (best?.confidence ?: 0.3f)
        }
    }

    // ── Parsing helpers ──

    private fun extractConfidence(text: String): Float {
        val confidencePattern = Regex("""confidence[:\s]*(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
        confidencePattern.find(text)?.let {
            return it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.7f
        }
        return 0.7f
    }

    private fun extractRecommendation(text: String): String {
        val recPattern = Regex("""recommendation[:\s]*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE)
        recPattern.find(text)?.let {
            return it.groupValues[1].trim()
        }
        // Take last sentence as recommendation
        val sentences = text.split(Regex("[.!?]")).filter { it.isNotBlank() }
        return sentences.lastOrNull()?.trim() ?: text.take(100)
    }

    private fun extractDataPoints(text: String): List<String> {
        val dataPoints = mutableListOf<String>()
        val numberPattern = Regex("""KES\s*[\d,]+\.?\d*|[\d]+%""")
        numberPattern.findAll(text).forEach {
            dataPoints.add(it.value)
        }
        return dataPoints.take(5)
    }
}

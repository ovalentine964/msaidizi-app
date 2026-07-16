package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import kotlinx.coroutines.*
import com.msaidizi.app.voice.LlmEngine
import com.msaidizi.app.evolution.SelfEvolutionManager
import com.msaidizi.app.loops.ReActLoop
import com.msaidizi.app.loops.ReflexionLoop
import com.msaidizi.app.loops.PlanExecuteLoop
import timber.log.Timber
import java.util.UUID

/**
 * Manages conversation memory, confidence escalation, and LLM fallback.
 *
 * Extracted from Orchestrator to handle the conversational intelligence layer:
 * - Multi-turn context tracking via [ConversationMemory]
 * - Confidence-based escalation (low → clarification, medium → confirmation)
 * - LLM escalation for complex/ambiguous queries
 * - Correction detection from previous transactions
 * - Post-processing: learning signals, reflexion critique
 *
 * ## Mathematical Foundations
 *
 * ### STA 443 §1.2 — Bayesian Confidence Escalation
 * Given intent confidence c, we define three regions:
 * - c < 0.70 → Low confidence → ask for clarification
 * - 0.70 ≤ c < 0.90 → Medium confidence → confirm with user
 * - c ≥ 0.90 → High confidence → route directly
 *
 * This implements a decision-theoretic approach: the cost of a wrong
 * classification exceeds the cost of one clarification turn.
 *
 * @see ConversationMemory for multi-turn context
 * @see IntentRouter for intent classification
 */
class ConversationManager(
    private val llmEngine: LlmEngine? = null,
    private val selfEvolution: SelfEvolutionManager? = null,
    private val adaptiveLearning: AdaptiveLearningEngine,
    private val learningAgent: LearningAgent,
    private val preferenceLearner: PreferenceLearner? = null,
    private val inferenceHarness: com.msaidizi.app.agent.harness.InferenceHarness? = null,
    private val reActLoop: ReActLoop = ReActLoop(),
    private val reflexionLoop: ReflexionLoop = ReflexionLoop(),
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    /** Hermes session manager for worker-keyed sessions and skill learning */
    val hermesSession: com.msaidizi.app.agent.hermes.HermesSessionManager? = null
) {
    /** Conversation learning pipeline — set by Orchestrator after injection */
    var conversationLearningPipeline: com.msaidizi.app.core.language.ConversationLearningPipeline? = null

    /** Current worker ID for Hermes session tracking */
    var currentWorkerId: String? = null
        private set

    /** Active Hermes trace ID */
    private var hermesTraceId: String? = null

    companion object {
        private const val CONFIDENCE_AUTO = 0.90
        private const val CONFIDENCE_CONFIRM = 0.70
    }

    /** Conversation memory for multi-turn context */
    val conversationMemory = ConversationMemory()

    /** Last response text for external access */
    var lastResponse: String = ""
        private set

    // ═══════════════ CORRECTION DETECTION ═══════════════

    /**
     * Check if the input is a correction of the last transaction.
     *
     * @return The correction response if detected, null otherwise
     */
    suspend fun checkForCorrection(
        text: String,
        language: String,
        lastTransaction: com.msaidizi.app.core.model.Transaction?
    ): AgentResponse? {
        if (lastTransaction == null) return null

        val isCorrection = adaptiveLearning.parseAndRecordCorrection(
            text = text,
            lastTransaction = lastTransaction,
            language = language
        )

        if (isCorrection) {
            selfEvolution?.recordFeatureUsage("CORRECTION")

            // Feed correction to conversation learning pipeline
            // This updates per-worker vocabulary and Bayesian ASR calibration
            try {
                conversationLearningPipeline?.recordCorrection(
                    originalText = lastTransaction?.item ?: "",
                    correctedText = text,
                    language = language
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to feed correction to learning pipeline")
            }

            val response = AgentResponse(
                text = if (language == "sw") "✅ Nimekumbuka! Nitakumbuka kwa mara ijayo."
                else "✅ Got it! I'll remember that for next time.",
                type = ResponseType.CONFIRMATION
            )
            conversationMemory.addTurn("msaidizi", response.text)
            lastResponse = response.text
            return response
        }

        return null
    }

    // ═══════════════ CONFIDENCE ESCALATION ═══════════════

    /**
     * Handle low-confidence intent classification.
     * Asks user to clarify what they meant.
     */
    fun handleLowConfidence(intentResult: IntentResult, text: String, language: String): AgentResponse {
        val intentName = when (intentResult.intent) {
            IntentType.SALE -> if (language == "sw") "mauzo" else "a sale"
            IntentType.PURCHASE -> if (language == "sw") "ununuzi" else "a purchase"
            IntentType.EXPENSE -> if (language == "sw") "matumizi" else "an expense"
            else -> if (language == "sw") "kitu" else "something"
        }
        return AgentResponse(
            text = if (language == "sw") "🤔 Sijaelewa vizuri. Unamaanisha $intentName? Sema tena kwa uwazi zaidi, mfano: 'Nimeuza mandazi kwa 500'"
            else "🤔 I'm not sure I understood. Did you mean $intentName? Please say it again more clearly, e.g. 'I sold mandazi for 500'",
            type = ResponseType.CLARIFICATION,
            data = mapOf(
                "originalText" to text,
                "detectedIntent" to intentResult.intent.name,
                "confidence" to intentResult.confidence.toString()
            )
        )
    }

    /**
     * Handle medium-confidence intent classification.
     * Confirms detected intent with user before proceeding.
     */
    fun handleMediumConfidence(intentResult: IntentResult, text: String, language: String): AgentResponse {
        val item = intentResult.extractedData["item"] ?: ""
        val amount = intentResult.extractedData["amount"] ?: ""
        val confirmationText = when (intentResult.intent) {
            IntentType.SALE -> if (language == "sw") "🤔 Unamaanisha umefanya mauzo ya ${item.ifBlank { "bidhaa" }}" + if (amount.isNotBlank()) " kwa KSh $amount?" else "? Sema 'ndio' au 'hapana'."
            else "🤔 Did you record a sale of ${item.ifBlank { "an item" }}" + if (amount.isNotBlank()) " for KSh $amount?" else "? Say 'yes' or 'no'."
            IntentType.PURCHASE -> if (language == "sw") "🤔 Unamaanisha umenunua ${item.ifBlank { "bidhaa" }}" + if (amount.isNotBlank()) " kwa KSh $amount?" else "? Sema 'ndio' au 'hapana'."
            else "🤔 Did you buy ${item.ifBlank { "an item" }}" + if (amount.isNotBlank()) " for KSh $amount?" else "? Say 'yes' or 'no'."
            IntentType.EXPENSE -> if (language == "sw") "🤔 Unamaanisha umetumia KSh $amount kwa ${item.ifBlank { "matumizi" }}? Sema 'ndio' au 'hapana'."
            else "🤔 Did you spend KSh $amount on ${item.ifBlank { "an expense" }}? Say 'yes' or 'no'."
            else -> if (language == "sw") "🤔 Sijaelewa vizuri. Sema tena kwa uwazi zaidi." else "🤔 I'm not sure. Please say it again more clearly."
        }
        return AgentResponse(
            text = confirmationText,
            type = ResponseType.CLARIFICATION,
            data = mapOf(
                "originalText" to text,
                "detectedIntent" to intentResult.intent.name,
                "confidence" to intentResult.confidence.toString(),
                "item" to item,
                "amount" to amount
            )
        )
    }

    // ═══════════════ LLM ESCALATION ═══════════════

    /**
     * Escalate to on-device LLM for complex queries.
     * Falls back to handler routing if LLM fails.
     *
     * @return LLM response, or null if LLM unavailable/failed (caller should route to handler)
     */
    suspend fun handleLlmEscalation(
        intentResult: IntentResult,
        text: String,
        language: String
    ): AgentResponse? {
        val engine = llmEngine ?: return null
        if (!engine.isModelLoaded()) return null

        // Build LLM context
        val context = buildString {
            val memCtx = conversationMemory.getContext()
            if (memCtx.lastItem != null) append("Bidhaa ya mwisho: ${memCtx.lastItem}. ")
            if (memCtx.lastAmount != null) append("Bei ya mwisho: KSh ${"%.0f".format(memCtx.lastAmount)}. ")
            if (memCtx.topicChain.isNotEmpty()) append("Mada: ${memCtx.topicChain.joinToString(", ")}. ")
        }

        // Route through InferenceHarness if available (timeout, retry, circuit breaker, quality gate)
        val harness = inferenceHarness
        val llmResponse = if (harness != null) {
            try {
                val result = harness.execute(
                    config = com.msaidizi.app.agent.harness.HarnessConfig(
                        timeoutMs = 15_000L,
                        maxRetries = 2
                    ),
                    providers = listOf(
                        com.msaidizi.app.agent.harness.ProviderCandidate(
                            providerId = "on-device-llm",
                            modelId = "gemma-4-e2b",  // Primary model (promoted 2026-07-16)
                            provider = {
                                engine.generateResponse(
                                    userInput = text, context = context, language = language
                                )
                            },
                            confidenceExtractor = { response: String ->
                                if (response.isBlank()) 0.0 else 0.8
                            }
                        )
                    ),
                    taskType = "llm:escalation",
                    qualityThreshold = 0.5
                )
                result.value
            } catch (e: Exception) {
                Timber.w(e, "Harness-wrapped LLM escalation failed, falling back to direct call")
                null
            }
        } else {
            // Legacy path: direct LLM call without harness
            try {
                engine.generateResponse(
                    userInput = text, context = context, language = language
                )
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM during LLM escalation")
                engine.unloadModel()
                null
            } catch (e: Exception) {
                Timber.e(e, "LLM escalation failed")
                null
            }
        }

        return if (!llmResponse.isNullOrBlank()) {
            AgentResponse(
                text = llmResponse,
                type = when (intentResult.intent) {
                    IntentType.ASK_ADVICE -> ResponseType.ADVICE
                    IntentType.CORRECTION -> ResponseType.CONFIRMATION
                    else -> ResponseType.INFORMATION
                },
                data = mapOf("source" to "llm", "intent" to intentResult.intent.name)
            )
        } else {
            Timber.w("LLM returned empty response, falling back")
            null
        }
    }

    // ═══════════════ POST-PROCESSING ═══════════════

    // ═══════════════ HERMES SESSION MANAGEMENT ═══════════════

    /**
     * Initialize the Hermes session for a worker.
     * Call this before processing input to set up worker-keyed tracking.
     *
     * @param workerId The worker's unique identifier
     * @param channel The current channel (app, whatsapp, ussd)
     */
    fun initHermesSession(workerId: String, channel: String = "app") {
        currentWorkerId = workerId
        hermesSession?.getOrCreateSession(workerId, channel)
    }

    /**
     * Discover relevant skills from Hermes before processing.
     * Returns skills that match the current query.
     */
    fun discoverHermesSkills(text: String): List<com.msaidizi.app.agent.hermes.LearnedSkill> {
        val workerId = currentWorkerId ?: return emptyList()
        return hermesSession?.discoverSkills(workerId, text) ?: emptyList()
    }

    /**
     * Start a Hermes interaction trace.
     * Call before processing to enable skill generation.
     */
    fun startHermesTrace(text: String): String? {
        val workerId = currentWorkerId ?: return null
        val traceId = hermesSession?.startTrace(workerId, text)
        hermesTraceId = traceId
        return traceId
    }

    /**
     * Complete the Hermes interaction trace.
     * May generate a new skill if the interaction was complex + successful.
     *
     * @return A LearnedSkill if generated, null otherwise
     */
    fun completeHermesTrace(
        response: String,
        outcome: String = "success"
    ): com.msaidizi.app.agent.hermes.LearnedSkill? {
        val workerId = currentWorkerId ?: return null
        val traceId = hermesTraceId ?: return null
        val skill = hermesSession?.completeInteraction(workerId, traceId, response, outcome)
        hermesTraceId = null
        return skill
    }

    /**
     * Record feedback on a Hermes skill.
     */
    fun recordHermesFeedback(skillId: String, success: Boolean, feedback: String? = null) {
        val workerId = currentWorkerId ?: return
        hermesSession?.recordFeedback(workerId, skillId, success, feedback)
    }

    // ═══════════════ POST-PROCESSING ═══════════════

    /**
     * Perform post-processing after response generation.
     * Updates conversation memory, learning signals, and reflexion critique.
     *
     * @return The reflexion critique score (for logging/monitoring)
     */
    suspend fun postProcess(
        inputText: String,
        intentResult: IntentResult,
        response: AgentResponse,
        language: String,
        trace: com.msaidizi.app.loops.ReActTrace? = null
    ): Double {
        // Update conversation memory
        conversationMemory.addTurn("worker", inputText, intentResult, intentResult.extractedData)
        conversationMemory.addTurn("msaidizi", response.text)

        // Learning signals
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf("input" to inputText, "intent" to intentResult.intent.name, "language" to language)
        )
        selfEvolution?.recordFeatureUsage(intentResult.intent.name)
        preferenceLearner?.learnResponseStyle(
            response.text.length,
            response.text.contains(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]")),
            response.type == ResponseType.CONFIRMATION
        )

        // Reflexion critique
        val critique = reflexionLoop.critiqueResponse(response.text, language)
        if (trace != null) {
            reActLoop.observe(
                trace,
                "Response generated (${response.text.length} chars), Reflexion score: ${String.format("%.2f", critique.score)}",
                critique.score
            )
            if (critique.shouldRetry) {
                reActLoop.reflect(
                    trace,
                    "Quality below threshold (${String.format("%.2f", critique.score)}). Issues: ${critique.issues}.",
                    critique.score
                )
            } else {
                reActLoop.reflect(
                    trace,
                    "Quality acceptable (${String.format("%.2f", critique.score)}).",
                    critique.score
                )
            }
        }

        // Feed critique to SelfEvolutionManager for learning
        feedCritiqueToEvolution(critique, intentResult.intent.name, language)

        // Record Hermes trace step
        hermesTraceId?.let { traceId ->
            currentWorkerId?.let { workerId ->
                hermesSession?.recordTraceStep(
                    workerId = workerId,
                    traceId = traceId,
                    action = "response_generated",
                    toolUsed = intentResult.intent.name,
                    success = critique.score >= 0.5
                )
            }
        }

        lastResponse = response.text
        return critique.score
    }

    // ═══════════════ CRITIQUE → EVOLUTION FEEDBACK LOOP ═══════════════

    /**
     * Feed ReflexionLoop critique scores and issues to SelfEvolutionManager.
     * This closes the learning loop: critique → pattern → improvement.
     *
     * When the reflexion loop identifies quality issues, the evolution
     * manager learns from them to improve future responses.
     */
    private fun feedCritiqueToEvolution(
        critique: com.msaidizi.app.loops.Critique,
        intentName: String,
        language: String
    ) {
        val evolution = selfEvolution ?: return

        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Record quality signal based on critique score
                val signal = when {
                    critique.score >= 0.8 -> com.msaidizi.app.evolution.SatisfactionSignal.POSITIVE
                    critique.score >= 0.5 -> com.msaidizi.app.evolution.SatisfactionSignal.NEUTRAL
                    else -> com.msaidizi.app.evolution.SatisfactionSignal.NEGATIVE
                }
                evolution.recordSatisfactionSignal(
                    adviceId = "reflexion_${intentName}_${System.currentTimeMillis()}",
                    signal = signal,
                    context = "score=${critique.score}, issues=${critique.issues.joinToString(",")}"
                )

                // If critique found language-specific issues, record for vocabulary learning
                if (critique.issues.any { it.contains("language", ignoreCase = true) ||
                        it.contains("swahili", ignoreCase = true) }) {
                    evolution.recordLanguageSignal(language)
                }

                // Track advice effectiveness if critique suggests response quality
                if (critique.suggestions.isNotEmpty()) {
                    evolution.recordFeatureUsage("REFLEXION_FEEDBACK_${intentName}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to feed critique to evolution manager")
            }
        }
    }

    // ═══════════════ SELF-EVOLUTION SIGNALS ═══════════════

    /**
     * Record self-evolution signals from user input.
     * Called at the start of processing before intent classification.
     */
    suspend fun recordEvolutionSignals(language: String) {
        selfEvolution?.recordLanguageSignal(language)
        selfEvolution?.recordTimeSignal(java.time.LocalTime.now().hour)
        preferenceLearner?.learnLanguagePreference(language, "")
        preferenceLearner?.learnInteractionTiming(
            java.time.LocalTime.now().hour,
            java.time.LocalDate.now().dayOfWeek.value - 1
        )
    }

    /**
     * Apply self-evolution text corrections and vocabulary enhancement.
     *
     * @return The enhanced text
     */
    suspend fun enhanceText(text: String): String {
        val evolvedText = selfEvolution?.applyCorrectionPatterns(text) ?: text
        return evolvedText // adaptiveVocabulary is applied at Orchestrator level before this
    }

    // ═══════════════ CONFIDENCE ROUTING ═══════════════

    /**
     * Determine the confidence level category for an intent result.
     *
     * @return The confidence category for routing decisions
     */
    fun classifyConfidence(intentResult: IntentResult): ConfidenceLevel {
        val exemptIntents = setOf(IntentType.UNKNOWN, IntentType.GREETING, IntentType.HELP)
        if (intentResult.intent in exemptIntents) return ConfidenceLevel.HIGH

        return when {
            intentResult.confidence < CONFIDENCE_CONFIRM -> ConfidenceLevel.LOW
            intentResult.confidence < CONFIDENCE_AUTO -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.HIGH
        }
    }

    // ═══════════════ CONVERSATION ACCESS ═══════════════

    /** Clear conversation memory */
    fun clearConversationMemory() = conversationMemory.clear()

    // ═══════════════ EVENT PUBLISHING ═══════════════

    /**
     * Publish intent classification event to the event bus.
     */
    fun publishIntentEvent(intentResult: IntentResult, language: String, rawText: String) {
        eventBus.publish(
            AgentEvent.IntentClassified(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "IntentRouter",
                intent = intentResult.intent.name,
                confidence = intentResult.confidence,
                extractedData = intentResult.extractedData,
                language = language,
                rawText = rawText
            )
        )
    }

    /**
     * Publish task started event.
     */
    fun publishTaskStarted() {
        eventBus.publish(
            AgentEvent.AgentTaskStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "Orchestrator",
                taskType = "process_input",
                agentName = "Orchestrator"
            )
        )
    }

    /**
     * Publish task completed event.
     */
    fun publishTaskCompleted(trace: com.msaidizi.app.loops.ReActTrace, response: AgentResponse) {
        eventBus.publish(
            AgentEvent.AgentTaskCompleted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "Orchestrator",
                taskType = "process_input",
                agentName = "Orchestrator",
                durationMs = System.currentTimeMillis() - trace.startedAt,
                resultSummary = response.text.take(100)
            )
        )
    }
}

/**
 * Confidence level categories for routing decisions.
 */
enum class ConfidenceLevel {
    /** Below 0.70 — ask user to clarify */
    LOW,
    /** 0.70–0.89 — confirm with user before proceeding */
    MEDIUM,
    /** 0.90+ — route directly to handler */
    HIGH
}

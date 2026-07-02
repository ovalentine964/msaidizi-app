package com.msaidizi.app.onboarding

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.model.DialectRegion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Msaidizi Bootstrap — First conversation with worker.
 *
 * Inspired by OpenClaw's bootstrap concept: establish relationship
 * before doing work. The worker and Msaidizi get to know each other.
 *
 * This is NOT a form to fill out. It's a conversation.
 * Msaidizi introduces itself as a CFO, learns the worker's name,
 * their business, and lets the worker name *it* — creating ownership.
 *
 * Flow:
 * 1. Msaidizi introduces itself as CFO
 * 2. Asks worker's name
 * 3. Asks about business type
 * 4. Worker names Msaidizi (personalization)
 * 5. Msaidizi explains what it can do
 * 6. First transaction recorded together
 *
 * Academic foundations:
 * - STA 343 (Experimental Design): A/B test different introduction styles
 * - BCB 108 (Business Communication): 7Cs principle for clear messaging
 * - ECO 206 (Microfinance): Trust-building through personalization
 * - PSY 101 (Behavioral): Naming creates psychological ownership (Kahneman)
 */
class BootstrapConversation {

    /**
     * Result of the bootstrap conversation.
     * Contains everything needed to personalize Msaidizi for this worker.
     */
    data class BootstrapResult(
        val workerName: String,
        val businessType: WorkerType,
        val businessDescription: String,
        val assistantName: String,       // What worker calls Msaidizi (e.g., "Rafiki")
        val language: String,
        val dialect: DialectRegion
    )

    /**
     * Introduction style variants for A/B testing (STA 343).
     * Different tones to measure which builds trust fastest.
     */
    enum class IntroductionStyle {
        /** Warm, friendly — "Mimi ni Rafiki yako" */
        WARM,
        /** Professional — "Mimi ni CFO wako" */
        PROFESSIONAL,
        /** Playful — "Mimi ni msaidizi wako mpya!" */
        PLAYFUL
    }

    /**
     * Start the bootstrap conversation as a Flow of steps.
     * Each step emits a prompt and waits for worker input.
     *
     * @param style Introduction style (for A/B testing)
     * @return Flow of conversation steps
     */
    fun startConversation(
        style: IntroductionStyle = IntroductionStyle.WARM
    ): Flow<ConversationStep> = flow {
        // Step 1: Msaidizi introduces itself
        emit(ConversationStep.Introduction(getIntroductionPrompt(style)))

        // Steps 2-6 are driven by worker responses
        // The Flow collector handles input and calls processResponse()
    }

    /**
     * Process worker's response and return the next conversation step.
     *
     * @param currentStep Which step we're on
     * @param workerResponse What the worker said
     * @param accumulatedData Data collected so far
     * @return Next step in the conversation
     */
    fun processResponse(
        currentStep: ConversationStep,
        workerResponse: String,
        accumulatedData: AccumulatedData
    ): ConversationStep {
        return when (currentStep) {
            is ConversationStep.Introduction -> {
                // After intro, ask for name
                ConversationStep.AskWorkerName(
                    prompt = "Jina lako nani?",
                    promptEn = "What's your name?"
                )
            }

            is ConversationStep.AskWorkerName -> {
                val name = extractName(workerResponse)
                accumulatedData.workerName = name
                ConversationStep.AskBusinessType(
                    prompt = "Karibu $name! Biashara yako ni gapi?",
                    promptEn = "Welcome $name! What's your business?"
                )
            }

            is ConversationStep.AskBusinessType -> {
                val businessType = classifyBusiness(workerResponse)
                accumulatedData.businessType = businessType
                accumulatedData.businessDescription = workerResponse
                ConversationStep.AskAssistantName(
                    prompt = "Nzuri! Sasa, ungependa uniite jina gani?\n" +
                        "Mimi ni CFO wako — nitakusaidia kufuatilia pesa yako, " +
                        "kupanga biashara yako, na kukusaidia kukua.",
                    promptEn = "Great! Now, what would you like to call me?\n" +
                        "I'm your CFO — I'll help you track your money, " +
                        "plan your business, and help you grow."
                )
            }

            is ConversationStep.AskAssistantName -> {
                val assistantName = extractName(workerResponse)
                accumulatedData.assistantName = assistantName
                ConversationStep.ExplainCapabilities(
                    prompt = "$assistantName! Napenda jina hilo.\n\n" +
                        "Sasa hebu tuanze. Kila siku nitakupa:\n" +
                        "• Muhtasari wa mauzo na faida\n" +
                        "• Mapendekezo ya kununua stock\n" +
                        "• Ushauri wa kuhifadhi pesa\n" +
                        "• Ripoti ya biashara yako\n\n" +
                        "Leo umefanya mauzo gapi?",
                    promptEn = "$assistantName! I love that name.\n\n" +
                        "Now let's get started. Every day I'll give you:\n" +
                        "• Sales and profit summary\n" +
                        "• Restock recommendations\n" +
                        "• Savings advice\n" +
                        "• Business reports\n\n" +
                        "How many sales have you made today?"
                )
            }

            is ConversationStep.ExplainCapabilities -> {
                // Transition to first transaction recording
                ConversationStep.FirstTransaction(
                    prompt = "Sawa! Hebu tuandike mauzo yako ya kwanza.\n" +
                        "Niuzia nani? Bei ngapi?",
                    promptEn = "Great! Let's record your first sale.\n" +
                        "What did you sell? For how much?"
                )
            }

            is ConversationStep.FirstTransaction -> {
                // Bootstrap complete
                ConversationStep.Complete(
                    result = BootstrapResult(
                        workerName = accumulatedData.workerName ?: "Biashara",
                        businessType = accumulatedData.businessType ?: WorkerType.TRADER,
                        businessDescription = accumulatedData.businessDescription ?: "",
                        assistantName = accumulatedData.assistantName ?: "Msaidizi",
                        language = accumulatedData.language ?: "sw",
                        dialect = accumulatedData.dialect ?: DialectRegion.STANDARD
                    )
                )
            }

            is ConversationStep.Complete -> currentStep
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTRODUCTION PROMPTS (A/B testable — STA 343)
    // ═══════════════════════════════════════════════════════════════

    private fun getIntroductionPrompt(style: IntroductionStyle): String {
        return when (style) {
            IntroductionStyle.WARM ->
                "Habari! Mimi ni Msaidizi, CFO wako wa biashara.\n" +
                "Nitakusaidia kufuatilia pesa yako, kupanga biashara yako, " +
                "na kukusaidia kukua. Tuanze kujulana!"

            IntroductionStyle.PROFESSIONAL ->
                "Habari. Mimi ni Msaidizi — CFO wako wa biashara.\n" +
                "Kazi yangu ni kukusaidia kufuatilia mauzo, faida, na stock. " +
                "Tuanze kwa kujulana."

            IntroductionStyle.PLAYFUL ->
                "Habari! Mimi ni rafiki yako mpya wa biashara! 🎉\n" +
                "Jina langu ni Msaidizi, na mimi ni CFO wako. " +
                "Nitakusaidia kupata pesa zaidi. Hebu tuanze!"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NAME EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract name from worker's response.
     * Handles common Swahili/English patterns:
     * - "Mimi ni Maria" → "Maria"
     * - "Jina langu ni John" → "John"
     * - "Maria" → "Maria"
     * - "Naitwa Amina" → "Amina"
     */
    private fun extractName(response: String): String {
        val cleaned = response.trim()

        // Pattern: "Mimi ni <name>"
        val mimiNiPattern = Regex("(?i)mimi\\s+ni\\s+(\\S+)")
        mimiNiPattern.find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }

        // Pattern: "Jina langu ni <name>"
        val jinaPattern = Regex("(?i)jina\\s+langu\\s+(?:ni\\s+)?(\\S+)")
        jinaPattern.find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }

        // Pattern: "Naitwa <name>"
        val naitwaPattern = Regex("(?i)naitwa\\s+(\\S+)")
        naitwaPattern.find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }

        // Pattern: "I am <name>" / "My name is <name>"
        val iAmPattern = Regex("(?i)(?:i am|my name is)\\s+(\\S+)")
        iAmPattern.find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }

        // Fallback: use the whole response (likely just the name)
        return cleaned.split("\\s+".toRegex()).first()
            .replaceFirstChar { it.uppercase() }
    }

    // ═══════════════════════════════════════════════════════════════
    // BUSINESS CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify business type from worker's description.
     * Quick keyword-based classification for onboarding.
     */
    private fun classifyBusiness(description: String): WorkerType {
        val lower = description.lowercase()

        return when {
            // Trader keywords
            listOf("nauza", "duka", "shop", "sokoni", "market", "mboga",
                "mama mboga", "mitumba", "hawker", "biashara", "nunua",
                "stock", "wholesale", "retail").any { lower.contains(it) } ->
                WorkerType.TRADER

            // Transport keywords
            listOf("boda", "pikipiki", "matatu", "abiria", "taxi", "uber",
                "nduthi", "delivery", "usafiri", "tuk-tuk", "bajaj").any { lower.contains(it) } ->
                WorkerType.TRANSPORT

            // Farmer keywords
            listOf("shamba", "kulima", "mazao", "mbegu", "mbolea", "mvua",
                "harvest", "mkulima", "mahindi", "ngano", "kahawa", "chai",
                "nyuki", "kuku", "mifugo").any { lower.contains(it) } ->
                WorkerType.FARMER

            // Service keywords
            listOf("salon", "kunyolewa", "haircut", "fundi", "mechanic",
                "repair", "service", "mteja", "client", "appointment",
                "massage", "dawa", "clinic").any { lower.contains(it) } ->
                WorkerType.SERVICE

            // Manufacturing keywords
            listOf("tengeneza", "chuma", "mbao", "welding", "furniture",
                "nguo", "ushona", "tailor", "maker", "production",
                "jua kali", "karai", "tofali").any { lower.contains(it) } ->
                WorkerType.MANUFACTURING

            // Digital keywords
            listOf("mpesa", "float", "airtime", "agent", "online",
                "social media", "instagram", "tiktok", "gig", "phone",
                "simu", "computer").any { lower.contains(it) } ->
                WorkerType.DIGITAL

            // Default: trader (most common in informal economy)
            else -> WorkerType.TRADER
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ACCUMULATED DATA (mutable state during conversation)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mutable container for data collected during the bootstrap conversation.
     * Passed between steps so each step can add its findings.
     */
    class AccumulatedData {
        var workerName: String? = null
        var businessType: WorkerType? = null
        var businessDescription: String? = null
        var assistantName: String? = null
        var language: String? = null
        var dialect: DialectRegion? = null
    }
}

// ═══════════════════════════════════════════════════════════════
// CONVERSATION STEPS (sealed class)
// ═══════════════════════════════════════════════════════════════

/**
 * Sealed class representing each step in the bootstrap conversation.
 * Each step carries the prompt(s) to display to the worker.
 *
 * The conversation flows:
 * Introduction → AskWorkerName → AskBusinessType → AskAssistantName
 *     → ExplainCapabilities → FirstTransaction → Complete
 */
sealed class ConversationStep {

    /** Msaidizi introduces itself as CFO */
    data class Introduction(val prompt: String) : ConversationStep()

    /** Ask the worker's name */
    data class AskWorkerName(
        val prompt: String,
        val promptEn: String
    ) : ConversationStep()

    /** Ask about the worker's business */
    data class AskBusinessType(
        val prompt: String,
        val promptEn: String
    ) : ConversationStep()

    /** Worker gets to name Msaidizi — creates ownership */
    data class AskAssistantName(
        val prompt: String,
        val promptEn: String
    ) : ConversationStep()

    /** Msaidizi explains its CFO capabilities */
    data class ExplainCapabilities(
        val prompt: String,
        val promptEn: String
    ) : ConversationStep()

    /** Record the first transaction together */
    data class FirstTransaction(
        val prompt: String,
        val promptEn: String
    ) : ConversationStep()

    /** Bootstrap complete — return the result */
    data class Complete(val result: BootstrapConversation.BootstrapResult) : ConversationStep()
}

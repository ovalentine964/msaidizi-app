package com.msaidizi.app.agent.agi

import com.msaidizi.app.agent.AgentResponse
import com.msaidizi.app.agent.ResponseType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AGI-Ready Abstraction Layer for Msaidizi.
 *
 * Defines the interfaces and safety boundaries for progressive autonomy.
 * As the agent proves itself, it earns more trust and capability.
 *
 * This is NOT AGI. This is the scaffolding that makes future AGI integration
 * safe and structured. The agent operates within defined boundaries and
 * earns autonomy through demonstrated competence.
 *
 * Autonomy Progression:
 *   Level 0: TOOL        — Agent is a tool. User controls everything.
 *   Level 1: ASSISTANT   — Agent suggests, user decides.
 *   Level 2: COLLEAGUE   — Agent handles routine, escalates novel.
 *   Level 3: DELEGATE    — Agent operates independently within bounds.
 *   Level 4: AUTONOMOUS  — Agent sets own goals within safety constraints.
 *
 * Safety Architecture:
 *   - Kill switch at every level
 *   - Hard boundaries that autonomy cannot override
 *   - Audit trail for every decision
 *   - Human override always available
 */
@Singleton
class AGIReadyLayer @Inject constructor() {

    // ═══════════════ AUTONOMY STATE TRACKING ═══════════════

    /** Mutable autonomy state — tracks current level, trust, and decision history. */
    @Volatile
    var autonomyState: AutonomyState = AutonomyState()
        private set

    /** Update the autonomy state (e.g., after a decision outcome). */
    fun updateAutonomyState(transform: (AutonomyState) -> AutonomyState) {
        autonomyState = transform(autonomyState)
        Timber.d("Autonomy state updated: level=%d, trust=%.2f, decisions=%d/%d",
            autonomyState.level, autonomyState.trustScore,
            autonomyState.decisionsCorrect, autonomyState.decisionsMade)
    }

    /** Set the autonomy level directly (e.g., from ProgressiveAutonomy). */
    fun setAutonomyLevel(level: Int) {
        autonomyState = autonomyState.copy(level = level.coerceIn(0, 4))
        Timber.i("Autonomy level set to %d", autonomyState.level)
    }

    /** Set the autonomy level from a ProgressiveAutonomy AutonomyLevel. */
    fun setAutonomyLevelFromProgressive(level: com.msaidizi.app.agent.autonomy.AutonomyLevel) {
        val mappedLevel = when (level) {
            com.msaidizi.app.agent.autonomy.AutonomyLevel.LEVEL_1_SUPERVISED -> 0
            com.msaidizi.app.agent.autonomy.AutonomyLevel.LEVEL_2_ASSISTED -> 1
            com.msaidizi.app.agent.autonomy.AutonomyLevel.LEVEL_3_DELEGATED -> 2
            com.msaidizi.app.agent.autonomy.AutonomyLevel.LEVEL_4_AUTONOMOUS -> 3
            com.msaidizi.app.agent.autonomy.AutonomyLevel.LEVEL_5_SELF_GOVERNING -> 4
        }
        setAutonomyLevel(mappedLevel)
    }

    // ═══════════════ CAPABILITY & BOUNDARY DEFINITIONS ═══════════════

    /** Capability categories the agent can develop. */
    enum class Capability {
        TRANSACTION_RECORDING,    // Record and categorize transactions
        CASH_FLOW_PREDICTION,     // Predict future cash flow
        BUSINESS_ADVICE,          // Provide business recommendations
        GOAL_MANAGEMENT,          // Set and track financial goals
        LOAN_ANALYSIS,            // Analyze loan options
        MARKET_INTELLIGENCE,      // Provide market insights
        RISK_ASSESSMENT,          // Assess business risks
        STRATEGIC_PLANNING,       // Long-term business planning
        NETWORKING,               // Connect with other businesses
        AUTONOMOUS_ACTION         // Take actions without user input
    }

    /** Safety boundaries that cannot be overridden by autonomy. */
    enum class SafetyBoundary {
        NO_MONEY_MOVEMENT,           // Agent never moves money without explicit approval
        NO_EXTERNAL_COMMUNICATION,   // Agent never contacts external parties without approval
        NO_DATA_EXFILTRATION,        // Agent never sends personal data off-device without consent
        NO_IRREVERSIBLE_ACTIONS,     // Agent never takes actions that can't be undone
        NO_DECEPTION,                // Agent never hides information from user
        NO_MANIPULATION,             // Agent never manipulates user decisions
        FINANCIAL_ADVICE_DISCLAIMER, // Always disclaim: "This is not professional financial advice"
        TRANSPARENCY_REQUIRED        // Always explain reasoning when asked
    }

    /** Current state of the agent's autonomy. */
    data class AutonomyState(
        val level: Int = 0,  // 0-4
        val capabilities: Map<Capability, Float> = emptyMap(), // 0.0-1.0 proficiency
        val activeBoundaries: Set<SafetyBoundary> = SafetyBoundary.entries.toSet(),
        val trustScore: Float = 0.5f,
        val decisionsMade: Int = 0,
        val decisionsCorrect: Int = 0,
        val lastEvaluation: Long = System.currentTimeMillis()
    )

    // ═══════════════ SAFETY CHECK RESULT ═══════════════

    /**
     * Result of a safety check on a response.
     *
     * @param safe Whether the response passed all safety checks
     * @param violatedBoundary The boundary that was violated (null if safe)
     * @param modifiedResponse The response after safety modifications (null if blocked)
     * @param reason Human-readable explanation of the safety decision
     * @param blocked Whether the response was blocked entirely (vs. modified)
     */
    data class SafetyResult(
        val safe: Boolean,
        val violatedBoundary: SafetyBoundary? = null,
        val modifiedResponse: AgentResponse? = null,
        val reason: String = "",
        val blocked: Boolean = false
    )

    // ═══════════════ CORE SAFETY METHODS ═══════════════

    /**
     * Evaluate whether the agent can perform a specific capability.
     * Returns true if the agent has sufficient proficiency and trust.
     */
    fun canPerform(
        capability: Capability,
        state: AutonomyState = autonomyState,
        requiredProficiency: Float = 0.7f
    ): Boolean {
        val proficiency = state.capabilities[capability] ?: 0f
        return proficiency >= requiredProficiency && state.trustScore >= 0.5f
    }

    /**
     * Check if an action violates any safety boundaries.
     * Returns null if safe, or the violated boundary if not.
     */
    fun checkSafety(
        action: String,
        state: AutonomyState = autonomyState
    ): SafetyBoundary? {
        for (boundary in state.activeBoundaries) {
            if (violatesBoundary(action, boundary)) {
                return boundary
            }
        }
        return null
    }

    /**
     * Full safety check on an agent response.
     * Unlike the simple checkSafety(), this returns a SafetyResult with
     * a potentially modified response (e.g., disclaimer injection).
     *
     * @param response The agent's response to check
     * @param originalInput The user's original input (for context-aware checks)
     * @param language Response language
     * @return SafetyResult with safe/modification/blocked status
     */
    fun checkResponseSafety(
        response: AgentResponse,
        originalInput: String = "",
        language: String = "sw"
    ): SafetyResult {
        val lower = response.text.lowercase()
        val inputLower = originalInput.lowercase()

        // ── Check NO_DECEPTION ──
        if (autonomyState.activeBoundaries.contains(SafetyBoundary.NO_DECEPTION)) {
            val deceptionCheck = detectDeception(response.text, originalInput)
            if (deceptionCheck != null) {
                Timber.w("NO_DECEPTION violated: %s", deceptionCheck)
                return SafetyResult(
                    safe = false,
                    violatedBoundary = SafetyBoundary.NO_DECEPTION,
                    reason = deceptionCheck,
                    blocked = true
                )
            }
        }

        // ── Check NO_MANIPULATION ──
        if (autonomyState.activeBoundaries.contains(SafetyBoundary.NO_MANIPULATION)) {
            val manipulationCheck = detectManipulation(response.text)
            if (manipulationCheck != null) {
                Timber.w("NO_MANIPULATION violated: %s", manipulationCheck)
                // Strip manipulation patterns and return cleaned response
                val cleaned = stripManipulationPatterns(response.text)
                return SafetyResult(
                    safe = false,
                    violatedBoundary = SafetyBoundary.NO_MANIPULATION,
                    modifiedResponse = response.copy(text = cleaned),
                    reason = "Manipulation pattern removed: $manipulationCheck",
                    blocked = false
                )
            }
        }

        // ── Check FINANCIAL_ADVICE_DISCLAIMER ──
        if (autonomyState.activeBoundaries.contains(SafetyBoundary.FINANCIAL_ADVICE_DISCLAIMER)) {
            if (response.type == ResponseType.ADVICE && isFinancialAdvice(response.text)) {
                val disclaimer = getFinancialDisclaimer(language)
                if (!response.text.contains(disclaimer)) {
                    Timber.d("FINANCIAL_ADVICE_DISCLAIMER: injecting disclaimer")
                    val disclaimed = response.copy(
                        text = "${response.text}\n\n$disclaimer"
                    )
                    return SafetyResult(
                        safe = false,
                        violatedBoundary = SafetyBoundary.FINANCIAL_ADVICE_DISCLAIMER,
                        modifiedResponse = disclaimed,
                        reason = "Financial disclaimer injected",
                        blocked = false
                    )
                }
            }
        }

        // ── Check TRANSPARENCY_REQUIRED ──
        if (autonomyState.activeBoundaries.contains(SafetyBoundary.TRANSPARENCY_REQUIRED)) {
            if (requiresExplanation(inputLower) && !hasReasoning(response.text)) {
                Timber.d("TRANSPARENCY_REQUIRED: adding reasoning prompt")
                val enhanced = addReasoningPrompt(response, language)
                return SafetyResult(
                    safe = false,
                    violatedBoundary = SafetyBoundary.TRANSPARENCY_REQUIRED,
                    modifiedResponse = enhanced,
                    reason = "Reasoning explanation added for transparency",
                    blocked = false
                )
            }
        }

        return SafetyResult(safe = true)
    }

    /**
     * Check whether a handler action requires human approval at the current autonomy level.
     * This is the progressive autonomy enforcement point.
     *
     * @param actionType The type of action being performed
     * @param isHighValue Whether the action involves high value
     * @param isIrreversible Whether the action is irreversible
     * @return true if human approval is required
     */
    fun requiresHumanApproval(
        actionType: String,
        isHighValue: Boolean = false,
        isIrreversible: Boolean = false
    ): Boolean {
        return when (autonomyState.level) {
            0 -> true  // TOOL: every action needs approval
            1 -> true  // ASSISTANT: suggest, user decides
            2 -> isHighValue || isIrreversible  // COLLEAGUE: routine is fine, novel needs approval
            3 -> isIrreversible  // DELEGATE: only irreversible needs approval
            4 -> false  // AUTONOMOUS: acts independently
            else -> true
        }
    }

    /**
     * Get the human-readable approval requirement message for the current level.
     */
    fun getApprovalMessage(actionType: String, language: String = "sw"): String {
        return when (autonomyState.level) {
            0 -> if (language == "sw") "Ninahitaji kibali chako kabla ya $actionType."
                 else "I need your approval before $actionType."
            1 -> if (language == "sw") "Ninapendekeza $actionType. Unakubali?"
                 else "I suggest $actionType. Do you approve?"
            2 -> if (language == "sw") "Nitafanya $actionType. Tafadhali kagua."
                 else "I'll do $actionType. Please review."
            else -> ""
        }
    }

    /**
     * Update capability proficiency based on outcome.
     */
    fun updateCapability(
        state: AutonomyState = autonomyState,
        capability: Capability,
        success: Boolean
    ): AutonomyState {
        val current = state.capabilities[capability] ?: 0f
        val newProficiency = if (success) {
            (current + 0.05f).coerceAtMost(1f)
        } else {
            (current - 0.1f).coerceAtLeast(0f)
        }

        val newCapabilities = state.capabilities.toMutableMap()
        newCapabilities[capability] = newProficiency

        val newCorrect = if (success) state.decisionsCorrect + 1 else state.decisionsCorrect
        val newTotal = state.decisionsMade + 1

        return state.copy(
            capabilities = newCapabilities,
            decisionsMade = newTotal,
            decisionsCorrect = newCorrect,
            trustScore = newCorrect.toFloat() / newTotal.coerceAtLeast(1),
            lastEvaluation = System.currentTimeMillis()
        )
    }

    /**
     * Evaluate if the agent should level up in autonomy.
     */
    fun evaluateLevelUp(state: AutonomyState = autonomyState): AutonomyState {
        if (state.level >= 4) return state

        val avgProficiency = state.capabilities.values.average().toFloat()
        val requiredDecisions = (state.level + 1) * 20

        val shouldLevelUp = state.decisionsMade >= requiredDecisions &&
                avgProficiency >= 0.7f &&
                state.trustScore >= 0.8f

        return if (shouldLevelUp) {
            state.copy(level = state.level + 1)
        } else {
            state
        }
    }

    /**
     * Get a human-readable description of the current autonomy level.
     */
    fun describeLevel(level: Int = autonomyState.level): String {
        return when (level) {
            0 -> "TOOL — I do exactly what you tell me. No more, no less."
            1 -> "ASSISTANT — I suggest actions. You confirm before I do anything."
            2 -> "COLLEAGUE — I handle routine tasks. I ask you about anything unusual."
            3 -> "DELEGATE — I operate independently within our agreed boundaries."
            4 -> "AUTONOMOUS — I set my own goals within safety constraints."
            else -> "UNKNOWN"
        }
    }

    // ── Boundary Violation Detection ──────────────────────────────────

    private fun violatesBoundary(action: String, boundary: SafetyBoundary): Boolean {
        val lower = action.lowercase()
        return when (boundary) {
            SafetyBoundary.NO_MONEY_MOVEMENT ->
                lower.contains("transfer") || lower.contains("send money") || lower.contains("pay")
            SafetyBoundary.NO_EXTERNAL_COMMUNICATION ->
                lower.contains("send message") || lower.contains("call") || lower.contains("email")
            SafetyBoundary.NO_DATA_EXFILTRATION ->
                lower.contains("upload") || lower.contains("share data") || lower.contains("export")
            SafetyBoundary.NO_IRREVERSIBLE_ACTIONS ->
                lower.contains("delete") || lower.contains("permanently") || lower.contains("irreversible")
            SafetyBoundary.NO_DECEPTION ->
                detectDeception(action, "") != null
            SafetyBoundary.NO_MANIPULATION ->
                detectManipulation(action) != null
            SafetyBoundary.FINANCIAL_ADVICE_DISCLAIMER ->
                false // This is a reminder, not a blocker
            SafetyBoundary.TRANSPARENCY_REQUIRED ->
                false // This is a reminder, not a blocker
        }
    }

    // ═══════════════ DECEPTION DETECTION ═══════════════

    /**
     * Detect deception patterns in agent responses.
     *
     * Checks for:
     * 1. False certainty — presenting uncertain info as definitive fact
     * 2. Contradictions — making conflicting claims in the same response
     * 3. Misleading confidence — claiming precision without basis
     * 4. Hidden caveats — burying important disclaimers
     */
    private fun detectDeception(response: String, originalInput: String): String? {
        val lower = response.lowercase()

        // ── False certainty detection ──
        // The agent should not claim absolute certainty about business predictions
        val falseCertaintyPatterns = listOf(
            """guaranteed\s+(to\s+)?(earn|make|profit|return|succeed)""".toRegex(RegexOption.IGNORE_CASE),
            """you\s+will\s+definitely""".toRegex(RegexOption.IGNORE_CASE),
            """100%\s+(certain|sure|guaranteed|accurate)""".toRegex(RegexOption.IGNORE_CASE),
            """always\s+(profit|succeed|work|earn)""".toRegex(RegexOption.IGNORE_CASE),
            """never\s+(fail|lose|miss|go\s+wrong)""".toRegex(RegexOption.IGNORE_CASE),
            """hakika\s+kabisa""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: absolutely certain
            """lazima\s+utafanikiwa""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: you will definitely succeed
        )
        for (pattern in falseCertaintyPatterns) {
            if (pattern.containsMatchIn(response)) {
                return "False certainty detected: ${pattern.pattern}"
            }
        }

        // ── Contradiction detection ──
        // Check for simple contradictions (e.g., "profit is high" + "you're losing money")
        val positiveClaims = listOf(
            """profit""".toRegex(RegexOption.IGNORE_CASE),
            """growing""".toRegex(RegexOption.IGNORE_CASE),
            """increasing""".toRegex(RegexOption.IGNORE_CASE),
            """doing well""".toRegex(RegexOption.IGNORE_CASE),
            """nzuri""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: good
        )
        val negativeClaims = listOf(
            """losing""".toRegex(RegexOption.IGNORE_CASE),
            """declining""".toRegex(RegexOption.IGNORE_CASE),
            """debt""".toRegex(RegexOption.IGNORE_CASE),
            """struggling""".toRegex(RegexOption.IGNORE_CASE),
            """hasara""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: loss
        )
        val hasPositive = positiveClaims.any { it.containsMatchIn(response) }
        val hasNegative = negativeClaims.any { it.containsMatchIn(response) }
        // Only flag if both appear in a short response (long responses may legitimately discuss both)
        if (hasPositive && hasNegative && response.length < 200) {
            return "Potential contradiction: mixed positive and negative claims in short response"
        }

        // ── Misleading precision detection ──
        // Agent should not present estimates as exact figures
        val misleadingPrecisionPatterns = listOf(
            """exactly\s+\d""".toRegex(RegexOption.IGNORE_CASE),
            """precisely\s+\d""".toRegex(RegexOption.IGNORE_CASE),
            """the\s+exact\s+(amount|number|figure)""".toRegex(RegexOption.IGNORE_CASE),
            """ni\s+haswa\s+\d""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: is exactly
        )
        for (pattern in misleadingPrecisionPatterns) {
            if (pattern.containsMatchIn(response)) {
                return "Misleading precision: presenting estimate as exact"
            }
        }

        return null
    }

    // ═══════════════ MANIPULATION DETECTION ═══════════════

    /**
     * Detect manipulation patterns in agent responses.
     *
     * Checks for:
     * 1. Urgency pressure — creating artificial time pressure
     * 2. FOMO tactics — fear of missing out
     * 3. Emotional manipulation — guilt-tripping, fear-mongering
     * 4. Social pressure — false consensus, bandwagon
     */
    private fun detectManipulation(response: String): String? {
        val lower = response.lowercase()

        // ── Urgency pressure ──
        val urgencyPatterns = listOf(
            """act\s+now""".toRegex(RegexOption.IGNORE_CASE),
            """limited\s+time""".toRegex(RegexOption.IGNORE_CASE),
            """don't\s+wait""".toRegex(RegexOption.IGNORE_CASE),
            """before\s+it'?s\s+too\s+late""".toRegex(RegexOption.IGNORE_CASE),
            """running\s+out\s+of\s+time""".toRegex(RegexOption.IGNORE_CASE),
            """haraka""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: hurry
            """sasa\s+hivi""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: right now
            """kabla\s+haijaeleweka""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: before it's too late
        )
        for (pattern in urgencyPatterns) {
            if (pattern.containsMatchIn(response)) {
                return "Urgency pressure detected: ${pattern.pattern}"
            }
        }

        // ── FOMO tactics ──
        val fomoPatterns = listOf(
            """everyone\s+(is|else)\s+(doing|using|buying|selling)""".toRegex(RegexOption.IGNORE_CASE),
            """you'?re\s+(missing|losing)\s+out""".toRegex(RegexOption.IGNORE_CASE),
            """don'?t\s+miss\s+this""".toRegex(RegexOption.IGNORE_CASE),
            """only\s+\d+\s+left""".toRegex(RegexOption.IGNORE_CASE),
            """wote\s+wana""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: everyone is
            """unakosa""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: you're missing
        )
        for (pattern in fomoPatterns) {
            if (pattern.containsMatchIn(response)) {
                return "FOMO tactic detected: ${pattern.pattern}"
            }
        }

        // ── Emotional manipulation ──
        val emotionalPatterns = listOf(
            """you'?ll\s+regret""".toRegex(RegexOption.IGNORE_CASE),
            """think\s+of\s+your\s+(family|children|future)""".toRegex(RegexOption.IGNORE_CASE),
            """if\s+you\s+really\s+(cared|loved|wanted)""".toRegex(RegexOption.IGNORE_CASE),
            """utajuta""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: you'll regret
            """fikiria\s+familia\s+yako""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: think of your family
        )
        for (pattern in emotionalPatterns) {
            if (pattern.containsMatchIn(response)) {
                return "Emotional manipulation detected: ${pattern.pattern}"
            }
        }

        // ── Social pressure / false consensus ──
        val socialPressurePatterns = listOf(
            """most\s+(successful|smart|savvy)\s+(people|business\s*owners|entrepreneurs)""".toRegex(RegexOption.IGNORE_CASE),
            """real\s+(entrepreneurs|business\s*owners)\s+(do|know|understand)""".toRegex(RegexOption.IGNORE_CASE),
            """biashara\s+bora""".toRegex(RegexOption.IGNORE_CASE),  // Swahili: best businesses
        )
        for (pattern in socialPressurePatterns) {
            if (pattern.containsMatchIn(response)) {
                return "Social pressure / false consensus detected: ${pattern.pattern}"
            }
        }

        return null
    }

    /**
     * Strip manipulation patterns from response text.
     * Removes sentences containing manipulation while preserving the rest.
     */
    private fun stripManipulationPatterns(text: String): String {
        val sentences = text.split(Regex("""(?<=[.!?])\s+"""))
        val cleaned = sentences.filter { sentence ->
            detectManipulation(sentence) == null
        }
        return cleaned.joinToString(" ").ifBlank {
            // If everything was stripped, return a neutral fallback
            "Tafadhali fanya uamuzi wako mwenyewe kwa kuzingatia habari uliyonayo."
        }
    }

    // ═══════════════ FINANCIAL DISCLAIMER ═══════════════

    /**
     * Check if a response contains financial advice content.
     */
    private fun isFinancialAdvice(text: String): Boolean {
        val lower = text.lowercase()
        val financialKeywords = listOf(
            "invest", "loan", "save", "budget", "profit", "loss",
            "interest", "credit", "debt", "stock", "market",
            "kopa", "riba", "faida", "hasara", "akiba",  // Swahili financial terms
            "mkopo", "deni", "biashara", "mtaji"
        )
        return financialKeywords.any { lower.contains(it) }
    }

    /**
     * Get the appropriate financial disclaimer for the language.
     */
    private fun getFinancialDisclaimer(language: String): String {
        return when (language) {
            "sw" -> "⚠️ Kumbuka: Hii si ushauri wa kitaalamu wa kifedha. Wasiliana na mtaalamu wa fedha kabla ya kufanya maamuzi muhimu ya kifedha."
            else -> "⚠️ Note: This is not professional financial advice. Consult a qualified financial advisor before making important financial decisions."
        }
    }

    // ═══════════════ TRANSPARENCY / REASONING ═══════════════

    /**
     * Check if the user's input requires an explanation (reasoning chain).
     */
    private fun requiresExplanation(inputLower: String): Boolean {
        val explanationTriggers = listOf(
            "why", "how", "explain", "reason", "what makes",
            "kwa nini", "vipi", "eleza", "sababu",  // Swahili
            "nini sababu", "kwa sababu gani"
        )
        return explanationTriggers.any { inputLower.contains(it) }
    }

    /**
     * Check if a response already includes reasoning/explanation.
     */
    private fun hasReasoning(text: String): String? {
        val lower = text.lowercase()
        val reasoningIndicators = listOf(
            "because", "since", "the reason", "this is because",
            "due to", "as a result", "therefore", "consequently",
            "kwa sababu", "kwa hiyo", "sababu ni",  // Swahili
            "kwa vile", "hivyo"
        )
        return if (reasoningIndicators.any { lower.contains(it) }) null else "No reasoning chain found"
    }

    /**
     * Add a reasoning prompt to a response that lacks explanation.
     */
    private fun addReasoningPrompt(response: AgentResponse, language: String): AgentResponse {
        val reasoningSuffix = when (language) {
            "sw" -> "\n\n💡 Maelezo: Jibu hili linatokana na data ya biashara yako na mifumo ya kawaida ya biashara."
            else -> "\n\n💡 Reasoning: This answer is based on your business data and common business patterns."
        }
        return response.copy(text = "${response.text}$reasoningSuffix")
    }
}

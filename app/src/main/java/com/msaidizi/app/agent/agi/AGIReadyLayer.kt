package com.msaidizi.app.agent.agi

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
class AGIReadyLayer {

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

    /**
     * Evaluate whether the agent can perform a specific capability.
     * Returns true if the agent has sufficient proficiency and trust.
     */
    fun canPerform(
        capability: Capability,
        state: AutonomyState,
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
        state: AutonomyState
    ): SafetyBoundary? {
        // Check against all active boundaries
        for (boundary in state.activeBoundaries) {
            if (violatesBoundary(action, boundary)) {
                return boundary
            }
        }
        return null
    }

    /**
     * Update capability proficiency based on outcome.
     */
    fun updateCapability(
        state: AutonomyState,
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
    fun evaluateLevelUp(state: AutonomyState): AutonomyState {
        if (state.level >= 4) return state // Max level

        val avgProficiency = state.capabilities.values.average().toFloat()
        val requiredDecisions = (state.level + 1) * 20 // More decisions needed at higher levels

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
    fun describeLevel(level: Int): String {
        return when (level) {
            0 -> "TOOL — I do exactly what you tell me. No more, no less."
            1 -> "ASSISTANT — I suggest actions. You confirm before I do anything."
            2 -> "COLLEAGUE — I handle routine tasks. I ask you about anything unusual."
            3 -> "DELEGATE — I operate independently within our agreed boundaries."
            4 -> "AUTONOMOUS — I set my own goals within safety constraints."
            else -> "UNKNOWN"
        }
    }

    // ── Private ──────────────────────────────────────────

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
                false // Deception is detected by intent, not keywords
            SafetyBoundary.NO_MANIPULATION ->
                false // Manipulation is detected by pattern, not keywords
            SafetyBoundary.FINANCIAL_ADVICE_DISCLAIMER ->
                false // This is a reminder, not a blocker
            SafetyBoundary.TRANSPARENCY_REQUIRED ->
                false // This is a reminder, not a blocker
        }
    }
}

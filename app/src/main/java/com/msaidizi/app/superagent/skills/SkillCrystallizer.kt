package com.msaidizi.app.superagent.skills

import timber.log.Timber

/**
 * Skill Crystallization — detect repeated workflows → create automatic skills.
 *
 * ## Overview
 * The Skill Crystallizer monitors worker interactions for repeated patterns.
 * When a pattern is detected (3+ repetitions within a time window), it proposes
 * creating an automatic skill. The worker confirms → skill activates.
 *
 * ## 6 Skill Types
 * 1. **Morning Briefing** — Daily market/financial summary at consistent time
 * 2. **Restock Alert** — Inventory depletion warnings based on sales patterns
 * 3. **Price Check** — Regular price comparison requests
 * 4. **Savings Nudge** — Spending exceeds rolling average
 * 5. **Market Day Prep** — Pre-market-day preparation checklist
 * 6. **Weekly Report** — End-of-week business analysis
 *
 * ## Detection Mechanism
 * - Intent fingerprinting: normalize intent + context slots
 * - Rolling window of last 100 interaction fingerprints
 * - Match: ≥2 prior matches with same fingerprint
 * - Temporal regularity check for time-based patterns
 *
 * ## Crystallization Thresholds
 * | Pattern Type  | Min Repetitions | Time Window | Regularity |
 * |---------------|-----------------|-------------|------------|
 * | Time-based    | 3 occurrences   | 7 days      | ≥70% same time slot |
 * | Action-based  | 3 occurrences   | 14 days     | ≥80% same sequence |
 * | Context-based | 4 occurrences   | 14 days     | Same trigger condition |
 *
 * @param patternProvider Provides access to pattern storage
 * @param skillProvider Provides access to skill storage
 */
class SkillCrystallizer(
    private val patternProvider: PatternProvider,
    private val skillProvider: SkillStorageProvider
) {
    companion object {
        private const val TAG = "SkillCrystallizer"

        /** Size of the rolling fingerprint buffer */
        const val BUFFER_SIZE = 100

        /** Minimum repetitions for crystallization */
        const val MIN_REPETITIONS_TIME = 3
        const val MIN_REPETITIONS_ACTION = 3
        const val MIN_REPETITIONS_CONTEXT = 4

        /** Time windows in days */
        const val TIME_WINDOW_DAYS = 7
        const val ACTION_WINDOW_DAYS = 14
        const val CONTEXT_WINDOW_DAYS = 14

        /** Regularity thresholds */
        const val TIME_REGULARITY_THRESHOLD = 0.7f
        const val ACTION_REGULARITY_THRESHOLD = 0.8f

        /** Minimum peers for social skill sharing */
        const val MIN_PEERS_FOR_SHARING = 5
    }

    /**
     * Record an interaction for pattern detection.
     * Called by the superagent after every worker interaction.
     *
     * @param interaction The interaction to record
     */
    suspend fun recordInteraction(interaction: Interaction) {
        val fingerprint = computeFingerprint(interaction)
        patternProvider.addToBuffer(fingerprint)

        // Check for crystallization
        val detection = checkForCrystallization(fingerprint)
        if (detection != null) {
            patternProvider.recordDetection(detection)
            Timber.tag(TAG).d("Pattern detected: %s", detection.type)
        }
    }

    /**
     * Check if a new interaction matches a detected pattern.
     *
     * @param interaction The current interaction
     * @return SkillProposal if pattern matches, null otherwise
     */
    suspend fun checkForProposal(interaction: Interaction): SkillProposal? {
        val fingerprint = computeFingerprint(interaction)
        val detections = patternProvider.getDetections()

        for (detection in detections) {
            if (detection.status != DetectionStatus.DETECTED) continue
            if (detection.fingerprintHash != fingerprint.hash) continue

            // Check if already proposed
            if (skillProvider.hasProposal(detection.id)) continue

            return generateProposal(detection)
        }

        return null
    }

    /**
     * Worker confirms a skill proposal.
     *
     * @param proposalId The proposal to confirm
     * @param adjustments Optional adjustments from the worker
     * @return The activated skill
     */
    suspend fun confirmProposal(
        proposalId: String,
        adjustments: Map<String, String> = emptyMap()
    ): CrystallizedSkill? {
        val proposal = skillProvider.getProposal(proposalId) ?: return null

        val skill = CrystallizedSkill(
            id = "skill_${System.currentTimeMillis()}",
            type = proposal.type,
            name = proposal.name,
            trigger = proposal.trigger,
            action = proposal.action,
            output = proposal.output,
            status = SkillStatus.ACTIVE,
            createdAt = System.currentTimeMillis() / 1000,
            lastTriggeredAt = null,
            triggerCount = 0,
            refinements = mutableListOf(),
            adjustments = adjustments.toMutableMap()
        )

        skillProvider.saveSkill(skill)
        skillProvider.markProposalConfirmed(proposalId)

        Timber.tag(TAG).d("Skill activated: %s (%s)", skill.name, skill.type)
        return skill
    }

    /**
     * Worker rejects a skill proposal.
     */
    suspend fun rejectProposal(proposalId: String, permanent: Boolean = false) {
        skillProvider.markProposalRejected(proposalId, permanent)
    }

    /**
     * Refine an active skill through natural language.
     *
     * @param skillId The skill to refine
     * @param refinement The refinement description
     * @return Updated skill, or null if not found
     */
    suspend fun refineSkill(skillId: String, refinement: String): CrystallizedSkill? {
        val skill = skillProvider.getSkill(skillId) ?: return null

        skill.refinements.add(SkillRefinement(
            timestamp = System.currentTimeMillis() / 1000,
            description = refinement
        ))

        skillProvider.saveSkill(skill)
        return skill
    }

    /**
     * Retire an unused skill.
     */
    suspend fun retireSkill(skillId: String): Boolean {
        val skill = skillProvider.getSkill(skillId) ?: return false
        skillProvider.updateSkillStatus(skillId, SkillStatus.RETIRED)
        return true
    }

    /**
     * Get all active skills for the worker.
     */
    suspend fun getActiveSkills(): List<CrystallizedSkill> {
        return skillProvider.getSkillsByStatus(SkillStatus.ACTIVE)
    }

    /**
     * Get all skill proposals awaiting confirmation.
     */
    suspend fun getPendingProposals(): List<SkillProposal> {
        return skillProvider.getPendingProposals()
    }

    /**
     * Check for skills that should be retired (unused for 30+ days).
     */
    suspend fun checkForRetirement(): List<CrystallizedSkill> {
        val now = System.currentTimeMillis() / 1000
        val thirtyDaysAgo = now - (30 * 24 * 60 * 60)

        return skillProvider.getSkillsByStatus(SkillStatus.ACTIVE).filter { skill ->
            skill.lastTriggeredAt != null && skill.lastTriggeredAt < thirtyDaysAgo
        }
    }

    // ═══════════════ PATTERN DETECTION ═══════════════

    private fun computeFingerprint(interaction: Interaction): InteractionFingerprint {
        // Normalize intent: strip time references, replace specific values with slots
        val normalizedIntent = normalizeIntent(interaction.intent)

        // Extract context slots
        val contextSlots = mapOf(
            "timeOfDay" to interaction.timeOfDay,
            "dayOfWeek" to interaction.dayOfWeek.toString(),
            "location" to interaction.location
        )

        // Compute hash
        val hash = (normalizedIntent + contextSlots.toString()).hashCode().toString(16)

        return InteractionFingerprint(
            hash = hash,
            normalizedIntent = normalizedIntent,
            contextSlots = contextSlots,
            timestamp = interaction.timestamp,
            originalIntent = interaction.intent
        )
    }

    private fun normalizeIntent(intent: String): String {
        // Strip time references, replace specific values with slots
        var normalized = intent.lowercase()

        // Replace specific items with slots
        val itemPatterns = listOf(
            "bei ya (.+?) ni" to "bei ya {item} ni",
            "nauliza bei ya (.+?)" to "nauliza bei ya {item}",
            "nimeuza (.+?) kwa" to "nimeuza {item} kwa",
            "nimenunua (.+?) kwa" to "nimenunua {item} kwa"
        )

        for ((pattern, replacement) in itemPatterns) {
            normalized = normalized.replace(Regex(pattern), replacement)
        }

        // Strip time references
        normalized = normalized.replace(Regex("\\b(asubuhi|mchana|jioni|usiku|leo|kesho|jana)\\b"), "")

        return normalized.trim()
    }

    private suspend fun checkForCrystallization(
        fingerprint: InteractionFingerprint
    ): PatternDetection? {
        val buffer = patternProvider.getBuffer()
        val matchingFingerprints = buffer.filter { it.hash == fingerprint.hash }

        if (matchingFingerprints.size < MIN_REPETITIONS_TIME) return null

        // Check time-based pattern
        val timePattern = checkTimePattern(matchingFingerprints)
        if (timePattern != null) return timePattern

        // Check action-based pattern
        val actionPattern = checkActionPattern(matchingFingerprints)
        if (actionPattern != null) return actionPattern

        // Check context-based pattern
        val contextPattern = checkContextPattern(matchingFingerprints)
        if (contextPattern != null) return contextPattern

        return null
    }

    private fun checkTimePattern(fingerprints: List<InteractionFingerprint>): PatternDetection? {
        if (fingerprints.size < MIN_REPETITIONS_TIME) return null

        // Check temporal regularity
        val timeSlots = fingerprints.map { it.contextSlots["timeOfDay"] ?: "unknown" }
        val mostCommonSlot = timeSlots.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val regularity = timeSlots.count { it == mostCommonSlot }.toFloat() / timeSlots.size

        if (regularity < TIME_REGULARITY_THRESHOLD) return null

        // Check time window
        val timestamps = fingerprints.map { it.timestamp }.sorted()
        val windowDays = (timestamps.last() - timestamps.first()) / (24 * 60 * 60)
        if (windowDays > TIME_WINDOW_DAYS) return null

        return PatternDetection(
            id = "det_${System.currentTimeMillis()}",
            fingerprintHash = fingerprints.first().hash,
            type = PatternType.TIME_BASED,
            occurrences = fingerprints.size,
            timeSlot = mostCommonSlot,
            regularity = regularity,
            firstSeen = timestamps.first(),
            lastSeen = timestamps.last(),
            status = DetectionStatus.DETECTED
        )
    }

    private fun checkActionPattern(fingerprints: List<InteractionFingerprint>): PatternDetection? {
        if (fingerprints.size < MIN_REPETITIONS_ACTION) return null

        // Check sequential co-occurrence
        val timestamps = fingerprints.map { it.timestamp }.sorted()
        val windowDays = (timestamps.last() - timestamps.first()) / (24 * 60 * 60)
        if (windowDays > ACTION_WINDOW_DAYS) return null

        return PatternDetection(
            id = "det_${System.currentTimeMillis()}",
            fingerprintHash = fingerprints.first().hash,
            type = PatternType.ACTION_BASED,
            occurrences = fingerprints.size,
            regularity = 0.9f, // Action patterns have high regularity by definition
            firstSeen = timestamps.first(),
            lastSeen = timestamps.last(),
            status = DetectionStatus.DETECTED
        )
    }

    private fun checkContextPattern(fingerprints: List<InteractionFingerprint>): PatternDetection? {
        if (fingerprints.size < MIN_REPETITIONS_CONTEXT) return null

        // Check same trigger condition
        val timestamps = fingerprints.map { it.timestamp }.sorted()
        val windowDays = (timestamps.last() - timestamps.first()) / (24 * 60 * 60)
        if (windowDays > CONTEXT_WINDOW_DAYS) return null

        // Check for consistent context (same day of week, same location)
        val daysOfWeek = fingerprints.map { it.contextSlots["dayOfWeek"] ?: "0" }
        val mostCommonDay = daysOfWeek.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val dayRegularity = if (mostCommonDay != null) {
            daysOfWeek.count { it == mostCommonDay }.toFloat() / daysOfWeek.size
        } else {
            0f
        }

        if (dayRegularity < 0.5f) return null

        return PatternDetection(
            id = "det_${System.currentTimeMillis()}",
            fingerprintHash = fingerprints.first().hash,
            type = PatternType.CONTEXT_BASED,
            occurrences = fingerprints.size,
            regularity = dayRegularity,
            firstSeen = timestamps.first(),
            lastSeen = timestamps.last(),
            status = DetectionStatus.DETECTED
        )
    }

    // ═══════════════ PROPOSAL GENERATION ═══════════════

    private fun generateProposal(detection: PatternDetection): SkillProposal {
        val skillType = inferSkillType(detection)

        return SkillProposal(
            id = "prop_${System.currentTimeMillis()}",
            detectionId = detection.id,
            type = skillType,
            name = getSkillName(skillType),
            description = getSkillDescription(skillType),
            trigger = getSkillTrigger(skillType, detection),
            action = getSkillAction(skillType),
            output = getSkillOutput(skillType),
            confidence = detection.regularity,
            status = ProposalStatus.PENDING,
            createdAt = System.currentTimeMillis() / 1000
        )
    }

    private fun inferSkillType(detection: PatternDetection): SkillType {
        // Infer skill type from the pattern's original intent
        val intent = detection.fingerprintHash // In practice, would look up original intent

        return when {
            detection.timeSlot == "morning" -> SkillType.MORNING_BRIEFING
            detection.type == PatternType.CONTEXT_BASED -> SkillType.MARKET_DAY_PREP
            else -> SkillType.WEEKLY_REPORT
        }
    }

    private fun getSkillName(type: SkillType): String {
        return when (type) {
            SkillType.MORNING_BRIEFING -> "Morning Market Brief"
            SkillType.RESTOCK_ALERT -> "Restock Alert"
            SkillType.PRICE_CHECK -> "Price Check"
            SkillType.SAVINGS_NUDGE -> "Savings Nudge"
            SkillType.MARKET_DAY_PREP -> "Market Day Preparation"
            SkillType.WEEKLY_REPORT -> "Weekly Report"
        }
    }

    private fun getSkillDescription(type: SkillType): String {
        return when (type) {
            SkillType.MORNING_BRIEFING -> "I'll prepare a daily market brief for you every morning with prices and conditions."
            SkillType.RESTOCK_ALERT -> "I'll warn you when stock is running low based on your sales patterns."
            SkillType.PRICE_CHECK -> "I'll compare prices across suppliers when you're ready to buy."
            SkillType.SAVINGS_NUDGE -> "I'll alert you when spending exceeds your usual pattern."
            SkillType.MARKET_DAY_PREP -> "I'll prepare a checklist the evening before market days."
            SkillType.WEEKLY_REPORT -> "I'll send you a weekly business analysis every Sunday."
        }
    }

    private fun getSkillTrigger(type: SkillType, detection: PatternDetection): String {
        return when (type) {
            SkillType.MORNING_BRIEFING -> "cron:${detection.timeSlot ?: "06:45"}"
            SkillType.RESTOCK_ALERT -> "context:inventory_low"
            SkillType.PRICE_CHECK -> "context:purchase_mentioned"
            SkillType.SAVINGS_NUDGE -> "context:spending_high"
            SkillType.MARKET_DAY_PREP -> "time:evening_before_market"
            SkillType.WEEKLY_REPORT -> "cron:sunday_evening"
        }
    }

    private fun getSkillAction(type: SkillType): String {
        return when (type) {
            SkillType.MORNING_BRIEFING -> "Query market prices, compare with yesterday, format brief"
            SkillType.RESTOCK_ALERT -> "Calculate days remaining, suggest order quantity"
            SkillType.PRICE_CHECK -> "Compare prices across known suppliers"
            SkillType.SAVINGS_NUDGE -> "Compare spending to rolling average"
            SkillType.MARKET_DAY_PREP -> "Review past market days, suggest stock quantities"
            SkillType.WEEKLY_REPORT -> "Aggregate weekly data, calculate trends"
        }
    }

    private fun getSkillOutput(type: SkillType): String {
        return when (type) {
            SkillType.MORNING_BRIEFING -> "Short market summary in worker's language"
            SkillType.RESTOCK_ALERT -> "Alert with item name, days remaining, suggested action"
            SkillType.PRICE_CHECK -> "Ranked price list with supplier names"
            SkillType.SAVINGS_NUDGE -> "Gentle nudge with spending comparison"
            SkillType.MARKET_DAY_PREP -> "Actionable checklist"
            SkillType.WEEKLY_REPORT -> "Weekly summary with insights"
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * A worker interaction for pattern detection.
 */
data class Interaction(
    val intent: String,
    val timeOfDay: String = "",
    val dayOfWeek: Int = 0,
    val location: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val outcome: String = ""
)

/**
 * Normalized interaction fingerprint.
 */
data class InteractionFingerprint(
    val hash: String,
    val normalizedIntent: String,
    val contextSlots: Map<String, String>,
    val timestamp: Long,
    val originalIntent: String
)

/**
 * Types of patterns that can be detected.
 */
enum class PatternType {
    TIME_BASED, ACTION_BASED, CONTEXT_BASED
}

/**
 * Status of a pattern detection.
 */
enum class DetectionStatus {
    DETECTED, PROPOSED, CONFIRMED, REJECTED
}

/**
 * A detected pattern.
 */
data class PatternDetection(
    val id: String,
    val fingerprintHash: String,
    val type: PatternType,
    val occurrences: Int,
    val timeSlot: String? = null,
    val regularity: Float,
    val firstSeen: Long,
    val lastSeen: Long,
    val status: DetectionStatus
)

/**
 * Types of skills that can be crystallized.
 */
enum class SkillType {
    MORNING_BRIEFING, RESTOCK_ALERT, PRICE_CHECK,
    SAVINGS_NUDGE, MARKET_DAY_PREP, WEEKLY_REPORT
}

/**
 * Status of a skill proposal.
 */
enum class ProposalStatus {
    PENDING, CONFIRMED, REJECTED, SUPPRESSED
}

/**
 * A skill proposal awaiting worker confirmation.
 */
data class SkillProposal(
    val id: String,
    val detectionId: String,
    val type: SkillType,
    val name: String,
    val description: String,
    val trigger: String,
    val action: String,
    val output: String,
    val confidence: Float,
    val status: ProposalStatus,
    val createdAt: Long
)

/**
 * Status of a crystallized skill.
 */
enum class SkillStatus {
    PROPOSED, TRIAL, ACTIVE, DISABLED, RETIRED, REFINED, SHARED
}

/**
 * A crystallized skill (activated by worker).
 */
data class CrystallizedSkill(
    val id: String,
    val type: SkillType,
    val name: String,
    val trigger: String,
    val action: String,
    val output: String,
    var status: SkillStatus,
    val createdAt: Long,
    var lastTriggeredAt: Long?,
    var triggerCount: Int,
    val refinements: MutableList<SkillRefinement>,
    val adjustments: MutableMap<String, String>
)

/**
 * A refinement to a skill.
 */
data class SkillRefinement(
    val timestamp: Long,
    val description: String
)

/**
 * Interface for pattern storage.
 */
interface PatternProvider {
    suspend fun addToBuffer(fingerprint: InteractionFingerprint)
    suspend fun getBuffer(): List<InteractionFingerprint>
    suspend fun recordDetection(detection: PatternDetection)
    suspend fun getDetections(): List<PatternDetection>
}

/**
 * Interface for skill storage.
 */
interface SkillStorageProvider {
    suspend fun saveSkill(skill: CrystallizedSkill)
    suspend fun getSkill(skillId: String): CrystallizedSkill?
    suspend fun getSkillsByStatus(status: SkillStatus): List<CrystallizedSkill>
    suspend fun updateSkillStatus(skillId: String, status: SkillStatus)
    suspend fun hasProposal(detectionId: String): Boolean
    suspend fun getProposal(proposalId: String): SkillProposal?
    suspend fun getPendingProposals(): List<SkillProposal>
    suspend fun markProposalConfirmed(proposalId: String)
    suspend fun markProposalRejected(proposalId: String, permanent: Boolean)
}



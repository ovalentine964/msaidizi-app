package com.msaidizi.core.common.model

/**
 * Project model for project-based workers (fundi, mama fua, artisans).
 *
 * Tracks multi-day/multi-visit work that can't be captured as single
 * transactions. Examples:
 * - Fundi building a house (30-day project)
 * - Mama fua weekly cleaning contract
 * - Tailor making wedding dress (3 visits)
 *
 * ## Proof Accumulation
 * Projects generate multiple proof points (one per milestone/payment),
 * accelerating Alama Score growth for service workers.
 */
data class Project(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Project name — "Nyumba ya Amina", "Harusi ya Njeri" */
    val name: String = "",
    /** Client name */
    val clientName: String = "",
    /** Client phone */
    val clientPhone: String = "",
    /** Project description */
    val description: String = "",
    /** Project category — "construction", "cleaning", "tailoring", "event" */
    val category: String = "",

    // ═══ FINANCIALS ═══
    /** Quoted/contracted price (KSh) */
    val quotedPrice: Double = 0.0,
    /** Total amount paid so far (KSh) */
    val totalPaid: Double = 0.0,
    /** Total expenses/materials cost (KSh) */
    val totalExpenses: Double = 0.0,
    /** Estimated profit = quotedPrice - totalExpenses */
    val estimatedProfit: Double = 0.0,
    /** Payment method for this project */
    val paymentMethod: String = "cash",

    // ═══ TIMELINE ═══
    /** When the project was started */
    val startDate: Long = System.currentTimeMillis(),
    /** Expected completion date */
    val expectedEndDate: Long = 0L,
    /** Actual completion date (null if ongoing) */
    val actualEndDate: Long? = null,
    /** Current phase — "planning", "in_progress", "finishing", "completed" */
    val phase: String = "planning",

    // ═══ PROGRESS ═══
    /** Milestones completed (comma-separated descriptions) */
    val milestonesCompleted: String = "",
    /** Total milestones expected */
    val totalMilestones: Int = 0,
    /** Progress percentage (0-100) */
    val progressPercent: Int = 0,

    // ═══ TRACKING ═══
    /** Number of work sessions/visits */
    val totalVisits: Int = 0,
    /** Total hours worked */
    val totalHours: Double = 0.0,
    /** Materials used (comma-separated) */
    val materialsUsed: String = "",

    // ═══ STATUS ═══
    /** Whether project is active */
    val isActive: Boolean = true,
    /** Whether there's an outstanding balance from client */
    val hasOutstandingBalance: Boolean = false,
    /** Outstanding amount (KSh) */
    val outstandingAmount: Double = 0.0,

    // ═══ TIMESTAMPS ═══
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    /**
     * Amount remaining to be collected.
     */
    val remainingAmount: Double
        get() = quotedPrice - totalPaid

    /**
     * Whether the project is overdue.
     */
    val isOverdue: Boolean
        get() = expectedEndDate > 0 && actualEndDate == null &&
                System.currentTimeMillis() > expectedEndDate

    /**
     * Current profit based on payments received.
     */
    val currentProfit: Double
        get() = totalPaid - totalExpenses

    /**
     * Project is completed if actualEndDate is set.
     */
    val isCompleted: Boolean
        get() = actualEndDate != null
}

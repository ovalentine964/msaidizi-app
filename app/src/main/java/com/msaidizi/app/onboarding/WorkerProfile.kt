package com.msaidizi.app.onboarding

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.msaidizi.app.core.database.Converters
import com.msaidizi.app.agent.WorkerType

/**
 * Worker Profile — everything Msaidizi learns about the worker during onboarding.
 *
 * This is NOT a form. It's what Msaidizi naturally learns through conversation,
 * the same way a real CFO would get to know a client over a cup of chai.
 *
 * ## Academic Foundations
 *
 * ### ECO 101 — Consumer Theory
 * - Budget constraints and preferences shape purchasing behavior
 * - Payment method reveals financial infrastructure access
 * - Record-keeping signals financial literacy level
 *
 * ### ECO 201 — Producer Theory
 * - Production function: inputs → outputs (supply method)
 * - Cost structure: fixed vs variable costs inferred from business type
 * - Returns to scale: solo vs team work
 *
 * ### ECO 204 — African Development Context
 * - Gender dimensions: work patterns differ by gender
 * - Rural-urban continuum: location affects market access
 * - Informal institutions: trust networks, social capital
 *
 * ### STA 142 — Bayesian Inference
 * - Each answer updates the prior distribution over worker type
 * - Early answers set strong priors, later answers refine
 * - Confidence increases with each piece of evidence
 *
 * ### BCB 108 — Communication
 * - Multilingual from the start: worker's language, not ours
 * - Culturally appropriate: respects communication norms
 * - Voice-first: matches how informal workers actually communicate
 *
 * @see OnboardingConversation for the interview that populates this
 * @see BusinessDiscoveryFragment for the UI that presents it
 */
@Entity(tableName = "worker_profile")
@TypeConverters(Converters::class)
data class WorkerProfile(
    @PrimaryKey
    val id: Long = 1,  // Singleton — one worker per device

    // ── Agent Personalization ──
    /** What the worker calls their Msaidizi (e.g., "Rafiki", "Biashara Yangu") */
    val msaidiziName: String = "Msaidizi",

    // ── Identity ──
    /** Worker's name as introduced */
    val workerName: String = "",

    // ── Business Core (ECO 201: Production Function) ──
    /** Classified business type */
    val businessType: WorkerType = WorkerType.UNKNOWN,

    /** Free-text business description from conversation */
    val businessDescription: String = "",

    /** Specific products or services offered */
    val products: List<String> = emptyList(),

    // ── Location & Time (ECO 204: Development Context) ──
    /** Where they work: market, roadside, home, mobile */
    val location: String = "",

    /** Market or area name for local context */
    val marketName: String = "",

    /** Typical working hours */
    val workingHours: WorkingHours = WorkingHours(),

    // ── Business Operations (ECO 201: Cost Structure) ──
    /** Whether they work alone or with others */
    val workAlone: Boolean = true,

    /** How they get their products/supplies */
    val supplyMethod: String = "",

    /** How customers find them */
    val customerFindMethod: String = "",

    // ── Financial Infrastructure (ECO 101: Budget Constraints) ──
    /** How they receive payment */
    val paymentMethod: PaymentType = PaymentType.BOTH,

    /** Whether and how they keep business records */
    val keepsRecords: RecordMethod = RecordMethod.MEMORY,

    // ── Challenges & Goals ──
    /** Their biggest business challenge (free text) */
    val biggestChallenge: String = "",

    // ── Language & Communication (BCB 108) ──
    /** Primary language code (e.g., "sw", "en", "ha") */
    val language: String = "sw",

    /** Dialect region for localized communication */
    val dialect: String = "STANDARD",

    // ── Bayesian Priors (STA 142) ──
    /** Confidence in business type classification (0.0-1.0) */
    val classificationConfidence: Double = 0.0,

    /** Number of conversation turns used to build this profile */
    val conversationTurns: Int = 0,

    // ── Metadata ──
    /** When onboarding completed */
    val onboardingCompletedAt: Long = 0,

    /** Whether models finished downloading */
    val modelsReady: Boolean = false,

    /** Profile version for migration */
    val version: Int = 1
)

/**
 * Working hours — when the worker does business.
 *
 * ECO 204: Time allocation theory — how workers split time between
 * market work, household production, and leisure.
 */
data class WorkingHours(
    /** Start hour (0-23) */
    val startHour: Int = 6,
    /** End hour (0-23) */
    val endHour: Int = 18,
    /** Whether hours are consistent day-to-day */
    val consistent: Boolean = true,
    /** Days per week they work */
    val daysPerWeek: Int = 6,
    /** Free-text description (e.g., "asubuhi mpaka jioni") */
    val description: String = ""
)

/**
 * Payment methods used by the worker.
 *
 * ECO 209 (Money & Banking): Payment method indicates financial
 * inclusion level and transaction cost burden.
 */
enum class PaymentType {
    /** Cash only — highest transaction costs, no digital trail */
    CASH,
    /** M-Pesa or other mobile money — lower costs, digital trail */
    MOBILE,
    /** Both cash and mobile — most common in Kenya's informal sector */
    BOTH,
    /** Bank transfers — rare for informal workers */
    BANK
}

/**
 * How the worker keeps business records.
 *
 * ECO 206 (Microfinance): Record-keeping is a strong predictor
 * of creditworthiness and business survival.
 */
enum class RecordMethod {
    /** No records — relies on memory (most common) */
    MEMORY,
    /** Notebook or paper records */
    NOTEBOOK,
    /** Phone (notes app, calculator, etc.) */
    PHONE,
    /** Formal bookkeeping (rare for informal sector) */
    BOOKKEEPING
}

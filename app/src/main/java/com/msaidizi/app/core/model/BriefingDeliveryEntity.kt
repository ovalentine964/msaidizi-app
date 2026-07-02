package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks briefing delivery and closes the feedback loop.
 *
 * When a briefing is delivered, we record it. When the worker acts on it
 * (records a sale, checks stock, etc.), we mark it as acted on. This lets
 * us compare predicted vs actual outcomes and improve future briefings.
 *
 * The Morning Briefing Loop:
 *   Generate → Deliver → Worker Acts → Track Outcome → Adjust → Better Briefing
 */
@Entity(
    tableName = "briefing_deliveries",
    indices = [
        Index(value = ["briefingType"]),
        Index(value = ["deliveredAt"]),
        Index(value = ["actedOn"])
    ]
)
data class BriefingDeliveryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Type of briefing: MORNING, EVENING, WEEKLY, ALERT */
    val briefingType: String,

    /** The actual text delivered to the worker */
    val briefingText: String,

    /** Predicted sales from briefing (KSh) */
    val predictedSales: Double = 0.0,

    /** Predicted profit from briefing (KSh) */
    val predictedProfit: Double = 0.0,

    /** Key advice given (e.g., "restock nyanya") */
    val keyAdvice: String = "",

    /** Whether the worker opened/viewed the briefing */
    val opened: Boolean = false,

    /** Timestamp when briefing was opened */
    val openedAt: Long = 0,

    /** Whether the worker acted on the briefing (recorded transaction after) */
    val actedOn: Boolean = false,

    /** Timestamp when worker acted on briefing */
    val actedOnAt: Long = 0,

    /** Actual sales recorded after briefing (KSh) */
    val actualSales: Double = 0.0,

    /** Actual profit recorded after briefing (KSh) */
    val actualProfit: Double = 0.0,

    /** Outcome score: how well did prediction match actual? (-1.0 to 1.0) */
    val outcomeScore: Double = 0.0,

    /** Whether advice was followed (e.g., restocked as recommended) */
    val adviceFollowed: Boolean? = null,

    /** Timestamp when briefing was delivered */
    val deliveredAt: Long = System.currentTimeMillis() / 1000
)

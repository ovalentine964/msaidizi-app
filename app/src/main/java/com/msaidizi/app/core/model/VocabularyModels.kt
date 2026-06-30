package com.msaidizi.app.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A word the user spoke that wasn't in the built-in dictionary.
 * Tracked by AdaptiveVocabulary for on-device learning.
 *
 * When frequency reaches a threshold, the word is promoted to
 * UserVocabulary with an inferred or user-confirmed canonical form.
 *
 * This entity is distinct from UserVocabulary:
 * - LearnedWord: raw unknown words pending classification
 * - UserVocabulary: confirmed vocabulary entries with price tracking
 */
@Entity(
    tableName = "learned_words",
    indices = [
        Index(value = ["frequency"]),
        Index(value = ["canonicalForm"])
    ]
)
@Serializable
data class LearnedWord(
    @PrimaryKey
    val word: String,

    /** How many times the user has spoken this word */
    val frequency: Int = 1,

    /** The dialect region where this word was heard */
    val dialectRegion: String = "STANDARD",

    /** Inferred canonical/standard form (null = not yet mapped) */
    val canonicalForm: String? = null,

    /** Category hint: "product", "unit", "currency", "action", "unknown" */
    val categoryHint: String = "unknown",

    /** First time this word was encountered */
    val firstSeenAt: Long = System.currentTimeMillis() / 1000,

    /** Last time this word was spoken */
    val lastSeenAt: Long = System.currentTimeMillis() / 1000,

    /** When the canonical form was mapped (null if unmapped) */
    val mappedAt: Long? = null
)

package com.msaidizi.app.agent.autonomy

import com.msaidizi.app.core.database.PatternDao

/**
 * Stub: Progressive autonomy for gradually increasing agent independence.
 */
class ProgressiveAutonomy(private val patternDao: PatternDao? = null) {
    fun getCurrentLevel(): Int = 1
    fun canAutoConfirm(): Boolean = false
    fun recordAccuracy(wasCorrect: Boolean) {}
}

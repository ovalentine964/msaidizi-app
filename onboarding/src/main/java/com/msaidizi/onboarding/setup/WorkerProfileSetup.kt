package com.msaidizi.onboarding.setup

import com.msaidizi.core.common.model.AlamaTier
import com.msaidizi.core.common.model.BusinessCategories
import com.msaidizi.core.database.dao.WorkerProfileDao
import com.msaidizi.core.database.entity.WorkerProfileEntity
import com.msaidizi.onboarding.bootstrap.BootstrapResult

/**
 * Worker profile setup — creates the initial worker profile from
 * bootstrap conversation data.
 *
 * ## M-KOPA Parallel
 * Like M-KOPA's customer profile that builds through payment history,
 * Msaidizi's WorkerProfile starts minimal and enriches over time.
 * The bootstrap captures only what's needed: name, business, location.
 *
 * ## Progressive Enrichment
 * Profile fields are filled over time:
 * - Day 1: name, location, business type (from bootstrap)
 * - Day 7: work patterns, operating hours (from transaction analysis)
 * - Day 30: financial patterns, margins (from accumulated data)
 * - Day 90: credit readiness, Alama Score (from proof accumulation)
 */
class WorkerProfileSetup(
    private val workerProfileDao: WorkerProfileDao
) {
    /**
     * Create initial worker profile from bootstrap result.
     * Called after successful bootstrap conversation.
     */
    suspend fun createFromBootstrap(result: BootstrapResult): WorkerProfileEntity {
        // Auto-classify business category
        val businessCategory = classifyBusinessCategory(result.businessType)

        // Auto-detect typical shelf life for common items
        val primaryProducts = detectPrimaryProducts(result.firstTransaction.item, result.secondTransaction.item)

        val profile = WorkerProfileEntity(
            name = result.name,
            businessType = result.businessType,
            businessCategory = businessCategory,
            primaryProducts = primaryProducts,
            locationName = result.location,
            language = result.language,
            dialect = result.dialect,
            workPattern = "new",
            daysActive = 1,
            currentStreak = 1,
            longestStreak = 1,
            totalTransactions = 2, // First sale + first purchase
            alamaScore = result.proofPoints.sumOf { it.weight },
            alamaTier = AlamaTier.MTOTO.name,
            totalProofPoints = result.proofPoints.size,
            createdAt = result.completedAt,
            lastInteractionAt = result.completedAt,
            updatedAt = result.completedAt
        )

        val id = workerProfileDao.insert(profile)
        return profile.copy(id = id)
    }

    /**
     * Update worker profile with new data.
     * Called as the worker uses Msaidizi and patterns emerge.
     */
    suspend fun updateProfile(
        profileId: Long,
        field: String,
        value: Any
    ) {
        when (field) {
            "name" -> workerProfileDao.updateName(profileId, value as String)
            "business" -> {
                val parts = (value as String).split("|")
                workerProfileDao.updateBusiness(
                    profileId,
                    parts.getOrElse(0) { "" },
                    parts.getOrElse(1) { "" }
                )
            }
            "location" -> workerProfileDao.updateLocation(
                profileId,
                value as String,
                null, null, "", ""
            )
            "language" -> workerProfileDao.updateLanguage(
                profileId,
                value as String, "", false, "casual"
            )
        }
    }

    /**
     * Auto-classify business category from business type description.
     */
    private fun classifyBusinessCategory(businessType: String): String {
        val lower = businessType.lowercase()
        return when {
            lower.contains("mboga") || lower.contains("chakula") || lower.contains("nyama") ||
            lower.contains("samaki") || lower.contains("matunda") || lower.contains("maziwa") ||
            lower.contains("mama") && lower.contains("njero") -> BusinessCategories.FOOD_VENDOR

            lower.contains("duka") || lower.contains("shop") || lower.contains("soko") ||
            lower.contains("biashara") -> BusinessCategories.RETAIL

            lower.contains("fundi") || lower.contains("service") || lower.contains("kufua") ||
            lower.contains("kupika") || lower.contains("mama fua") -> BusinessCategories.SERVICES

            lower.contains("boda") || lower.contains("pikipiki") || lower.contains("tuktuk") ||
            lower.contains("matatu") -> BusinessCategories.TRANSPORT

            lower.contains("shamba") || lower.contains("kulima") || lower.contains("mazao") ->
                BusinessCategories.AGRICULTURE

            lower.contains("simu") || lower.contains("computer") || lower.contains("cyber") ->
                BusinessCategories.TECH

            else -> BusinessCategories.RETAIL // Default
        }
    }

    /**
     * Detect primary products from first transactions.
     */
    private fun detectPrimaryProducts(item1: String, item2: String): String {
        return listOf(item1, item2)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    /**
     * Get onboarding progress status.
     */
    suspend fun getOnboardingProgress(): OnboardingProgress {
        val profile = workerProfileDao.getProfile()
        return if (profile == null) {
            OnboardingProgress.NOT_STARTED
        } else when {
            profile.daysActive < 1 -> OnboardingProgress.BOOTSTRAP_COMPLETE
            profile.daysActive < 7 -> OnboardingProgress.ESTABLISHING_HABIT
            profile.daysActive < 30 -> OnboardingProgress.BUILDING_PATTERNS
            profile.daysActive < 90 -> OnboardingProgress.MATURE
            else -> OnboardingProgress.FULLY_ONBOARDED
        }
    }
}

/**
 * Onboarding progress stages.
 */
enum class OnboardingProgress {
    /** No profile exists — show bootstrap */
    NOT_STARTED,
    /** Bootstrap complete, first transactions recorded */
    BOOTSTRAP_COMPLETE,
    /** 1-7 days active, building daily habit */
    ESTABLISHING_HABIT,
    /** 7-30 days active, patterns emerging */
    BUILDING_PATTERNS,
    /** 30-90 days active, reliable data */
    MATURE,
    /** 90+ days active, fully on-boarded, credit eligible */
    FULLY_ONBOARDED
}

package com.msaidizi.app.core.model

/**
 * Model delivery tiers for graduated download strategy.
 *
 * Designed for African data plans (1GB/month typical):
 *
 * - BUNDLED: Ships in APK (~15MB). Available immediately, no download needed.
 * - FIRST_LAUNCH: Downloaded on first launch over mobile data (~65MB).
 *   Small enough for any data plan.
 * - INITIAL_DOWNLOAD: Smallest viable LLM for data-limited users (~300MB).
 *   Q2_K quantization — works, but quality is reduced. Upgrades later on WiFi.
 * - ON_DEMAND: Full quality models (~580MB-1.5GB). WiFi-only or peer transfer.
 *
 * ## Progressive Loading Strategy
 *
 * Users with limited data follow this path:
 * 1. BUNDLED → works immediately (mini-model in APK)
 * 2. FIRST_LAUNCH → voice features (~65MB, any network)
 * 3. INITIAL_DOWNLOAD → basic LLM (~300MB, warned on mobile data)
 * 4. ON_DEMAND → full LLM (~580MB-1.5GB, WiFi-only)
 *
 * Users with WiFi/unlimited data skip to ON_DEMAND directly.
 */
enum class ModelTier {
    BUNDLED,
    FIRST_LAUNCH,
    INITIAL_DOWNLOAD,
    ON_DEMAND
}

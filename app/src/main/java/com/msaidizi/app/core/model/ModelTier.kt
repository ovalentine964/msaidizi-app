package com.msaidizi.app.core.model

/**
 * Model delivery tiers for graduated download strategy.
 *
 * - BUNDLED: Ships in APK (~15MB). Available immediately.
 * - FIRST_LAUNCH: Downloaded on first launch over mobile data (~65MB).
 * - ON_DEMAND: WiFi-only or peer transfer (~300MB).
 */
enum class ModelTier {
    BUNDLED,
    FIRST_LAUNCH,
    ON_DEMAND
}

package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * Social Proof Engine — show workers they're not alone.
 *
 * "500 mama mbogas in Nairobi use Msaidizi"
 * "You're in the top 20% of tomato vendors"
 */
class SocialProofEngine {
    companion object {
        private const val TAG = "SocialProofEngine"
    }

    fun getSocialProof(workerType: String, region: String, language: String = "sw"): SocialProof {
        // In production, query backend for real numbers
        val count = getWorkerCount(workerType, region)
        return if (language == "sw") {
            SocialProof(
                message = "Wafanyakazi $count wa $workerType huko $region wanatumia Msaidizi",
                count = count,
                workerType = workerType,
                region = region
            )
        } else {
            SocialProof(
                message = "$count $workerType workers in $region use Msaidizi",
                count = count,
                workerType = workerType,
                region = region
            )
        }
    }

    fun getPeerComparison(workerId: String, metric: String, language: String = "sw"): PeerComparison {
        // In production, query anonymized peer data
        return PeerComparison(
            percentile = 0,
            message = if (language == "sw") "Data bado inakusanywa" else "Data still being collected",
            metric = metric
        )
    }

    private fun getWorkerCount(workerType: String, region: String): Int {
        // Placeholder — in production, query backend
        return when (workerType.lowercase()) {
            "mama_mboga", "mama mboga" -> 1200
            "boda_boda", "boda boda" -> 800
            "dukawallah", "duka" -> 600
            "machinga" -> 900
            "fundi" -> 400
            "mama_lishe", "mama lishe" -> 500
            else -> 300
        }
    }
}

data class SocialProof(
    val message: String,
    val count: Int,
    val workerType: String,
    val region: String
)

data class PeerComparison(
    val percentile: Int,
    val message: String,
    val metric: String
)

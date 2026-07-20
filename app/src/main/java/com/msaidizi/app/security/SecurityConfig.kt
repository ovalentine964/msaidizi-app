package com.msaidizi.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import com.msaidizi.app.BuildConfig
import com.msaidizi.app.security.crypto.EncryptedStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized security configuration and runtime security checks.
 *
 * Consolidates device integrity, SIM change detection, environment config,
 * and certificate pin management into a single entry point.
 *
 * Design philosophy: Real threats first, quantum later.
 * - Root detection: prevents extraction of SQLCipher keys, tokens, PII
 * - SIM change detection: defends against SIM swap attacks on M-Pesa accounts
 * - Environment-aware cert pins: dev can use mitmproxy, prod enforces strict pinning
 */
@Singleton
class SecurityConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedStorage: EncryptedStorage
) {
    companion object {
        private const val TAG = "SecurityConfig"
        private const val KEY_BASELINE_SIM_SERIAL = "security_baseline_sim_serial"
        private const val KEY_BASELINE_CARRIER = "security_baseline_carrier"
        private const val KEY_BASELINE_SIM_COUNTRY = "security_baseline_sim_country"
        private const val KEY_SIM_CHECK_RESULT = "security_last_sim_check"
    }

    // ── Environment ──────────────────────────────────────────

    enum class Environment {
        DEVELOPMENT,
        STAGING,
        PRODUCTION
    }

    /**
     * Current environment. Derived from BuildConfig.DEBUG and a system property override.
     *
     * Override via adb for testing:
     *   adb shell setprop debug.msaidizi.env staging
     */
    fun getEnvironment(): Environment {
        val override = System.getProperty("debug.msaidizi.env")
            ?: System.getenv("MSAIDIZI_ENV")
        return when (override?.lowercase()) {
            "dev", "development" -> Environment.DEVELOPMENT
            "staging", "stage" -> Environment.STAGING
            "prod", "production" -> Environment.PRODUCTION
            else -> if (BuildConfig.DEBUG) Environment.DEVELOPMENT else Environment.PRODUCTION
        }
    }

    // ── Root Detection ───────────────────────────────────────

    /**
     * Check if the device is rooted.
     *
     * Uses multiple heuristics — no single check is reliable alone.
     * A positive result means the device has elevated privileges that could
     * compromise SQLCipher keys, EncryptedSharedPreferences, or Keystore.
     *
     * For production apps with Play Store distribution, also integrate
     * Play Integrity API (com.google.android.play:integrity) for
     * hardware-backed device attestation. This basic check covers
     * sideloaded APKs and non-Play-Store distribution.
     */
    fun isRootedDevice(): Boolean {
        return checkSuBinary() ||
            checkDangerousProps() ||
            checkRootPackages() ||
            checkSuWhich() ||
            checkTestKeys()
    }

    /**
     * Detailed root detection result for logging/analytics.
     */
    data class RootDetectionResult(
        val isRooted: Boolean,
        val suBinaryFound: Boolean,
        val dangerousProps: Boolean,
        val rootPackages: Boolean,
        val testKeys: Boolean,
        val suWhich: Boolean
    )

    fun getRootDetectionDetail(): RootDetectionResult {
        val su = checkSuBinary()
        val props = checkDangerousProps()
        val packages = checkRootPackages()
        val testKeys = checkTestKeys()
        val suWhich = checkSuWhich()
        return RootDetectionResult(
            isRooted = su || props || packages || testKeys || suWhich,
            suBinaryFound = su,
            dangerousProps = props,
            rootPackages = packages,
            testKeys = testKeys,
            suWhich = suWhich
        )
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su", "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk", "/cache/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkDangerousProps(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.tags"))
            val tags = process.inputStream.bufferedReader().readLine()?.trim() ?: ""
            process.waitFor()
            tags.contains("test-keys")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkTestKeys(): Boolean {
        return Build.TAGS?.contains("test-keys") == true
    }

    private fun checkRootPackages(): Boolean {
        val rootPackages = setOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.noshufou.android.su",
            "com.devadvance.rootcloak",
            "de.robv.android.xposed.installer"
        )
        return try {
            val pm = context.packageManager
            rootPackages.any { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSuWhich(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val result = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            !result.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    // ── SIM Change Detection ─────────────────────────────────

    data class SimInfo(
        val serialNumber: String?,
        val carrierName: String?,
        val networkCountry: String?,
        val simOperator: String?
    )

    enum class SimCheckResult {
        /** SIM matches baseline — normal operation */
        MATCH,
        /** SIM has changed since last baseline — possible swap */
        CHANGED,
        /** No baseline recorded yet — first run */
        NO_BASELINE,
        /** TelephonyManager unavailable (no SIM, tablet, emulator) */
        UNAVAILABLE,
        /** Permission denied — cannot read SIM info */
        PERMISSION_DENIED
    }

    data class SimChangeAssessment(
        val result: SimCheckResult,
        val currentSim: SimInfo?,
        val baselineCarrier: String?,
        val changedAt: Long?
    )

    /**
     * Record the current SIM as the trusted baseline.
     * Called during onboarding or after user re-verification.
     */
    fun recordSimBaseline() {
        val simInfo = getCurrentSimInfo() ?: return
        encryptedStorage.putString(KEY_BASELINE_SIM_SERIAL, simInfo.serialNumber ?: "")
        encryptedStorage.putString(KEY_BASELINE_CARRIER, simInfo.carrierName ?: "")
        encryptedStorage.putString(KEY_BASELINE_SIM_COUNTRY, simInfo.networkCountry ?: "")
        Timber.i("SIM baseline recorded: carrier=%s, country=%s",
            simInfo.carrierName, simInfo.networkCountry)
    }

    /**
     * Check if the current SIM matches the recorded baseline.
     *
     * This is a client-side heuristic. For production M-Pesa protection,
     * also use Safaricom's SIM swap detection API (server-side).
     *
     * @return Assessment with match status and details
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun checkSimChanged(): SimChangeAssessment {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return SimChangeAssessment(SimCheckResult.UNAVAILABLE, null, null, null)

        val currentSim = try {
            getCurrentSimInfo()
        } catch (e: SecurityException) {
            return SimChangeAssessment(SimCheckResult.PERMISSION_DENIED, null, null, null)
        }

        if (currentSim == null) {
            return SimChangeAssessment(SimCheckResult.UNAVAILABLE, null, null, null)
        }

        val baselineCarrier = encryptedStorage.getString(KEY_BASELINE_CARRIER)
        val baselineCountry = encryptedStorage.getString(KEY_BASELINE_SIM_COUNTRY)

        if (baselineCarrier.isNullOrEmpty()) {
            return SimChangeAssessment(
                SimCheckResult.NO_BASELINE, currentSim, null, null
            )
        }

        val carrierChanged = baselineCarrier.isNotBlank() &&
            currentSim.carrierName != null &&
            !carrierEquals(baselineCarrier, currentSim.carrierName)

        val countryChanged = !baselineCountry.isNullOrBlank() &&
            currentSim.networkCountry != null &&
            !baselineCountry.equals(currentSim.networkCountry, ignoreCase = true)

        val result = if (carrierChanged || countryChanged) {
            Timber.w("SIM CHANGE DETECTED: carrier %s→%s, country %s→%s",
                baselineCarrier, currentSim.carrierName,
                baselineCountry, currentSim.networkCountry)
            encryptedStorage.putString(KEY_SIM_CHECK_RESULT, System.currentTimeMillis().toString())
            SimCheckResult.CHANGED
        } else {
            SimCheckResult.MATCH
        }

        return SimChangeAssessment(
            result = result,
            currentSim = currentSim,
            baselineCarrier = baselineCarrier,
            changedAt = if (result == SimCheckResult.CHANGED)
                encryptedStorage.getString(KEY_SIM_CHECK_RESULT)?.toLongOrNull() else null
        )
    }

    /**
     * Is the user currently in the SIM change cooling period (48h)?
     */
    fun isInSimCoolingPeriod(): Boolean {
        val changeTime = encryptedStorage.getString(KEY_SIM_CHECK_RESULT)?.toLongOrNull()
            ?: return false
        return (System.currentTimeMillis() - changeTime) < 48 * 60 * 60 * 1000L
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getCurrentSimInfo(): SimInfo? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null

        return try {
            // SIM serial requires READ_PHONE_STATE; carrier/country do not
            val serial = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    tm.simSerialNumber
                } else {
                    // Android 10+: simSerialNumber requires READ_PRIVILEGED_PHONE_STATE
                    null
                }
            } catch (e: SecurityException) {
                null
            }

            SimInfo(
                serialNumber = serial,
                carrierName = tm.simOperatorName?.takeIf { it.isNotBlank() },
                networkCountry = tm.networkCountryIso?.takeIf { it.isNotBlank() },
                simOperator = tm.simOperator?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to read SIM info")
            null
        }
    }

    /**
     * Fuzzy carrier name comparison — handles variations like
     * "Safaricom" vs "SAFARICOM" vs "Safaricom PLC"
     */
    private fun carrierEquals(a: String, b: String): Boolean {
        val normalize: (String) -> String = { s ->
            s.lowercase().replace(Regex("\\s+(plc|ltd|limited)"), "").trim()
        }
        return normalize(a) == normalize(b)
    }

    // ── Certificate Pin Configuration ────────────────────────

    data class CertificatePinConfig(
        val host: String,
        val pins: List<String>,
        val enforcePinning: Boolean
    )

    /**
     * Get certificate pin configuration for the current environment.
     *
     * - DEVELOPMENT: Pinning disabled (allows mitmproxy, Charles, etc.)
     * - STAGING: Pinning enabled with staging pins
     * - PRODUCTION: Pinning enforced with production pins
     */
    fun getCertificatePins(): List<CertificatePinConfig> {
        return when (getEnvironment()) {
            Environment.DEVELOPMENT -> {
                Timber.d("Certificate pinning DISABLED for development")
                emptyList()
            }
            Environment.STAGING -> listOf(
                CertificatePinConfig(
                    host = "api.angavu.io",
                    pins = listOf(
                        "sha256/C/5hW8MVw+3h8YoFOUoRz2OgNFNPTq3MwGE/2siEpx0=",
                        "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0="
                    ),
                    enforcePinning = true
                )
            )
            Environment.PRODUCTION -> listOf(
                CertificatePinConfig(
                    host = "api.angavu.com",
                    pins = listOf(
                        "sha256/C/5hW8MVw+3h8YoFOUoRz2OgNFNPTq3MwGE/2siEpx0=",
                        "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0="
                    ),
                    enforcePinning = true
                ),
                CertificatePinConfig(
                    host = "api.angavu.io",
                    pins = listOf(
                        "sha256/C/5hW8MVw+3h8YoFOUoRz2OgNFNPTq3MwGE/2siEpx0=",
                        "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0="
                    ),
                    enforcePinning = true
                )
            )
        }
    }

    /**
     * Should certificate pinning be enforced for this build?
     */
    fun shouldEnforceCertificatePinning(): Boolean {
        return getEnvironment() != Environment.DEVELOPMENT
    }

    // ── Security Gate — App Startup Check ────────────────────

    data class SecurityGateResult(
        val allowed: Boolean,
        val isRooted: Boolean,
        val simChanged: Boolean,
        val environment: Environment,
        val warnings: List<String>
    )

    /**
     * Run all security checks at app startup.
     * Returns a gate result that determines whether to proceed, warn, or block.
     *
     * Policy:
     * - Rooted device: WARNING (allow but log, reduce feature access)
     * - SIM changed: WARNING + cooling period for sensitive ops
     * - Always allow app to start (don't lock users out)
     */
    fun runSecurityGate(): SecurityGateResult {
        val warnings = mutableListOf<String>()

        val rooted = isRootedDevice()
        if (rooted) {
            warnings.add("Device appears to be rooted — sensitive features may be restricted")
            Timber.w("SECURITY: Rooted device detected at startup")
        }

        val simResult = checkSimChanged()
        val simChanged = simResult.result == SimCheckResult.CHANGED
        if (simChanged) {
            warnings.add("SIM card change detected — transactions may require extra verification")
            Timber.w("SECURITY: SIM change detected at startup")
        }

        val env = getEnvironment()
        if (env == Environment.DEVELOPMENT) {
            warnings.add("Running in DEVELOPMENT mode — security enforcement relaxed")
        }

        return SecurityGateResult(
            allowed = true, // Never block app startup
            isRooted = rooted,
            simChanged = simChanged,
            environment = env,
            warnings = warnings
        )
    }
}

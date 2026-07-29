package com.msaidizi.core.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.msaidizi.core.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APK integrity verification for tamper detection.
 *
 * Verifies at runtime that:
 * 1. The APK signature matches the expected signing certificate
 * 2. The app is not debuggable in release builds
 * 3. The installer package is a legitimate source (Play Store, Galaxy Store, etc.)
 *
 * Call verifyIntegrity() on app startup. If verification fails,
 * the app should restrict sensitive operations.
 */
@Singleton
class ApkIntegrityVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Comprehensive integrity check. Returns detailed result.
     */
    fun verifyIntegrity(): IntegrityResult {
        val issues = mutableListOf<String>()
        var passed = true

        // 1. Verify signing certificate hash
        val certHash = getSigningCertificateHash()
        if (certHash == null) {
            issues.add("Could not read signing certificate")
            passed = false
        } else if (EXPECTED_CERT_HASH != null && certHash != EXPECTED_CERT_HASH) {
            issues.add("Signing certificate mismatch — APK may be repackaged")
            passed = false
        }

        // 2. Check debuggable flag in release
        if (!BuildConfig.DEBUG) {
            val isDebuggable = try {
                val flags = context.applicationInfo.flags
                (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (_: Exception) { false }

            if (isDebuggable) {
                issues.add("App is debuggable in release build")
                passed = false
            }
        }

        // 3. Check installer package (informational, not blocking)
        val installer = getInstallerPackage()
        if (installer != null && installer !in KNOWN_INSTALLERS) {
            issues.add("Installed from unknown source: $installer")
            // Don't fail — sideloading is legitimate, but log it
            Timber.w("APK installed from unknown source: $installer")
        }

        val result = IntegrityResult(
            passed = passed,
            certHash = certHash,
            installer = installer,
            isDebuggable = !BuildConfig.DEBUG && isDebuggableFlag(),
            issues = issues
        )

        if (!passed) {
            Timber.e("APK integrity check FAILED: ${issues.joinToString("; ")}")
        } else {
            Timber.d("APK integrity check passed")
        }

        return result
    }

    /**
     * Get the SHA-256 hash of the APK signing certificate.
     */
    @SuppressLint("NewApi")
    fun getSigningCertificateHash(): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                // On API 28+, signatures contains the signing info
                info.signatures
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                info.signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(signatures[0].toByteArray())
                .joinToString(":") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read signing certificate")
            null
        }
    }

    /**
     * Get the package that installed this app.
     */
    private fun getInstallerPackage(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDebuggableFlag(): Boolean {
        return try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) { false }
    }

    data class IntegrityResult(
        val passed: Boolean,
        val certHash: String?,
        val installer: String?,
        val isDebuggable: Boolean,
        val issues: List<String>
    )

    companion object {
        /**
         * Expected SHA-256 hash of the release signing certificate.
         * Set this to your production keystore's certificate hash.
         * null = skip cert check (for development/CI builds).
         */
        // TODO: Set this to the actual production certificate hash before release
        // Generate with: keytool -list -v -keystore release.keystore -alias key0 | grep SHA256
        private val EXPECTED_CERT_HASH: String? = null

        /**
         * Known legitimate installer packages.
         */
        private val KNOWN_INSTALLERS = setOf(
            "com.android.vending",           // Google Play Store
            "com.google.android.feedback",   // Play Store feedback
            "com.sec.android.app.samsungapps", // Samsung Galaxy Store
            "com.huawei.appmarket",          // Huawei AppGallery
            "com.xiaomi.mipicks",            // Xiaomi GetApps
            null                             // adb install (development)
        )
    }
}

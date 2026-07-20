# Security Policy — Msaidizi Android App

Msaidizi is an **offline-first Android app** for informal-sector workers in Kenya/East Africa. It records business transactions via voice, manages inventory, tracks M-Pesa payments, and provides AI-powered financial advice — all on-device where possible.

The security model must protect real threats to users who store sensitive financial data on shared, low-cost, potentially compromised devices.

---

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | ✅ Yes |
| 0.1.x   | ❌ No |

## Reporting a Vulnerability

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, report via email to [security@msaidizi.app](mailto:security@msaidizi.app).

You should receive a response within 48 hours.

### What to Include

- Type of vulnerability (e.g., SQL injection, key extraction, auth bypass, etc.)
- Full paths of source file(s) related to the vulnerability
- The location of the affected source code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact assessment: how an attacker could exploit this on a real user's phone

### What to Expect

1. **Acknowledgment** within 48 hours
2. **Assessment** of the vulnerability and its impact
3. **Fix** developed and tested
4. **Disclosure** coordinated with you
5. **Credit** in the security advisory (unless you prefer anonymity)

---

## Threat Model

### Who Are We Protecting?

Msaidizi's users are small business owners in Kenya and East Africa who:
- Often share devices with family members or employees
- May use budget Android phones (2GB RAM, Tecno/Infinix/Itel)
- Store M-Pesa transaction data and business financial records
- May not have screen locks or understand security prompts

### Priority Threats (Real First)

| Priority | Threat | Status | Mitigation |
|----------|--------|--------|------------|
| **P0** | Device theft / unauthorized access | ✅ Addressed | SQLCipher encryption, BiometricAuthManager, EncryptedSharedPreferences |
| **P0** | SIM swap / account takeover | ✅ Addressed | SIM change detection (SecurityConfig), SuspiciousLoginDetector, 48h cooling period |
| **P0** | Rooted device key extraction | ✅ Addressed | SecurityConfig.isRootedDevice(), reduced feature access on rooted devices |
| **P1** | Network MITM | ✅ Addressed | TLS 1.3 only, certificate pinning (env-aware), cleartext HTTP blocked |
| **P1** | M-Pesa credential theft | ✅ Addressed | EncryptedStorage with HMAC integrity, SecureTokenStorage |
| **P1** | Data exfiltration from backup | ✅ Addressed | android:allowBackup="false" |
| **P2** | Social engineering (impersonation) | ⚠️ Partial | Biometric + OTP verification, SuspiciousLoginDetector risk scoring |
| **P2** | Supply chain (dependency compromise) | ⚠️ Partial | Dependency verification, Detekt static analysis |
| **P3** | Post-quantum "Harvest Now, Decrypt Later" | ⏳ Deferred | PQC code present but disabled (PqcConfig defaults to classical). See below. |

### Post-Quantum Cryptography (PQC) — Honest Assessment

**Status: Code exists, disabled by default.**

We have Bouncy Castle PQC code (ML-KEM, ML-DSA) in `security/crypto/pqc/`. This was built as forward-looking R&D. **It is currently disabled** (`PqcConfig` defaults to `PHASE_0_CLASSICAL`) because:

1. Android's TLS stack doesn't support hybrid PQC key exchange groups yet
2. The real threats to our users (SIM swap, device theft, rooted phones) are classical
3. AES-256-GCM already provides 128-bit post-quantum security for the symmetric layer
4. Short token lifetimes (15-min access tokens) limit the "Harvest Now, Decrypt Later" window

**When to re-enable:** When Android 16+ adds ML-KEM hybrid TLS support, and when Let's Encrypt or another CA issues PQC certificates. See `PqcConfig.kt` for migration phase control.

---

## Security Architecture

### Data-at-Rest Encryption

- **SQLCipher** (`net.zetetic:android-database-sqlcipher:4.5.4`): The Room database (`msaidizi.db`) is fully encrypted with AES-256. The 256-bit passphrase is stored in EncryptedSharedPreferences (backed by Android Keystore / TEE).
- **EncryptedSharedPreferences**: Used for tokens, SIM baseline data, and device binding info.
- **EncryptedStorage**: Custom file-level AES-256-GCM encryption with HMAC integrity verification for cached data.

**Migration path:** Existing unencrypted databases are automatically detected and migrated to SQLCipher on first launch. See `AppModule.provideDatabase()`.

### Data-in-Transit Encryption

- **TLS 1.3 only** — TLS 1.2 is rejected for all API calls
- **Certificate pinning** — environment-aware (disabled in dev, enforced in prod) via `SecurityConfig.getCertificatePins()`
- **Cleartext HTTP blocked** — all non-HTTPS requests throw `SecurityException`
- **Cipher suites**: `TLS_AES_256_GCM_SHA384` (preferred), `TLS_CHACHA20_POLY1305_SHA256`, `TLS_AES_128_GCM_SHA256`
- **Network security config**: `network_security_config.xml` enforces cleartext traffic policy at the OS level

### Authentication & Authorization

- **JWT tokens** with 15-minute access tokens and secure refresh mechanism
- **Biometric authentication** (`BiometricAuthManager`) for sensitive operations
- **OTP verification** (`OtpManager`) for phone number verification during onboarding
- **Session management** (`SessionManager`) with automatic timeout

### Device Security

- **Root detection** (`SecurityConfig.isRootedDevice()`): Checks for su binaries, dangerous props, root packages (Magisk, SuperSU), and test-keys builds. Rooted devices get reduced feature access.
- **SIM change detection** (`SecurityConfig.checkSimChanged()`): Records SIM baseline on first run, detects carrier/country changes, triggers 48-hour cooling period for sensitive operations.
- **Device binding** (`security/simswap/DeviceBinder`): Binds user account to device fingerprint, tracks device changes for anomaly detection.
- **Suspicious login detection** (`SuspiciousLoginDetector`): Risk scoring based on SIM change, new device, unusual location/time, login velocity, and biometric failures.

### Network Security

- **Certificate pinning**: `TlsConfig` uses OkHttp `CertificatePinner` with SHA-256 SPKI hashes. Environment-aware via `SecurityConfig` — dev disables pinning for debugging.
- **Domain allowlisting**: API calls restricted to `api.angavu.com` and `api.angavu.io`

### Privacy

- **Data minimization** (`DataMinimizer`): Strips unnecessary PII from logs and analytics
- **Consent management** (`ConsentManager`): GDPR/Kenya DPA compliant consent flow
- **Differential privacy** (`DifferentialPrivacy`): Noise injection for federated learning
- **Data retention** (`DataRetentionManager`): Automatic purge of old data

---

## Security Checklist

### Android App

- [x] SQLCipher database encryption (AES-256)
- [x] EncryptedSharedPreferences for tokens and keys
- [x] Biometric authentication for sensitive ops
- [x] Root detection (su binary, props, packages, test-keys)
- [x] SIM change detection with cooling period
- [x] Device binding and fingerprinting
- [x] Certificate pinning (environment-aware)
- [x] TLS 1.3 enforcement (no TLS 1.2 fallback)
- [x] Cleartext HTTP blocked
- [x] android:allowBackup="false"
- [x] ProGuard/R8 code obfuscation (release builds)
- [x] Sentry crash reporting (no PII in crash logs)
- [x] Secure token storage with auto-expiry
- [ ] Play Integrity API integration (recommended for Play Store builds)
- [ ] SafetyNet attestation (deprecated but still useful for older devices)

### Backend / API

- [x] JWT token validation
- [x] Rate limiting
- [x] Input validation
- [x] Security headers (HSTS, X-Content-Type-Options)
- [x] CORS configuration
- [ ] SIM swap detection API integration (Safaricom)
- [ ] Webhook signature verification

---

## Security Config

All security decisions are centralized in `SecurityConfig.kt`:

```kotlin
// Check device integrity
val rooted = securityConfig.isRootedDevice()

// Check SIM change
val simResult = securityConfig.checkSimChanged()

// Get environment-aware cert pins
val pins = securityConfig.getCertificatePins()

// Run full security gate at startup
val gate = securityConfig.runSecurityGate()
```

Environment is determined by:
1. Build type (debug → development, release → production)
2. System property override: `adb shell setprop debug.msaidizi.env staging`
3. Environment variable: `MSAIDIZI_ENV=staging`

---

## Incident Response

### Severity Levels

| Level | Description | Response Time |
|-------|-------------|---------------|
| **Critical** | Key extraction, data breach, account takeover | Immediate |
| **High** | Auth bypass, SQLCipher compromise, TLS downgrade | 24 hours |
| **Medium** | Root detection bypass, cert pin bypass | 72 hours |
| **Low** | Informational, no immediate risk | 1 week |

### Response Process

1. **Detection**: Identify and verify the security incident
2. **Containment**: Contain the incident to prevent further damage
3. **Eradication**: Remove the root cause
4. **Recovery**: Restore affected systems
5. **Lessons Learned**: Document and update threat model

---

## Contact

- **Security Email**: [security@msaidizi.app](mailto:security@msaidizi.app)
- **PGP Key**: [Available on request]

---

Last updated: July 2026

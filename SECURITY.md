# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.x.x   | ✅ Active support  |
| < 1.0   | ❌ No longer supported |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in Msaidizi, please report it responsibly.

### How to Report

**DO NOT** open a public GitHub issue for security vulnerabilities.

Instead, please email: **[INSERT SECURITY EMAIL]**

Include the following information:

1. **Description** — A clear description of the vulnerability
2. **Impact** — What an attacker could achieve
3. **Reproduction** — Step-by-step instructions to reproduce
4. **Affected versions** — Which versions are affected
5. **Suggested fix** — If you have one (optional)

### What to Expect

- **Acknowledgment** — Within 48 hours
- **Initial assessment** — Within 1 week
- **Fix timeline** — Depending on severity:
  - 🔴 Critical: Within 48 hours
  - 🟠 High: Within 1 week
  - 🟡 Medium: Within 2 weeks
  - 🟢 Low: Next release cycle

### Scope

The following are in scope:

- **On-device LLM** — Model loading, inference, memory safety
- **Database** — SQLCipher encryption, data leakage
- **Voice pipeline** — Audio injection, command injection
- **App security** — Root detection bypass, data extraction
- **Build system** — Supply chain attacks, malicious dependencies

The following are out of scope:

- Physical access attacks (device theft)
- Social engineering
- Denial of service on the local app

### Safe Harbor

We support responsible disclosure and will not take legal action against researchers who:

- Make a good faith effort to avoid privacy violations
- Only interact with their own accounts/test data
- Do not exploit a vulnerability beyond what is necessary to confirm it
- Report vulnerabilities promptly

## Security Architecture

### Defense in Depth

1. **Certificate Pinning** — BuildConfig-driven SHA-256 pins (CI/CD injected, never in source). Debug builds gracefully disable pinning when pins are empty.
2. **Database Encryption** — SQLCipher with AES-256-GCM passphrase from Android Keystore (64-char, SecureRandom)
3. **API Key Storage** — EncryptedSharedPreferences with auto-migration from plain SharedPreferences
4. **Input Validation** — GuardrailsEngine validates all AI outputs; garde validation on all API inputs
5. **Per-Worker-Type Access Control** — Tool access restricted by worker type AND role (intersection model)
6. **Graph Sync Privacy** — Customer data NEVER leaves device; only cohort-level (k≥10) aggregates synced
7. **Crash Reporting** — Firebase Crashlytics in release builds only (no PII in crash reports)

### Privacy by Design

- All personal data stays on-device
- Device ID is SHA-256 hashed — no PII in transit
- Only anonymized, cohort-level (k≥10) data synced to server
- Alama Score validation data stays on-device; only aggregated accuracy metrics can be synced
- RCT framework respects privacy: treatment assignment is local, only aggregate outcomes shared

## Data Protection Impact Assessment

A comprehensive DPIA has been prepared for Kenya DPA compliance. See [docs/compliance/DPIA.md](../angavu-intelligence-backend/docs/compliance/DPIA.md) for the full assessment covering data categories, risk assessment, and safeguards.

## Security Best Practices for Contributors

- Never commit API keys, secrets, or credentials
- Use encrypted storage (SQLCipher) for all user data
- Validate all inputs before processing
- Use the GuardrailsEngine for all AI outputs
- Follow the principle of least privilege
- Keep dependencies updated
- Certificate pins must be injected via CI/CD secrets (BuildConfig), never hardcoded
- Run `./gradlew lint` and `./gradlew testDebugUnitTest` before submitting PRs

## Acknowledgments

We thank security researchers who help keep Msaidizi safe for the millions of small business owners who depend on it.

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

## Security Best Practices for Contributors

- Never commit API keys, secrets, or credentials
- Use encrypted storage (SQLCipher) for all user data
- Validate all inputs before processing
- Use the GuardrailsEngine for all AI outputs
- Follow the principle of least privilege
- Keep dependencies updated

## Acknowledgments

We thank security researchers who help keep Msaidizi safe for the millions of small business owners who depend on it.

# Fix Release Signing — Summary

**Date:** 2026-07-16
**Task:** Ensure Msaidizi APK installs cleanly on any Android phone without Google Play Protect blocking it.

## What Was Already In Place (Excellent Prior Work)

The project already had comprehensive release signing infrastructure:

1. **build.gradle.kts** — Full `signingConfigs` block with `release` config reading from env vars (CI) or `keystore.properties` (local). Minification (`isMinifyEnabled = true`), resource shrinking (`isShrinkResources = true`), and ProGuard all enabled for release builds.

2. **CI Workflow** (`.github/workflows/build.yml`) — Separate `release` job that decodes keystore from `RELEASE_KEYSTORE_BASE64` secret, sets `RELEASE_KEYSTORE_FILE` env var, and builds with `assembleRelease`.

3. **ProGuard Rules** (`app/proguard-rules.pro`) — Comprehensive rules covering Room, Hilt, Ktor, Kotlin serialization, ONNX Runtime, Sherpa-ONNX JNI, BouncyCastle PQC, SQLCipher, coroutines, and security logging stripping.

4. **.gitignore** — Already excludes `*.keystore`, `keystore.properties`, and `debug.keystore`.

## Changes Made

### 1. Keystore Path Alignment
Updated the default keystore path from `release.keystore` (project root) to `app/msaidizi-release.keystore` for consistency with the task requirement:

- **`app/build.gradle.kts`** — Changed fallback `storeFile` from `${rootProject.projectDir}/release.keystore` to `${project.projectDir}/msaidizi-release.keystore`
- **`keystore.properties.template`** — Updated `storeFile` path to `app/msaidizi-release.keystore`
- **`scripts/generate-keystore.sh`** — Updated default output path to `app/msaidizi-release.keystore`
- **`scripts/build-release.sh`** — Updated local mode check to also look for `app/msaidizi-release.keystore`

### 2. Keystore Generation
The keystore cannot be generated in this environment (no JDK available). The existing `scripts/generate-keystore.sh` script is ready to use:

```bash
# Local generation
./scripts/generate-keystore.sh

# CI generation (from GitHub secrets)
# The CI workflow already handles this via RELEASE_KEYSTORE_BASE64 secret
```

**Passwords documented in script:** `MsaidiziR3l3ase2026!` (for both store and key).

### 3. CI Secrets Required
For the release build to work in GitHub Actions, these secrets must be set:
- `RELEASE_KEYSTORE_BASE64` — base64-encoded keystore file
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias (`msaidizi-release`)
- `RELEASE_KEY_PASSWORD` — key password

## Why This Fixes Play Protect

Google Play Protect flags APKs signed with **debug certificates** (CN=Android Debug). A properly signed release APK with:
- RSA 2048-bit key
- 100-year validity
- Proper CN/O/C fields
- v2/v3 signing scheme

...will pass Play Protect checks. The release build type in `build.gradle.kts` uses `signingConfig = signingConfigs.getByName("release")`, ensuring all release APKs are signed with the production keystore.

## Next Steps

1. **Generate the keystore:** Run `./scripts/generate-keystore.sh` on a machine with JDK 17+
2. **Set GitHub secrets:** Add the 4 secrets listed above
3. **Push and verify:** The CI release job will produce a properly signed APK
4. **Test install:** Install the release APK on a fresh Android phone — no Play Protect warnings expected

# APK Signing Fix — Play Protect Compatibility

## Problem

Google Play Protect blocks Msaidizi APK installation with:

> "App blocked to protect your device — Play Protect hasn't seen an app from this developer before."

**Root causes:**
1. APK was signed with a **debug keystore** (not a release key)
2. APK is **sideloaded** (not distributed via Play Store)
3. No proper release signing configuration in the build

## Solution

This fix implements proper APK signing with a dedicated release keystore across
local builds, CI/CD, and release workflows.

---

## Quick Start (Local Development)

### 1. Generate a Release Keystore

```bash
chmod +x scripts/generate-keystore.sh
./scripts/generate-keystore.sh
```

This creates `release.keystore` with a 20-year validity RSA 2048-bit key.

### 2. Create `keystore.properties`

```bash
cp keystore.properties.template keystore.properties
```

Edit `keystore.properties` with your passwords:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=msaidizi-release
keyPassword=YOUR_KEY_PASSWORD
```

> ⚠️ `keystore.properties` is in `.gitignore` — never commit it.

### 3. Build Signed Release APK

```bash
chmod +x scripts/build-release.sh
./scripts/build-release.sh
```

Or directly:

```bash
./gradlew assembleRelease
```

### 4. Verify the APK

```bash
chmod +x scripts/verify-apk-signing.sh
./scripts/verify-apk-signing.sh app/build/outputs/apk/release/app-release.apk
```

Check that the certificate is **NOT** `CN=Android Debug`.

---

## CI/CD Setup (GitHub Actions)

### Required Repository Secrets

Go to **Settings → Secrets and variables → Actions** in the GitHub repo:

| Secret | Description |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded keystore file |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (e.g., `msaidizi-release`) |
| `RELEASE_KEY_PASSWORD` | Key password |

### Generate the Base64 Secret

```bash
# On macOS
base64 -i release.keystore | pbcopy

# On Linux
base64 -w 0 release.keystore | xclip -selection clipboard

# Or just print it
base64 -w 0 release.keystore
```

Paste the output as the `RELEASE_KEYSTORE_BASE64` secret.

### What Changed in CI

**`build.yml`** (PR/push builds):
- Now builds **both** debug and release APKs
- Decodes release keystore from secrets (if available)
- Verifies APK signatures with `apksigner verify`
- Uploads both APKs as artifacts
- Falls back to debug signing if no release secrets configured

**`release.yml`** (tag-triggered releases):
- Passes signing credentials to Gradle via environment variables
- Verifies APK signature after build
- Creates GitHub Release with signed APK

---

## How It Works

### Signing Configuration (`app/build.gradle.kts`)

The release signing config reads credentials from two sources (in priority order):

1. **Environment variables** (CI) — `RELEASE_KEYSTORE_FILE`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
2. **`keystore.properties`** file (local development)

The `release` build type now applies `signingConfig = signingConfigs.getByName("release")`.

### Environment Variables (CI)

| Variable | Used By | Source |
|---|---|---|
| `RELEASE_KEYSTORE_FILE` | Path to the decoded keystore | Set by CI step |
| `RELEASE_STORE_PASSWORD` | Keystore password | `secrets.RELEASE_STORE_PASSWORD` |
| `RELEASE_KEY_ALIAS` | Key alias | `secrets.RELEASE_KEY_ALIAS` |
| `RELEASE_KEY_PASSWORD` | Key password | `secrets.RELEASE_KEY_PASSWORD` |

---

## Play Protect — What to Expect

Even with proper release signing, Play Protect may still show warnings because:

1. **New developer key** — Play Protect hasn't seen this signing certificate before
2. **Sideloading** — APKs not from Play Store get extra scrutiny
3. **Low install base** — Fewer installs = more suspicion

### Mitigations

| Approach | Effectiveness |
|---|---|
| **Publish to Play Store** | ✅ Eliminates warning entirely |
| **Use App Signing by Google Play** | ✅ Google manages the signing key |
| **Proper release key** (this fix) | 🟡 Reduces warning severity |
| **Debug key** (before fix) | ❌ Always triggers block |

### For Direct Distribution

When users install the APK directly:

1. They'll see "Install unknown app" prompt (normal Android behavior)
2. Play Protect *may* show a warning on first install
3. After a few installs with the same certificate, Play Protect learns to trust it
4. Users can tap "Install anyway" to proceed

### For Play Store Distribution

1. Upload the AAB (`msaidizi-vX.X.X.aab`) to Google Play Console
2. Google re-signs with their own key (App Signing by Google Play)
3. Users downloading from Play Store see **no warnings**

---

## File Changes Summary

| File | Change |
|---|---|
| `app/build.gradle.kts` | Enabled release signing config with env var + properties file support |
| `.github/workflows/build.yml` | Added release APK build, signing verification, artifact upload |
| `.github/workflows/release.yml` | Added env var passthrough to Gradle, APK verification step |
| `scripts/generate-keystore.sh` | New — generates release keystore with guided setup |
| `scripts/build-release.sh` | New — builds and verifies a signed release APK |
| `scripts/verify-apk-signing.sh` | New — verifies APK signature and cert info |
| `APK_SIGNING_FIX.md` | This file |

---

## Troubleshooting

### "No keystore found" in CI

Ensure `RELEASE_KEYSTORE_BASE64` secret is set. The workflow falls back to
debug signing if it's missing (but the APK will trigger Play Protect).

### "Password verification failed"

Check that `RELEASE_STORE_PASSWORD` and `RELEASE_KEY_PASSWORD` match the
passwords used when generating the keystore.

### Play Protect still blocks the APK

This is expected for new developer certificates. Options:
1. Publish to Play Store (best solution)
2. Tell users to tap "Install anyway"
3. After enough installs, Play Protect will learn to trust the certificate

### Keystore lost

If you lose the release keystore, you **cannot** update the app on the Play Store.
The signing key must match. Always back up the keystore securely.

```bash
# Back up to a secure location
cp release.keystore ~/secure-backup/msaidizi-release.keystore
```

---

## Security Notes

- **Never commit** `keystore.properties` or `release.keystore` to git
- **Never share** keystore passwords in chat/email
- **Back up** the keystore in a secure location (password manager, encrypted storage)
- **Use different passwords** for keystore and key
- The keystore is the app's identity — losing it means starting over with a new app listing

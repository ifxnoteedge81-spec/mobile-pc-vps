# Build & GitHub Actions Guide

## Local Build
Run `gradle assembleDebug` or `gradle :app:testDebugUnitTest` (requires JDK 17 and Android SDK API 36).

## Automated GitHub Actions (`.github/workflows/android-build.yml`)
Every push to `main` or tag `v*`:
1. Runs unit tests & lint (`testDebugUnitTest lintDebug`).
2. Signs a release APK if `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` secrets are configured; otherwise builds a clearly labeled debug fallback APK.
3. Computes SHA-256 checksum, writes `site/downloads/version.json`, uploads artifacts, creates GitHub Releases on `v*` tags, and deploys `site/` to Netlify.

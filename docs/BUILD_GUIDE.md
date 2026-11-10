# Build guide

## Toolchain

| Tool | Version | Where it is set |
| --- | --- | --- |
| JDK | 17 | `build-logic` (`JvmTarget.JVM_17`), CI |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin / KSP | 2.2.20 / 2.2.20-2.0.3 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 36 | `gradle/libs.versions.toml` |
| minSdk | 26 (Android 8.0) | `gradle/libs.versions.toml` |

Google Play requires new apps and updates to target API 36 since 31 August 2026.

AGP 8.13 is the last 8.x line and supports API 36. Moving to AGP 9 changes how Kotlin is applied (built-in Kotlin) and needs a matching KSP release; do it as its own change, not mixed with feature work.

## Build locally

```bash
./gradlew :app:assembleDebug             # debug APK, applicationId app.hivpn.android.debug
./gradlew lintDebug                      # Android lint, all modules
./gradlew testDebugUnitTest :domain:test # unit tests
```

Open the root folder in Android Studio and run the `app` configuration on a device or emulator (API 26+).

## Backend settings

| Property | Meaning |
| --- | --- |
| `hivpn.apiBaseUrl` | `https://api.example.com`. Empty = no backend; only imported WireGuard configs |
| `hivpn.directoryPublicKey` | Printed by `hivpn-api keygen`. Without it, no directory is trusted |
| `hivpn.apiPins` | Optional, ≥ 2 comma-separated `sha256/…` pins; fewer = pinning off |

Set them in `gradle.properties`, with `-P` on the command line, or as GitHub repository variables `HIVPN_API_BASE_URL`, `HIVPN_DIRECTORY_PUBLIC_KEY`, `HIVPN_API_PINS` (used by CI).

## Application ID

Set in `gradle.properties`:

```properties
hivpn.applicationId=app.hivpn.android
```

This is a placeholder. **Choose the final ID before the first Play Console upload; it can never change afterwards.** Use a reverse domain you control.

## Release signing

No keystore, password or key is ever committed (`.gitignore` blocks `*.jks`, `*.keystore`, `keystore.properties`). Release builds read signing details from environment variables:

| Variable | Meaning |
| --- | --- |
| `HIVPN_KEYSTORE_PATH` | Path to the `.jks` file |
| `HIVPN_KEYSTORE_PASSWORD` | Keystore password |
| `HIVPN_KEY_ALIAS` | Key alias |
| `HIVPN_KEY_PASSWORD` | Key password |

Without them, `assembleRelease` produces an unsigned APK.

Create an upload key once, and keep it outside the repository:

```bash
keytool -genkeypair -v -keystore hivpn-upload.jks -alias hivpn \
  -keyalg RSA -keysize 4096 -validity 10000
```

Use Play App Signing: Google holds the app signing key, you keep only this upload key.

## CI (GitHub Actions)

`.github/workflows/android.yml`:

- **Every push and PR:** lint, unit tests, debug APK (uploaded as an artifact).
- **Tags `v*`:** the above, then a signed release APK and AAB, uploaded as artifacts and attached to a GitHub Release.

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `HIVPN_KEYSTORE_BASE64` | `base64 -w0 hivpn-upload.jks` |
| `HIVPN_KEYSTORE_PASSWORD` | Keystore password |
| `HIVPN_KEY_ALIAS` | `hivpn` (or your alias) |
| `HIVPN_KEY_PASSWORD` | Key password |

Release flow: bump `hivpn.versionName` and `hivpn.versionCode` in `gradle.properties`, commit, then `git tag v0.1.0 && git push --tags`.

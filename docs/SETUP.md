# Setup Instructions

## Required Software
- **Android Studio:** Latest stable release (optional — the wrapper builds from the command line).
- **JDK:** 17 or newer (verified on Temurin 21).
- **Android SDK:** Platform 36.1 and Build-Tools 36.1.0 (the versions this project is
  verified against; CI installs the same).
- **Gradle:** none needed — the repository ships a Gradle wrapper (`./gradlew`, pinned to 9.6.1
  with a distribution checksum). Never use a system-wide `gradle`.
- **Git:** For repository management.

## 1. Repository Checkout
```bash
git clone https://github.com/merilainen-star/Treenivalmentaja2000.git
cd Treenivalmentaja2000
```

If you are not using Android Studio, point the build at your SDK by creating `local.properties`
(git-ignored):
```properties
sdk.dir=/path/to/Android/sdk
```

The debug build is signed with a local `debug.keystore` at the repository root. It is git-ignored
(a signing key never belongs in version control), so generate it once after cloning:
```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug, O=Android, C=US"
```
Without it, `./gradlew assembleDebug` fails at `:app:validateSigningDebug`.

**Do not generate a new key if one already exists**, and do not generate one just to make CI
work. The signing certificate is the app's identity to Android: build with a different key and
the phone refuses to update the installed app, demanding an uninstall that takes the training
database with it. The key currently in use has certificate SHA-256
`ED:64:98:C9:3B:60:AF:75:82:CE:CE:4B:4A:D4:80:CC:5B:18:97:CC:F9:6A:52:28:52:24:E6:77:A8:78:10:C0`,
and the same key is stored as the `ANDROID_DEBUG_KEYSTORE_BASE64` Actions secret so GitHub builds
carry the same identity. To rotate it deliberately, replace the local keystore, re-upload the
secret, and expect to uninstall the app once:

```bash
base64 -w 0 debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64   --repo merilainen-star/Treenivalmentaja2000
```

Never print, commit or paste the base64 value — pipe it straight into `gh`.

## 2. Environment Variables & Secrets
**Rule:** Never place real secret values in documentation or version control.

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` and fill in the required keys. Do not commit `.env` — it is git-ignored.

## 3. Backend Setup
None required. Per [ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp) the MVP has no
server-side component: no Firebase project, no Cloud Functions, no `google-services.json`.

## 4. Oura Developer Application Setup

**No PC required.** Every step below works in a phone browser, and the credentials are typed into
the app rather than compiled into it
([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)).
That is deliberate: a build from CI has no `.env`, so an app that needed one could never connect
Oura on the phone it is actually installed on.

1. Sign in to the Oura developer portal (<https://developer.ouraring.com>).
2. Create a new Application.
3. Set the Redirect URI to exactly `treenivalmentaja://oauth2callback`, and allow the scopes
   **Daily**, **Workout** and **Heartrate**. Heartrate is what the average and maximum on a finished
   session are computed from — Oura puts no heart rate on a workout itself.
4. Open **Asetukset → Oura** in the app, paste the Client ID and Client Secret, and tap
   "Tallenna tunnukset". They are stored encrypted on the device and never leave it except to
   Oura's own token endpoint.
5. Tap "Yhdistä Oura". The login opens in the browser and returns to the app.

**If the scopes change, reconnect.** An authorization carries the permissions it was granted with,
so a connection made before a scope was added keeps working without it — heart rate simply stays
empty. "Katkaise Oura-yhteys" and then "Yhdistä Oura" is what grants the new one.

Note: Oura **personal access tokens were withdrawn in December 2025**, so there is no single-token
shortcut, even though the vendored `docs/api/oura-openapi-1.37.json` still declares a `BearerAuth`
scheme.

### Optional: credentials at build time instead
A local build may still supply them through a git-ignored `.env` at the repository root:
```properties
OURA_CLIENT_ID=your-client-id
OURA_CLIENT_SECRET=your-client-secret
```
They are injected into `BuildConfig` by the Secrets Gradle Plugin, and are used only when nothing
has been entered in the app. `.env` must never be committed.

## 5. Android Studio Setup
1. Open Android Studio.
2. Select "Open" and navigate to the `Treenivalmentaja` folder.
3. Allow Gradle to sync (it will use the wrapper).

## 6. Execution
- **Build and install:** `./gradlew assembleDebug`, or click "Run" in Android Studio.
- **Unit tests:** `./gradlew :app:testDebugUnitTest`
- **Without Oura credentials:** the app builds and runs fine — the plan, import, and scheduling
  features are fully local. The Oura card in Settings asks for a Client ID and Client Secret, which
  is where a fresh install starts; see section 4.

## 7. Installing a Test Build on the Phone

Routine test installs need no PC, cable, ADB or Android Studio. GitHub Actions builds the APK and
publishes it to one rolling prerelease, so the phone only has to open a link.

**Direct APK** (bookmark this — the URL never changes):
<https://github.com/merilainen-star/Treenivalmentaja2000/releases/download/test-build/Treenivalmentaja-test.apk>

**Release page:**
<https://github.com/merilainen-star/Treenivalmentaja2000/releases/tag/test-build>

Open the APK on Android and accept *Install* or *Update*. The first install from an unknown source
asks for permission once.

The build runs automatically on every push to `main` that touches code, and can be started by hand
from <https://github.com/merilainen-star/Treenivalmentaja2000/actions> — "Build Treenivalmentaja
Test APK" → *Run workflow*, which works from a phone browser.

An APK is published only when `assembleDebug`, `testDebugUnitTest`, `verifyRoborazziDebug` and
`lintDebug` all pass. If any fails, the workflow fails and the previous APK stays downloadable.
Instrumented tests are **not** run in CI — they need a device, so they remain a local check.

Each build's version name carries its commit (`1.0-0fd8c46`), visible in Android's app info, so
you can tell which build is on the phone. `versionCode` deliberately stays `1`: Android accepts a
sideloaded reinstall when the package id and certificate match and the version code is equal or
higher.

### When an update is refused

Android compares signing certificates, not version numbers. If it refuses to update, the installed
app was signed with a different key, and the only route is to uninstall it first — **which deletes
the training database**. Take a copy with `tools/backup-db.ps1` first and put it back afterwards
with its `-Restore` switch. Once the app has been installed from a GitHub build, every later
GitHub build updates it in place.

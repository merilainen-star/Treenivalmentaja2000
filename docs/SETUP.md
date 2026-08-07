# Setup Instructions

## Required Software
- **Android Studio:** Latest stable release (optional — the wrapper builds from the command line).
- **JDK:** 17 or newer (verified on Temurin 21).
- **Android SDK:** Platform 36.1 and Build-Tools 36.0.0.
- **Gradle:** none needed — the repository ships a Gradle wrapper (`./gradlew`, pinned to 9.6.1
  with a distribution checksum). Never use a system-wide `gradle`.
- **Git:** For repository management.

## 1. Repository Checkout
```bash
git clone https://github.com/merilainen-star/Treenivalmentaja.git
cd Treenivalmentaja
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
1. Go to the Oura Developer portal.
2. Create a new Application.
3. Set the Redirect URI to `treenivalmentaja://oauth2callback`.
4. Put **both** the Client ID and the Client Secret in your local `.env`:
   ```properties
   OURA_CLIENT_ID=your-client-id
   OURA_CLIENT_SECRET=your-client-secret
   ```
   They are injected into `BuildConfig` at build time by the Secrets Gradle Plugin. `.env` must
   never be committed.

## 5. Android Studio Setup
1. Open Android Studio.
2. Select "Open" and navigate to the `Treenivalmentaja` folder.
3. Allow Gradle to sync (it will use the wrapper).

## 6. Execution
- **Build and install:** `./gradlew assembleDebug`, or click "Run" in Android Studio.
- **Unit tests:** `./gradlew :app:testDebugUnitTest`
- **Without Oura credentials:** the app builds and runs fine — the plan, import, and scheduling
  features are fully local. Only the Oura connection is unavailable.

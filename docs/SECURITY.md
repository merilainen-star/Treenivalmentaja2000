# Security

## Threat Model
The app handles personal health data and training schedules. Threats include unauthorized access to biometric data, leakage of API keys, and unauthorized modification of the training schedule.

## Secret Management
- **Rule:** Client secrets (Oura client secret, API keys for AI) must **never** be hardcoded in the
  Android source code or committed to version control.
- **Implementation:** all configuration, including the Oura client secret, lives in a git-ignored
  `.env` at the repository root and is injected into `BuildConfig` by the Secrets Gradle Plugin.
  `.env.example` documents the keys with placeholders only.
- **Accepted risk (see [ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)):** because
  there is no backend, the Oura client secret is present in the built APK. This is accepted **only**
  for the private single-user build, which is never published. The mitigations are: the APK is
  side-loaded onto the owner's own device only, and PKCE is used so an intercepted authorization
  code is not usable without the `code_verifier`. **Publishing the app, or shipping it to a second
  user, reinstates the need for a backend** — ADR-006 lists the revisit triggers.

## OAuth & Token Storage
- The OAuth2 code-for-token exchange runs **in the app** (no proxy backend).
- Authorization Code flow with **PKCE (S256)**.
- Oura access and refresh tokens are stored using Android `EncryptedSharedPreferences`.
- OAuth `state` validation prevents Cross-Site Request Forgery (CSRF).

## Log Redaction
- Network interceptors (e.g., OkHttp logging) must redact `Authorization` headers.
- User biometric data (readiness, sleep scores) must not be logged to Crashlytics or Logcat.

## Network Security
- All external communication enforces HTTPS.

## Exported Android Components
- Activities handling deep links (`treenivalmentaja://`) are exported but validate incoming Intent data before taking action to prevent malicious intent spoofing.

## Backend Attack Surface
There is none. The MVP has no server-side component, no Firebase project, and no remote token
store, so there is no backend to secure, no service account to leak, and no Firestore rules to get
wrong ([ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)). The trade-off is that the
client secret lives in the APK — see "Secret Management" above.

## User Data Deletion & Privacy
- Biometric data is strictly minimized. The app only requests scopes needed for scheduling (readiness, sleep, workouts).
- AI prompts (future) will minimize data, sending only abstracted metrics rather than raw identifiable health data.
- User can trigger a complete local data wipe from the Settings screen.

## Known Security Gaps
- Oura integration is not implemented yet, so no tokens exist and `EncryptedSharedPreferences` is
  not yet wired up.
- The Room database is not encrypted (no SQLCipher). Contents are protected only by the Android
  app sandbox. Accepted for a private single-user build; revisit alongside ADR-006.
- The client secret ships in the APK — see "Secret Management".

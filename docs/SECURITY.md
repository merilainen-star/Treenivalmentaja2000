# Security

## Threat Model
The app handles personal health data and training schedules. Threats include unauthorized access to biometric data, leakage of API keys, and unauthorized modification of the training schedule.

## Secret Management
- **Rule:** Client secrets (Oura client secret, API keys for AI) must **never** be hardcoded in the
  Android source code or committed to version control.
- **Implementation:** all configuration, including the Oura client secret, lives in a git-ignored
  `.env` at the repository root and is injected into `BuildConfig` by the Secrets Gradle Plugin.
  `.env.example` documents the keys with placeholders only.
- **The Oura client secret is no longer in the APK.** It is typed into Settings and stored
  encrypted on the device
  ([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)),
  which removes the accepted risk below for the published test build entirely. The paragraph is
  kept because it still applies to a local build that supplies credentials through `.env`.
- **Accepted risk (see [ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)):** in a build
  that does compile them in, the Oura client secret is present in the built APK. This is accepted
  **only** for the private single-user build, which is never published. The mitigations are: the APK is
  side-loaded onto the owner's own device only, and PKCE is used so an intercepted authorization
  code is not usable without the `code_verifier`. **Publishing the app, or shipping it to a second
  user, reinstates the need for a backend** — ADR-006 lists the revisit triggers.

## OAuth & Token Storage
- The OAuth2 code-for-token exchange runs **in the app** (no proxy backend).
- Authorization Code flow with **PKCE (S256)**. The `code_verifier` never travels through the
  browser — only its SHA-256 — so an intercepted authorization code is not usable on its own.
- Oura access and refresh tokens, and the pending `code_verifier`, are encrypted with AES-256-GCM
  under a non-extractable Android Keystore key
  ([ADR-008](DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)).
  They are excluded from cloud backup and device transfer.
- OAuth `state` validation prevents Cross-Site Request Forgery (CSRF). The check happens **before**
  the authorization code is read, so a redirect carrying a valid-looking code but the wrong state
  never reaches the token endpoint.
- The login opens an **external browser**, never a WebView: a WebView would let this app read the
  Oura password as it is typed.

## Log Redaction
- Network interceptors (e.g., OkHttp logging) must redact `Authorization` headers.
- User biometric data (readiness, sleep scores) must not be logged to Crashlytics or Logcat.

## Network Security
- All external communication enforces HTTPS.

## Exported Android Components
- `OuraCallbackActivity` (`treenivalmentaja://oauth2callback`) is once again the **only** exported
  component in the app. It has to be: a browser starts it. It therefore acts on nothing it is
  given — it forwards the URI to `OuraConnection`, which discards anything whose `state` is not the
  exact value this device generated for a login it actually started. A forged redirect produces a
  visible refusal and no token exchange, which `OuraConnectionTest` holds in place.
- **The intervals.icu integration adds no exported surface at all.** It authenticates with a
  personal API key over HTTP Basic, so there is no browser round trip, no callback activity, no
  `state` to validate and no refresh token that could be spent twice — a smaller attack surface
  than the Strava OAuth flow it replaced, not merely a different one. The key lives under its own
  Android Keystore alias in its own preferences file, is excluded from backup and device transfer,
  is never logged, and is never redisplayed in the UI once saved.

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
- **The whole OAuth flow is untested against Oura.** It is covered by unit tests against a local
  server, but no login has ever been completed — that needs credentials only the owner's account
  can issue. Until one has, nothing here is proven end to end.
- The app cannot revoke its own access, because the Oura specification documents no revoke
  endpoint. Disconnecting deletes everything locally; revoking the application is done from Oura's
  account settings.
- The Room database is not encrypted (no SQLCipher). Contents are protected only by the Android
  app sandbox. Accepted for a private single-user build; revisit alongside ADR-006.
- The client secret ships in the APK — see "Secret Management".

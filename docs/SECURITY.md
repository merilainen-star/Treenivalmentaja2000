# Security

## Threat Model
The app handles personal health data and training schedules. Threats include unauthorized access to biometric data, leakage of API keys, and unauthorized modification of the training schedule.

## Secret Management
- **Rule:** Client secrets (Oura client secret, intervals.icu and AI API keys) must **never** be hardcoded in the
  Android source code or committed to version control.
- **Implementation:** credentials are entered in Settings and encrypted on the device. A
  git-ignored `.env`/`BuildConfig` value remains only as an optional Oura fallback for local builds;
  published test builds do not contain it. `.env.example` contains placeholders only.
- **The Oura client secret is no longer in the APK.** It is typed into Settings and stored
  encrypted on the device
  ([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)),
  which removes the accepted risk below for the published test build entirely. The paragraph is
  kept because it still applies to a local build that supplies credentials through `.env`.
- **The AI provider API keys** — Anthropic, OpenAI and Google — follow the same mechanism: typed
  into Settings, stored with AES-256-GCM under **their own** Keystore aliases in **their own**
  preferences files (`data/analysis/AnalysisApiKeyStore.kt`,
  [ADR-010](DECISIONS.md#adr-010-on-demand-ai-workout-analysis-called-directly-from-the-app-with-a-user-supplied-key),
  [ADR-011](DECISIONS.md#adr-011-three-analysis-providers-behind-one-interface)). Separate files per
  provider so clearing one key cannot touch another, and so a key pasted into the wrong field cannot
  authenticate somewhere it was not meant to. None is redisplayed once saved, and none is logged.
  These are further instances of the entered-at-run-time mechanism, not new mechanisms.
- Credential fields use ordinary in-memory Compose state, password keyboards and disabled
  autocorrection, so secrets do not enter Android saved-instance state. A Keystore or preferences
  write returns a typed failure; connection/configuration state changes only after a successful
  write and otherwise shows an error. **The field is also wiped once the key is stored**, so the
  plaintext does not sit in composition for as long as the card is on screen — and only then, so a
  failed write leaves the key in place to retry rather than demanding it be pasted again.
- **Gemini's key travels in the `x-goog-api-key` header, never the `?key=` query parameter** that
  Google's own examples show. Both authenticate; only one keeps the secret out of proxy logs, crash
  reports and `Referer` headers. `AnalysisClientTest` asserts the header form rather than trusting
  it.
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
- The application opts out of Android backup and device transfer entirely. XML exclusions also
  name the Room database, DataStore and every credential file as defence in depth.
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
- **The update APK is verified before it is committed, not merely fetched over TLS.** The release
  publishes `apkSizeBytes` and `apkSha256`; the app computes the SHA-256 of the bytes as they are
  written into the install session and refuses to commit unless both match, abandoning the session
  so nothing unverified is left on the device. `latest.json` is refused outright if its download
  URL is not HTTPS or its digest is not a SHA-256, and the download itself requires an
  `HttpsURLConnection`, so a redirect down to plain HTTP fails rather than proceeding. Android
  checks the package name and the signing certificate on top of this: an APK signed with another
  key cannot replace this app whatever the digest says.
  `ApkTransferTest` and `UpdateInfoParsingTest` hold both halves in place.

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
- **`UpdateInstallReceiver` is not exported, and that is the whole reason it exists.**
  `PackageInstaller` reports the result of an install session by sending back an `Intent` — and
  when the status is `STATUS_PENDING_USER_ACTION`, that intent carries another intent in
  `EXTRA_INTENT` which the app then *starts*. Delivered through `MainActivity`, which is exported,
  any application on the device could send one, and the app would launch whatever it named while
  believing it was Android's install prompt. The callback therefore goes to a receiver with
  `android:exported="false"`, reached by an explicit `PendingIntent` that names its component, so
  the system is the only sender that can reach it. The `PendingIntent` is mutable on Android 12+
  because the platform fills in the status extras; naming the component is what makes that safe.
  `UpdateInstallReceiverTest` asserts that the carried intent is started only for
  `STATUS_PENDING_USER_ACTION` and never for a delivery carrying another action.
  See [ADR-013](DECISIONS.md#adr-013-the-app-installs-its-own-update-through-packageinstaller).
- **`REQUEST_INSTALL_PACKAGES` is an entitlement to ask, not to install.** Android still requires
  the per-app "Asenna tuntemattomia sovelluksia" toggle, and `USER_ACTION_REQUIRED` is set on every
  session, so the ordinary system confirmation appears for every update. The app requests no
  storage permission with it: the APK is streamed into the install session and never becomes a file
  on the device.
- **The raw-data diagnostics screen is the one place this had to be thought about twice**, because
  it exists to be read and copied. The request line it displays is built from the URL's path and
  query, which cannot carry the credential; the key travels in an `Authorization` header that is
  attached inside the client and recorded in no field that reaches a screen, a clipboard or a log.
  The copy button puts the response body on the clipboard and nothing else.
  `IntervalsClientTest` and `IntervalsRawResponseTest` assert all of this rather than trusting it.

## Backend Attack Surface
There is none. The MVP has no server-side component, no Firebase project, and no remote token
store, so there is no backend to secure, no service account to leak, and no Firestore rules to get
wrong ([ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)). The trade-off is that the
OAuth exchange and provider calls happen in the app; a local build that opts into the Oura
`BuildConfig` fallback carries that secret in its APK — see "Secret Management" above.

## User Data Deletion & Privacy
- Biometric data is strictly minimized. The app only requests scopes needed for scheduling (readiness, sleep, workouts).
- **AI prompts send raw health measurements, not abstracted ones — this line used to promise the
  opposite, and the promise was not kept.** It said prompts "will minimize data, sending only
  abstracted metrics rather than raw identifiable health data". The analysis built in
  [ADR-010](DECISIONS.md#adr-010-on-demand-ai-workout-analysis-called-directly-from-the-app-with-a-user-supplied-key)
  sends a week of nightly HRV in milliseconds and resting heart rate in beats per minute, which is
  exactly the raw health data that sentence excluded. Abstracting them would have defeated the
  feature: the reason HRV is fetched at all is that a *measurement* means the same thing next season
  where a score does not.

  The honest position, which replaces it: the data is raw, and the protections are that **nothing is
  sent unless the user taps the button**, that the request is **shown to them verbatim** afterwards,
  that **only one workout and about a week of readings** go with it rather than the whole history,
  and that **not entering a key disables the feature entirely**. Recorded as a change of position
  rather than edited away, because a security document that quietly drops a promise it broke is
  worse than one that never made it.
- **AI plan proposals are untrusted input.** Only `MOVE` and `LIGHTEN` JSON operations parse; every
  referenced session, date and legal transition is checked again inside one Room transaction.
  Nothing is written before the user taps **Hyväksy muutokset**, and a partly invalid list rolls
  back as a whole. Accepted event rows are tagged `AI_ADVISOR`.
- User can trigger a complete local data wipe from the Settings screen.

## Known Security Gaps
- **The whole OAuth flow is untested against Oura.** It is covered by unit tests against a local
  server, but no login has ever been completed — that needs credentials only the owner's account
  can issue. Until one has, nothing here is proven end to end.
- The app cannot revoke its own access, because the Oura specification documents no revoke
  endpoint. Disconnecting deletes everything locally; revoking the application is done from Oura's
  account settings.
- The Room database is not encrypted (no SQLCipher). Contents are protected by the Android app
  sandbox and cannot enter Android backup or device transfer. Accepted for a private single-user
  build; physical access to a rooted/unlocked device remains outside this boundary and should be
  revisited if distribution expands beyond the owner.
- A local build can opt into compiling the Oura client secret into the APK. Published test builds
  leave that fallback empty — see "Secret Management".

# Architecture Decision Records (ADRs)

## ADR-001: Use Kotlin and Jetpack Compose
- **Status:** Accepted
- **Context:** The app needs a modern, maintainable UI toolkit that integrates seamlessly with Kotlin.
- **Decision:** Build all UI components using Jetpack Compose and Kotlin exclusively.
- **Consequences:** Faster UI iteration, but requires understanding of Compose state management. XML layouts are avoided entirely (except for base Android requirements like Splash screen vectors).
- **Alternatives Considered:** XML-based views (rejected due to legacy status).
- **Related Files:** `build.gradle.kts`, `MainActivity.kt`

## ADR-002: MVVM and Clean Architecture
- **Status:** Accepted
- **Context:** The app requires complex logic for scheduling and rescheduling workouts that must be testable and separate from UI.
- **Decision:** Adopt Model-View-ViewModel (MVVM) paired with Clean Architecture (UI -> Presentation -> Domain -> Data).
- **Consequences:** Clear separation of concerns. Easy to mock the data layer (currently implemented via `MockData` in ViewModel).
- **Alternatives Considered:** MVI (rejected as overly complex for current scope).
- **Related Files:** `WorkoutViewModel.kt`, `domain/`, `data/repository/TrainingRepository.kt`

## ADR-003: Local Offline-First Source of Truth
- **Status:** Accepted
- **Context:** Users need access to their schedule and notifications without an active internet connection.
- **Decision:** Room Database is the single source of truth. All API data (Oura) is synced to Room in the background. The UI only observes Room.
- **Consequences:** Ensures full offline functionality. Requires robust mapping between API models and Room entities.
- **Alternatives Considered:** Cloud-first via Firebase Firestore (rejected due to strict offline-first requirement).
- **Related Files:** `data/local/AppDatabase.kt`, `data/repository/TrainingRepository.kt`,
  `DATA_MODEL.md`. Implemented for training data; Oura tables exist but are not yet populated.

## ADR-004: Firebase Functions for OAuth Token Exchange
- **Status:** Superseded by [ADR-006](#adr-006-no-separate-backend-in-the-mvp)
- **Context:** The Oura API requires a client secret for OAuth token exchange.
- **Decision:** Use Firebase Functions as a proxy backend to perform the exchange, ensuring the client secret is never shipped in the APK.
- **Consequences:** Requires Firebase setup and anonymous auth to link tokens to users. Increased security.
- **Alternatives Considered:** Storing secret in Android Keystore / BuildConfig (rejected as insecure).
- **Related Files:** `AUTHENTICATION.md`

## ADR-005: AI Advisor as Proposal Only
- **Status:** Accepted
- **Context:** The AI training advisor might make inappropriate or hallucinatory schedule changes.
- **Decision:** AI must never directly modify the local training plan. It only returns a validated JSON proposal which the user must explicitly approve.
- **Consequences:** Protects the integrity of the user's schedule.
- **Alternatives Considered:** Autonomous AI adjustment (rejected due to safety and trust concerns).
- **Related Files:** `TRAINING_ENGINE.md`

## ADR-006: No separate backend in the MVP
- **Status:** Accepted (2026-08-05). Supersedes [ADR-004](#adr-004-firebase-functions-for-oauth-token-exchange).
- **Context:** ADR-004 assumed a Firebase Functions proxy so the Oura client secret would never
  ship inside the APK. That reasoning holds for a publicly distributed app, but Treenivalmentaja
  is a **private, single-user APK**: it is built locally and side-loaded onto one device by the
  same person who owns the Oura developer application. A backend would add a Firebase project,
  anonymous auth, function deployments, and a second place to keep secrets — all to protect a
  secret from the one person who already owns it. The cost is real and the benefit is not.
- **Decision:** The MVP ships with **no server-side component**.
  - The OAuth2 authorization-code exchange is performed **in the app**.
  - The Oura **client secret is injected into `BuildConfig`** by the Secrets Gradle Plugin from a
    local `.env` file. `.env` is git-ignored and is **never** committed.
  - Access and refresh tokens are stored encrypted on the device. *(This originally said
    `EncryptedSharedPreferences`; that specific mechanism is superseded by
    [ADR-008](#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences). The
    requirement — tokens encrypted at rest with a key that cannot leave the device — is unchanged.)*
  - Firebase Auth, Firebase Functions, and Firestore are **not used**.
- **Consequences:**
  - Much simpler setup: clone, create `.env`, build, install. No cloud account required.
  - The client secret is present in the APK. This is accepted **only** because the APK is never
    published; anyone able to decompile it already has physical access to the owner's device.
    This decision **must be revisited before any public or multi-user distribution** — see
    "Revisit triggers" below.
  - Token refresh is handled locally by an OkHttp `Authenticator`, not by a backend.
  - PKCE is used in addition to the client secret so the authorization code alone is not usable.
  - `AGENTS.md` still forbids hardcoded secrets in source; `BuildConfig` injection from an
    untracked `.env` is the sanctioned mechanism, and it is the **only** exception.
- **Revisit triggers:** publishing to Play Store or any store; a second user; sharing the APK
  outside the owner's own devices; Oura requiring secret rotation. Any of these reinstates a
  backend (ADR-004's approach) as the correct design.
- **Alternatives Considered:**
  - *Firebase Functions proxy (ADR-004)* — rejected for the MVP as disproportionate operational
    overhead for a single-user private build.
  - *Public client with PKCE and no secret* — rejected because the Oura V2 token endpoint requires
    client authentication; a secret-less public client is not supported.
- **Related Files:** `AUTHENTICATION.md`, `ARCHITECTURE.md`, `SECURITY.md`, `.env.example`,
  `app/build.gradle.kts`

## ADR-007: OkHttp, not Retrofit, for the Oura client
- **Status:** Accepted (2026-08-10).
- **Context:** `ROADMAP.md` and `AUTHENTICATION.md` both named Retrofit, developed against
  MockWebServer. That was written before the app had any networking at all. Since then two HTTP
  callers have been built — the update check in `data/update` and the exercise-guide lookups in
  `data/guide` — and both are plain `HttpURLConnection` tested against the JDK's own
  `com.sun.net.httpserver`. So the choice was not "Retrofit or nothing" but "Retrofit, the existing
  `HttpURLConnection` convention, or OkHttp", and the Oura client differs from the other two in one
  way that matters: it needs the 401 → refresh → retry cycle, with concurrent refreshes serialised
  so a rotated refresh token is not spent twice.
- **Decision:** The Oura client (`data/oura/`) uses **OkHttp** directly, with Moshi for parsing and
  the JDK's `HttpServer` in tests. Retrofit and MockWebServer are not added.
- **Consequences:**
  - No new dependency. OkHttp 4.12.0 is already inside the APK as Coil's transitive dependency —
    verified by extracting the baseline debug APK's DEX and finding `okhttp3/OkHttpClient` in it,
    not by reading the dependency tree. `app/build.gradle.kts` now declares it directly so the
    version that ships is written down rather than inherited.
  - `Authenticator` is available for the token renewal `AUTHENTICATION.md` specifies. It is
    deliberately **not** installed yet: it needs the refresh token and client secret that arrive
    with the OAuth flow. Until then a `401` surfaces as `OuraAuthException`.
  - Three HTTP styles now exist in one app — two `HttpURLConnection` callers and one OkHttp client.
    That is the real cost of this decision. It is accepted because the two old ones are single
    unauthenticated GETs with nothing to renew, and rewriting working code to match would be
    churn; if a third authenticated caller ever appears, they should converge on OkHttp.
  - Retrofit's per-endpoint interfaces would have bought little here: every Oura collection is the
    *same* request and the same envelope with a different item type, so one generic paged fetch
    covers all four.
- **Alternatives Considered:**
  - *Retrofit + converter-moshi + MockWebServer* — rejected: two new dependencies and APK bytes for
    a thin wrapper over one endpoint shape.
  - *Plain `HttpURLConnection`, as in `data/guide`* — rejected: token renewal and serialised
    refresh would have to be hand-written, which is exactly the part worth not hand-writing.
- **Related Files:** `app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/`,
  `AUTHENTICATION.md`, `API_INTEGRATIONS.md`, `ROADMAP.md`, `app/build.gradle.kts`

## ADR-008: Android Keystore directly, rather than `EncryptedSharedPreferences`
- **Status:** Accepted (2026-08-10). Supersedes the token-storage mechanism named in
  [ADR-006](#adr-006-no-separate-backend-in-the-mvp), not its reasoning.
- **Context:** ADR-006 and `AUTHENTICATION.md` both specified `EncryptedSharedPreferences` from
  `androidx.security:security-crypto`. That library was **deprecated in April 2025 at
  `1.1.0-alpha07`** and receives no further fixes — including for the Keystore crash reported
  against it. Its last stable release, `1.0.0`, is equally unmaintained. So the choice at
  implementation time was between an abandoned dependency, a third-party fork of it, and the
  platform primitives the library itself wraps.
- **Decision:** Store the tokens in ordinary `SharedPreferences`, encrypted with **AES-256-GCM**
  under a key generated inside the **Android Keystore** — `data/oura/OuraTokenStore.kt`.
  - The key is generated with `KeyGenParameterSpec` and is not extractable from the device.
  - The IV is generated per encryption by the Keystore (`setRandomizedEncryptionRequired` defaults
    to on) and stored alongside the ciphertext, so a GCM nonce is never reused.
  - GCM authenticates as well as encrypts: a tampered ciphertext fails to decrypt rather than
    decrypting to something else.
  - `setUserAuthenticationRequired(false)`, because reminders and a background sync have to work
    with the phone locked in a pocket.
  - The PKCE `code_verifier` is stored the same way while a login is in flight. It is the secret
    half of PKCE and writing it in the clear would undo the point of using PKCE at all.
- **Consequences:**
  - No dependency at all, where `security-crypto` would have added Tink. Nothing to keep up to date
    and nothing to migrate off later.
  - This is standard use of platform primitives, not home-made cryptography: no algorithm, mode or
    key-derivation scheme is invented here. What was avoided is the *library*, not the crypto.
  - The tokens are excluded from cloud backup and device transfer (`res/xml/backup_rules.xml`,
    `res/xml/data_extraction_rules.xml`). The Keystore key does not travel with a backup, so
    restored ciphertext would be unreadable; a restored install asks to connect Oura again instead
    of holding bytes it can never decrypt.
  - Anything that cannot be decrypted — a factory reset, a restored backup, an invalidated key —
    reads as "Oura is not connected" rather than crashing. That is a deliberate outcome, not a
    swallowed error.
  - The store cannot be unit-tested: there is no Android Keystore on the JVM. It is covered by an
    instrumented test (`OuraTokenStoreTest`), and everything that merely *uses* a store is tested
    against an in-memory `OuraTokenStorage` instead. That interface exists for this reason.
- **Alternatives Considered:**
  - *`androidx.security:security-crypto` anyway* — rejected: an unmaintained dependency with an
    open Keystore crash, for the one security-critical store in the app.
  - *A maintained third-party fork (`dev.spght:encryptedprefs`)* — rejected: it keeps the deprecated
    API at the cost of putting an outside maintainer this project cannot vet in the security path.
- **Related Files:** `app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/OuraTokenStore.kt`,
  `app/src/androidTest/java/fi/merilainen/treenivalmentaja/data/oura/OuraTokenStoreTest.kt`,
  `AUTHENTICATION.md`, `SECURITY.md`, `res/xml/backup_rules.xml`

## ADR-009: The Oura client credentials are entered in the app, not compiled into it
- **Status:** Accepted (2026-08-10). Replaces ADR-006's delivery mechanism for the client
  credentials; ADR-006's "no backend" decision is untouched.
- **Context:** Two facts collided.
  - **The app is not installed from a PC.** [SETUP.md](SETUP.md#7-installing-a-test-build-on-the-phone)
    exists because the owner installs test builds by opening a GitHub release link on the phone —
    "no PC, cable, ADB or Android Studio". But ADR-006 put the Oura client secret in `BuildConfig`
    from a git-ignored `.env`, and CI has no `.env`. So the only build the owner actually runs
    could *never* connect Oura, no matter what was implemented. The documented design and the
    documented install path contradicted each other, and nobody noticed until the feature was
    finished and the question "should the Oura section show up in Settings?" was asked.
  - **Oura withdrew personal access tokens** (December 2025). The obvious phone-only alternative —
    paste one token, no OAuth at all — is not available. The vendored specification still declares
    a `BearerAuth` scheme on 69 operations, so *the specification is stale on this point*; Oura's
    own authentication documentation now describes OAuth2 only. A registered application is the
    only way in.
- **Decision:** The client id and secret are **typed into the Settings screen** and stored
  encrypted, under the same Android Keystore key as the tokens
  ([ADR-008](#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)).
  - Credentials are read at run time, store first, falling back to `BuildConfig` — so a local
    `.env` build still works with nothing typed.
  - Registering the application (developer portal, redirect URI `treenivalmentaja://oauth2callback`)
    is done in a browser, which works on the phone.
  - Disconnecting keeps the credentials; a separate "Vaihda tunnukset" forgets them.
- **Consequences:**
  - The whole feature is reachable from a phone. No PC, no checkout, no `.env`, no file transfer.
  - **The secret is no longer in the APK at all**, which is strictly better than ADR-006's accepted
    risk. The published test APK now carries no Oura secret, so the "never publish this APK"
    constraint is one step less load-bearing. ADR-006's revisit triggers still stand for the rest.
  - The owner pastes two strings once. That is the price, and it is paid on the phone.
  - `AGENTS.md`'s rule — no secrets in source, `BuildConfig` from `.env` the only sanctioned
    mechanism — is about *secrets in the repository*. A value the user types at run time is user
    data, not a committed secret, and `.env` remains the only way a secret may enter a build.
- **Alternatives Considered:**
  - *Personal access token pasted into Settings* — the simplest possible design, and the one
    initially chosen. Rejected on evidence: Oura withdrew them, so it cannot be built.
  - *Keep `.env` and require a local build* — rejected: it makes the feature unusable for the way
    this app is actually installed.
- **Related Files:** `data/oura/OuraConnection.kt`, `data/oura/OuraTokenStore.kt`, `OuraCard.kt`,
  `TreenivalmentajaApplication.kt`, `AUTHENTICATION.md`, `SETUP.md`, `SECURITY.md`

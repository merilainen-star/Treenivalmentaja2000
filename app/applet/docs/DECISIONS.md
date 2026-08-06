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
  - Access and refresh tokens are stored in Android **`EncryptedSharedPreferences`**.
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

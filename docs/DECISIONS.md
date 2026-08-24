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

## ADR-010: On-demand AI workout analysis, called directly from the app with a user-supplied key
- **Status:** Accepted (2026-08-17), and built. Implements the "AI coach comments, read-only
  (Phase B)" item in `ROADMAP.md`, which this ADR makes concrete. Governed by
  [ADR-005](#adr-005-ai-advisor-as-proposal-only) — this feature returns prose, never a plan edit —
  and follows the credential pattern of
  [ADR-009](#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it) and
  [ADR-008](#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences).
- **Context:** An "AI-analyysi" button under a workout card, tapped by hand, that calls Claude and
  shows the result inline. Two variants: what a **completed** session cost against that morning's
  recovery, and how to execute an **upcoming** one against the current recovery trend. Three things
  found during investigation shape the design below:
  - **No raw HRV or resting heart rate is stored anywhere in the app.** `DailyRecovery` holds only
    Oura's three composite scores (readiness/sleep/activity, 0–100). An analysis of "how did this
    session sit against that morning's recovery" written from a single 0–100 composite is a thinner
    claim than the data allows: Oura holds the underlying nightly HRV and resting heart rate, and a
    trend in those is what a coach actually reads. **This ADR fetches them** — see the sleep-periods
    decision below.
  - **Acute/chronic training load (`atl`/`ctl`) is stored but read by nothing.** The most recent
    commit added these columns to `IntervalsActivityEntity` for "the fatigue rule in ROADMAP.md";
    `IntervalsRepository.toMetrics()` never copies them into `CompletedRunMetrics`. This ADR is that
    rule's first reader, and includes wiring the two fields through as part of the same change.
  - **The vendored specification declares no per-operation scopes.** Every path's `security` block is
    `{"BearerAuth": []}, {"OAuth2": []}` — empty arrays, including on `daily_activity`, which is
    known to need `daily`. So the spec cannot answer which scope covers the sleep-periods endpoint,
    and the answer has to be measured rather than read. This is the same class of gap
    `AUTHENTICATION.md` already records about the stale `BearerAuth` declaration.
- **Decision:**
  - **Key storage.** A new `AnthropicApiKeyStore`, byte-for-byte the same construction as
    `IntervalsApiKeyStore` — AES-256-GCM under its own Android Keystore alias, its own
    `SharedPreferences` file, excluded from backup, never redisplayed once saved. Entered on a new
    card in Settings, below the Intervals.icu card. **Unlike** the Intervals key, saving does **not**
    trigger an immediate test call — intervals.icu's `testKey()` is free, an Anthropic call is not,
    and spending the user's money to validate a paste they didn't ask to spend money on is the wrong
    default. The first real "AI-analyysi" tap is the test; a `401` there says so.
  - **Nightly HRV and resting heart rate are fetched from Oura**, as a fifth collection:
    `GET /v2/usercollection/sleep` (`PublicModifiedSleepModel`), which is the *sleep periods*
    endpoint and a different thing from the `daily_sleep` **score** the app already reads. The
    `OuraCollection` constant is therefore `SLEEP_PERIODS("sleep")`, not `SLEEP` — it sits directly
    beside `DAILY_SLEEP("daily_sleep")` in the same enum, and two constants whose names differ only
    by a prefix, whose paths differ only by a prefix, and which return entirely different documents
    is a mistake waiting to be made by whoever reads this next. Three fields are taken from it, and
    taking all three is free once the request is made — they arrive in the same document:

    | Field | Type | What it is |
    | --- | --- | --- |
    | `average_hrv` | int, nullable | Average heart-rate variability during sleep, in ms |
    | `lowest_heart_rate` | int, nullable | Lowest heart rate during sleep — the resting-HR figure Oura's own app shows |
    | `average_heart_rate` | number, nullable | Average heart rate during sleep |

    Four things about this endpoint differ from the four collections already read, and each is a
    decision rather than a detail:
    - **It returns more than one document per day.** Naps are sleep periods too. The `type` field
      (`long_sleep` / `sleep` / `late_nap` / `rest` / `deleted`) is what separates them: the night's
      figures come from the `long_sleep` document, falling back to the longest `total_sleep_duration`
      among the remainder when there is none. `rest` (a falsely detected period the user rejected)
      and `deleted` are discarded outright. **Averaging the periods together would be wrong** — a
      twenty-minute nap's HRV is not a comparable measurement to a night's, and blending them would
      quietly corrupt exactly the trend this feature exists to read.
    - **`day` already means what this app needs.** The spec defines it as the day the sleep *belongs
      to* — the morning you wake up — which is the same keying `oura_daily_summaries` uses. No offset
      arithmetic, and the row merges into the existing day rather than creating a parallel table.
    - **The scope is an assumption, not a reading.** The spec declares empty scope arrays everywhere
      (see Context), so nothing in it says whether `daily` covers this path. Oura's own documentation
      puts sleep periods under `daily`, so `OuraOAuth.SCOPES` is **left unchanged** and no reconnect
      is expected. If that assumption is wrong the endpoint answers `401` and the diagnostics screen
      says so in one tap — which is why it gains a row for this collection.
    - **Its failure must not fail the sync.** The existing `sync()` fetches all four collections
      before writing anything, so any one failing discards the lot. That is right for four
      collections known to work; it is wrong for a new one whose scope coverage is unverified — a
      `401` here would otherwise take down readiness, sleep, activity and workouts with it. This call
      is therefore caught on its own and its absence leaves the three new columns `null`, following
      the precedent `withHeartRatePerWorkout` already sets for the `heartrate` scope.
  - **Default model `claude-sonnet-5`, changeable in Settings.** The default is Sonnet because of the
    task shape: interpreting a page of numbers already labelled with what they mean and writing a
    short assessment against explicit bands is summarisation and judgment over structured input, not
    the open-ended multi-step reasoning Opus-tier is bought for. But the right tier for *this* prompt
    on *this* athlete's data is a judgment best made by reading a few real answers, not by reasoning
    about it in advance — so Settings carries a selector rather than the ADR carrying a verdict:

    | Option | Model id | $/Mtok in ⋅ out | Shown as |
    | --- | --- | --- | --- |
    | Cheapest | `claude-haiku-4-5` | 1 ⋅ 5 | "Nopein ja edullisin" |
    | **Default** | `claude-sonnet-5` | 3 ⋅ 15 | "Tasapainoinen (oletus)" |
    | Most capable | `claude-opus-5` | 5 ⋅ 25 | "Paras arvio, kallein" |

    **What a tap actually costs**, on a prompt of roughly 1 500 input tokens and a few hundred words
    of Finnish out: on the order of **half a cent on Haiku, 2–3 cents on Sonnet, 4–6 cents on Opus**.
    The spread is wider than output length alone suggests because Sonnet 5 and Opus 5 think
    adaptively by default and **thinking is billed as output tokens** — the thinking, not the visible
    answer, is most of the bill. Even the dearest option is cents per tap for something read a few
    times a week, which is the whole reason this is a selector and not an optimisation problem.

    *(Sonnet 5 is at an introductory 2 ⋅ 10 until 2026-08-31 — a fortnight from this ADR. The table
    lists the standard rate deliberately: the figures above should not become wrong on 1 September.)*

    A **fixed list, not a free-text field.** A mistyped model id is a `404` discovered at tap time,
    on a phone, with no way to tell it from a broken key; three known-good ids in a dropdown cannot
    produce that. The cost is that the list goes stale as models are released — accepted, because it
    is one constant in one file, and a stale list still works where a typo does not. A model that is
    *retired* rather than merely superseded also answers `404`, which is why the failure table below
    gives that status its own message rather than folding it into the generic one.

    The selector is stored with `NotificationSettingsStore`'s DataStore preferences, **not** in the
    Keystore: a model id is a preference, not a secret, and putting it behind encryption would imply
    otherwise. The request body is identical for all three ids, so the selector really is a string
    swap — see the next bullet.
  - **Call shape.** Direct OkHttp POST to `https://api.anthropic.com/v1/messages`, no SDK, no
    backend, matching ADR-007's reasoning for the Oura client — one endpoint, already have OkHttp
    and Moshi, a dependency buys nothing here. Non-streaming: the response is a paragraph or two,
    nowhere near the size that needs streaming to avoid a timeout.
    ```
    POST https://api.anthropic.com/v1/messages
    x-api-key: <stored key>
    anthropic-version: 2023-06-01
    content-type: application/json

    { "model": "<selected id>", "max_tokens": 8192,
      "messages": [{ "role": "user", "content": "<constructed prompt>" }] }
    ```
    **No `thinking` parameter is sent**, and that is what makes one request body serve all three
    models: Sonnet 5 and Opus 5 think adaptively when the field is absent, Haiku 4.5 does not think
    at all, and all three accept the request as written.

    **`max_tokens` is 8192 rather than the ~500 the visible answer needs**, because on Sonnet 5 and
    Opus 5 it bounds thinking *and* response text together — a value sized for the prose alone would
    spend the budget on reasoning and truncate the answer mid-sentence. A ceiling costs nothing
    unless it is reached; only generated tokens are billed.

    **The answer is not `content[0]`.** `content` is a list of typed blocks, and on the two models
    that think, the **thinking blocks come first** — so `content[0]` is a `thinking` block whose text
    is empty (`display` defaults to `omitted`, which is what this app wants: the reasoning is neither
    shown nor paid attention to, only billed). On Haiku 4.5, which does not think, `content[0]` *is*
    the text. Reading index zero would therefore work on one of the three models and silently render
    an empty analysis on the other two. The client **scans for the first block whose `type` is
    `"text"`** instead, after checking `stop_reason`. That block's text is displayed as-is — no
    structured output, no further parsing. A malformed-JSON failure mode is not worth buying for a
    feature that changes nothing in the plan either way.

    **No `fallbacks` parameter**, though Anthropic's own guidance recommends one by default for
    `claude-opus-5` code: it re-runs a refused request on another model inside the same call. It is a
    beta header plus a second model id in every request, to rescue a refusal category ("cyber", "bio")
    that a Finnish training-analysis prompt has no route to. The refusal row below is the cheaper
    honest handling. Worth revisiting only if a refusal is ever actually observed.
  - **Prompt construction is a pure function**, `AnalysisPromptBuilder` in `domain/`, built entirely
    from state the ViewModel already holds (`completedMetrics`, `runMetrics`, `recoveryByDay`,
    `workouts`) — no new fetches. Two builders, one per analysis type:
    - **Completed workout:** the session's type/planned duration/description; whatever of
      `CompletedSessionMetrics` (Oura) and `CompletedRunMetrics` (intervals.icu — pace, HR,
      `trainingLoad`, `intensityPercent`, `hrLoad`, `trimp`) exist for it, nulls omitted rather than
      sent as zero; that morning's `DailyRecovery` — readiness/sleep/activity scores **and the
      nightly HRV, resting heart rate and sleep heart rate** added by this change; the same figures
      for the preceding six days as trend.
    - **Upcoming workout:** the session's type/planned duration/description/`Intensity` enum
      (`EASY`/`MODERATE`/`HARD`/`MAX` — Plan Schema v1's only notion of intended load, there is no
      numeric target); the last seven days of readiness, HRV and resting heart rate as trend; the
      latest known `atl`/`ctl` (added to `CompletedRunMetrics` as part of this change) as the
      fatigue/fitness signal, when a matched activity carries one.
    **Absent values are omitted rather than sent as zero or as a dash**, the same rule the rest of
    the app obeys — a night the ring was not worn must not reach the model as an HRV of 0, which
    reads as a catastrophic reading rather than as no reading.
    Both end with an explicit instruction: assess or advise only, in Finnish, and never propose or
    imply a specific plan edit — the app has no mechanism to act on one and must not read as if it
    does (ADR-005).
  - **Time windows for the button.** A session gets the button in exactly one of these two states,
    never both, decided by `status` rather than a separate flag:
    - **Completed:** `status == COMPLETED` and `dayOffset` in `-7..0` — the last seven days,
      inclusive of today.
    - **Upcoming:** `status == PLANNED || status == NOTIFIED` and `dayOffset` in `0..3` — today
      through three days out. `STARTED` is deliberately excluded: "how should I execute this" is
      moot once the session is already under way.
    `SKIPPED` sessions get no button — there is nothing completed to assess and nothing upcoming to
    advise on.
  - **Where it renders.** `WorkoutDetails.kt` stays read-only, as documented; the button is a new
    shared composable (`AiAnalysisSection`), called from both `WorkoutCardToday` (today's session,
    beside its existing action-button column) and `WorkoutCardWeek` (inside the expanded content,
    beside `WorkoutDetails`) — the two places sessions in the -7..+3 window are actually shown.
  - **State** lives in `WorkoutViewModel` as `Map<sessionId, AiAnalysisState>`
    (`Idle` / `Loading` / `Loaded(text)` / `Failed(message)`), so scrolling the week list doesn't
    lose an open analysis and more than one card can be open at once.
  - **Transparency.** The result card carries a collapsed "Näytä pyyntö" row that expands to the
    exact prompt text sent — no separate request/response log, just the string already built,
    shown. This is the ROADMAP item's own requirement, and it costs one more `Text` in a
    `Column`.
  - **Failure handling**, mirroring `IntervalsClient`'s existing exception shapes:
    | Case | Behaviour |
    | --- | --- |
    | No key saved | **The section draws nothing at all** — no button, no explanation. An opt-in feature that has not been opted into is invisible, and Settings is where its existence is advertised. The first implementation drew a "set a key in Settings" line instead, by analogy with the Oura card; the analogy was wrong, because that card appears once in Settings whereas this renders on every workout in a ten-day window. Thirteen screenshot baselines changed, which is what caught it. The client still refuses without a key (`AnthropicNotConfiguredException`, no network call), because the state can change between composition and tap. |
    | Offline / connection failure | `AnthropicUnavailableException`, Finnish message, "Yritä uudelleen". |
    | `401` | `AnthropicAuthException` — "Avain ei kelpaa. Tarkista se Asetuksista." |
    | `404` | The selected model no longer exists — a retired id in a list that has gone stale. Says so, and points at the model selector: "Valittua mallia ei enää ole. Valitse toinen malli Asetuksista." Anything else sends the owner hunting for a broken key. |
    | `429` | `AnthropicRateLimitException`, reading `Retry-After` if present, same pattern as `IntervalsRateLimitException`. |
    | `529` | Overloaded — retryable, and distinct from `429` in that waiting is the only remedy and no quota was spent. Same "yritä hetken päästä" wording as `OuraRateLimitException`. |
    | Other non-200 | Generic Finnish message naming the HTTP status. |
    | `stop_reason: "refusal"` | A `200` with no usable text — the model's safety classifiers declined. Nothing in a training prompt should reach them, but `stop_reason` is checked before the content list is walked regardless: a refusal can carry an empty `content`, and "shouldn't happen" is not a reason to skip a one-line guard. |
    | While waiting | Button replaced by a disabled state, a small `CircularProgressIndicator`, and "Analysoidaan…" — the `IntervalsCard.Testing()` shape. |
- **Consequences:**
  - **Database schema v11.** `oura_daily_summaries` gains three nullable columns (`averageHrvMs`,
    `restingHeartRate`, `sleepHeartRate`) — purely additive, so `AutoMigration(10, 11)` writes the
    SQL, and `MigrationTest` gains the 10→11 case that `AppDatabase`'s own four-step rule requires.
    A day stored before this keeps its scores and gets nulls, which is indistinguishable from a night
    the ring was not worn — correct in both cases, because in both cases the app does not know.
  - **The HRV values are useful beyond this feature.** They are a stored column on the day, not a
    field on a prompt: the Today card, the readiness rule, and the easy-run drift rule in
    `ROADMAP.md` can all read them without another fetch. This ADR is the reason they arrive, not
    the limit of what may use them.
  - **One more request per sync**, on the same window as the other four. The sleep-periods response
    is larger than a score document (it carries the 30-second phase and movement strings, which this
    app ignores), but it is one call over a fortnight, not one per day.
  - `PRIVACY.md` changes in **two** places, not one: the network-destinations table grows a sixth row
    (`api.anthropic.com`) describing exactly what is sent, **and** the "What the app requests from
    Oura" section — which currently says the app reads only scores — has to say it now also reads
    nightly HRV and heart rate. Shipping without that second edit would leave the policy stating
    something untrue about health data, which is worse than leaving the feature unbuilt.
  - `SECURITY.md`'s "Secret Management" section, which already names "API keys for AI" as a future
    entry, gets an implementation line pointing at `AnthropicApiKeyStore`; `AGENTS.md`'s two-mechanism
    secrets list gains this as a third instance of mechanism 1, not a new mechanism. `SECURITY.md`'s
    "AI prompts (future) will minimize data, sending only abstracted metrics rather than raw
    identifiable health data" needs revisiting rather than quietly contradicting: nightly HRV **is**
    a raw health metric, and the honest position is that the user chooses per tap to send it, sees
    exactly what is sent, and it leaves the device only on that tap.
  - `DATA_MODEL.md` § 4 and `API_INTEGRATIONS.md` both describe the Oura tables and collections and
    both need the fifth collection and the three columns.
  - `CompletedRunMetrics` gains two nullable fields (`atl`, `ctl`) purely by copying already-stored
    columns through `toMetrics()` — no migration, no new fetch.

    **Amended 2026-08-19: those were the wrong numbers, and the analysis no longer reads them.**
    An activity's `icu_atl`/`icu_ctl` are frozen at the moment of that session and never decay,
    while the real figures fall every day — ATL on roughly a 7-day time constant, CTL on 42. Reading
    the newest activity therefore reported however stale a fatigue the last session left behind.
    Measured on the owner's own account: the 16 August run stored 17.7 / 11.7, while intervals.icu's
    wellness record for 19 August said 11.5 / 10.9 — a TSB of −5.9 against a true −0.6, which is the
    difference between "ease off" and "go as planned". The upcoming-workout prompt now reads
    intervals.icu's **daily wellness series** (`intervals_wellness`, schema v12) and carries the date
    of the figures in the heading, so a stale one is visible rather than silent. The activity columns
    stay: the load immediately after a session is a true and different fact, and dropping it would
    lose data to fix a misuse.
  - Every tap spends the user's own money against their own key. Nothing about that is hidden: the
    request shown under "Näytä pyyntö" is the actual bill.
  - The two analysis types share a builder shape but not a prompt — a future third type (e.g. a
    weekly rollup) adds a builder, not a rework of these two.
- **Alternatives Considered:**
  - *Readiness score alone, no new Oura collection* — the design this ADR started from, and rejected
    once the cost was counted honestly: one endpoint, one DTO, three additive columns and an auto
    migration, against an analysis that can read a real HRV trend instead of a composite that hides
    it. The migration is the cheap kind Room writes itself.
  - *`daily_readiness.contributors.hrv_balance` and `.resting_heart_rate`* — available on a
    collection the app **already fetches**, so it would need no new endpoint at all. Rejected because
    they are 0–100 *contributor scores*, not measurements: `hrv_balance: 82` says Oura's opinion of
    the night relative to the athlete's own baseline, where `average_hrv: 61` is a number in
    milliseconds that means the same thing tomorrow and next season. The request was for HRV, and a
    score of HRV is not HRV.
  - *The `hrv` sample series on the same document* — the per-night time series rather than its
    average. Rejected: hundreds of samples to store and summarise per night, to produce a figure the
    same document already carries as `average_hrv`.
  - *Free-text model id in Settings* — rejected: a typo is a `404` at tap time on a phone,
    indistinguishable from a bad key, and the failure lands in the middle of the one flow this
    feature has. The staleness of a fixed list is the cheaper problem.
  - *No selector, `claude-sonnet-5` fixed* — rejected: whether Sonnet's Finnish and its judgment on a
    real week of this athlete's data are good enough is an empirical question, and a selector answers
    it by letting the user compare rather than by requiring a rebuild to find out.
  - *Test the Anthropic key on save, like intervals.icu* — rejected: that test is free on
    intervals.icu and is not free here. Spending money to validate a paste is a choice the user
    should make by tapping "AI-analyysi", not one the Settings screen makes for them.
  - *Route through a backend* — rejected on the same grounds as ADR-006: no second user, no need to
    hide a key from the person who already owns it, and a backend for one HTTP call is pure overhead.
  - *Structured JSON output instead of prose* — rejected: nothing downstream acts on the response
    (ADR-005), so there is nothing to validate a schema against; parsing JSON only adds a failure
    mode a read-only feature does not need.
- **Related Files:** `data/anthropic/AnthropicApiKeyStore.kt`, `data/anthropic/AnthropicClient.kt`,
  `domain/AnalysisPromptBuilder.kt`, `WorkoutDetails.kt`, `WorkoutCardToday` (`TodayScreen.kt`),
  `WorkoutCardWeek` (`WeekScreen.kt`), `SettingsScreen.kt`, `WorkoutViewModel.kt`,
  `data/settings/NotificationSettingsStore.kt`, `data/repository/IntervalsRepository.kt`,
  `domain/CompletedRunMetrics.kt` — and, for the Oura side: `data/oura/OuraApi.kt` (fifth
  collection), `data/oura/OuraDto.kt` (`OuraSleepPeriodDto`), `data/oura/OuraClient.kt`,
  `data/oura/OuraMappers.kt`, `data/repository/OuraRepository.kt`,
  `data/local/entity/Entities.kt`, `data/local/AppDatabase.kt` (v11),
  `domain/DailyRecovery.kt`, `MigrationTest`. Docs: `ROADMAP.md`, `PRIVACY.md`, `SECURITY.md`,
  `DATA_MODEL.md`, `API_INTEGRATIONS.md`, `AGENTS.md`

## ADR-011: Three analysis providers behind one interface
- **Status:** Accepted (2026-08-17), and built. Extends
  [ADR-010](#adr-010-on-demand-ai-workout-analysis-called-directly-from-the-app-with-a-user-supplied-key),
  which assumed a single provider. Everything ADR-010 decided about *what the feature is* — read-only
  prose, the two time windows, the prompt contents, no stored analyses, keys typed into Settings —
  stands unchanged. This ADR is only about there being three of them.
- **Context:** ADR-010 shipped with Claude alone and a model selector, on the reasoning that which
  tier suits this athlete's data is an empirical question. The owner then answered a bigger version
  of that question by hand: the same real prompt was pasted into Claude, ChatGPT and Gemini, and
  **all three returned the same substantive judgement in different prose.** That is the finding this
  ADR rests on, and it points two ways at once.
  - *The prompt is doing the work, not the model.* If three independently-trained models read the
    same numbers and reach the same conclusion, the conclusion is in the data and the prompt. That
    makes the provider a matter of taste and price rather than of correctness, and it makes the
    shared prompt the thing worth protecting.
  - *The real defect was length, not quality.* All three wrote something that read well on a laptop
    and was far too long on a phone — the only screen this app has. That was a prompt bug, present
    in all three, and no amount of provider choice would have fixed it.
- **Decision:**
  - **One `AnalysisClient` interface, one method**, with three implementations in `data/analysis/`.
    The differences between providers are entirely inside it; the ViewModel picks a client by the
    selected model's provider and never learns which one answered.
  - **One shared exception hierarchy** (`AnalysisException`), because the card needs exactly two
    things from a failure — Finnish text and whether waiting would help — and three parallel
    hierarchies would mean the UI knowing which provider failed in order to read a message.
  - **One key store class, three instances**, each with its own Keystore alias and its own
    preferences file derived from the provider. Clearing one provider's key must not be able to
    touch another's, and a key pasted into the wrong field must not silently authenticate elsewhere.
  - **One model enum across all providers.** The stored preference is the enum *constant name*, not
    the model id: a provider can rename or retire an id, and a stored choice should survive that
    rather than silently resetting.
  - **A hard word count in the shared prompt** — "enintään 110 sanaa" — replacing "2–4 kappaletta".
    A number rather than an adjective, because "lyhyt" is a word each model interprets against its
    own defaults where 110 words is the same length for all three. The reason (a phone screen) is
    named in the prompt so the model has something to reason about when trimming.
  - **Each provider's response extraction is its own code and its own test**, because that is where
    a bug is silent — every status code says success and the card renders empty:

    | Provider | Where the answer is | The trap |
    | --- | --- | --- |
    | Claude | first block whose `type` is `text` | thinking blocks precede it and their text is empty |
    | ChatGPT | `choices[0].message.content` | `finish_reason: content_filter` is a `200` with no text |
    | Gemini | `candidates[0].content.parts[*].text`, joined | a blocked prompt omits `candidates` **entirely** |

    ADR-010 already shipped the Claude one as a real bug. Each provider now has a fixture for its own
    version of it.
  - **Gemini's key goes in the `x-goog-api-key` header, never the `?key=` query parameter** that
    Google's own examples show. Both work; only one keeps the secret out of proxy logs, crash
    reports and `Referer` headers.
  - **Gemini's `400` is read as an auth failure**, not a malformed request. It answers `400` for a
    rejected key where the other two answer `401`, and the generic wording would tell the owner their
    app is broken when their key is merely wrong.
  - **Paid Gemini only, stated in the UI.** Google's free tier uses submitted content to improve
    their products; the paid tier does not. This app sends nightly HRV and resting heart rate, and
    `PRIVACY.md` promises that no data is used to train machine-learning models. The Settings hint
    for the Gemini key therefore says "käytä maksullista tasoa" rather than leaving the tier to
    chance — the owner confirmed they are on the paid tier.
- **Consequences:**
  - `PRIVACY.md`'s destinations table gains two rows and its AI section now names the *selected*
    provider rather than one company. The ML-training promise stays true **only because of the
    paid-tier decision above**, which is why that decision is recorded here rather than left to the
    reader.
  - Three secrets on the device where ADR-010 had one, each behind its own Keystore alias.
  - Three model lists going stale independently. A retired id answers `404`, which has its own
    Finnish message pointing at the model selector rather than at the key.
  - The provider-neutral half — `AnalysisPromptBuilder`, `AiAnalysisAvailability`, `AiAnalysisState`,
    `AiAnalysisSection` and their tests — was not touched. That it did not need to be is the
    strongest evidence the original layering was right.
- **Alternatives Considered:**
  - *Stay on one provider* — genuinely defensible, and the honest cost comparison nearly won it:
    paid Gemini Flash saves roughly 30 cents a month against Sonnet and nothing at all against Haiku,
    which was already in the list. It was rejected on the owner's own comparison — they preferred
    ChatGPT's phrasing — which is a taste argument, and taste is exactly what a selector is for.
  - *An OpenAI-compatible shim for all three* — many providers expose an OpenAI-shaped endpoint, and
    one client would have served all of them. Rejected: neither Anthropic's nor Gemini's first-party
    API is OpenAI-shaped, so this would mean routing the owner's health data through a translation
    layer or a third-party gateway to save perhaps sixty lines.
  - *Free-tier Gemini* — the cheapest option and the reason Gemini was raised at all. Rejected on
    the data-use condition; see above.
- **Related Files:** `data/analysis/` (whole package), `domain/AnalysisModel.kt`,
  `domain/AnalysisPromptBuilder.kt`, `AnalysisCard.kt`, `data/settings/AnalysisSettingsStore.kt`,
  `WorkoutViewModel.kt`, `TreenivalmentajaApplication.kt`, `PRIVACY.md`, `SECURITY.md`, `AGENTS.md`

## ADR-012: What the guided workout recorded travels on the completion event
- **Status:** Accepted (2026-08-21), and built. Extends
  [ADR-010](#adr-010-on-demand-ai-workout-analysis-called-directly-from-the-app-with-a-user-supplied-key);
  changes nothing it decided about what the feature is.
- **Context:** Asked to analyse a completed strength session, the model answered: *"Toteutuneista
  liikkeistä, toistoista, kuormista tai kierroksista ei ole tietoa, joten suunnitelman tarkkaa
  toteutumista tai voimatasoa ei voi arvioida."* It was telling the truth about what it had been
  sent, and two separate gaps produced it.
  - **The plan's own movements never reached the prompt.** `CompletedAnalysisInput` carried a type,
    a duration, an intensity and a free-text description. `exercisesJson` — names, sets, reps,
    `weightKg`, `setPlan` — and `rounds` sat in the database, structured, and were never read for
    the analysis. Only the Oura and watch numbers described the session, and neither device knows
    what a squat is.
  - **The guided workout's ticks never left the screen.** The counter is a `rememberSaveable` inside
    the card. Pressing "Valmis" called `updateWorkoutStatus(COMPLETED)`, which recorded that a
    button had been pressed and nothing about the ten movements ticked off on the way there.
- **Decision:**
  - **The count rides on the completion event, in `payloadJson`, under a `guided` key.** It
    describes *that act of finishing*, not the plan, and the events table is append-only, so the
    record cannot later be quietly rewritten. No schema change: the column and
    `transition(payloadJson = …)` already existed for the reschedule payload.
  - **The shape is stored with the count.** `{"done", "rounds", "perRound"}`, never `done` alone. A
    bare count cannot be read later — "6" is two thirds of one workout and a fifth of another — and
    "Kevyempi versio" can swap the movement list under a started session. Stored together, a reader
    can tell when the list it was counted against is no longer the list on screen.
  - **The prompt names movements only while the shape still matches.** When it does not, the counts
    are written and the names are not. Naming the wrong movements as done is the one failure mode
    here that yields a confident wrong analysis rather than a vaguer one.
  - **A full tick promotes the plan to the record.** The prompt says so in a sentence
    (*"Ohjelma toteutui suunnitellusti"*) and the task instruction tells the model to read it that
    way, because without the instruction all three providers keep hedging about loads they have just
    been told were performed.
  - **The counter stays in the card.** `rememberSaveable` is what carries it through the process
    being killed mid-set; a ViewModel field would not. It is *mirrored* up on every change so that
    the completion — which outlives the composition — has something to write down. One direction
    only: the ViewModel never writes back.
- **Consequences:**
  - A tick says a movement was performed, **never at what load**. The plan's prescription is the
    only account of that, so a session done at 45 kg where the plan said 55 reaches the model as 55.
    Recording real loads means an entry field per set, which is a larger and different feature.
  - Progress is not persisted while a workout is in progress — it lives in the card's saved state
    until the session is completed. Force-stopping the app mid-workout keeps it; clearing the app
    from memory in a way that discards saved state does not.
  - Sessions completed before this shipped carry no payload. They render **no** guided section
    rather than zero movements done: nothing recorded and nothing done are different facts.
- **Alternatives Considered:**
  - *A column on `workout_sessions`* — survives anything, and was the obvious place. Rejected for
    this change: it needs a Room migration and a schema JSON for a count that is only read after the
    session is over, and the event row already answers the question at the moment it is asked.
  - *One event row per tick* — the events table is the natural home for "what happened when", and
    this would have given a timeline of the workout. Rejected: `session_events` records *status
    transitions*, and a tick is not one. Ten rows per workout to answer a question that one row
    answers is a change to what the table means.
  - *Sending only the ticks, not the plan* — half the fix, and the half that reads worse: "6 / 10
    done" is meaningless to a model that was never told what the ten were.
- **Related Files:** `domain/GuidedProgress.kt`, `data/SessionPayloadJson.kt`,
  `domain/AnalysisPromptBuilder.kt`, `data/repository/TrainingRepository.kt`, `WorkoutViewModel.kt`,
  `TodayScreen.kt`, `docs/DATA_MODEL.md`

## ADR-013: The app installs its own update through `PackageInstaller`
- **Status:** Accepted (2026-08-24), and built. Replaces the delivery mechanism of the update check
  built alongside [ADR-006](#adr-006-no-separate-backend-in-the-mvp); the "no backend" decision and
  the rolling test release are untouched.
- **Context:** The version card found the published build and then handed its URL to
  `ACTION_VIEW`. The browser downloaded the APK into the user's Downloads folder, the user found
  it there, tapped it, and Android's package installer took over. Three things were wrong with
  that, and only the first is cosmetic.
  - **An installable APK was left among the user's documents**, one copy per update, on a phone
    that never asked for a file. Nothing removed them.
  - **Nothing checked what arrived.** The release published a size that no one compared against
    the file, and no digest at all. The signing certificate was the whole of the protection — it
    is a real protection, and it is what stops a substituted binary replacing this app, but it says
    nothing until installation is already under way and nothing whatsoever about a truncated or
    stale download.
  - **The browser was in the update path.** A redirect, a download manager's retry, a mirror, a
    cached older asset: each is a place where what the card described and what the user installed
    could differ, invisibly.
- **Decision:**
  - **The APK is streamed straight into a `PackageInstaller` session** — `MODE_FULL_INSTALL`,
    `setAppPackageName`, `setSize` — and never becomes a file the user or any other app can see.
    No `DownloadManager`, no browser, no Downloads folder, and therefore **no storage permission**.
    The session is Android's own staging area; abandoning it takes the bytes with it.
  - **The release publishes `apkSha256`, and the download is verified against it while it is
    written.** The digest is computed by the same CI step that uploads the APK, and by the app from
    the bytes as they pass into the session — they cannot be read back out of one. A mismatch in
    either the digest or the byte count abandons the session, so nothing unverified is ever
    committed. The field is **required**: a release with no digest fails the parse rather than
    installing unchecked, because "no digest" is not a weaker check but no check.
  - **`USER_ACTION_REQUIRED` is set, deliberately.** Android's own "Päivitetäänkö tämä sovellus?"
    dialog is the confirmation, exactly as before. This app has no business installing anything
    without it being asked, and `REQUEST_INSTALL_PACKAGES` grants only the right to *ask*.
  - **The status callback goes to a non-exported `BroadcastReceiver`, not through `MainActivity`.**
    The callback carries an `Intent` in `EXTRA_INTENT` which the app then *starts*. Routed through
    the app's one exported component, any application on the device could send one and this app
    would launch whatever it named while believing it was the system's install prompt. A
    non-exported receiver reached by an explicit `PendingIntent` can be delivered to by the system
    alone. The `PendingIntent` is mutable on Android 12+ because the platform fills in the status
    extras; that is safe precisely because it names the component it starts.
- **Consequences:**
  - **A release published before this shipped cannot be installed by a build that has it**, and
    reports the parse failure rather than pretending. The first CI run after this lands publishes
    the field, so the window is one build long.
  - **The permission is visible in the manifest and to Play Protect.** `REQUEST_INSTALL_PACKAGES`
    is treated with suspicion by design, and rightly. This app is not distributed through Play; if
    it ever is, this is one of the things that has to be reconsidered rather than defended.
  - **The first update after installing asks for "Asenna tuntemattomia sovelluksia" once.** The app
    opens that settings screen itself and continues the download when the user comes back, because
    the settings screen returns no useful result code — the permission is simply read again.
  - **The download does not survive the process being killed.** The session does, but nothing
    resumes it: pressing the button again starts a fresh one. A resumable download is a larger
    feature for a file that takes seconds on any usable connection.
- **Alternatives Considered:**
  - *Keep the browser hand-off* — no permission, no code, and Play Protect never raises an eyebrow.
    Rejected because it cannot verify what it installs and leaves the APK behind; those are the two
    reasons this work exists.
  - *`DownloadManager` into app-private storage, then `FileProvider` + `ACTION_INSTALL_PACKAGE`* —
    the conventional sideload route, and it does keep the file out of Downloads. Rejected: it needs
    the same `REQUEST_INSTALL_PACKAGES`, adds a `FileProvider` and a file to clean up afterwards,
    and `ACTION_INSTALL_PACKAGE` is deprecated in favour of the API chosen here.
  - *Silent installation* — impossible without being a device owner or a system app, and not wanted
    if it were. The user seeing what is about to replace their app is a feature.
  - *Publishing the digest in the release notes instead of `latest.json`* — human-readable, and
    unusable: the app would have to parse prose, and nothing would compare it to anything.
- **Related Files:** `data/update/ApkInstaller.kt`, `data/update/PackageInstallerApkInstaller.kt`,
  `data/update/UpdateInstallReceiver.kt`, `data/update/UpdateService.kt`,
  `domain/InstallUpdateUseCase.kt`, `domain/CheckForUpdateUseCase.kt`, `UpdateCard.kt`,
  `SettingsScreen.kt`, `AndroidManifest.xml`, `.github/workflows/build-test-apk.yml`,
  `docs/SECURITY.md`

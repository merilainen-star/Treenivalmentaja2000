# Roadmap

Status claims here are measured, not estimated. See [PROJECT_STATUS.md](../PROJECT_STATUS.md) for
the numbers behind them.

## Completed (MVP Phase 3)
- Reminder system: AlarmManager notifications with a 7-day sliding window, `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED` and timezone-change restoration, and notification permission checks. Uses
  inexact `setAndAllowWhileIdle` to comply with Google Play exact alarm policies.
- Room database migrations: `exportSchema = true`, both `MIGRATION_3_4` (hand-written) and the
  4 → 5 auto migration covered by instrumented tests, and no destructive fallback — a missing
  migration fails loudly rather than emptying the database.
- Deterministic Training Engine: missed-session handling, plan shifting, illness pause
  (`markSick`) and the graduated return (`markRecovered`), wired into the Today screen.

## Completed (MVP Phase 2)
- Room database as the local source of truth (entities, DAOs, repository).
- Session state machine with a validated transition table and an append-only event log.
- Training plan JSON import (file + clipboard) against Plan Schema v1, with validation and
  duplicate detection.
- First-launch seeding through the real importer.

## Completed (MVP Phase 1)
- Initial Android project setup (Kotlin, Jetpack Compose, Gradle configurations).
- Splash screen with custom animations and logo.
- Main layout scaffold with Bottom Navigation.
- `TodayScreen`, `WeekScreen` and `SettingsScreen`.

## Completed (after Phase 3)
- Screen state hoisting: every screen is a stateless `…Content` plus a thin ViewModel wrapper, so
  whole screens are renderable in a test and `WorkoutViewModel` finally has some.
- Test APK distribution: every push to `main` that touches code builds, verifies and republishes a
  rolling GitHub prerelease, and Settings says whether the installed build is the current one.
- Import asks where a plan lands — the file's dates, or starting today — and replaces the previous
  plan rather than leaving it behind.
- Exercises are shown as the plan wrote them: prescriptions (`3 × 12 · 17,5 kg`), per-set ramps,
  and clocks for timed movements that run once per side or per set.
- Exercise guides: tap a movement for an animation and instructions, fetched on demand and never
  stored, with attribution — [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md). Two sources, because neither
  has everything: ExerciseDB animates all 1500 of its movements but has no plank, side plank,
  plain squat, bird dog or cat-cow, and wger has all of those under CC-BY-SA but pictures for only
  a third. There is no disk cache anywhere in the path, because ExerciseDB permits free personal
  use with attribution but not persistent caching.

## In Progress
- Nothing. The app is being used in real training between milestones.

## Completed (Oura milestone)
1. ~~**Oura API V2 client**~~ — built. `data/oura` reads readiness, sleep, activity and workouts
   between two dates, follows `next_token` to the last page, maps the five documented status codes
   to typed already-Finnish failures, and turns what comes back into `oura_daily_summaries` and
   `oura_workouts` rows. Written against the vendored `docs/api/oura-openapi-1.37.json` and a local
   `com.sun.net.httpserver`, so it needed no credentials; on OkHttp rather than Retrofit
   ([ADR-007](DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client)). It now runs against a
   real account. **What no one has checked is whether it parses that account correctly** — the
   fixtures behind its tests are derived from the specification rather than captured from Oura, so
   the remaining step is comparing a number the app displays with Oura's own app.
2. ~~**In-app OAuth2 token exchange with PKCE**~~ — built, and reachable from Settings. The
   authorization request, `state` validation, the code exchange, encrypted token storage, and the
   OkHttp `Authenticator` that renews on `401` without spending a rotated refresh token twice.
   Tokens are under an Android Keystore key rather than in `EncryptedSharedPreferences`, whose
   library is deprecated
   ([ADR-008](DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)).
   The client id and secret are **typed into Settings**, not compiled in, so the whole setup happens
   on the phone — the previous design needed a local `.env` and therefore a PC, which is not how
   this app is installed
   ([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)).
   **A real login has been completed** from the phone, against a real Oura account.
3. ~~**WorkManager** for background biometric syncing.~~ — built. A daily periodic worker fetching
   the last five days (Oura revises a day after the fact, and an offline weekend would otherwise
   leave a permanent hole), plus a foreground sync when the Today screen opens. Scheduled only while
   Oura is connected.
4. ~~**Put a recovery reading back on the Today screen**~~ — done, and this is the first point where
   the Oura work is visible at all. The card tells four situations apart: Oura not connected (no
   indicator, exactly as before), nothing fetched yet, a day Oura answered about with no score, and
   a reading. The third is the one the whole design turns on — the ring was not worn, so the card
   says "ei tietoa" about a day that exists rather than drawing a zero.

## Next Milestone

The path towards an AI coach, in the order the pieces earn their keep. Decided 2026-08-15; the
reasoning for watch-telemetry-first is that running is the most taxing session type and the one
with real numbers behind it (pace, distance, time, calories, training load), while a strength
session's lone heart-rate line says little — an advisor analysing only what Oura holds would have
almost nothing to say about the sessions that matter most.

1. ~~**Watch telemetry, via intervals.icu**~~ — built. `data/intervals` reads
   `/api/v1/athlete/0/activities` between two dates and stores `intervals_activities` (schema v7).
   Runs match to planned sessions through the same use case Oura's workouts go through, and a
   matched session shows a pace line Oura cannot supply, plus intervals.icu's own training load.

   **This was Strava for about a day.** Strava paywalled its API in June 2026 — developer access
   now needs an active subscription — so that integration was removed entirely and replaced. Suunto's
   own API was ruled out first: its FAQ states access is for "companies/organizations" and that "we
   do not provide this for personal use". intervals.icu already receives the watch's activities, its
   API is free for personal use, and it authenticates with a personal API key over HTTP Basic — no
   OAuth, no callback activity, no refresh token, and one fewer exported component than before.

   **Connected to a real account and fetching**, confirmed 2026-08-15. What remains unchecked is
   whether every displayed number matches intervals.icu's own interface for the same activity, and
   two undocumented fields are read on a stated assumption — see
   [API_INTEGRATIONS.md](API_INTEGRATIONS.md#two-fields-the-specification-does-not-describe). The
   setup steps are in [INTERVALS_SETUP.md](INTERVALS_SETUP.md).
2. ~~**Readiness rule, deterministic (Phase A)**~~ — built, and no AI anywhere in it.
   `ReadinessAdviceUseCase` is a pure function of the stored Oura days and the plan: a session left
   open on a day whose readiness was below 70 raises a card the next morning offering to shift the
   programme forward or start today lighter, and a poor reading on a day with a session offers
   lightening alone. Both buttons call operations that already existed
   (`handleMissedSessions`, `applyLighterVersion`), so nothing new can happen to the plan and every
   change carries an author in the event log. A day the ring was not worn produces nothing — see
   [TRAINING_ENGINE.md](TRAINING_ENGINE.md#readiness-advice--asking-never-acting). This is the
   first point where the readiness number reaches the plan at all.
3. **AI coach comments, read-only (Phase B)** — a "pyydä valmentajan kommentit" button. BYOK: the
   user's own LLM API key, typed into Settings and stored like the Oura credentials. The advisor
   reads completed sessions, Oura-recorded other activity and the watch's own runs, and writes an
   assessment — it changes nothing. The exact request payload is shown to the user before/with
   the response (see [INSPIRATION.md](INSPIRATION.md)); [PRIVACY.md](PRIVACY.md) must be updated
   before this ships, because health data leaves the device for a third party.
4. **AI plan adjustments with approval (Phase C)** — the advisor may propose changes as
   structured operations (move session X to day Y, lighten session Z) that map onto the same
   engine operations Phase A uses. Nothing is written without the user accepting, and accepted
   changes go through the append-only session-event log like every other transition. Standing
   constraints — e.g. runs belong on weekends, because that is when there is time to run — are
   user preferences carried in the prompt, and the advisor asks its clarifying questions before
   proposing.

## Later (Phase 4 & Beyond)
- Logging what was actually lifted, so a strength session can be compared with the last time it
  was done. Oura holds completed workouts but not per-set loads, so the "history lives in Oura"
  reasoning does not cover this one.

## Blocked
- None. The Oura milestone is built end to end and a real account is connected.

## Technical Debt
- Extract use cases from `TrainingRepository`: it still carries domain logic (lighter-version
  fallback, reschedule chaining).
- `versionCode` has never been raised from `1`, so builds cannot be told apart on a device.
- The ICS parsers in `tools/` guess plan structure from Finnish prose with regular expressions.
  They are a legacy path; write plans against [PLAN_SCHEMA.md](PLAN_SCHEMA.md) instead.
